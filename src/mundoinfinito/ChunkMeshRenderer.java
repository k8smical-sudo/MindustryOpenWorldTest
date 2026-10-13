package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.graphics.Blending;
import arc.graphics.Camera;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.Texture;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.gl.Shader;
import arc.struct.LongMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.CacheLayer;
import mindustry.graphics.FloorRenderer;
import mindustry.graphics.Layer;
import mundoinfinito.ChunkGeometryCooker.Cocinado;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

/**
 * RENDERIZADOR DE CHUNKS (estilo Sodium). Dibuja el terreno (pisos, bordes, menas y muros estáticos) SIN pasar por la matriz
 * de casillas de Mindustry:
 *
 *   hilos de fondo  ->  ChunkGeometryCooker (float[] de vértices)  ->  aquí: subida a una Mesh de GPU  ->  1 draw call por chunk y capa
 *
 * Qué cambia respecto al juego:
 *  1. Se desactiva el FloorRenderer vanilla (sus campos privados 'cache' y 'dirty' se ponen a null por reflexión y se liberan
 *     sus mallas). Con eso Vars.world.rawTile(...).setFloor()/setBlock() ya no marcan chunks "sucios" que el juego recachea en
 *     el hilo principal al dibujar (el microcongelamiento), y desaparece la duplicación de texturas del suelo.
 *  2. Lo visible se decide por chunk VIRTUAL contra el viewport de Core.camera (frustum culling estricto): lo que no se ve no
 *     se cocina ni se sube ni se dibuja. La cámara puede estar fuera de la ventana de tiles: el terreno sale de CacheChunks.
 *  3. Pool fijo de Mesh asignado al iniciar (MALLAS). Se reciclan por LRU; nunca se crea ni se destruye una Mesh en juego.
 *
 * Si la reflexión falla (otra versión del juego), el renderizador NO se activa y el juego sigue dibujando como siempre.
 * Seguro: solo se usa en el hilo de renderizado (Trigger.draw y los callbacks de Draw.draw).
 */
final class ChunkMeshRenderer{
    /** Mallas de GPU asignadas al inicio: cada una cuesta MAX_SPRITES * 4 * 20 B ~ 480 KB. Chunks visibles simultáneos máximos. */
    static final int MALLAS = 48;
    /** Tope al que puede crecer el pool (según la RAM de Java): con zoom alejado en el móvil se ven más de 48 chunks a la vez. */
    private static int mallasMax = MALLAS, mallasTotal;
    private static VertexAttribute[] atributosMalla;
    private static short[] indicesMalla;
    static final int MAX_SPRITES = ChunkGeometryCooker.MAX_SPRITES;
    static final int CAPAS = ChunkGeometryCooker.MAX_CAPAS;
    private static final int FLOATS_SPRITE = ChunkGeometryCooker.FLOATS_SPRITE;
    private static final float CH = Coord.CHUNK * 8f;       // lado de un chunk en unidades de mundo (256)

    private static final int LIBRE = 0, SIN_DATOS = 1, COCINANDO = 2, LISTA = 3;
    private static final int MAX_COCCIONES_FRAME = 4, MAX_SUBIDAS_FRAME = 4, MAX_TERRENO_FRAME = 8;
    /**
     * Tiempo máximo por fotograma subiendo mallas a la GPU (siempre se permite al menos UNA, para que cargar avance). Como el
     * presupuesto de mallas de Luanti: lo que no cabe en el fotograma sigue en la cola de salida y se sube en el siguiente.
     */
    private static final long SUBIDA_NS = 1_200_000L, SUBIDA_LENTO_NS = 500_000L;
    /** Una ranura sin verse tanto tiempo (~60 s) se libera aunque sobren ranuras; antes, ~1,5 s (volver a una zona obligaba a recocinarla). */
    private static final int OLVIDO_FRAMES = 3600;
    private static final int MAX_VISIBLES = 512;

    private static final String VERT =
        "attribute vec2 a_position;\n" +
        "attribute vec4 a_color;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "uniform mat4 u_proj;\n" +
        "uniform vec2 u_off;\n" +
        "varying vec4 v_color;\n" +
        "varying vec2 v_texCoords;\n" +
        "void main(){\n" +
        "  v_color = a_color;\n" +
        "  v_color.a = v_color.a * (255.0 / 254.0);\n" +
        "  v_texCoords = a_texCoord0;\n" +
        // la posición del vértice es LOCAL al chunk; u_off lo coloca en el mundo (así la malla vale aunque la ventana se mueva)
        "  gl_Position = u_proj * vec4(a_position + u_off, 0.0, 1.0);\n" +
        "}\n";
    private static final String FRAG =
        "varying vec4 v_color;\n" +
        "varying vec2 v_texCoords;\n" +
        "uniform sampler2D u_texture;\n" +
        "void main(){\n" +
        "  gl_FragColor = v_color * texture2D(u_texture, v_texCoords);\n" +
        "}\n";

