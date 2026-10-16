package mundoinfinito;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.io.MapIO;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;
import arc.util.Time;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPInputStream;

/**
 * MINIMAPA DEL MOD, independiente del minimapa del juego. El del juego es una textura del tamaño de la ventana en
 * coordenadas locales, y cada tile que cambia cuesta una llamada de GPU de 1x1 píxel (por eso se cuelga al cargar chunks).
 * Este guarda una MINIATURA de 16x16 por chunk (cada píxel resume 2x2 tiles) en coordenadas VIRTUALES, por regiones en
 * disco, y muestra lo que el jugador ha recorrido sin importar dónde esté la ventana del juego.
 */
final class Mapa{
    static final int LADO = 16;                          // píxeles por lado de una miniatura
    static final int REG = MundoInfinitoMod.REGION_CHUNKS;
    static final int VISTO = 0xff101018;                 // color de lo no explorado

    // región (clave) -> miniaturas por índice dentro de la región (null = sin explorar)
    static final HashMap<Long, short[][]> regiones = new HashMap<>();
    static final HashSet<Long> sucias = new HashSet<>();
    static final HashSet<Long> enVuelo = new HashSet<>();
    static final ConcurrentLinkedQueue<Object[]> llegadas = new ConcurrentLinkedQueue<>();
    static volatile int epoca;
    static File carpeta;
    private static int cursor;

    // ---- núcleos propios REALES, en coordenadas virtuales (clave virtual -> {vx, vy}). Persisten en nucleos.dat.
    // El minimapa del juego solo conoce los de la ventana; el núcleo "proxy" oculto que el mod pone en una esquina
    // cuando el real queda fuera NO es un núcleo y nunca entra aquí.
    static final HashMap<Long, int[]> nucleos = new HashMap<>();
    static final ConcurrentLinkedQueue<int[]> nucleosLlegados = new ConcurrentLinkedQueue<>();
    private static boolean nucleosSucios;
    private static float tNucleos;
    private static final HashSet<Long> vivos = new HashSet<>();

    private Mapa(){}

    static void iniciar(File dir){
        carpeta = dir;
        regiones.clear();
        sucias.clear();
        enVuelo.clear();
        llegadas.clear();
        epoca++;
        cursor = 0;
        anillo.clear();
        anilloR = -1;
        nucleos.clear();
        nucleosLlegados.clear();
        nucleosSucios = false;
        tNucleos = 0f;
        cargarNucleos();
    }

    // ---------------- núcleos ----------------
    static File archivoNucleos(){
        return new File(carpeta, "nucleos.dat");
    }

    private static void cargarNucleos(){
        if(carpeta == null) return;
        File f = archivoNucleos();
        if(!f.exists()){
            escanearAntiguas();   // partida anterior a este registro: se buscan los núcleos en las regiones ya guardadas
            return;
        }
        try(DataInputStream in = new DataInputStream(new FileInputStream(f))){
            in.readInt();
            int n = in.readInt();
            for(int i = 0; i < n; i++){
                int vx = in.readInt(), vy = in.readInt();
                nucleos.put(Coord.claveVirtual(vx, vy), new int[]{vx, vy});
            }
        }catch(Throwable t){
            Log.err("[MundoInfinito] Registro de núcleos ilegible", t);
        }
    }

    /** Una sola vez, para mundos guardados antes de existir nucleos.dat: lee las regiones en el hilo de E/S, una tarea por archivo. */
    private static void escanearAntiguas(){
        nucleosSucios = true;   // aunque no se encuentre nada, se escribe el archivo para no repetir la búsqueda
        File[] fs = new File(carpeta, "regiones").listFiles();
        if(fs == null) return;
        final int ep = epoca;
        for(final File f : fs){
            String nom = f.getName();
            if(!nom.startsWith("r.") || !nom.endsWith(".dat")) continue;
            Disco.leer(() -> {
                try{
                    MundoInfinitoMod.Regiones.Region r = new MundoInfinitoMod.Regiones.Region(0, 0);
                    MundoInfinitoMod.Regiones.leer(r, new arc.files.Fi(f));
                    for(MundoInfinitoMod.Regiones.ChunkGuardado c : r.chunks.values()){
                        for(MundoInfinitoMod.Reg b : c.edificios){
                            if(b.bloque != null && b.bloque.startsWith("core-") && b.equipo == Team.sharded.id && ep == epoca){
                                nucleosLlegados.add(new int[]{b.x, b.y});
                            }
                        }
                    }
                }catch(Throwable t){
                    Log.warn("[MundoInfinito] No se pudo buscar núcleos en @", f.getName());
                }
            });
        }
    }

