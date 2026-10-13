package mundoinfinito;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.util.Log;
import arc.util.Threads;
import mindustry.Vars;
import mindustry.graphics.CacheLayer;
import mindustry.world.Block;
import mindustry.world.blocks.TileBitmask;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.environment.SteamVent;
import mundoinfinito.MundoInfinitoMod.ChunkData;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * COCINADOR DE GEOMETRÍA (estilo C2ME). Convierte un chunk VIRTUAL de terreno (short[] piso/mena/bloque, sacado de
 * CacheChunks) en un float[] de vértices listo para subir a la GPU. TODO ocurre en hilos de fondo: nunca toca Vars.world,
 * ni el renderizador de Mindustry, ni el hilo principal.
 *
 * Reproduce, con las mismas reglas que Floor/StaticWall del juego, lo que el FloorRenderer vanilla hace al cachear un chunk:
 *   - variantes aleatorias de patrón (aquí con un hash de la coordenada VIRTUAL: no cambian al deslizar la ventana),
 *   - autotiling de pisos y muros (TileBitmask de 47 casos) y muros grandes 2x2,
 *   - bordes entre pisos (drawEdges/drawNonLayer): hasta 8 sprites de borde por casilla, ordenados como el juego,
 *   - capas de caché (agua, alquitrán, escoria, normal, muros...): un tramo contiguo de sprites por capa,
 *   - menas encima del piso y menas de muro encima del muro,
 *   - iluminación horneada: los muros se oscurecen según su profundidad (el juego lo hace con una textura aparte, y solo
 *     dentro de la ventana de tiles; fuera de ella no existe, por eso se hornea en el vértice).
 *
 * Formato del vértice (5 floats = 20 bytes, el mismo orden que el SpriteBatch de Arc):  X, Y, COLOR(packed), U, V.
 * X, Y son LOCALES AL CHUNK (0..256 unidades de mundo, centro de casilla = lx * 8). El desplazamiento del chunk se aplica en
 * la GPU con un uniform (u_off), así la geometría cocinada es válida para siempre aunque la ventana de Mindustry se mueva.
 *
 * Cero asignaciones en régimen: los float[] viajan dentro de objetos Cocinado que se reciclan (pool), y cada hilo reutiliza su
 * propio Scratch. Los vecinos para bordes/autotiling se leen de CacheChunks (padding de 4 casillas); si un vecino aún no está
 * en caché se replica el borde del chunk y se anota en 'faltantes' para recocinarlo cuando aparezca.
 */
final class ChunkGeometryCooker{
    static final int TAM = Coord.CHUNK;                 // 32 casillas por lado
    static final int PAD = 4;                           // casillas de vecinos que se leen alrededor (profundidad de muro <= 4)
    static final int ANCHO = TAM + PAD * 2;             // lado de la grilla con padding
    static final int FLOATS_VERTICE = 5;
    static final int FLOATS_SPRITE = FLOATS_VERTICE * 4;
    /** Tope de sprites por chunk (una malla de GPU tiene esta capacidad fija). Lo que sobre se descarta, nunca corrompe. */
    static final int MAX_SPRITES = 6144;
    static final int MAX_CAPAS = 16;
    private static final float GROW_MUROS = 0.04f;      // igual que FloorRenderer.growSprites en la capa de muros