    /** Estado de un chunk virtual visible (o recién visible). Se reciclan; sin asignaciones por fotograma. */
    private static final class Ranura{
        long clave;
        int vcx, vcy;
        int estado, ultimoVisto, proximo, faltantes;
        boolean oscuro, recocinando;
        /** LISTA y sin geometría (interior macizo de roca): no ocupa malla de GPU. */
        boolean vacia;
        Mesh malla;
        float offX, offY;       // posición del chunk en el mundo de juego (unidades), recalculada cada fotograma
        final int[] ini = new int[CAPAS], cuenta = new int[CAPAS];

        void reiniciar(){
            estado = LIBRE;
            malla = null;
            vacia = false;
            recocinando = false;
            faltantes = 0;
            proximo = 0;
            java.util.Arrays.fill(cuenta, 0);
        }
    }

    private static boolean iniciado, habilitado, vanillaApagado;
    private static Shader shader;
    private static Texture atlas;
    private static final ArrayDeque<Mesh> mallasLibres = new ArrayDeque<>();
    private static final ArrayDeque<Ranura> ranurasLibres = new ArrayDeque<>();
    private static final LongMap<Ranura> mapa = new LongMap<>();
    private static final Ranura[] visibles = new Ranura[MAX_VISIBLES];
    private static final Ranura[] dibujo = new Ranura[MAX_VISIBLES];
    private static final Ranura[] cubiertos = new Ranura[MAX_VISIBLES];
    /**
     * Chunks dentro de la ventana de tiles con la malla LISTA pero con las casillas aún sin escribir (Streamer.generados no los tiene).
     * Antes se tapaban en negro hasta que sus casillas se aplicaban (~0,9 ms por fotograma): al moverse, el borde de la pantalla
     * parpadeaba en negro aunque el terreno ya estuviera calculado y cocinado. Ahora se dibujan con su propia malla POR ENCIMA de lo
     * que haya en las casillas (restos de rocas de la ventana anterior), así el terreno visible no depende de las casillas.
     */
    private static final Ranura[] sobrepuestos = new Ranura[MAX_VISIBLES];
    private static int nVisibles, nDibujo, nCubiertos, nSobrepuestos, cuadro, mundoVisto = Integer.MIN_VALUE;
    private static int cocciones, terreno;
    private static final int[] lcx = new int[MAX_VISIBLES], lcy = new int[MAX_VISIBLES];
    private static final float[] ldist = new float[MAX_VISIBLES];
    private static final long[] porBorrar = new long[256];
    private static final int[] NDX = {1, 1, 0, -1, -1, -1, 0, 1}, NDY = {0, 1, 1, 1, 0, -1, -1, -1};
    private static boolean avisado;

    // reflexión sobre el FloorRenderer del juego
    private static FloorRenderer suelo;
    private static Field fCache, fDirty, fSumergidos;
    private static Seq<Runnable> sumergidos;

    private static final Blending BAJO_AGUA = new Blending(Gl.srcAlpha, Gl.oneMinusSrcAlpha, Gl.dstAlpha, Gl.oneMinusSrcAlpha);
    private static final Runnable DIBUJAR_SUELO = () -> { long t0 = Perfil.t(); pasada(false); Perfil.fin(Perfil.S_RENDER_DIBUJO, t0); };
    private static final Runnable DIBUJAR_MUROS = () -> { long t0 = Perfil.t(); pasada(true); Perfil.fin(Perfil.S_RENDER_DIBUJO, t0); };
    /** Suelo y muros de los chunks 'sobrepuestos', a la altura de la capa de cubierta (sobre rocas/props, bajo unidades y balas). */
    private static final Runnable DIBUJAR_SOBREPUESTOS = () -> {
        long t0 = Perfil.t();
        pasada(false, sobrepuestos, nSobrepuestos, false);
        pasada(true, sobrepuestos, nSobrepuestos, false);
        Perfil.fin(Perfil.S_RENDER_DIBUJO, t0);
    };

    private ChunkMeshRenderer(){}