    static byte[] bytesNucleos(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(1);
            out.writeInt(nucleos.size());
            for(int[] v : nucleos.values()){
                out.writeInt(v[0]);
                out.writeInt(v[1]);
            }
            out.flush();
            return bos.toByteArray();
        }catch(Throwable t){
            return null;
        }
    }

    /**
     * Mantiene el registro: añade los núcleos reales de la ventana y quita los que ya no existen (solo si su chunk está
     * cargado y ya restaurado). Barato: una vez por segundo.
     */
    static void actualizarNucleos(){
        if(Time.time - tNucleos < 60f) return;
        tNucleos = Time.time;
        int[] a;
        while((a = nucleosLlegados.poll()) != null){
            if(nucleos.putIfAbsent(Coord.claveVirtual(a[0], a[1]), a) == null) nucleosSucios = true;
        }
        vivos.clear();
        for(Building n : Vars.state.teams.get(Team.sharded).cores){
            if(n.tile == null || n.tile.pos() == MundoInfinitoMod.Mundos.proxyPos) continue;   // el proxy no es un núcleo
            int vx = n.tile.x + Streamer.ox, vy = n.tile.y + Streamer.oy;
            long k = Coord.claveVirtual(vx, vy);
            vivos.add(k);
            if(!nucleos.containsKey(k)){
                nucleos.put(k, new int[]{vx, vy});
                nucleosSucios = true;
            }
        }
        boolean estable = !Streamer.restaurando && !MundoInfinitoMod.Mundos.viajando
            && Streamer.colaRestaurar.isEmpty();
        if(!estable) return;
        final int tam = Streamer.meta == null ? 0 : Streamer.meta.tam;
        java.util.Iterator<java.util.Map.Entry<Long, int[]>> it = nucleos.entrySet().iterator();
        while(it.hasNext()){
            java.util.Map.Entry<Long, int[]> e = it.next();
            if(vivos.contains(e.getKey())) continue;
            int[] v = e.getValue();
            int lx = v[0], ly = v[1];
            int cx = Math.floorDiv(lx, Coord.CHUNK), cy = Math.floorDiv(ly, Coord.CHUNK);
            if(!Streamer.generados.contains(Streamer.clave(cx, cy))) continue;           // chunk no cargado: se conserva
            if(!Anillo.restaurados.contains(Streamer.clave(cx, cy))) continue;                  // aún no se restauraron sus edificios
            Tile t = Vars.world.tile(lx, ly);
            if(t != null && t.build instanceof CoreBlock.CoreBuild) continue;
            it.remove();                                                    // el núcleo ya no está: destruido
            nucleosSucios = true;
        }
    }

    static long kReg(int rx, int ry){
        return Coord.claveVirtual(rx, ry);
    }

    static File archivo(int rx, int ry){
        return new File(carpeta, "mapa_" + rx + "_" + ry + ".dat");
    }

    // ---------------- color ----------------
    static short a565(int rgba){
        int r = (rgba >>> 24) & 0xff, g = (rgba >>> 16) & 0xff, b = (rgba >>> 8) & 0xff;
        return (short)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
    }

    static int deRgb565(short c){
        int v = c & 0xffff;
        int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
        r = (r << 3) | (r >> 2);
        g = (g << 2) | (g >> 4);
        b = (b << 3) | (b >> 2);
        return 0xff000000 | (r << 16) | (g << 8) | b;     // ARGB para composición interna
    }

    // ---------------- almacén ----------------
    /** @return la región en memoria o null si todavía no está (y entonces se pide en segundo plano). */
    static short[][] regionSiLista(int rx, int ry){
        long k = kReg(rx, ry);
        short[][] r = regiones.get(k);
        if(r != null) return r;
        if(carpeta != null && !enVuelo.contains(k) && archivo(rx, ry).exists()){
            enVuelo.add(k);
            final File f = archivo(rx, ry);
            final int ep = epoca;
            Disco.leer(() -> {
                short[][] leida = leerArchivo(f);
                if(ep == epoca) llegadas.add(new Object[]{k, leida});
            });
        }else if(!archivo(rx, ry).exists() && carpeta != null){
            r = new short[REG * REG][];
            regiones.put(k, r);
            return r;
        }
        return null;
    }

    /** Región para escribir: si está en disco y aún no llegó, se lee ahora (poco frecuente: se precarga antes). */
    static short[][] regionParaEscribir(int rx, int ry){
        long k = kReg(rx, ry);
        short[][] r = regiones.get(k);
        if(r == null){
            r = leerArchivo(archivo(rx, ry));
            regiones.put(k, r);
        }
        return r;
    }

    static void sincronizar(){
        Object[] o;
        while((o = llegadas.poll()) != null){
            long k = (Long)o[0];
            enVuelo.remove(k);
            regiones.putIfAbsent(k, (short[][])o[1]);
        }
    }

    static short[][] leerArchivo(File f){
        short[][] r = new short[REG * REG][];
        if(f == null || !f.exists()) return r;
        try(DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))){
            in.readInt();
            int n = in.readInt();
            for(int i = 0; i < n; i++){
                int idx = in.readUnsignedShort();
                short[] m = new short[LADO * LADO];
                for(int j = 0; j < m.length; j++) m[j] = in.readShort();
                if(idx < r.length) r[idx] = m;
            }
        }catch(Throwable t){
            Log.err("[MundoInfinito] Mapa ilegible " + f.getName(), t);
        }
        return r;
    }

    static byte[] bytes(short[][] r){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            int n = 0;
            for(short[] m : r) if(m != null) n++;
            out.writeInt(1);
            out.writeInt(n);
            for(int i = 0; i < r.length; i++){
                if(r[i] == null) continue;
                out.writeShort(i);
                for(short c : r[i]) out.writeShort(c);
            }
            out.flush();
            return bos.toByteArray();
        }catch(Throwable t){
            return null;
        }
    }

    /** Pone en cola de E/S las regiones modificadas (sin comprimir: lo hace el hilo de E/S). */
    static void guardar(){
        if(carpeta == null) return;
        if(nucleosSucios){
            byte[] nb = bytesNucleos();
            if(nb != null) Disco.escribir(archivoNucleos(), nb, false);
            nucleosSucios = false;
        }
        for(long k : new HashSet<>(sucias)){
            int rx = Coord.vcxDeClave(k), ry = Coord.vcyDeClave(k);
            short[][] r = regiones.get(k);
            if(r == null) continue;
            byte[] b = bytes(r);
            if(b != null) Disco.escribir(archivo(rx, ry), b, true);
        }
        sucias.clear();
    }

    static short[] get(int vcx, int vcy){
        short[][] r = regionSiLista(Math.floorDiv(vcx, REG), Math.floorDiv(vcy, REG));
        if(r == null) return null;
        return r[Math.floorMod(vcy, REG) * REG + Math.floorMod(vcx, REG)];
    }

    static void poner(int vcx, int vcy, short[] m){
        int rx = Math.floorDiv(vcx, REG), ry = Math.floorDiv(vcy, REG);
        short[][] r = regionParaEscribir(rx, ry);
        r[Math.floorMod(vcy, REG) * REG + Math.floorMod(vcx, REG)] = m;
        sucias.add(kReg(rx, ry));
    }

    // ---------------- fotografía del mundo ----------------
    /** Miniatura 16x16 de un chunk LOCAL tomada de los tiles del juego (con edificios del color de su equipo). */
    static short[] fotografiar(int cxLocal, int cyLocal){
        short[] m = new short[LADO * LADO];
        int x0 = cxLocal * Coord.CHUNK, y0 = cyLocal * Coord.CHUNK;
        for(int ty = 0; ty < LADO; ty++){
            for(int tx = 0; tx < LADO; tx++){
                int r = 0, g = 0, b = 0, n = 0;
                for(int dy = 0; dy < 2; dy++){
                    for(int dx = 0; dx < 2; dx++){
                        Tile t = Vars.world.rawTile(x0 + tx * 2 + dx, y0 + ty * 2 + dy);
                        int c = MapIO.colorFor(t.block(), t.floor(), t.overlay(), t.team());
                        r += (c >>> 24) & 0xff;
                        g += (c >>> 16) & 0xff;
                        b += (c >>> 8) & 0xff;
                        n++;
                    }
                }
                m[ty * LADO + tx] = a565(((r / n) << 24) | ((g / n) << 16) | ((b / n) << 8) | 0xff);
            }
        }
        return m;
    }

    private static final Seq<int[]> anillo = new Seq<>();
    private static int anilloR = -1;

    /** El minimapa fotografía el DISCO de "distancia de carga" (Ajustes), no un cuadrado fijo: sigue a la configuración. */
    static void prepararAnillo(){
        int r = Ajustes.rMapa();
        if(anilloR == r && anillo.size > 0) return;
        anilloR = r;
        anillo.clear();
        cursor = 0;
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                if(Ajustes.dentro(dx, dy, r)) anillo.add(new int[]{dx, dy});
            }
        }
        anillo.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
    }

    /**
     * Cada fotograma fotografía un par de chunks de alrededor del jugador: primero los que aún no están en el mapa y,
     * si no falta ninguno, va refrescando en rueda (para que se vean los cambios y las construcciones).
     */
    static void tick(int pcx, int pcy){
        if(carpeta == null || !Streamer.activo) return;
        prepararAnillo();
        sincronizar();
        actualizarNucleos();
        int hechos = 0;
        // con el juego lento (< ~30 FPS) solo una fotografía por fotograma: cada una lee 1024 casillas
        final int tope = arc.Core.graphics != null && arc.Core.graphics.getDeltaTime() > 0.033f ? 1 : 2;
        // 1) los que faltan
        for(int i = 0; i < anillo.size && hechos < tope; i++){
            int cx = pcx + anillo.get(i)[0], cy = pcy + anillo.get(i)[1];
            if(!Streamer.enMapa(cx, cy) || !Streamer.generados.contains(Streamer.clave(cx, cy))) continue;
            if(get(Coord.vcx(cx), Coord.vcy(cy)) != null) continue;
            if(!regionLista(Coord.vcx(cx), Coord.vcy(cy))) continue;   // la región aún se está leyendo del disco
            poner(Coord.vcx(cx), Coord.vcy(cy), fotografiar(cx, cy));
            hechos++;
        }
        // 2) refresco en rueda
        for(int k = 0; k < 2 && hechos < tope; k++){
            int[] d = anillo.get(cursor++ % anillo.size);
            int cx = pcx + d[0], cy = pcy + d[1];
            if(!Streamer.enMapa(cx, cy) || !Streamer.generados.contains(Streamer.clave(cx, cy))) continue;
            if(!regionLista(Coord.vcx(cx), Coord.vcy(cy))) continue;
            poner(Coord.vcx(cx), Coord.vcy(cy), fotografiar(cx, cy));
            hechos++;
        }
    }

    private static boolean regionLista(int vcx, int vcy){
        return regiones.containsKey(kReg(Math.floorDiv(vcx, REG), Math.floorDiv(vcy, REG)));
    }

    /** Descarta de memoria las regiones lejanas ya guardadas. */
    static void evictar(int vcxJugador, int vcyJugador){
        if(regiones.size() <= 16) return;
        int prx = Math.floorDiv(vcxJugador, REG), pry = Math.floorDiv(vcyJugador, REG);
        for(long k : new HashSet<>(regiones.keySet())){
            if(sucias.contains(k)) continue;
            int d = Math.max(Math.abs(Coord.vcxDeClave(k) - prx), Math.abs(Coord.vcyDeClave(k) - pry));
            if(d > 3) regiones.remove(k);
        }
    }

    // ---------------- composición (pura: se prueba sin el juego) ----------------
    interface Fuente{
        short[] miniatura(int vcx, int vcy);
    }

    /**
     * Rellena 'out' (w x h, ARGB, fila 0 = arriba) con la zona centrada en la posición virtual (cx, cy),
     * a 'tpp' tiles por píxel. Lo no explorado se pinta de VISTO.
     */
    static void componer(int[] out, int w, int h, double cx, double cy, int tpp, Fuente f){
        // caché de la última miniatura: píxeles contiguos casi siempre caen en el mismo chunk
        int ultVcx = Integer.MIN_VALUE, ultVcy = Integer.MIN_VALUE;
        short[] ult = null;
        for(int py = 0; py < h; py++){
            double ty = cy - (py - h / 2) * tpp;
            int ity = (int)Math.floor(ty);
            int vcy = Math.floorDiv(ity, Coord.CHUNK), ly = (ity - vcy * Coord.CHUNK) >> 1;
            for(int px = 0; px < w; px++){
                double tx = cx + (px - w / 2) * tpp;
                int itx = (int)Math.floor(tx);
                int vcx = Math.floorDiv(itx, Coord.CHUNK);
                if(vcx != ultVcx || vcy != ultVcy){
                    ult = f.miniatura(vcx, vcy);
                    ultVcx = vcx;
                    ultVcy = vcy;
                }
                int lx = (itx - vcx * Coord.CHUNK) >> 1;
                out[py * w + px] = ult == null ? VISTO : deRgb565(ult[ly * LADO + lx]);
            }
        }
    }

    // ---------------- minimapa del juego: se le quita la cola de tiles ----------------
    private static java.lang.reflect.Field campoUpdates;
    private static boolean sinCampo;

    /**
     * El minimapa del juego encola CADA tile que cambia y luego hace una llamada de GPU de 1x1 píxel por cada uno: al cargar
     * o reubicar chunks se encolan miles. Se vacía esa cola al final de cada fotograma (este mod usa su propio minimapa).
     */
    static void vaciarColaDelJuego(){
        if(sinCampo || Vars.headless || Vars.renderer == null || Vars.renderer.minimap == null) return;
        try{
            if(campoUpdates == null){
                campoUpdates = Vars.renderer.minimap.getClass().getDeclaredField("updates");
                campoUpdates.setAccessible(true);
            }
            Object cola = campoUpdates.get(Vars.renderer.minimap);
            if(cola instanceof arc.struct.IntSet) ((arc.struct.IntSet)cola).clear();
        }catch(Throwable t){
            sinCampo = true;
            Log.warn("[MundoInfinito] No se pudo vaciar la cola del minimapa del juego");
        }
    }
}