    // vecinos en el mismo orden que arc.math.geom.Geometry.d8 (el orden importa para el autotiling)
    private static final int[] NDX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] NDY = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] OFF = new int[8];
    static{
        for(int i = 0; i < 8; i++) OFF[i] = NDY[i] * ANCHO + NDX[i];
    }

    static final float BLANCO = Color.toFloatBits(1f, 1f, 1f, 1f);
    /** Luz horneada por profundidad de muro (misma curva que BlockRenderer.drawDarkness). */
    private static final float[] LUZ = new float[6];
    static{
        LUZ[0] = BLANCO;
        for(int d = 1; d < LUZ.length; d++){
            float l = 1f - Math.min((d + 0.5f) / 4f, 1f);
            LUZ[d] = Color.toFloatBits(l, l, l, 1f);
        }
    }

    private ChunkGeometryCooker(){}

    // ================================================================== tabla visual (se construye en el hilo principal)
    /**
     * Todo lo que el cocinador necesita saber de cada Block, indexado por Block.id, ya resuelto a UVs. Se construye UNA vez en
     * el hilo principal (Core.atlas no se toca desde los hilos de fondo) y luego solo se lee.
     */
    static final class Tabla{
        final int n;
        /** 0 nada/aire, 1 piso, 2 mena de suelo, 3 muro estático, 4 mena de muro, 5 otro bloque (no va en la malla) */
        final byte[] tipo;
        final byte[] capa;                              // id de CacheLayer del piso
        final int[] grupo;                              // blendGroup.id
        final int[] padre;                              // respiraderos (SteamVent): id del piso padre sobre el que se dibujan; 0 = no es respiradero
        final int[] autoVars;                           // variantes de autotile
        final boolean[] bordeEntra, bordeSale;
        final float[][] vuv;                            // [id] -> 4 floats (u,v,u2,v2) por variante
        final float[][] auv;                            // [id] -> autotile: (variantes * 47) regiones
        final float[][] amid;                           // [id] -> variantes del centro del autotile
        final float[][] borde;                          // [id del blendGroup] -> 3x3 regiones de borde
        final float[][] grande;                         // [id] -> 2x2 regiones del muro grande
        Texture textura;

        Tabla(int n){
            this.n = n;
            tipo = new byte[n];
            capa = new byte[n];
            grupo = new int[n];
            padre = new int[n];
            autoVars = new int[n];
            bordeEntra = new boolean[n];
            bordeSale = new boolean[n];
            vuv = new float[n][];
            auv = new float[n][];
            amid = new float[n][];
            borde = new float[n][];
            grande = new float[n][];
        }

        int tipoDe(int id){
            return id < n ? tipo[id] : 0;
        }
    }

    private static volatile Tabla tabla;

    /** HILO PRINCIPAL. Lee el atlas de Arc y resuelve las regiones de cada bloque. Seguro de llamar otra vez (p. ej. tras cargar contenido). */
    static Tabla construirTabla(){
        final int n = Vars.content.blocks().size;
        final Tabla t = new Tabla(n);
        final TextureRegion base = Core.atlas.find("grass1");
        final Texture tex = base.texture;       // el atlas del entorno: todo el terreno vive en esta textura
        final TextureRegion e = Core.atlas.find("env-error");
        final TextureRegion err = Core.atlas.isFound(e) ? e : base;
        final int ts = Math.max(1, (int)(Vars.tilesize / Draw.scl));
        t.textura = tex;

        for(Block b : Vars.content.blocks()){
            final int id = b.id;
            if(id <= 0 || id >= n) continue;    // id 0 = aire
            if(b instanceof StaticWall){
                StaticWall w = (StaticWall)b;
                t.tipo[id] = 3;
                t.vuv[id] = variantes(b, tex, err);
                if(w.large != null && Core.atlas.isFound(w.large) && w.split != null && w.split.length >= 2 && w.split[0].length >= 2){
                    float[] g = new float[16];
                    for(int i = 0; i < 2; i++){
                        for(int j = 0; j < 2; j++) uv(g, (i * 2 + j) * 4, w.split[i][j], tex, err);
                    }
                    t.grande[id] = g;
                }
                if(w.autotile) autotile(t, id, b.name, 1, w.autotileMidVariants, tex, err);
            }else if(b instanceof Floor){
                Floor f = (Floor)b;
                boolean overlay = b instanceof OverlayFloor;
                t.tipo[id] = (byte)(overlay ? (f.wallOre ? 4 : 2) : 1);
                t.capa[id] = (byte)f.cacheLayer.id;
                t.grupo[id] = f.blendGroup.id;
                t.bordeEntra[id] = f.drawEdgeIn;
                t.bordeSale[id] = f.drawEdgeOut;
                t.vuv[id] = variantes(b, tex, err);
                if(b instanceof SteamVent){
                    // el respiradero NO es un piso de 1 casilla: sus variantes son sprites de 3x3. Se dibuja el piso padre en cada casilla
                    // y UN solo sprite grande encima de cada bloque completo de 3x3 (igual que SteamVent.drawMain)
                    Block pa = ((SteamVent)b).parent;
                    if(pa instanceof Floor && pa.id > 0 && pa.id < n) t.padre[id] = pa.id;
                }
                if(f.autotile && !overlay) autotile(t, id, b.name, f.autotileVariants, f.autotileMidVariants, tex, err);
                if(f.blendGroup == f){
                    // bordes: "<piso>-edge" partido en 3x3, igual que Floor.load()
                    TextureRegion er = Core.atlas.find(b.name + "-edge");
                    if(Core.atlas.isFound(er)){
                        TextureRegion[][] sp = er.split(ts, ts);
                        if(sp != null && sp.length == 3 && sp[0].length == 3){
                            float[] a = new float[9 * 4];
                            for(int i = 0; i < 3; i++){
                                for(int j = 0; j < 3; j++) uv(a, (i * 3 + j) * 4, sp[i][j], tex, err);
                            }
                            t.borde[id] = a;
                        }
                    }
                }
            }else{
                t.tipo[id] = 5;
            }
        }
        return t;
    }

    private static void uv(float[] dst, int off, TextureRegion r, Texture tex, TextureRegion err){
        // el FloorRenderer vanilla sustituye por "env-error" toda región que no esté en el atlas del entorno
        TextureRegion s = (r == null || !Core.atlas.isFound(r) || r.texture != tex) ? err : r;
        dst[off] = s.u;
        dst[off + 1] = s.v;
        dst[off + 2] = s.u2;
        dst[off + 3] = s.v2;
    }

    private static float[] variantes(Block b, Texture tex, TextureRegion err){
        TextureRegion[] vr = b.variantRegions;
        if(vr == null || vr.length == 0) vr = new TextureRegion[]{b.region};
        float[] a = new float[vr.length * 4];
        for(int i = 0; i < vr.length; i++) uv(a, i * 4, vr[i], tex, err);
        return a;
    }

    private static void autotile(Tabla t, int id, String name, int variantes, int medios, Texture tex, TextureRegion err){
        int nv = Math.max(1, variantes);
        float[] a = new float[nv * 47 * 4];
        if(nv > 1){
            TextureRegion[][] vv = TileBitmask.loadVariants(name, nv);
            for(int v = 0; v < nv; v++){
                for(int k = 0; k < 47; k++) uv(a, (v * 47 + k) * 4, vv[v][k], tex, err);
            }
        }else{
            TextureRegion[] rr = TileBitmask.load(name);
            for(int k = 0; k < 47; k++) uv(a, k * 4, rr[k], tex, err);
        }
        t.auv[id] = a;
        t.autoVars[id] = nv;
        if(medios > 1){
            float[] m = new float[medios * 4];
            for(int i = 0; i < medios; i++) uv(m, i * 4, Core.atlas.find(i == 0 ? name + "-13" : name + "-mid-" + (i + 1)), tex, err);
            t.amid[id] = m;
        }
    }

    // ================================================================== resultado cocinado (reciclable)
    /** Un chunk cocinado. Los arrays se reutilizan: nada de esto genera basura una vez calentado el pool. */
    static final class Cocinado{
        long clave;                     // clave virtual (Coord.claveVirtual)
        int mundo;                      // CacheChunks.mundo() con el que se cocinó
        boolean oscuro;                 // ¿se horneó la oscuridad de los muros?
        boolean exito;                  // false = faltaba el terreno del chunk
        int faltantes;                  // bit i = el vecino d8[i] no estaba en caché
        int nSprites;
        float[] datos = new float[FLOATS_SPRITE * 2048];
        final int[] inicio = new int[MAX_CAPAS];    // primer sprite de cada capa (indexado por CacheLayer.id)
        final int[] cuenta = new int[MAX_CAPAS];    // sprites de cada capa

        void reiniciar(long clave, int mundo, boolean oscuro){
            this.clave = clave;
            this.mundo = mundo;
            this.oscuro = oscuro;
            exito = false;
            faltantes = 0;
            nSprites = 0;
            Arrays.fill(inicio, 0);
            Arrays.fill(cuenta, 0);
        }
    }

    private static final ConcurrentLinkedQueue<Cocinado> POOL = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger poolN = new AtomicInteger();
    private static final int POOL_MAX = 64;

    private static Cocinado obtener(){
        Cocinado c = POOL.poll();
        if(c != null){
            poolN.decrementAndGet();
            return c;
        }
        return new Cocinado();
    }

    /** Devuelve un Cocinado al pool. Llamar cuando ya se subió a la GPU (o se descartó). */
    static void liberar(Cocinado c){
        if(poolN.get() >= POOL_MAX) return;
        poolN.incrementAndGet();
        POOL.offer(c);
    }

    // ================================================================== cola de trabajo y hilos
    private static final Object lock = new Object();
    private static final long[] cola = new long[96];        // anillo de peticiones, sin objetos
    private static final int[] colaMeta = new int[96];      // (mundo << 1) | oscuro
    private static int cabeza, cuentaCola;
    private static final ConcurrentLinkedQueue<Cocinado> LISTOS = new ConcurrentLinkedQueue<>();
    private static boolean iniciado;

    /** HILO PRINCIPAL. Construye la tabla de regiones y arranca los hilos de cocinado. */
    static void iniciar(){
        if(iniciado) return;
        iniciado = true;
        tabla = construirTabla();
        int hilos = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
        for(int i = 0; i < hilos; i++){
            Thread t = Threads.daemon("MundoInfinito-Cocinero-" + i, ChunkGeometryCooker::bucle);
            try{ t.setPriority(Thread.MIN_PRIORITY + 1); }catch(Throwable ignored){}
        }
    }

    /** HILO PRINCIPAL. Rehace la tabla si se añadió contenido después de iniciar. */
    static void revisarTabla(){
        Tabla t = tabla;
        if(t != null && t.n != Vars.content.blocks().size) tabla = construirTabla();
    }

    /** Pide cocinar un chunk virtual. false = la cola está llena (reintentar en otro fotograma). No bloquea. */
    static boolean solicitar(long clave, int mundo, boolean oscuro){
        synchronized(lock){
            if(cuentaCola == cola.length) return false;
            int i = (cabeza + cuentaCola) % cola.length;
            cola[i] = clave;
            colaMeta[i] = (mundo << 1) | (oscuro ? 1 : 0);
            cuentaCola++;
            lock.notify();
            return true;
        }
    }

    /** Siguiente chunk cocinado, o null. El llamador DEBE devolverlo con liberar(). */
    static Cocinado recoger(){
        return LISTOS.poll();
    }

    private static void bucle(){
        final Scratch s = new Scratch();
        while(true){
            long clave;
            int meta;
            synchronized(lock){
                while(cuentaCola == 0){
                    try{ lock.wait(); }catch(InterruptedException e){ return; }
                }
                clave = cola[cabeza];
                meta = colaMeta[cabeza];
                cabeza = (cabeza + 1) % cola.length;
                cuentaCola--;
            }
            Cocinado o = obtener();
            o.reiniciar(clave, meta >> 1, (meta & 1) != 0);
            boolean ok = false;
            try{
                if(o.mundo == CacheChunks.mundo()) ok = cocinar(s, o);   // un mundo viejo no se cocina
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error cocinando geometría de un chunk", t);
            }
            o.exito = ok;
            LISTOS.offer(o);    // también los fallos: el renderizador necesita saber que hay que reintentar
        }
    }

    // ================================================================== el cocinado en sí
    /** Buffers de trabajo de UN hilo: se asignan una vez y se reutilizan en cada chunk. */
    private static final class Scratch{
        final short[] piso = new short[ANCHO * ANCHO];
        final short[] mena = new short[ANCHO * ANCHO];
        final short[] bloque = new short[ANCHO * ANCHO];
        final byte[] sol = new byte[ANCHO * ANCHO];     // 1 = muro estático
        final byte[] prof = new byte[TAM * TAM];        // profundidad del muro (1..5), 0 si no es muro
        final int[] bl = new int[8];                    // pisos que dibujan borde sobre la casilla actual
        final int[] dirs = new int[8];
        int nbl;
        // contexto de la casilla actual
        Tabla t;
        Cocinado o;
        int lx, ly, p, vx, vy, fid, bid, oid;
        float luz;
    }

    private static int hash(int x, int y, int salt){
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ salt * 0x9e3779b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /** Variante estable por coordenada VIRTUAL (Mathf.randomSeed del juego usa un Rand compartido: no es seguro entre hilos). */
    private static int azar(int vx, int vy, int salt, int max){
        return max <= 1 ? 0 : (hash(vx, vy, salt) >>> 1) % max;
    }

    private static boolean cocinar(Scratch s, Cocinado o){
        final Tabla t = tabla;
        if(t == null) return false;
        final long clave = o.clave;
        final int mid = o.mundo, vcx = Coord.vcxDeClave(clave), vcy = Coord.vcyDeClave(clave);

        // ---- 1. leer el chunk y sus 8 vecinos (CacheChunks.copiar da una copia: el original no se toca) ----
        ChunkData c = CacheChunks.copiar(mid, clave, 0, 0, 0);
        if(c == null) return false;     // aún no generado: el renderizador lo pedirá a los hilos de terreno
        for(int ly = 0; ly < TAM; ly++){
            int dst = (ly + PAD) * ANCHO + PAD, src = ly * TAM;
            System.arraycopy(c.piso, src, s.piso, dst, TAM);
            System.arraycopy(c.mena, src, s.mena, dst, TAM);
            System.arraycopy(c.bloque, src, s.bloque, dst, TAM);
        }
        c.liberar();

        int faltantes = 0;
        for(int i = 0; i < 8; i++){
            final int dx = NDX[i], dy = NDY[i];
            final int x0 = dx < 0 ? 0 : dx > 0 ? PAD + TAM : PAD, x1 = dx < 0 ? PAD : dx > 0 ? ANCHO : PAD + TAM;
            final int y0 = dy < 0 ? 0 : dy > 0 ? PAD + TAM : PAD, y1 = dy < 0 ? PAD : dy > 0 ? ANCHO : PAD + TAM;
            ChunkData nb = CacheChunks.copiar(mid, Coord.claveVirtual(vcx + dx, vcy + dy), 0, 0, 0);
            if(nb != null){
                for(int py = y0; py < y1; py++){
                    for(int px = x0; px < x1; px++){
                        int src = (py - PAD - dy * TAM) * TAM + (px - PAD - dx * TAM), p = py * ANCHO + px;
                        s.piso[p] = nb.piso[src];
                        s.mena[p] = nb.mena[src];
                        s.bloque[p] = nb.bloque[src];
                    }
                }
                nb.liberar();
            }else{
                // vecino ausente: se replica el borde de ESTE chunk y se anota para recocinar cuando llegue
                faltantes |= 1 << i;
                for(int py = y0; py < y1; py++){
                    int cpy = Math.max(PAD, Math.min(PAD + TAM - 1, py));
                    for(int px = x0; px < x1; px++){
                        int cpx = Math.max(PAD, Math.min(PAD + TAM - 1, px)), p = py * ANCHO + px, q = cpy * ANCHO + cpx;
                        s.piso[p] = s.piso[q];
                        s.mena[p] = s.mena[q];
                        s.bloque[p] = s.bloque[q];
                    }
                }
            }
        }

        // ---- 2. muros y profundidad (para la oscuridad horneada y para no dibujar el interior de las masas de roca) ----
        final int total = ANCHO * ANCHO;
        for(int p = 0; p < total; p++) s.sol[p] = (byte)(t.tipoDe(s.bloque[p] & 0xffff) == 3 ? 1 : 0);
        int mascara = 0;
        final boolean oscuro = o.oscuro;
        final int limite = oscuro ? 4 : 5;      // con oscuridad horneada la luz llega a 0 en profundidad 4
        boolean hayMuros = false, visible = false;
        for(int ly = 0; ly < TAM; ly++){
            for(int lx = 0; lx < TAM; lx++){
                int p = (ly + PAD) * ANCHO + lx + PAD, d = 0;
                if(s.sol[p] != 0){
                    d = 5;
                    busca:
                    for(int r = 1; r <= 4; r++){
                        for(int dy = -r; dy <= r; dy++){
                            int fila = p + dy * ANCHO;
                            if(dy == -r || dy == r){
                                for(int dx = -r; dx <= r; dx++){
                                    if(s.sol[fila + dx] == 0){ d = r; break busca; }
                                }
                            }else if(s.sol[fila - r] == 0 || s.sol[fila + r] == 0){
                                d = r;
                                break busca;
                            }
                        }
                    }
                    if(d < limite) hayMuros = true;
                }
                if(d < limite) visible = true;      // casilla abierta o muro de borde: algo se dibuja
                s.prof[ly * TAM + lx] = (byte)d;
            }
        }
        if(!visible){
            // Interior macizo de roca: ninguna casilla es visible (los muros profundos y el suelo bajo ellos no se dibujan, y los
            // bordes de capa quedarían bajo el muro). No emite ni un sprite: se evita recorrer todas las capas y, en el renderizador,
            // gastar una malla de GPU en un chunk vacío.
            o.faltantes = faltantes;
            return true;
        }
        // capas presentes: las de los pisos del chunk y de 1 casilla alrededor (sus bordes se pintan aquí), más los muros
        for(int py = PAD - 1; py <= PAD + TAM; py++){
            for(int px = PAD - 1; px <= PAD + TAM; px++){
                int f = s.piso[py * ANCHO + px] & 0xffff;
                if(t.tipoDe(f) == 1) mascara |= 1 << t.capa[f];
            }
        }
        if(hayMuros && CacheLayer.walls != null) mascara |= 1 << CacheLayer.walls.id;

        // ---- 3. una pasada por capa (como cacheChunkLayer del juego); cada capa deja un tramo contiguo de sprites ----
        s.t = t;
        s.o = o;
        final CacheLayer[] todas = CacheLayer.all;
        for(int id = 0; id < todas.length && id < MAX_CAPAS; id++){
            if((mascara & (1 << id)) == 0) continue;
            final boolean muros = todas[id] == CacheLayer.walls;
            final int ini = o.nSprites;
            for(int ly = 0; ly < TAM; ly++){
                for(int lx = 0; lx < TAM; lx++){
                    s.lx = lx;
                    s.ly = ly;
                    s.p = (ly + PAD) * ANCHO + lx + PAD;
                    s.vx = vcx * TAM + lx;
                    s.vy = vcy * TAM + ly;
                    s.fid = s.piso[s.p] & 0xffff;
                    s.bid = s.bloque[s.p] & 0xffff;
                    s.oid = s.mena[s.p] & 0xffff;
                    final boolean muro = t.tipoDe(s.bid) == 3;
                    if(muros){
                        int d = s.prof[ly * TAM + lx];
                        if(muro && d < limite){
                            s.luz = oscuro ? LUZ[d] : BLANCO;
                            dibujarMuro(s);
                        }
                    }else{
                        if(t.tipoDe(s.fid) != 1) continue;
                        if(t.capa[s.fid] == id){
                            if(!muro || accesible(s)) dibujarPiso(s);       // bajo un muro no accesible no se dibuja el piso
                        }else{
                            bordes(s, false, id);                           // drawNonLayer: bordes de pisos de ESTA capa sobre pisos de otra
                        }
                    }
                }
            }
            o.inicio[id] = ini;
            o.cuenta[id] = o.nSprites - ini;
        }
        o.faltantes = faltantes;
        s.t = null;
        s.o = null;
        return true;
    }

    /** ¿La casilla actual es la esquina de arriba-derecha de un bloque completo de 3x3 del mismo piso? (PAD = 4 cubre los 2 de margen) */
    private static boolean ventanaCompleta(Scratch s){
        final int p = s.p;
        final short f = s.piso[p];
        for(int dy = 0; dy < 3; dy++){
            for(int dx = 0; dx < 3; dx++){
                if(s.piso[p - dy * ANCHO - dx] != f) return false;
            }
        }
        return true;
    }

    private static boolean accesible(Scratch s){
        final int p = s.p;
        return s.sol[p + 1] == 0 || s.sol[p - 1] == 0 || s.sol[p + ANCHO] == 0 || s.sol[p - ANCHO] == 0;
    }

    // ------------------------------------------------------------------ un sprite = 4 vértices (x, y, color, u, v)
    private static void quad(Scratch s, float[] uv, int off, float color, float grow){
        quadEn(s, uv, off, color, s.lx * 8f, s.ly * 8f, 4f + grow);
    }

    /** Sprite cuadrado de semilado 'mitad' centrado en (cx, cy) locales al chunk (los respiraderos miden 3x3 casillas = mitad 12). */
    private static void quadEn(Scratch s, float[] uv, int off, float color, float cx, float cy, float mitad){
        final Cocinado o = s.o;
        if(o.nSprites >= MAX_SPRITES) return;
        final int need = (o.nSprites + 1) * FLOATS_SPRITE;
        if(o.datos.length < need){
            o.datos = Arrays.copyOf(o.datos, Math.min(MAX_SPRITES * FLOATS_SPRITE, Math.max(need, o.datos.length * 2)));
        }
        final float[] d = o.datos;
        int i = o.nSprites * FLOATS_SPRITE;
        final float x0 = cx - mitad, y0 = cy - mitad, x1 = cx + mitad, y1 = cy + mitad;
        final float u = uv[off], v = uv[off + 1], u2 = uv[off + 2], v2 = uv[off + 3];
        // mismo orden y mismas UVs que FloorRenderBatch.draw(): abajo-izq, arriba-izq, arriba-der, abajo-der
        d[i] = x0;      d[i + 1] = y0;  d[i + 2] = color;   d[i + 3] = u;   d[i + 4] = v2;
        d[i + 5] = x0;  d[i + 6] = y1;  d[i + 7] = color;   d[i + 8] = u;   d[i + 9] = v;
        d[i + 10] = x1; d[i + 11] = y1; d[i + 12] = color;  d[i + 13] = u2; d[i + 14] = v;
        d[i + 15] = x1; d[i + 16] = y0; d[i + 17] = color;  d[i + 18] = u2; d[i + 19] = v2;
        o.nSprites++;
    }

    // ------------------------------------------------------------------ pisos (Floor.drawBase)
    private static void dibujarPiso(Scratch s){
        final Tabla t = s.t;
        final int fidReal = s.fid;
        // un respiradero dibuja primero SU PISO PADRE (con autotile y variantes del padre) y luego el sprite 3x3
        final int fid = fidReal < t.n && t.padre[fidReal] != 0 ? t.padre[fidReal] : fidReal;
        final float[] vuv = t.vuv[fid];
        if(vuv == null) return;
        final float[] auv = t.auv[fid];
        if(auv != null){
            // piso con autotile (Floor.drawMain): 8 bits de vecinos del mismo blendGroup
            int bits = 0;
            final int g = t.grupo[fid];
            for(int i = 0; i < 8; i++){
                int nf = s.piso[s.p + OFF[i]] & 0xffff;
                if(nf < t.n && t.grupo[nf] == g && t.tipo[nf] == 1) bits |= 1 << i;
            }
            final int bit = TileBitmask.values[bits];
            final float[] mid = t.amid[fid];
            if(bit == 13 && mid != null){
                quad(s, mid, 4 * azar(s.vx, s.vy, 17, mid.length / 4), BLANCO, 0f);
            }else{
                int nv = t.autoVars[fid];
                int v = nv > 1 ? azar(s.vx, s.vy, 13, nv) : 0;
                quad(s, auv, (v * 47 + bit) * 4, BLANCO, 0f);
            }
        }else{
            quad(s, vuv, 4 * azar(s.vx, s.vy, 11, vuv.length / 4), BLANCO, 0f);
        }
        if(fid != fidReal){
            // SteamVent.drawMain: el sprite se dibuja solo si las 9 casillas (esta y las 8 de abajo-izquierda) son del mismo respiradero, y se
            // emite en la casilla de ARRIBA-DERECHA del bloque (la última en orden de dibujo), centrado en la casilla central.
            final float[] vv = t.vuv[fidReal];
            if(vv != null && ventanaCompleta(s)) quadEn(s, vv, 4 * azar(s.vx, s.vy, 29, vv.length / 4), BLANCO, (s.lx - 1) * 8f, (s.ly - 1) * 8f, 12f);
        }
        if(t.bordeEntra[fid]) bordes(s, true, t.capa[fid]);
        // drawOverlay: la mena de suelo va encima del piso, en la misma capa
        final int oid = s.oid;
        if(oid != 0 && oid != fid && t.tipoDe(oid) == 2){
            final float[] ov = t.vuv[oid];
            if(ov != null) quad(s, ov, 4 * azar(s.vx, s.vy, 19, ov.length / 4), BLANCO, 0f);
        }
    }

    /**
     * Bordes entre pisos. dentro = true replica Floor.drawEdges (esta casilla es de la capa que se cocina y recibe los bordes de
     * vecinos con id de blend mayor); dentro = false replica Floor.drawNonLayer (la casilla es de OTRA capa y recibe los bordes
     * de los vecinos que sí son de la capa que se cocina).
     */
    private static void bordes(Scratch s, boolean dentro, int capaRef){
        final Tabla t = s.t;
        final int fid = s.fid;
        final int[] bl = s.bl, dirs = s.dirs;
        int nbl = 0;
        for(int i = 0; i < 8; i++){
            dirs[i] = 0;
            final int nf = s.piso[s.p + OFF[i]] & 0xffff;
            if(nf >= t.n || t.tipo[nf] != 1 || !t.bordeSale[nf] || t.capa[nf] != capaRef) continue;
            if(t.borde[t.grupo[nf]] == null) continue;
            if(dentro && !(t.grupo[nf] > t.grupo[fid] || t.borde[t.grupo[fid]] == null)) continue;      // doEdge()
            boolean ya = false;
            for(int k = 0; k < nbl; k++){
                if(bl[k] == nf){ ya = true; break; }
            }
            if(!ya) bl[nbl++] = nf;
            if(dentro) dirs[i] = nf;
        }
        if(nbl == 0) return;
        // orden ascendente por id, como blenders.sort(...) en drawBlended
        for(int a = 1; a < nbl; a++){
            int v = bl[a], b = a - 1;
            while(b >= 0 && bl[b] > v){ bl[b + 1] = bl[b]; b--; }
            bl[b + 1] = v;
        }
        for(int k = 0; k < nbl; k++){
            final int bid = bl[k];
            final float[] reg = t.borde[t.grupo[bid]];
            for(int i = 0; i < 8; i++){
                final int nf = s.piso[s.p + OFF[i]] & 0xffff;
                if(nf == bid && (!dentro || dirs[i] == bid)){
                    // Floor.edge(x, y, 1 - dx, 1 - dy) = edges[rx][2 - ry]
                    final int idx = ((1 - NDX[i]) * 3 + (1 + NDY[i])) * 4;
                    quad(s, reg, idx, BLANCO, 0f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ muros (StaticWall.drawBase)
    private static void dibujarMuro(Scratch s){
        final Tabla t = s.t;
        final int bid = s.bid;
        final float[] vuv = t.vuv[bid];
        if(vuv == null) return;
        final float[] auv = t.auv[bid];
        if(auv != null){
            int bits = 0;
            for(int i = 0; i < 8; i++){
                if((s.bloque[s.p + OFF[i]] & 0xffff) == bid) bits |= 1 << i;
            }
            final int bit = TileBitmask.values[bits];
            final float[] mid = t.amid[bid];
            if(bit == 13 && mid != null) quad(s, mid, 4 * azar(s.vx, s.vy, 23, mid.length / 4), s.luz, GROW_MUROS);
            else quad(s, auv, bit * 4, s.luz, GROW_MUROS);
        }else{
            final float[] g = t.grande[bid];
            boolean grande = false;
            if(g != null){
                // muro grande 2x2: solo si las 4 casillas del bloque par son del mismo muro, y con probabilidad 1/2
                final int p0 = (s.ly & ~1) * ANCHO + (s.lx & ~1) + PAD * ANCHO + PAD;
                if((s.bloque[p0] & 0xffff) == bid && (s.bloque[p0 + 1] & 0xffff) == bid
                    && (s.bloque[p0 + ANCHO] & 0xffff) == bid && (s.bloque[p0 + ANCHO + 1] & 0xffff) == bid){
                    int rx = (s.vx >> 1) << 1, ry = (s.vy >> 1) << 1;
                    grande = (hash(rx, ry, 7) & 0xffff) < 0x8000;
                }
            }
            if(grande){
                quad(s, g, ((s.lx & 1) * 2 + (1 - (s.ly & 1))) * 4, s.luz, GROW_MUROS);
            }else{
                quad(s, vuv, 4 * azar(s.vx, s.vy, 3, vuv.length / 4), s.luz, GROW_MUROS);
            }
        }
        // StaticWall.drawBase: la mena de muro se dibuja encima del muro
        final int oid = s.oid;
        if(oid != 0 && t.tipoDe(oid) == 4){
            final float[] ov = t.vuv[oid];
            if(ov != null) quad(s, ov, 4 * azar(s.vx, s.vy, 5, ov.length / 4), s.luz, GROW_MUROS);
        }
    }
}