    // ================================================================== arranque
    /** HILO PRINCIPAL (hay contexto GL). Llamar una vez, p. ej. en el ClientLoadEvent. */
    static void iniciar(){
        if(iniciado || Vars.headless) return;
        iniciado = true;
        try{
            // 1) reflexión: sin ella no se toca nada del renderizador vanilla
            suelo = Vars.renderer.blocks.floor;
            fCache = FloorRenderer.class.getDeclaredField("cache");
            fDirty = FloorRenderer.class.getDeclaredField("dirty");
            fCache.setAccessible(true);
            fDirty.setAccessible(true);
            try{
                fSumergidos = FloorRenderer.class.getDeclaredField("underwaterDraw");
                fSumergidos.setAccessible(true);
                @SuppressWarnings("unchecked") Seq<Runnable> sd = (Seq<Runnable>)fSumergidos.get(suelo);
                sumergidos = sd;
            }catch(Throwable ignored){
                sumergidos = null;
            }

            // 2) cocinador (hilos de fondo + tabla de regiones del atlas)
            ChunkGeometryCooker.iniciar();
            atlas = Core.atlas.find("grass1").texture;

            // 3) shader propio: posición local al chunk + uniform de desplazamiento
            shader = new Shader(VERT, FRAG);

            // 4) pool de mallas (ARC: arc.graphics.Mesh). Vértice = position(2) + color(4 bytes = 1 float) + texCoords(2) = 5 floats.
            //    Los índices son los de un SpriteBatch (0,1,2, 2,3,0) y son idénticos para todas: se cargan una vez por malla.
            final VertexAttribute[] atributos = {VertexAttribute.position, VertexAttribute.color, VertexAttribute.texCoords};
            final short[] indices = new short[MAX_SPRITES * 6];
            atributosMalla = atributos;
            indicesMalla = indices;
            mallasMax = Math.max(MALLAS, Math.min(96, (int)(Runtime.getRuntime().maxMemory() >> 22)));   // ~4 MB de heap por malla de margen
            for(int i = 0, v = 0; i < MAX_SPRITES; i++, v += 4){
                int k = i * 6;
                indices[k] = (short)v;
                indices[k + 1] = (short)(v + 1);
                indices[k + 2] = (short)(v + 2);
                indices[k + 3] = (short)(v + 2);
                indices[k + 4] = (short)(v + 3);
                indices[k + 5] = (short)v;
            }
            for(int i = 0; i < MALLAS; i++){
                Mesh m = new Mesh(false, MAX_SPRITES * 4, MAX_SPRITES * 6, atributos);   // isStatic=false: se reescribe al reciclar
                m.setIndices(indices);
                mallasLibres.add(m);
                mallasTotal++;
            }
            for(int i = 0; i < MAX_VISIBLES; i++) ranurasLibres.add(new Ranura());

            habilitado = true;
            // 5) enganche al bucle de dibujo de Arc: Trigger.draw se dispara dentro de Renderer.draw(), con Draw.sort(true) activo
            Events.run(Trigger.draw, ChunkMeshRenderer::alCuadro);
            Log.info("[MundoInfinito] Renderizador por chunks listo (@ mallas de GPU).", MALLAS);
        }catch(Throwable t){
            habilitado = false;
            Log.err("[MundoInfinito] No se pudo activar el renderizador por chunks; se usa el del juego.", t);
            aviso("Render por chunks: NO activo (" + t.getClass().getSimpleName() + "); sigue el suelo del juego");
        }
    }

    /** Aviso en pantalla (una vez por tipo de mensaje): así se sabe sin mirar el log si el renderizador nuevo está en marcha. */
    private static void aviso(String texto){
        Log.info("[MundoInfinito] @", texto);
        try{
            if(!avisado && Vars.ui != null){
                avisado = true;
                Vars.ui.showInfoToast(texto, 4f);
            }
        }catch(Throwable ignored){}
    }

    // ================================================================== renderizador vanilla: apagar / restaurar
    /**
     * Pone a null los campos 'cache' y 'dirty' del FloorRenderer. Con cache == null, drawFloor/beginDraw/drawLayer salen al
     * principio y recacheTile no hace nada. Hay que repetirlo: WorldLoadEvent llama a FloorRenderer.reload(), que los recrea.
     */
    private static void apagarVanilla(){
        try{
            Object cache = fCache.get(suelo);
            if(cache != null){
                disponer(cache);            // libera las mallas vanilla: si no, quedarían huérfanas en la GPU
                fCache.set(suelo, null);
                fDirty.set(suelo, null);
            }
            vanillaApagado = true;
            aviso("Render por chunks: ACTIVO (suelo del juego apagado)");
        }catch(Throwable t){
            habilitado = false;
            Log.err("[MundoInfinito] Falló la reflexión sobre FloorRenderer; renderizador por chunks desactivado.", t);
            aviso("Render por chunks: NO activo (" + t.getClass().getSimpleName() + "); sigue el suelo del juego");
        }
    }

    private static void disponer(Object o){
        if(o instanceof Mesh) ((Mesh)o).dispose();
        else if(o instanceof Object[]){
            for(Object e : (Object[])o) disponer(e);
        }
    }

    /** Fuera del mundo del mod: el juego vuelve a dibujar con su FloorRenderer. */
    private static void restaurarVanilla(){
        if(!vanillaApagado) return;
        vanillaApagado = false;
        vaciarTodo();
        nCubiertos = 0;
        nSobrepuestos = 0;
        try{
            if(Vars.world != null && Vars.world.tiles != null) suelo.reload();
        }catch(Throwable t){
            Log.warn("[MundoInfinito] No se pudo reconstruir el suelo vanilla: @", t.toString());
        }
    }

    // ================================================================== bucle por fotograma
    private static void alCuadro(){
        if(!habilitado) return;
        if(!Streamer.activo || Streamer.meta == null || Vars.state == null || Vars.state.isMenu() || Core.camera == null){
            restaurarVanilla();
            return;
        }
        try{
            if(!vanillaApagado || fCache.get(suelo) != null) apagarVanilla();
            if(!habilitado) return;
            final long tAct = Perfil.t();
            actualizar();
            Perfil.fin(Perfil.S_RENDER_ACTUALIZAR, tAct);
            if(nCubiertos > 0){
                final float z0 = Draw.z();
                Draw.z(Layer.blockOver + 0.5f);         // sobre suelo, muros y rocas; bajo edificios, unidades y balas
                Draw.color(0f, 0f, 0f, 1f);
                for(int i = 0; i < nCubiertos; i++){
                    final Ranura r = cubiertos[i];
                    Fill.crect(r.offX - 4.5f, r.offY - 4.5f, CH + 1f, CH + 1f);   // 0,5 de solape: sin costuras
                }
                Draw.color();
                Draw.z(z0);
            }
            if(nSobrepuestos > 0) Draw.draw(Layer.blockOver + 0.5f, DIBUJAR_SOBREPUESTOS);   // misma altura que la cubierta negra
            Draw.draw(Layer.floor, DIBUJAR_SUELO);                  // misma capa y orden que Renderer.draw()
            Draw.draw(Layer.block - 0.09f, DIBUJAR_MUROS);
        }catch(Throwable t){
            habilitado = false;
            Log.err("[MundoInfinito] Error en el renderizador por chunks; vuelve el del juego.", t);
            restaurarVanilla();
        }
    }

    private static void vaciarTodo(){
        for(LongMap.Entry<Ranura> e : mapa){
            Ranura r = e.value;
            if(r.malla != null) mallasLibres.add(r.malla);
            r.reiniciar();
            ranurasLibres.add(r);
        }
        mapa.clear();
        nVisibles = nDibujo = 0;
    }

    private static void actualizar(){
        final int mid = CacheChunks.mundo();
        if(mid != mundoVisto){
            vaciarTodo();               // otro mundo / otra semilla: nada de lo cocinado vale
            mundoVisto = mid;
        }
        cuadro++;
        cocciones = terreno = 0;
        if(cuadro % 120 == 1) ChunkGeometryCooker.revisarTabla();

        final Camera cam = Core.camera;
        final float hw = cam.width * 0.5f, hh = cam.height * 0.5f;
        final float camL = cam.position.x - hw, camR = cam.position.x + hw, camB = cam.position.y - hh, camT = cam.position.y + hh;

        // ---- zona de cocinado = viewport + 1 chunk de margen (el cocinado tarda 1-2 fotogramas: así no hay huecos al borde) ----
        // + 1 chunk MÁS por el lado hacia el que se mueve la cámara: cocinar tarda (terreno + vecinos + cocción + subida) y a velocidad
        // el borde de la pantalla alcanzaba a los chunks antes de que tuvieran malla.
        final float dX = InfiniteCameraHandler.dirX, dY = InfiniteCameraHandler.dirY;
        final int aX0 = dX < -0.3f ? 1 : 0, aX1 = dX > 0.3f ? 1 : 0, aY0 = dY < -0.3f ? 1 : 0, aY1 = dY > 0.3f ? 1 : 0;
        final int x0 = Math.floorDiv((int)Math.floor(camL - CH), (int)CH) - aX0, x1 = Math.floorDiv((int)Math.floor(camR + CH), (int)CH) + aX1;
        final int y0 = Math.floorDiv((int)Math.floor(camB - CH), (int)CH) - aY0, y1 = Math.floorDiv((int)Math.floor(camT + CH), (int)CH) + aY1;
        int n = 0;
        final float mx = cam.position.x, my = cam.position.y;
        for(int cy = y0; cy <= y1 && n < MAX_VISIBLES; cy++){
            for(int cx = x0; cx <= x1 && n < MAX_VISIBLES; cx++){
                float dx = (cx + 0.5f) * CH - mx, dy = (cy + 0.5f) * CH - my, d = dx * dx + dy * dy;
                int j = n++;
                while(j > 0 && ldist[j - 1] > d){        // inserción: los chunks más cercanos a la cámara van primero
                    ldist[j] = ldist[j - 1];
                    lcx[j] = lcx[j - 1];
                    lcy[j] = lcy[j - 1];
                    j--;
                }
                ldist[j] = d;
                lcx[j] = cx;
                lcy[j] = cy;
            }
        }

        // ---- estado de cada chunk de la zona: pedir terreno / cocinar / revisar si quedó obsoleto ----
        nVisibles = 0;
        for(int i = 0; i < n; i++){
            final int vcx = Coord.vcx(lcx[i]), vcy = Coord.vcy(lcy[i]);
            final long k = Coord.claveVirtual(vcx, vcy);
            Ranura r = mapa.get(k, null);
            if(r == null){
                r = ranurasLibres.poll();
                if(r == null) continue;                 // sin ranuras: ya hay demasiados chunks en juego
                r.reiniciar();
                r.clave = k;
                mapa.put(k, r);
            }
            // ¿vuelve a verse un chunk que se conservó fuera de pantalla? Entonces se revisa YA (no en su turno de 1 de cada 16
            // fotogramas): su malla cocinada sirve tal cual salvo que haya cambiado la oscuridad o hayan llegado vecinos.
            final boolean regreso = r.ultimoVisto < cuadro - 1;
            r.ultimoVisto = cuadro;
            r.vcx = vcx;
            r.vcy = vcy;
            r.offX = lcx[i] * CH;
            r.offY = lcy[i] * CH;
            visibles[nVisibles++] = r;
            if(r.estado == LIBRE || r.estado == SIN_DATOS){
                intentarCocinar(r, mid, vcx, vcy);
            }else if(r.estado == LISTA && !r.recocinando && (regreso || ((cuadro + (int)(k & 15)) & 15) == 0)){
                revisarObsoleto(r, mid, vcx, vcy);
            }
        }

        // ---- precarga de TERRENO (solo a la caché, sin cocinar) hacia donde va la cámara: evita el "vacío" al moverla rápido ----
        if((cuadro & 3) == 0) precargar(mid);

        subirListos(mid);

        // ---- frustum culling estricto: solo se dibuja el chunk cuyo rectángulo corta el viewport ----
        // Regla "como Minecraft": un chunk solo se muestra cuando está COMPLETO. Dentro de la ventana de tiles eso significa que su
        // malla esté lista y que sus casillas reales ya estén aplicadas (Streamer.generados); si no, se tapa con negro, así no se
        // ven rocas, sombras ni edificios sueltos de casillas viejas o de la reubicación. Fuera de la ventana basta con la malla.
        nDibujo = 0;
        nCubiertos = 0;
        nSobrepuestos = 0;
        for(int i = 0; i < nVisibles; i++){
            final Ranura r = visibles[i];
            if(r.offX + CH - 4f < camL || r.offX - 4f > camR || r.offY + CH - 4f < camB || r.offY - 4f > camT) continue;
            final boolean listo = r.estado == LISTA && (r.malla != null || r.vacia) && r.faltantes == 0;
            final int lx = Coord.cxLocal(r.vcx), ly = Coord.cyLocal(r.vcy);
            final boolean enVentana = Streamer.enMapa(lx, ly);
            final boolean aplicado = !enVentana || Streamer.generados.contains(Streamer.clave(lx, ly));
            if(listo && aplicado) dibujo[nDibujo++] = r;
            else if(listo) sobrepuestos[nSobrepuestos++] = r;   // malla lista, casillas sin escribir: se ve su malla, sin negro
            else if(enVentana) cubiertos[nCubiertos++] = r;     // sin malla todavía: negro (es lo único que puede quedar)
        }

        if((cuadro % 30) == 0) reciclar();
        if((cuadro % 900) == 0){
            Log.info("[MundoInfinito] Render chunks: @ visibles, @ dibujados, @ sobre casillas sin aplicar, @ en negro (sin malla), @ mallas libres de @, @ ranuras en uso", nVisibles, nDibujo, nSobrepuestos, nCubiertos, mallasLibres.size(), mallasTotal, mapa.size);
        }
    }

    /** ¿El chunk está "de verdad" en la ventana de tiles? Entonces el juego ya aplica su propia oscuridad y no se hornea. */
    private static boolean oscuroDeseado(int vcx, int vcy){
        final int lx = Coord.cxLocal(vcx), ly = Coord.cyLocal(vcy);
        if(!Streamer.enMapa(lx, ly)) return true;
        return !Streamer.generados.contains(Streamer.clave(lx, ly));
    }

    /**
     * Un chunk se cocina UNA sola vez y solo cuando él y sus 8 vecinos están en la caché: así sus bordes y su autotiling nacen
     * definitivos y no cambian de patrón cuando llega un vecino (el "cambio de textura al terminar de cargar"). Lo que falte se
     * pide a los hilos de terreno y mientras tanto el chunk no se muestra.
     */
    private static void intentarCocinar(Ranura r, int mid, int vcx, int vcy){
        if(cuadro < r.proximo) return;
        boolean completo = true;
        if(!CacheChunks.tiene(mid, r.clave)){
            completo = false;
            if(terreno < MAX_TERRENO_FRAME && pedirTerreno(r.clave)) terreno++;
        }
        for(int i = 0; i < 8; i++){
            final long k = Coord.claveVirtual(vcx + NDX[i], vcy + NDY[i]);
            if(!CacheChunks.tiene(mid, k)){
                completo = false;
                if(terreno < MAX_TERRENO_FRAME && pedirTerreno(k)) terreno++;
            }
        }
        if(completo){
            if(cocciones < MAX_COCCIONES_FRAME && ChunkGeometryCooker.solicitar(r.clave, mid, oscuroDeseado(vcx, vcy))){
                r.estado = COCINANDO;
                cocciones++;
            }
        }else{
            r.estado = SIN_DATOS;
            r.proximo = cuadro + 10;                    // reintento en ~0,15 s
        }
    }

    /** Un chunk ya dibujado se recocina si cambió la oscuridad deseada o si le llegaron vecinos que faltaban (bordes/autotiling). */
    private static void revisarObsoleto(Ranura r, int mid, int vcx, int vcy){
        if(cocciones >= MAX_COCCIONES_FRAME) return;
        boolean obsoleto = oscuroDeseado(vcx, vcy) != r.oscuro;
        if(!obsoleto && r.faltantes != 0){
            for(int i = 0; i < 8 && !obsoleto; i++){
                if((r.faltantes & (1 << i)) == 0) continue;
                final long kv = Coord.claveVirtual(vcx + NDX[i], vcy + NDY[i]);
                if(CacheChunks.tiene(mid, kv)) obsoleto = true;
                else if(terreno < MAX_TERRENO_FRAME && pedirTerreno(kv)) terreno++;   // antes nadie lo pedía: el chunk quedaba tapado
            }
        }
        if(obsoleto && ChunkGeometryCooker.solicitar(r.clave, mid, oscuroDeseado(vcx, vcy))){
            r.recocinando = true;                       // sigue mostrando la malla vieja hasta que llegue la nueva
            cocciones++;
        }
    }

    private static void precargar(int mid){
        final float x0 = InfiniteCameraHandler.predX0, x1 = InfiniteCameraHandler.predX1;
        final float y0 = InfiniteCameraHandler.predY0, y1 = InfiniteCameraHandler.predY1;
        if(x1 <= x0 || y1 <= y0) return;
        final int cx0 = Math.floorDiv((int)Math.floor(x0), (int)CH), cx1 = Math.floorDiv((int)Math.floor(x1), (int)CH);
        final int cy0 = Math.floorDiv((int)Math.floor(y0), (int)CH), cy1 = Math.floorDiv((int)Math.floor(y1), (int)CH);
        for(int cy = cy0; cy <= cy1; cy++){
            for(int cx = cx0; cx <= cx1; cx++){
                if(terreno >= MAX_TERRENO_FRAME) return;
                long k = Coord.claveVirtual(Coord.vcx(cx), Coord.vcy(cy));
                if(!CacheChunks.tiene(mid, k) && pedirTerreno(k)) terreno++;
            }
        }
    }

    /** Mismo mecanismo que Ventana.pedirTerreno: cola de baja prioridad de los hilos de terreno (solo a la caché). */
    private static boolean pedirTerreno(long vk){
        synchronized(Streamer.cola){
            if(Streamer.colaBaja.size() > 160) return false;
            if(Streamer.pendBaja.add(vk)){
                Streamer.colaBaja.addLast(vk);
                Streamer.cola.notifyAll();
            }
            return true;
        }
    }

    // ================================================================== subida a la GPU (hilo principal, con presupuesto)
    private static void subirListos(int mid){
        int subidas = 0;
        final long t0 = System.nanoTime();
        final long limite = Core.graphics != null && Core.graphics.getDeltaTime() > 0.033f ? SUBIDA_LENTO_NS : SUBIDA_NS;
        Cocinado co;
        // siempre sube al menos una (si hay); las siguientes solo mientras quede tiempo del fotograma
        while(subidas < MAX_SUBIDAS_FRAME && (subidas == 0 || System.nanoTime() - t0 < limite) && (co = ChunkGeometryCooker.recoger()) != null){
            final Ranura r = mapa.get(co.clave, null);
            if(r == null || co.mundo != mid){
                ChunkGeometryCooker.liberar(co);
                continue;
            }
            r.recocinando = false;
            if(!co.exito){
                if(r.estado != LISTA) r.estado = SIN_DATOS;
                r.proximo = cuadro + 20;
                ChunkGeometryCooker.liberar(co);
                continue;
            }
            if(co.nSprites == 0){
                // chunk macizo: nada que dibujar. No toma malla (y devuelve la que tuviera de una versión anterior no vacía).
                if(r.malla != null){ mallasLibres.add(r.malla); r.malla = null; }
                java.util.Arrays.fill(r.cuenta, 0);
                r.oscuro = co.oscuro;
                r.faltantes = co.faltantes;
                r.vacia = true;
                r.estado = LISTA;
                ChunkGeometryCooker.liberar(co);
                continue;                                  // no cuenta como subida: no toca la GPU
            }
            Mesh m = r.malla != null ? r.malla : tomarMalla();
            if(m == null){                              // sin mallas libres ni desalojables: se reintenta luego
                if(r.estado != LISTA) r.estado = SIN_DATOS;
                r.proximo = cuadro + 10;
                ChunkGeometryCooker.liberar(co);
                continue;
            }
            // ARC: Mesh.setVertices(float[], offset, count) copia 'count' floats al VBO de la GPU (una sola llamada por chunk).
            m.setVertices(co.datos, 0, co.nSprites * FLOATS_SPRITE);
            System.arraycopy(co.inicio, 0, r.ini, 0, CAPAS);
            System.arraycopy(co.cuenta, 0, r.cuenta, 0, CAPAS);
            r.oscuro = co.oscuro;
            r.faltantes = co.faltantes;
            r.malla = m;
            r.vacia = false;
            r.estado = LISTA;
            ChunkGeometryCooker.liberar(co);
            subidas++;
        }
    }

    /** Una malla libre; si no hay, desaloja la de un chunk que NO se ve (el menos reciente primero). */
    private static Mesh tomarMalla(){
        Mesh m = mallasLibres.poll();
        if(m != null) return m;
        if(mallasTotal < mallasMax && atributosMalla != null){
            // el pool se agota con el zoom alejado: crear una malla (~0,5 MB) cuesta menos que dejar chunks sin dibujar
            try{
                m = new Mesh(false, MAX_SPRITES * 4, MAX_SPRITES * 6, atributosMalla);
                m.setIndices(indicesMalla);
                mallasTotal++;
                if(mallasTotal == MALLAS + 1 || mallasTotal == mallasMax) Log.info("[MundoInfinito] Pool de mallas ampliado a @ (máx. @)", mallasTotal, mallasMax);
                return m;
            }catch(Throwable t){
                mallasMax = mallasTotal;   // sin memoria: no se intenta más
            }
        }
        Ranura peor = null;
        for(LongMap.Entry<Ranura> e : mapa){
            Ranura r = e.value;
            if(r.malla == null || r.ultimoVisto >= cuadro) continue;
            if(peor == null || r.ultimoVisto < peor.ultimoVisto) peor = r;
        }
        if(peor == null) return null;
        m = peor.malla;
        peor.malla = null;
        peor.estado = LIBRE;
        java.util.Arrays.fill(peor.cuenta, 0);
        return m;
    }

    /**
     * Cada 30 fotogramas. Los chunks que salen de pantalla CONSERVAN su malla ya cocinada y subida: si el jugador vuelve, se dibujan
     * al instante en vez de recocinarse y resubirse todos a la vez (el trabajo en ráfaga al regresar a una zona ya visitada).
     * Las mallas no se pierden por eso: cuando el pool se agota, tomarMalla() desaloja la del chunk invisible menos reciente
     * (LRU). Aquí solo se liberan RANURAS: las muy viejas (OLVIDO_FRAMES) o, si quedan pocas libres, las que llevan ~1,5 s sin verse.
     */
    private static void reciclar(){
        final boolean faltanRanuras = ranurasLibres.size() < MAX_VISIBLES / 4;
        final int umbral = faltanRanuras ? cuadro - 90 : cuadro - OLVIDO_FRAMES;
        int n = 0;
        for(LongMap.Entry<Ranura> e : mapa){
            if(e.value.ultimoVisto < umbral && n < porBorrar.length) porBorrar[n++] = e.key;
        }
        for(int i = 0; i < n; i++){
            Ranura r = mapa.remove(porBorrar[i]);
            if(r == null) continue;
            if(r.malla != null) mallasLibres.add(r.malla);
            r.reiniciar();
            ranurasLibres.add(r);
        }
    }

    // ================================================================== dibujo
    /** Prepara el estado GL propio. Mismo orden que FloorRenderer.beginDraw(): vaciar el batch, shader, textura, blend. */
    private static void enlazar(){
        shader.bind();
        // ARC: Core.camera.mat es la matriz proyección*vista de la cámara del juego
        shader.setUniformMatrix4("u_proj", Core.camera.mat);
        atlas.bind(0);                                  // solo se usa la textura del atlas del entorno
        shader.setUniformi("u_texture", 0);
        Gl.enable(Gl.blend);
    }

    /** muros = false: todas las capas de suelo (en el orden de CacheLayer.all); true: la capa de muros. */
    private static void pasada(boolean muros){
        pasada(muros, dibujo, nDibujo, true);
    }

    /** principal = la pasada normal del suelo (la única que ejecuta y vacía la lista de edificios sumergidos). */
    private static void pasada(boolean muros, Ranura[] lista, int cuenta, boolean principal){
        final boolean agua = principal && !muros && sumergidos != null && sumergidos.size > 0;
        if(cuenta == 0 && !agua) return;
        Draw.flush();                                   // sprites pendientes del batch de Arc antes de tocar el estado GL
        final CacheLayer[] capas = CacheLayer.all;
        for(int c = 0; c < capas.length; c++){
            final CacheLayer cl = capas[c];
            if((cl == CacheLayer.walls) != muros) continue;
            final int id = cl.id;
            if(id >= CAPAS) continue;
            boolean hay = false;
            for(int i = 0; i < cuenta && !hay; i++) hay = lista[i].cuenta[id] > 0;
            final boolean sub = principal && cl.liquid && sumergidos != null && sumergidos.size > 0;
            if(!hay && !sub) continue;

            cl.begin();                                 // capas de líquido: abre el framebuffer del shader de agua/alquitrán...
            enlazar();
            for(int i = 0; i < cuenta; i++){
                final Ranura r = lista[i];
                final int cnt = r.cuenta[id];
                if(cnt <= 0) continue;
                shader.setUniformf("u_off", r.offX, r.offY);
                // ARC: Mesh.render(shader, primitiva, offset, count) con índices: offset/count en ÍNDICES (6 por sprite)
                r.malla.render(shader, Gl.triangles, r.ini[id] * 6, cnt * 6);
            }
            if(sub){
                // lo que el juego dibuja "bajo el agua" (edificios sumergidos) se ejecuta una vez por capa líquida, como en vanilla
                Draw.blend(BAJO_AGUA);
                final Runnable[] items = sumergidos.items;
                final int len = sumergidos.size;
                for(int i = 0; i < len; i++) items[i].run();
                Draw.flush();
                Draw.blend(Blending.normal);
                Blending.normal.apply();
                enlazar();
            }
            cl.end();                                   // ...y lo vuelca a pantalla con su shader
        }
        // el FloorRenderer vanilla vacía esta lista al final de drawFloor; como ya no corre, lo hacemos aquí
        if(agua) sumergidos.clear();
    }
}
