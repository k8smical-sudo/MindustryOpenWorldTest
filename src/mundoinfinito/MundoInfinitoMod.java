package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Bits;
import arc.struct.FloatSeq;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Http;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.mod.Mod;
import mindustry.type.Item;
import mindustry.type.Planet;
import mindustry.type.UnitType;
import mindustry.type.ItemStack;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.meta.Env;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * MUNDO INFINITO v2 (Java puro, sin JNI)
 *
 * Qué hace, en una frase: mantiene un mundo de tamaño fijo (500/800/1000 tiles) vacío y lo va
 * "rellenando" por chunks de 32x32 SOLO donde hay jugador, unidades, cámara o edificios, igual
 * que Minecraft carga chunks alrededor de los jugadores.
 *
 * Reglas de oro que evitan los problemas de la v1:
 *  1. Los hilos secundarios NUNCA tocan el mundo. Solo calculan arreglos de ids (ChunkData).
 *     El hilo principal los aplica con un presupuesto de tiempo por frame. (Tile.setFloor/setBlock
 *     disparan eventos de render/minimapa/indexador que no son thread-safe: eso congelaba el juego.)
 *  2. La generación es una función pura de (semilla, x, y): cualquier chunk sale idéntico sin
 *     importar el orden en que se genere, así no hay costuras ni "barreras".
 *  3. Niebla estática de campaña (rules.fog): lo no explorado no se dibuja ni en pantalla ni en
 *     el minimapa; se descubre al explorar. El progreso de exploración se guarda con el mundo.
 *  4. Los mundos se guardan en disco (solo lo que el jugador cambió; el terreno se regenera de la semilla).
 *
 * Archivo único con clases estáticas internas; cada una puede moverse a su propio .java.
 */
public class MundoInfinitoMod extends Mod{

    public static final int TAM_CHUNK = 32;
    /** Ventana cargada en RAM (fija, sin opciones para el jugador). El mundo virtual es de ±30 000 000 tiles, como Minecraft. */
    public static int ventana(){
        return Runtime.getRuntime().maxMemory() >= (900L << 20) ? 960 : 800;
    }
    public static final int LIMITE_MUNDO = 30_000_000;      // borde del mundo virtual, igual que Minecraft Java
    public static final int REGION_CHUNKS = 16;             // 16x16 chunks por archivo de región (512x512 tiles)
    public static final int MARGEN_REBASE = 5;              // chunks al borde de la ventana que disparan la reubicación
    public static final String URL_SEMILLAS = "https://raw.githubusercontent.com/TU_USUARIO/mundo-infinito-semillas/main/semillas/actual.json"; // TODO: URL real
    public static final int TIMEOUT_HTTP_MS = 4000;
    public static final int GEN_ACTUAL = 3;                 // 3 = dos dimensiones (Serpulo/Erekir). Los mundos gen 2 (terreno mezclado) siguen jugables tal cual.
    public static final int GEN_MEZCLADO = 2;
    public static final int DIM_SERPULO = 0, DIM_EREKIR = 1;

    public MundoInfinitoMod(){
        // Botón en el menú principal cuando el cliente terminó de cargar.
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, () -> { MenuUI.inyectarBoton(); Hud.crear(); MapaVista.crear(); Ajustes.registrar(); }));

        // Bucle de streaming: se ejecuta cada frame (presupuesto de tiempo propio).
        Events.run(Trigger.update, Streamer::tick);
        // Drones de carga orbital: puro dato, avanza aunque el jugador esté en la otra dimensión.
        Events.run(Trigger.update, Orbita::tick);

        // Guardar al abrir el menú de pausa (así se guarda antes de salir al menú).
        Events.on(StateChangeEvent.class, e -> {
            if(e.to == State.paused && Streamer.activo) Mundos.guardar(false);
        });
        Events.on(ResetEvent.class, e -> { Streamer.activo = false; Disco.vaciar(20000); });
        Events.on(mindustry.game.EventType.DisposeEvent.class, e -> Disco.vaciar(20000));
    }

    @Override
    public void loadContent(){
        SiloInterdimensional.cargar();
    }

    /** Entorno combinado: así el menú de construcción ofrece bloques de AMBOS planetas. */
    static final int ENTORNO_COMBINADO = Env.terrestrial | Env.spores | Env.groundOil | Env.groundWater | Env.oxygen | Env.scorching;

    // ==================================================================
    // Meta de un mundo guardado
    // ==================================================================
    static class Meta{
        String id, nombre, semillaTxt = "";
        int semilla, tam, nucleo, gen;   // nucleo: 0 = según la semilla, 1 = Shard (Serpulo), 2 = Bastion (Erekir)
        int dim;                         // dimensión actual (solo gen >= 3): 0 = Serpulo, 1 = Erekir
        long creado, ultimo;
        double reloj;                    // ticks de juego transcurridos en TODO el mundo (las dos dimensiones): mide las ausencias

        Jval aJson(){
            Jval j = Jval.newObject();
            j.put("nombre", nombre);
            j.put("semilla", semilla);
            j.put("semillaTxt", semillaTxt == null ? "" : semillaTxt);
            j.put("tam", tam);
            j.put("nucleo", nucleo);
            j.put("gen", gen);
            j.put("dim", dim);
            j.put("creado", creado);
            j.put("ultimo", ultimo);
            j.put("reloj", reloj);
            return j;
        }

        static Meta desde(String id, Jval j){
            Meta m = new Meta();
            m.id = id;
            m.nombre = j.getString("nombre", id);
            m.semilla = j.getInt("semilla", 1);
            m.semillaTxt = j.getString("semillaTxt", "");
            m.tam = j.getInt("tam", 800);
            m.nucleo = j.getInt("nucleo", 0);
            m.gen = j.getInt("gen", 0);
            m.dim = j.getInt("dim", 0);
            m.creado = j.getLong("creado", 0);
            m.ultimo = j.getLong("ultimo", 0);
            m.reloj = j.getDouble("reloj", 0.0);
            return m;
        }

        boolean compatible(){
            return gen == GEN_ACTUAL || gen == GEN_MEZCLADO;
        }

        /** true = mundo con dos dimensiones separadas; false = mundo antiguo de terreno mezclado. */
        boolean dimensional(){
            return gen >= GEN_ACTUAL;
        }

        /** Dimensión que recibe el generador: -1 = terreno mezclado (mundos antiguos). */
        int dimGen(){
            return dimensional() ? dim : -1;
        }

        /** Dimensión en la que empieza un mundo nuevo (misma regla que el antiguo "núcleo inicial"). */
        int dimInicial(){
            int t = nucleo;
            if(t == 0) t = 1 + (Muestreo.sem(semilla, 77) & 1);
            return t == 1 ? DIM_SERPULO : DIM_EREKIR;
        }

        Block nucleoBlock(){
            if(dimensional()) return dim == DIM_SERPULO ? Blocks.coreShard : Blocks.coreBastion;
            int t = nucleo;
            if(t == 0) t = 1 + (Muestreo.sem(semilla, 77) & 1);
            return t == 1 ? Blocks.coreShard : Blocks.coreBastion;
        }

        String nombreDim(){
            return !dimensional() ? "Mixto" : (dim == DIM_SERPULO ? "Serpulo" : "Erekir");
        }

        String semillaTexto(){
            return semillaTxt == null || semillaTxt.isEmpty() ? String.valueOf(semilla) : semillaTxt;
        }

        int chunksPorLado(){
            return (tam + TAM_CHUNK - 1) / TAM_CHUNK;
        }
    }

    // ==================================================================
    // Disco: carpeta por mundo
    // ==================================================================
    static class Almacen{
        static Fi raiz(){
            Fi f = Core.settings.getDataDirectory().child("mundo-infinito");
            f.mkdirs();
            return f;
        }

        static Fi carpeta(String id){
            return raiz().child(id);
        }

        static Seq<Meta> listar(){
            Seq<Meta> r = new Seq<>();
            for(Fi d : raiz().list()){
                if(!d.isDirectory()) continue;
                Fi m = d.child("meta.json");
                if(!m.exists()) continue;
                try{
                    r.add(Meta.desde(d.name(), Jval.read(m.readString())));
                }catch(Throwable t){
                    Log.warn("[MundoInfinito] meta ilegible en @", d.name());
                }
            }
            r.sort((a, b) -> Long.compare(b.ultimo, a.ultimo));
            return r;
        }

        static void guardarMeta(Meta m){
            Fi c = carpeta(m.id);
            c.mkdirs();
            Disco.escribirYa(c.child("meta.json").file(), m.aJson().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        }

        static void borrar(String id){
            borrarRec(carpeta(id));
        }

        static void borrarRec(Fi f){
            if(f.isDirectory()){
                for(Fi c : f.list()) borrarRec(c);
            }
            f.delete();
        }

        /** Mundos dimensionales: una subcarpeta por dimensión (regiones + estado). Mundos antiguos: la raíz, como siempre. */
        static Fi carpetaDatos(Meta m){
            return m.dimensional() ? carpeta(m.id).child("d" + m.dim) : carpeta(m.id);
        }

        static Fi archivoEstado(Meta m){
            return carpetaDatos(m).child("estado.dat");
        }

        /** Estado COMPARTIDO por las dos dimensiones (viven en la raíz del mundo). */
        static Fi archivoOrbita(Meta m){
            return carpeta(m.id).child("orbita.dat");
        }

        /** Estructuras ya colocadas y checkpoints: POR dimensión (cada una tiene su propio espacio). */
        static Fi archivoEstructuras(Meta m){
            return carpetaDatos(m).child("estructuras.dat");
        }
    }

    // ==================================================================
    // Semillas: online (GitHub) con respaldo offline
    // ==================================================================
    static class Semillas{
        /** Pide la semilla a GitHub (timeout 4 s). Cualquier fallo => semilla local. Callback en hilo principal, una sola vez. */
        static void obtener(Cons<Integer> alListo){
            AtomicBoolean resuelto = new AtomicBoolean(false);
            Cons<Integer> entregar = s -> {
                if(!resuelto.compareAndSet(false, true)) return;
                Core.app.post(() -> alListo.get(s));
            };
            Time.runTask(60f * 5f, () -> entregar.get(semillaOffline()));
            try{
                Http.get(URL_SEMILLAS)
                    .timeout(TIMEOUT_HTTP_MS)
                    .error(err -> entregar.get(semillaOffline()))
                    .submit(res -> {
                        try{
                            Jval j = Jval.read(res.getResultAsString());
                            if(!j.isObject() || !j.has("semilla")) throw new IllegalStateException("sin semilla");
                            entregar.get(j.getInt("semilla", semillaOffline()));
                        }catch(Throwable t){
                            entregar.get(semillaOffline());
                        }
                    });
            }catch(Throwable t){
                entregar.get(semillaOffline());
            }
        }

        /**
         * Como Minecraft: un número o cualquier texto sirven de semilla. Se mezcla con un hash de 64 bits
         * (FNV-1a + avalancha) para que semillas parecidas ("1", "2", "3") den mundos totalmente distintos.
         */
        static int deTexto(String t){
            String x = t.trim();
            long v;
            try{
                v = Long.parseLong(x);
            }catch(Throwable e){
                v = 0xcbf29ce484222325L;
                for(byte b : x.getBytes(java.nio.charset.StandardCharsets.UTF_8)){
                    v ^= (b & 0xff);
                    v *= 0x100000001b3L;
                }
            }
            v ^= v >>> 33;
            v *= 0xff51afd7ed558ccdL;
            v ^= v >>> 33;
            v *= 0xc4ceb9fe1a85ec53L;
            v ^= v >>> 33;
            int r = (int)(v ^ (v >>> 32));
            return r == 0 ? 1 : r;
        }

        static int semillaOffline(){
            int r = new java.util.Random().nextInt();
            return r == 0 ? 1 : r;
        }
    }

    // ==================================================================
    // GENERADOR PURO (sin clases de Mindustry => se puede probar fuera del juego)
    // ==================================================================
    static final class Muestreo{
        // ---- Códigos de piso (Serpulo 0..31, Erekir 32..63) ----
        static final int P_DEEP = 0, P_WATER = 1, P_SANDW = 2, P_DSW = 3, P_DTW = 4, P_TW = 5, P_DEEPT = 6,
            P_SAND = 7, P_DARKSAND = 8, P_SALT = 9, P_STONE = 10, P_BASALT = 11, P_MOSS = 12, P_SPORE = 13,
            P_SNOW = 14, P_ICESNOW = 15, P_ICE = 16, P_HOT = 17, P_MAGMA = 18, P_TAR = 19, P_CRATERS = 20,
            P_COREZONE = 21,
            P_REGO = 32, P_YELLOW = 33, P_RHYO = 34, P_CARBON = 35, P_CRYST = 36, P_CRYSTF = 37, P_BERYL = 38,
            P_ARKYIC = 39, P_ARKYCITE = 40, P_RED = 41, P_DENSERED = 42, P_REDICE = 43, P_SLAG = 44,
            P_YPLATES = 45, P_ROUGH = 46, P_RCRATER = 47, P_VRHYO = 48, P_VARKY = 49, P_VYELLOW = 50,
            P_VRED = 51, P_VCARBON = 52;
        static final int N_PISOS = 64;

        // ---- Códigos de mena (overlay) ----
        static final int O_COBRE = 1, O_PLOMO = 2, O_CHATARRA = 3, O_CARBON = 4, O_TITANIO = 5, O_TORIO = 6,
            O_BERILIO = 7, O_TUNGSTENO = 8, O_TORIO_CRISTAL = 9, O_TORIO_MURO = 10, O_BERILIO_MURO = 11,
            O_TUNGSTENO_MURO = 12;
        static final int N_MENAS = 16;

        // ---- Tipos de depósito ----
        static final int D_COBRE = 0, D_PLOMO = 1, D_CHATARRA = 2, D_CARBON = 3, D_TITANIO = 4, D_TORIO = 5,
            D_BERILIO = 10, D_TUNGSTENO = 11, D_TORIO_E = 12, D_GRAFITO = 13, D_ARENA = 14;

        // ---- Afinación (todo en un sitio para calibrar) ----
        static final float K_ALTURA = 2.3f, K_TEMP = 2.3f;       // estiramiento del ruido a [0,1]
        static final float LIM_S = 0.522f, LIM_E = 0.522f;       // umbral base de muros (más alto = menos muros)
        static final float ANCHO_PASO = 0.018f;                  // anchura base de los pasillos que cortan los macizos
        static final float K_VENA = 0.42f;                       // grosor general de las vetas globales
        static final int STRIDE_DEP = 10, STRIDE_POZ = 6;
        // Dos orientaciones fijas para los macizos alargados (constantes: no se deforman lejos del origen)
        static final float CA = 0.9004f, SA = 0.4350f, CB = -0.5048f, SB = 0.8632f;

        // ---- Tabla de biomas Serpulo (copiada de la generación de campaña: filas = temperatura, columnas = altura) ----
        static final int[][] TABLA_S = {
            {P_WATER, P_DSW, P_DARKSAND, P_DARKSAND, P_DARKSAND, P_DARKSAND, P_SAND, P_SAND, P_SAND, P_SAND, P_DTW, P_STONE, P_STONE},
            {P_WATER, P_DSW, P_DARKSAND, P_DARKSAND, P_SAND, P_SAND, P_SAND, P_SAND, P_SAND, P_DTW, P_STONE, P_STONE, P_STONE},
            {P_WATER, P_DSW, P_DARKSAND, P_SAND, P_SALT, P_SAND, P_SAND, P_SAND, P_SAND, P_DTW, P_STONE, P_STONE, P_STONE},
            {P_WATER, P_SANDW, P_SAND, P_SALT, P_SALT, P_SALT, P_SAND, P_STONE, P_STONE, P_STONE, P_SNOW, P_ICESNOW, P_ICE},
            {P_DEEP, P_WATER, P_SANDW, P_SAND, P_SALT, P_SAND, P_SAND, P_BASALT, P_SNOW, P_SNOW, P_SNOW, P_SNOW, P_ICE},
            {P_DEEP, P_WATER, P_SANDW, P_SAND, P_SAND, P_SAND, P_MOSS, P_ICESNOW, P_SNOW, P_SNOW, P_ICE, P_SNOW, P_ICE},
            {P_DEEP, P_SANDW, P_SAND, P_SAND, P_MOSS, P_MOSS, P_SNOW, P_BASALT, P_BASALT, P_BASALT, P_ICE, P_SNOW, P_ICE},
            {P_DEEPT, P_DTW, P_DARKSAND, P_DARKSAND, P_BASALT, P_MOSS, P_BASALT, P_HOT, P_BASALT, P_ICE, P_SNOW, P_ICE, P_ICE},
            {P_DSW, P_DARKSAND, P_DARKSAND, P_DARKSAND, P_MOSS, P_SPORE, P_SNOW, P_BASALT, P_BASALT, P_ICE, P_SNOW, P_ICE, P_ICE},
            {P_DSW, P_DARKSAND, P_DARKSAND, P_SPORE, P_ICE, P_ICE, P_SNOW, P_SNOW, P_SNOW, P_SNOW, P_ICE, P_ICE, P_ICE},
            {P_DEEPT, P_DTW, P_DARKSAND, P_SPORE, P_SPORE, P_ICE, P_ICE, P_SNOW, P_SNOW, P_ICE, P_ICE, P_ICE, P_ICE},
            {P_TW, P_DTW, P_DARKSAND, P_SPORE, P_MOSS, P_SPORE, P_ICESNOW, P_SNOW, P_ICE, P_ICE, P_ICE, P_ICE, P_ICE},
            {P_DSW, P_DARKSAND, P_SNOW, P_ICE, P_ICESNOW, P_SNOW, P_SNOW, P_SNOW, P_ICE, P_ICE, P_ICE, P_ICE, P_ICE}
        };
        // Terreno base de Erekir (campaña): regolito domina, luego yellowStone, riolita y carbón.
        static final int[] TERRENO_E = {P_REGO, P_REGO, P_REGO, P_REGO, P_YELLOW, P_RHYO, P_RHYO, P_CARBON};

        // ---- Ruido de gradiente 2D (determinista, rango conocido) ----
        static int hash(int x, int y, int s){
            int h = mix(s * 0x9E3779B9 ^ 0x7F4A7C15);
            h = mix(h ^ (x * 0x85EBCA6B));
            return mix(h ^ (y * 0xC2B2AE35));
        }

        static float rnd(int x, int y, int s){
            return (hash(x, y, s) & 0xFFFFFF) / 16777215f;
        }

        static final float[] GX = {1f, -1f, 0f, 0f, 0.7071f, -0.7071f, 0.7071f, -0.7071f};
        static final float[] GY = {0f, 0f, 1f, -1f, 0.7071f, 0.7071f, -0.7071f, -0.7071f};

        static float quintica(float t){
            return t * t * t * (t * (t * 6f - 15f) + 10f);
        }

        static float grad(int xi, int yi, int s, float dx, float dy){
            int g = hash(xi, yi, s) & 7;
            return GX[g] * dx + GY[g] * dy;
        }

        /** Perlin 2D, rango aprox [-0.707, 0.707]. */
        static float perlin(double x, double y, int s){
            int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y);
            float fx = (float)(x - x0), fy = (float)(y - y0);
            float u = quintica(fx), v = quintica(fy);
            float a = grad(x0, y0, s, fx, fy), b = grad(x0 + 1, y0, s, fx - 1f, fy);
            float c = grad(x0, y0 + 1, s, fx, fy - 1f), d = grad(x0 + 1, y0 + 1, s, fx - 1f, fy - 1f);
            float l1 = a + (b - a) * u, l2 = c + (d - c) * u;
            return l1 + (l2 - l1) * v;
        }

        /** fBm normalizado a [0,1]. escala = tamaño de la mancha principal en tiles. Cada octava se rota para evitar rejillas. */
        static float fbm(int s, float escala, int oct, double x, double y){
            double f = 1.0 / escala, cx = x, cy = y;
            float amp = 1f, sum = 0f, tot = 0f;
            for(int i = 0; i < oct; i++){
                sum += perlin(cx * f + i * 31.7, cy * f + i * 17.3, s + i * 131) * amp;
                tot += amp;
                amp *= 0.5f;
                f *= 2.0;
                double nx = cx * 0.8 - cy * 0.6;
                cy = cx * 0.6 + cy * 0.8;
                cx = nx;
            }
            float v = 0.5f + 0.5f * (sum / tot) * 1.4142f;
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }

        /**
         * fBm ANISOTRÓPICO: las manchas salen alargadas a lo largo de una dirección fija (c, sn = coseno y seno del ángulo).
         * Con 'estira' > 1 el ruido varía más rápido de lado a lado y las formaciones quedan como crestas, no como círculos.
         * El ángulo es una constante: nunca se interpola con la posición (lejos del origen eso destrozaría el ruido).
         */
        static float fbmA(int s, float escala, float estira, float c, float sn, int oct, double x, double y){
            double u = x * c + y * sn, v = -x * sn + y * c;
            return fbm(s, escala, oct, u, v * estira);
        }

        static float est(float v, float k){
            float r = (v - 0.5f) * k + 0.5f;
            return r < 0f ? 0f : (r > 1f ? 1f : r);
        }

        static float suave(float x){
            if(x <= 0f) return 0f;
            if(x >= 1f) return 1f;
            return x * x * (3f - 2f * x);
        }

        static float lerp(float a, float b, float t){
            return a + (b - a) * t;
        }

        static boolean liquido(int p){
            return p == P_DEEP || p == P_WATER || p == P_SANDW || p == P_DSW || p == P_DTW || p == P_TW || p == P_DEEPT
                || p == P_ARKYCITE || p == P_SLAG || p == P_TAR;
        }

        static boolean profundo(int p){
            return p == P_DEEP || p == P_DEEPT;
        }

        static boolean seco(int p){
            return !liquido(p);
        }

        // ---- Dominio: qué zonas del mundo son "tipo Serpulo" y cuáles "tipo Erekir" (frontera irregular = mezcla) ----
        /** Semilla efectiva por dimensión: Serpulo y Erekir NO comparten ruido (mismo mundo, dos planetas distintos). -1 = igual que antes. */
        static int semDim(int s, int dim){
            return dim < 0 ? s : mix(s ^ ((dim + 1) * 0x632BE5AB));
        }

        static boolean esE(int s, double x, double y, Spawn sp){
            if(sp.dim >= 0) return sp.dim == 1;   // dimensión forzada: sin mezcla posible
            float v = fbm(sem(s, 9100), 380f, 3, x + sp.domX, y + sp.domY);
            float j = (fbm(sem(s, 9101), 55f, 3, x, y) - 0.5f) * 0.30f;
            return v + j > 0.5f;
        }

        // ---- Seed -> semillas independientes por capa (sin correlación entre capas ni entre semillas vecinas) ----
        static int mix(int h){
            h ^= h >>> 16;
            h *= 0x7feb352d;
            h ^= h >>> 15;
            h *= 0x846ca68b;
            h ^= h >>> 16;
            return h;
        }

        static int sem(int s, int capa){
            return mix(s * 0x9E3779B9 + capa * 0x85EBCA6B + 0x165667B1);
        }

        // ---- Todo lo que cambia de un mundo a otro cerca del spawn sale de la semilla ----
        static final class Spawn{
            int semilla;
            int dim = -1;            // -1 mezclado (mundos antiguos), 0 Serpulo, 1 Erekir
            float radioLibre;        // radio medio sin muros ni líquidos (10..18); el borde es irregular
            float paredes;           // desplazamiento del umbral de muros cerca del spawn (+ abierto, - rocoso)
            double domX, domY;       // desplazamiento del campo de dominio (qué bioma toca en el origen)
            float[] dep = new float[0];  // [tipo, x, y, semilargo, forma, rot, semiancho, curva] * n   (vetas iniciales)
            float[] poz = new float[0];  // [tipo, x, y, radio, rot, razon] * n                          (pozos de líquidos y energía)
            float[] cam = new float[0];  // [x, y] * n   extremos de los caminos despejados hacia las vetas esenciales
            int celdaE;              // lado de la celda de estructuras (160..256): cada mundo tiene su propio ritmo
            float probE;             // probabilidad base de que una celda tenga una estructura
            int rx, ry;              // ruina garantizada (siempre hay un portal a una distancia razonable)
        }

        static volatile Spawn cacheSpawn;

        static Spawn spawn(int s, int dim){
            Spawn c = cacheSpawn;
            if(c != null && c.semilla == s && c.dim == dim) return c;
            Spawn sp = crearSpawn(s, dim);
            cacheSpawn = sp;
            return sp;
        }

        /** Formas de la veta de grafito: maciza, rota, doble, dos tramos, dispersa, mixta. */
        static int formaGrafito(float u){
            if(u < 0.20f) return 0;
            if(u < 0.40f) return 1;
            if(u < 0.55f) return 2;
            if(u < 0.70f) return 3;
            if(u < 0.85f) return 4;
            return 5;
        }

        /** Qué puede aparecer en cada dimensión (dim < 0 = todo, como antes). */
        static boolean permitido(int clase, int tipo, int dim){
            if(dim < 0) return true;
            if(clase == 0){
                boolean erekir = tipo == D_BERILIO || tipo == D_TUNGSTENO || tipo == D_TORIO_E || tipo == D_GRAFITO;
                return dim == 1 ? erekir : !erekir;
            }
            boolean pozoErekir = tipo >= 3;   // 3 respiraderos, 4 arkycita, 5 escoria
            return dim == 1 ? pozoErekir : !pozoErekir;
        }

        // ---- Morfologías de depósito: cada mena puede ser una veta corta, media o larga, un racimo, un parche, ----
        // ---- una dispersión, una doble veta o un arco. Ninguna es un círculo perfecto.                      ----
        static final int M_CORTA = 0, M_MEDIA = 1, M_LARGA = 2, M_RACIMO = 3, M_PARCHE = 4, M_DISPERSA = 5, M_DOBLE = 6, M_ARCO = 7;
        static final float[] PESO_MORF = {0.26f, 0.16f, 0.05f, 0.17f, 0.18f, 0.12f, 0.03f, 0.03f};

        static int elegirMorf(float r){
            float acc = 0f;
            for(int i = 0; i < PESO_MORF.length; i++){
                acc += PESO_MORF[i];
                if(r < acc) return i;
            }
            return M_CORTA;
        }

        static boolean ladrillo(int morf){   // formas con eje (aceptan patrones de grafito)
            return morf == M_CORTA || morf == M_MEDIA || morf == M_LARGA || morf == M_DOBLE || morf == M_ARCO;
        }

        /** Parámetros {semilargo/dispersión, semiancho/escala, curva} de una morfología; r1..r3 en [0,1). */
        static void paramsMorf(int morf, float r1, float r2, float r3, float mult, float[] out){
            float hl, hw, cv = (r3 - 0.5f) * 1.2f;
            switch(morf){
                case M_CORTA: hl = 4f + r1 * 5f; hw = 1.0f + r2 * 1.2f; break;
                case M_MEDIA: hl = 9f + r1 * 7f; hw = 1.2f + r2 * 1.4f; break;
                case M_LARGA: hl = 16f + r1 * 8f; hw = 0.9f + r2 * 0.6f; break;
                case M_RACIMO: hl = 5f + r1 * 4f; hw = 1.3f + r2 * 1.1f; break;
                case M_PARCHE: hl = 3f + r1 * 3.5f; hw = 0.5f + r2 * 0.35f; break;      // hw = razón entre ejes
                case M_DISPERSA: hl = 5f + r1 * 5f; hw = 0.22f + r2 * 0.28f; break;     // hw = densidad
                case M_DOBLE: hl = 5f + r1 * 5f; hw = 0.9f + r2 * 0.7f; break;
                default: hl = 6f + r1 * 5f; hw = 1.1f + r2 * 0.9f; cv = (r3 < 0.5f ? -1f : 1f) * (1.7f + r3 * 0.7f); break;   // arco
            }
            out[0] = hl * mult;
            out[1] = (morf == M_PARCHE || morf == M_DISPERSA) ? hw : hw * Math.max(0.7f, mult);
            out[2] = cv;
        }

        /** Radio (en tiles) dentro del cual puede haber algo de la forma: sirve para descartar rápido. */
        static float alcance(int morf, float hl, float hw){
            switch(morf){
                case M_RACIMO: return hl + hw * 1.5f + 3f;
                case M_PARCHE: case M_DISPERSA: return hl * 1.4f + 3f;
                case M_DOBLE: return hl * 1.3f + hw * 5f + 4f;
                default: return hl * 1.25f + hw * 1.6f + 4f + Math.abs(hl) * 0.1f;
            }
        }

        /**
         * Forma de un depósito en la casilla (dx, dy) respecto a su centro. @return distancia normalizada al eje
         * (< 1 dentro de la mena; hasta 1 + margen/tamaño es el borde de roca de las menas de pared) o -1 si queda fuera.
         * aux[0] = coordenada a lo largo (-1..1), aux[1] = coordenada a lo ancho (-1..1): las usan los patrones de grafito.
         */
        static float forma(int morf, float dx, float dy, float hl, float hw, float rot, float curva, int id, int x, int y, int s, float margen, float[] aux){
            float c = (float)Math.cos(rot), sn = (float)Math.sin(rot);
            float a = dx * c + dy * sn, b = -dx * sn + dy * c;
            float dn, tam;
            switch(morf){
                case M_RACIMO: {
                    int nb = 3 + (hash(id, 1, sem(s, 7600)) & 3);
                    float mejor = 9e9f;
                    tam = 1.8f;
                    for(int k = 0; k < nb; k++){
                        float ang = rnd(id, k, sem(s, 7601)) * 6.2831853f, rad = rnd(id, k + 9, sem(s, 7602)) * hl;
                        float bx = (float)Math.cos(ang) * rad, by = (float)Math.sin(ang) * rad;
                        float rb = hw * (0.7f + 0.6f * rnd(id, k + 19, sem(s, 7603)));
                        float d = (float)Math.hypot(dx - bx, dy - by) / (rb * (0.8f + 0.45f * fbm(sem(s, 7604), 5f, 2, x, y)));
                        mejor = Math.min(mejor, d);
                        tam = rb;
                    }
                    dn = mejor;
                    aux[0] = 0f; aux[1] = Mathf.clamp(dn, -1f, 1f);
                    break;
                }
                case M_PARCHE: {
                    float A = hl, B = Math.max(1.5f, hl * hw);
                    dn = (float)Math.sqrt((a / A) * (a / A) + (b / B) * (b / B)) + (fbm(sem(s, 7605), 6f, 2, x, y) - 0.5f) * 0.55f;
                    tam = B;
                    aux[0] = Mathf.clamp(a / A, -1f, 1f); aux[1] = Mathf.clamp(b / B, -1f, 1f);
                    break;
                }
                case M_DISPERSA: {
                    dn = (float)Math.hypot(dx, dy) / hl + (fbm(sem(s, 7606), 7f, 2, x, y) - 0.5f) * 0.5f;
                    tam = hl * 0.5f;
                    if(dn < 1f && rnd(x, y, sem(s, 7607)) > hw * (1f - 0.6f * dn)) return -1f;   // solo algunas casillas: "pecas"
                    aux[0] = 0f; aux[1] = Mathf.clamp(dn, -1f, 1f);
                    break;
                }
                default: {   // veta (corta, media, larga, doble o arco)
                    float u = a / hl;
                    if(u < -1.05f || u > 1.05f) return -1f;
                    float arco = curva * u * u * hl * 0.45f;
                    float lente = (float)Math.sqrt(Math.max(0f, 1f - u * u));
                    float hwl = Math.max(0.3f, hw * (0.25f + 0.75f * lente));
                    float ruido = 0.78f + 0.50f * fbm(sem(s, 7608), 6f, 2, x, y);
                    float across = b - arco;
                    if(morf == M_DOBLE){
                        float off = hw * 1.8f + 1.2f;
                        float a1 = Math.abs(across - off), a2 = Math.abs(across + off);
                        across = a1 < a2 ? across - off : across + off;
                    }
                    dn = Math.abs(across) / (hwl * ruido);
                    tam = hwl * ruido;
                    aux[0] = Mathf.clamp(u, -1f, 1f); aux[1] = Mathf.clamp(across / hwl, -1f, 1f);
                    break;
                }
            }
            return dn <= 1f + margen / Math.max(0.6f, tam) ? dn : -1f;
        }

        /** Mena (tipo) que admite variante de pared en Erekir. */
        static boolean tieneMuro(int tipo){
            return tipo == D_BERILIO || tipo == D_TUNGSTENO || tipo == D_TORIO_E;
        }

        static Spawn crearSpawn(int s, int dim){
            Spawn sp = new Spawn();
            sp.semilla = s;
            sp.dim = dim;
            sp.radioLibre = 10f + rnd(1, 1, sem(s, 9001)) * 8f;
            float[] paredes = {0.14f, 0.10f, 0.06f, 0.02f, -0.04f};
            sp.paredes = paredes[Math.min(paredes.length - 1, (int)(rnd(2, 2, sem(s, 9002)) * paredes.length))];
            sp.domX = (rnd(3, 3, sem(s, 9003)) - 0.5) * 6000.0;
            sp.domY = (rnd(4, 4, sem(s, 9004)) - 0.5) * 6000.0;

            // Ritmo de estructuras de ESTE mundo: cada cuántos tiles aparece algo y con qué frecuencia
            sp.celdaE = 160 + 16 * (int)(rnd(11, 11, sem(s, 9040)) * 7f);
            sp.probE = 0.16f + 0.26f * rnd(12, 12, sem(s, 9041));
            float angE = rnd(13, 13, sem(s, 9042)) * 6.2831853f, disE = 150f + rnd(14, 14, sem(s, 9043)) * 70f;
            sp.rx = (int)(Math.cos(angE) * disE);
            sp.ry = (int)(Math.sin(angE) * disE);

            // Cosas que se reparten alrededor del spawn: {clase (0 veta, 1 pozo), tipo, dMin, dMax, pared (0/1)}
            // TODO lo imprescindible queda a unos 20-60 tiles: la base nunca obliga a irse lejos ni a modificar el terreno.
            Seq<float[]> cosas = new Seq<>();
            cosas.add(new float[]{0, D_COBRE, 18, 44, 0});
            cosas.add(new float[]{0, D_PLOMO, 18, 46, 0});
            cosas.add(new float[]{0, D_ARENA, 16, 44, 0});
            cosas.add(new float[]{0, D_BERILIO, 18, 46, 0});          // Erekir: berilio de suelo...
            cosas.add(new float[]{0, D_BERILIO, 24, 54, 1});          // ...y berilio EN PARED, a mano desde el inicio
            cosas.add(new float[]{0, D_GRAFITO, 24, 56, 0});
            cosas.add(new float[]{0, D_TUNGSTENO, 36, 70, 0});                 // tungsteno de suelo...
            cosas.add(new float[]{0, D_TUNGSTENO, 44, 84, 1});                 // ...y de pared, siempre los dos
            // extras con probabilidad
            if(rnd(5, 1, sem(s, 9010)) < 0.80f) cosas.add(new float[]{0, D_CHATARRA, 36, 74, 0});
            if(rnd(5, 2, sem(s, 9010)) < 0.75f) cosas.add(new float[]{0, D_CARBON, 46, 86, 0});
            if(rnd(5, 3, sem(s, 9010)) < 0.85f) cosas.add(new float[]{0, D_COBRE, 46, 90, 0});
            if(rnd(5, 4, sem(s, 9010)) < 0.80f) cosas.add(new float[]{0, D_PLOMO, 46, 90, 0});
            if(rnd(5, 5, sem(s, 9010)) < 0.60f) cosas.add(new float[]{0, D_BERILIO, 46, 92, rnd(5, 22, sem(s, 9010)) < 0.5f ? 1 : 0});
            if(rnd(5, 6, sem(s, 9010)) < 0.12f) cosas.add(new float[]{0, D_TITANIO, 95, 150, 0});
            if(rnd(5, 7, sem(s, 9010)) < 0.35f) cosas.add(new float[]{0, D_GRAFITO, 60, 104, 0});
            // pozos: 1 a 3, de tipos distintos entre 6 (agua, alquitrán, geotérmica, respiraderos, arkycita, escoria)
            int nPozos = Math.min(3, 1 + (int)(rnd(5, 8, sem(s, 9020)) * 3f));
            int[] tipos = {0, 1, 2, 3, 4, 5};
            for(int i = 5; i > 0; i--){
                int j = (int)(rnd(i, 3, sem(s, 9021)) * (i + 1));
                int t = tipos[i]; tipos[i] = tipos[j]; tipos[j] = t;
            }
            for(int k = 0; k < nPozos; k++) cosas.add(new float[]{1, tipos[k], 30, 58, 0});

            if(dim >= 0){
                // Dimensión propia: se quita todo lo del otro planeta...
                for(int i = cosas.size - 1; i >= 0; i--){
                    float[] c = cosas.get(i);
                    if(!permitido((int)c[0], (int)c[1], dim)) cosas.remove(i);
                }
                // ...y se garantiza lo imprescindible de ese planeta, que el filtro pudo quitar
                boolean hayCarbon = false, hayAgua = false, hayRespiradero = false;
                for(float[] c : cosas){
                    if(c[0] == 0 && (int)c[1] == D_CARBON) hayCarbon = true;
                    if(c[0] == 1 && (int)c[1] == 0) hayAgua = true;
                    if(c[0] == 1 && (int)c[1] == 3) hayRespiradero = true;
                }
                if(dim == 0){
                    if(!hayCarbon) cosas.add(new float[]{0, D_CARBON, 46, 86, 0});
                    if(!hayAgua) cosas.add(new float[]{1, 0, 30, 58, 0});
                }else if(!hayRespiradero){
                    cosas.add(new float[]{1, 3, 30, 58, 0});
                }
            }

            // orden angular aleatorio
            for(int i = cosas.size - 1; i > 0; i--){
                int j = (int)(rnd(i, 6, sem(s, 9030)) * (i + 1));
                float[] t = cosas.get(i); cosas.set(i, cosas.get(j)); cosas.set(j, t);
            }
            int n = cosas.size;
            float a0 = rnd(7, 7, sem(s, 9031)) * 6.2831853f;
            float[] px = new float[n], py = new float[n], pr = new float[n], hl = new float[n], hw = new float[n], cv = new float[n];
            int[] morfs = new int[n];
            float[] par = new float[3];
            for(int k = 0; k < n; k++){
                float[] c = cosas.get(k);
                float ang = a0 + (k + (rnd(k, 8, sem(s, 9032)) - 0.5f) * 0.7f) * (6.2831853f / n);
                float d = c[2] + rnd(k, 9, sem(s, 9033)) * (c[3] - c[2]);
                int tipo = (int)c[1];
                if(c[0] == 0){
                    int morf;
                    if(tipo == D_GRAFITO) morf = new int[]{M_CORTA, M_MEDIA, M_DOBLE, M_ARCO}[(int)(rnd(k, 23, sem(s, 9045)) * 4f) & 3];
                    else if(tipo == D_ARENA) morf = M_PARCHE;
                    else if(c[4] == 1f) morf = new int[]{M_CORTA, M_RACIMO, M_PARCHE, M_MEDIA}[(int)(rnd(k, 23, sem(s, 9045)) * 4f) & 3];
                    else{
                        morf = elegirMorf(rnd(k, 23, sem(s, 9045)));
                        if(morf == M_LARGA || morf == M_DISPERSA) morf = M_CORTA;   // lo imprescindible nunca es una raya enorme ni polvo suelto
                    }
                    float mult = tipo == D_ARENA ? 2.2f : (tipo == D_GRAFITO ? 1.5f : (tipo == D_COBRE || tipo == D_PLOMO || tipo == D_BERILIO ? 1.3f : 1f));
                    paramsMorf(morf, rnd(k, 10, sem(s, 9034)), rnd(k, 13, sem(s, 9037)), rnd(k, 14, sem(s, 9038)), mult, par);
                    morfs[k] = morf;
                    hl[k] = par[0]; hw[k] = par[1]; cv[k] = par[2];
                    pr[k] = alcance(morf, hl[k], hw[k]) * 0.7f;
                }else{
                    pr[k] = 6.5f + rnd(k, 10, sem(s, 9034)) * 3f;
                }
                px[k] = (float)Math.cos(ang) * d;
                py[k] = (float)Math.sin(ang) * d;
            }
            // que no se pisen: si dos se solapan, el segundo se aleja del spawn
            for(int it = 0; it < 6; it++){
                for(int i = 0; i < n; i++){
                    for(int j = i + 1; j < n; j++){
                        float need = pr[i] * 1.15f + pr[j] * 1.15f + 3f;
                        float ddx = px[j] - px[i], ddy = py[j] - py[i];
                        float dd = (float)Math.sqrt(ddx * ddx + ddy * ddy);
                        if(dd >= need) continue;
                        float rj = (float)Math.sqrt(px[j] * px[j] + py[j] * py[j]);
                        if(rj < 1f) continue;
                        float k2 = (rj + (need - dd) + 1f) / rj;
                        px[j] *= k2;
                        py[j] *= k2;
                    }
                }
            }
            Seq<Float> deps = new Seq<>(), pozs = new Seq<>(), cams = new Seq<>();
            boolean vCobre = false, vPlomo = false, vBer = false;
            for(int k = 0; k < n; k++){
                float[] c = cosas.get(k);
                if(c[0] == 0){
                    int tipo = (int)c[1];
                    int forma = tipo == D_GRAFITO ? formaGrafito(rnd(k, 11, sem(s, 9035))) : 0;
                    float rot = rnd(k, 12, sem(s, 9036)) * 6.2831853f;
                    deps.add(c[1]); deps.add(px[k]); deps.add(py[k]); deps.add(hl[k]); deps.add((float)forma); deps.add(rot);
                    deps.add(hw[k]); deps.add(cv[k]); deps.add(c[4]); deps.add((float)morfs[k]);
                    boolean esencial = false;
                    if(tipo == D_COBRE && !vCobre){ vCobre = true; esencial = true; }
                    if(tipo == D_PLOMO && !vPlomo){ vPlomo = true; esencial = true; }
                    if(tipo == D_BERILIO && !vBer){ vBer = true; esencial = true; }
                    if(tipo == D_GRAFITO || tipo == D_ARENA) esencial = true;
                    if(esencial && Math.hypot(px[k], py[k]) < 125f){ cams.add(px[k]); cams.add(py[k]); }
                }else{
                    float rot = rnd(k, 15, sem(s, 9039)) * 6.2831853f;
                    float razon = 0.60f + 0.40f * rnd(k, 16, sem(s, 9044));
                    pozs.add(c[1]); pozs.add(px[k]); pozs.add(py[k]); pozs.add(pr[k]); pozs.add(rot); pozs.add(razon);
                }
            }
            sp.dep = new float[deps.size];
            for(int i = 0; i < deps.size; i++) sp.dep[i] = deps.get(i);
            sp.poz = new float[pozs.size];
            for(int i = 0; i < pozs.size; i++) sp.poz[i] = pozs.get(i);
            sp.cam = new float[cams.size];
            for(int i = 0; i < cams.size; i++) sp.cam[i] = cams.get(i);
            return sp;
        }

        // ---- Depósitos globales (todo el mundo salvo el spawn): una celda de 40x40 puede tener UN depósito, de ----
        // ---- tipo, forma y tamaño propios, y hay zonas ricas y zonas pobres. Todo es función pura de la celda.  ----
        static final int CELDA_MENA = 40;
        static final float PROB_MENA = 1.15f;
        // {tipo, distMin, peso}
        static final float[][] TABLA_MENAS_S = {
            {D_COBRE, 0, 30}, {D_PLOMO, 0, 28}, {D_CHATARRA, 40, 9}, {D_CARBON, 60, 13}, {D_TITANIO, 140, 11}, {D_TORIO, 320, 5}
        };
        static final float[][] TABLA_MENAS_E = {
            {D_BERILIO, 0, 34}, {D_GRAFITO, 25, 20}, {D_TUNGSTENO, 100, 13}, {D_TORIO_E, 260, 5}
        };

        /** @return tipo*1000 + distancia normalizada*100, o -1. aux = {along, across, forma, rot, flag} (flag: 0 suelo, 2 pared). */
        static int enDepositoGlobal(int s, int x, int y, boolean ere, float dist, float[] aux){
            if(dist < 30f) return -1;
            int ci = Math.floorDiv(x, CELDA_MENA), cj = Math.floorDiv(y, CELDA_MENA);
            float[][] tabla = ere ? TABLA_MENAS_E : TABLA_MENAS_S;
            float[] par = new float[3];
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    float r0 = rnd(i, j, sem(s, 7000));
                    if(r0 > PROB_MENA * 1.8f) continue;                     // descarte barato
                    float cx0 = (i + 0.5f) * CELDA_MENA, cy0 = (j + 0.5f) * CELDA_MENA;
                    if(r0 > PROB_MENA * (0.30f + 1.50f * fbm(sem(s, 7001), 380f, 2, cx0, cy0))) continue;   // zonas ricas / pobres
                    float dsp = (float)Math.hypot(cx0, cy0);
                    float tot = 0f;
                    for(float[] g : tabla) if(dsp >= g[1]) tot += g[2];
                    if(tot <= 0f) continue;
                    float pick = rnd(i, j, sem(s, 7003)) * tot, acc = 0f;
                    int tipo = (int)tabla[0][0];
                    for(float[] g : tabla){
                        if(dsp < g[1]) continue;
                        acc += g[2];
                        if(pick < acc){ tipo = (int)g[0]; break; }
                    }
                    int morf = elegirMorf(rnd(i, j, sem(s, 7004)));
                    float mult = tipo == D_TORIO || tipo == D_TORIO_E ? 0.8f : (tipo == D_TITANIO ? 0.9f : 1f);
                    paramsMorf(morf, rnd(i, j, sem(s, 7009)), rnd(i, j, sem(s, 7010)), rnd(i, j, sem(s, 7011)), mult, par);
                    float mx = 8f;
                    float px = i * CELDA_MENA + mx + rnd(i, j, sem(s, 7005)) * (CELDA_MENA - 2 * mx);
                    float py = j * CELDA_MENA + mx + rnd(i, j, sem(s, 7012)) * (CELDA_MENA - 2 * mx);
                    float dx = x - px, dy = y - py, R = alcance(morf, par[0], par[1]);
                    if(dx * dx + dy * dy > R * R) continue;
                    boolean pared = ere && tieneMuro(tipo) && morf != M_DISPERSA && rnd(i, j, sem(s, 7007)) < 0.40f;
                    float rot = rnd(i, j, sem(s, 7006)) * 6.2831853f;
                    int id = hash(i, j, sem(s, 7008));
                    float dn = forma(morf, dx, dy, par[0], par[1], rot, par[2], id, x, y, s, 0f, aux);
                    if(dn < 0f) continue;
                    aux[2] = Mathf.clamp((int)(rnd(i, j, sem(s, 7013)) * 6f), 0, 5);
                    aux[3] = rot;
                    aux[4] = pared ? 2f : 0f;
                    return tipo * 1000 + Math.min(999, (int)(dn * 100f));
                }
            }
            return -1;
        }

        /** Paredes de grafito según la forma, en coordenadas de la veta: u a lo largo (-1..1), v a lo ancho (-1..1). */
        static boolean muroGrafito(int forma, float u, float v, int s, int x, int y){
            float av = Math.abs(v);
            switch(forma){
                case 0: return av < 0.80f;                                             // veta maciza
                case 1: return av < 0.85f && fbm(sem(s, 9430), 8f, 2, x, y) > 0.38f;   // veta rota en trozos
                case 2: return Math.abs(av - 0.60f) < 0.32f;                            // dos vetas paralelas con pasillo
                case 3: return av < 0.85f && Math.abs(u) > 0.22f;                       // dos tramos con hueco al medio
                case 4: return av < 1f && fbm(sem(s, 9431), 5f, 2, x, y) > 0.50f;       // dispersa
                default: return av < 0.50f || (av < 1f && fbm(sem(s, 9432), 6f, 2, x, y) > 0.56f); // núcleo con costras
            }
        }

        /** @return tipo*1000 + distancia normalizada*100, o -1. aux = {along, across, forma, rot, flag}: flag 1 inicial, 3 inicial de pared. */
        static int enDeposito(int s, int x, int y, Spawn sp, float[] aux, boolean ere, float dist){
            float[] dep = sp.dep;
            for(int k = 0; k < dep.length; k += STRIDE_DEP){
                float dx = (float)(x - (double)dep[k + 1]), dy = (float)(y - (double)dep[k + 2]);
                int morf = (int)dep[k + 9];
                boolean pared = dep[k + 8] == 1f;
                float R = alcance(morf, dep[k + 3], dep[k + 6]) + (pared ? 2f : 0f);
                if(dx * dx + dy * dy > R * R) continue;
                float dn = forma(morf, dx, dy, dep[k + 3], dep[k + 6], dep[k + 5], dep[k + 7], k, x, y, s, 0f, aux);
                if(dn < 0f) continue;
                aux[2] = dep[k + 4];
                aux[3] = dep[k + 5];
                aux[4] = pared ? 3f : 1f;
                return (int)dep[k] * 1000 + Math.min(999, (int)(dn * 100f));
            }
            return enDepositoGlobal(s, x, y, ere, dist, aux);
        }

        // ---- Cráteres de impacto (Serpulo) ----
        /** @return 0 nada, 1 interior, 2 borde; en interior suma 10*porcentaje radio. */
        static int crater(int s, int x, int y, float dist){
            if(dist < 36f) return 0;
            int ci = Math.floorDiv(x, 64), cj = Math.floorDiv(y, 64);
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    if(rnd(i, j, sem(s, 5001)) > 0.30f) continue;
                    float prx = 12f + rnd(i, j, sem(s, 5002)) * 40f, pry = 12f + rnd(i, j, sem(s, 5003)) * 40f;
                    float r = 5f + rnd(i, j, sem(s, 5004)) * 6.5f;
                    float dx = (x - i * 64) - prx, dy = (y - j * 64) - pry;
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(sem(s, 5005), 5f, 2, x, y) - 0.5f) * 3.2f;
                    if(d < r) return 1 + 10 * Math.min(99, (int)(d / r * 100f)) + (r > 8.5f ? 5000 : 0);
                    if(d < r + 1.8f) return 2;
                }
            }
            return 0;
        }

        /** Cráter con respiradero (Erekir): @return 0 nada, 1 interior, 2 centro (respiradero 3x3). */
        static int craterVent(int s, int x, int y, float dist){
            if(dist < 40f) return 0;
            int ci = Math.floorDiv(x, 72), cj = Math.floorDiv(y, 72);
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    if(rnd(i, j, sem(s, 6001)) > 0.30f) continue;
                    int px = i * 72 + 14 + (int)(rnd(i, j, sem(s, 6002)) * 44f), py = j * 72 + 14 + (int)(rnd(i, j, sem(s, 6003)) * 44f);
                    float r = 3.6f + rnd(i, j, sem(s, 6004)) * 2.6f;
                    int dx = x - px, dy = y - py;
                    if(Math.abs(dx) <= 1 && Math.abs(dy) <= 1) return 2;
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(sem(s, 6005), 4f, 2, x, y) - 0.5f) * 2.4f;
                    if(d < r) return 1;
                }
            }
            return 0;
        }

        // ---- Variación local de suelos: parches y vetas de otro suelo dentro de cada bioma ----
        static int variante(int s, int x, int y, int p, boolean ere){
            float a = fbm(sem(s, 90), 38f, 2, x, y);
            float b = fbm(sem(s, 91), 24f, 2, x + 800.0, y + 800.0);
            if(!ere){
                switch(p){
                    case P_STONE:
                        if(a > 0.63f) return P_BASALT;
                        if(Math.abs(a - 0.5f) < 0.016f) return P_DARKSAND;
                        if(b < 0.28f) return P_SAND;
                        break;
                    case P_SAND:
                        if(a > 0.62f) return P_DARKSAND;
                        if(Math.abs(a - 0.5f) < 0.014f) return P_STONE;
                        if(b > 0.71f) return P_SALT;
                        break;
                    case P_DARKSAND:
                        if(a > 0.64f) return P_SAND;
                        if(Math.abs(b - 0.5f) < 0.015f) return P_STONE;
                        if(a < 0.29f) return P_BASALT;
                        break;
                    case P_SALT:
                        if(a > 0.60f) return P_SAND;
                        break;
                    case P_MOSS:
                        if(a > 0.62f) return P_SPORE;
                        if(b < 0.29f) return P_DARKSAND;
                        break;
                    case P_SPORE:
                        if(a > 0.64f) return P_MOSS;
                        break;
                    case P_SNOW:
                        if(a > 0.63f) return P_ICESNOW;
                        if(b > 0.71f) return P_ICE;
                        break;
                    case P_ICE:
                        if(a > 0.62f) return P_SNOW;
                        break;
                    case P_ICESNOW:
                        if(b > 0.64f) return P_SNOW;
                        break;
                    case P_BASALT:
                        if(a > 0.64f) return P_STONE;
                        break;
                    default: break;
                }
            }else{
                switch(p){
                    case P_REGO:
                        if(a > 0.64f) return P_YELLOW;
                        if(Math.abs(a - 0.5f) < 0.015f) return P_RHYO;
                        break;
                    case P_RHYO:
                        if(Math.abs(b - 0.5f) < 0.014f) return P_REGO;
                        if(a < 0.28f) return P_CARBON;
                        break;
                    case P_YELLOW:
                        if(a > 0.64f) return P_REGO;
                        break;
                    case P_CARBON:
                        if(a > 0.62f) return P_RHYO;
                        break;
                    default: break;
                }
            }
            return p;
        }

        // ---- Pisos base ----
        static int pisoSerpulo(int s, int x, int y, float dist, double wx, double wy){
            // dos escalas de relieve (regiones grandes y pequeñas) mezcladas por un campo lento: biomas de tamaño dinámico
            float hA = fbm(sem(s, 1), 170f, 5, wx, wy);
            float hB = fbm(sem(s, 2), 85f, 4, wx, wy);
            float wh = suave((fbm(sem(s, 14), 700f, 2, x, y) - 0.35f) * 3.3f);
            float h = est(lerp(hA, hB, wh * 0.65f), K_ALTURA);
            float t = est(fbm(sem(s, 11), 420f, 4, wx, wy), K_TEMP);
            // dispersión fina: la frontera entre biomas deja de ser una curva de nivel limpia (blending)
            h += (fbm(sem(s, 15), 12f, 2, x, y) - 0.5f) * 0.12f;
            t += (fbm(sem(s, 16), 16f, 2, x + 700.0, y - 400.0) - 0.5f) * 0.14f;
            h = Mathf.clamp(h, 0f, 0.9999f);
            t = Mathf.clamp(t, 0f, 0.9999f);
            int row = Mathf.clamp((int)(Math.pow(t, 1.7) * 13f), 0, 12);
            int col = Mathf.clamp((int)(h * 13f), 0, 12);
            int p = TABLA_S[row][col];

            p = variante(s, x, y, p, false);
            if(p == P_DARKSAND && dist > 40f){
                if(Math.abs(0.5f - fbm(sem(s, 41), 80f, 2, x, y)) > 0.17f && Math.abs(0.5f - fbm(sem(s, 42), 60f, 1, x, y)) > 0.31f) p = P_TAR;
            }
            if(p == P_HOT){
                float n = Math.abs(0.5f - fbm(sem(s, 43), 80f, 4, x, y));
                if(n > 0.04f) p = P_BASALT;
                else if(n < 0.012f) p = P_MAGMA;
            }
            if(dist > 60f && (p == P_SAND || p == P_DARKSAND || p == P_SALT || p == P_STONE || p == P_MOSS || p == P_SPORE)){
                float lk = fbm(sem(s, 44), 200f, 5, x, y);
                if(lk > 0.655f){
                    boolean arena = p == P_SAND || p == P_SALT;
                    if(lk > 0.69f) p = arena ? P_SANDW : P_DTW;
                    else if(lk > 0.672f) p = arena ? P_SANDW : P_DSW;
                    else p = arena ? P_SAND : P_DARKSAND;
                }
            }
            if(dist > 25f && seco(p) && p != P_SNOW && p != P_ICE && p != P_ICESNOW && p != P_BASALT && p != P_HOT && p != P_MAGMA){
                if(Math.abs(fbm(sem(s, 45), 150f, 2, x, y) - 0.5f) < 0.0045f) p = (p == P_SAND || p == P_SALT) ? P_SANDW : P_DSW;
            }
            return p;
        }

        static int pisoErekir(int s, int x, int y, float dist, double wx, double wy){
            float hA = fbm(sem(s, 1), 170f, 5, wx, wy);
            float hB = fbm(sem(s, 2), 85f, 4, wx, wy);
            float wh = suave((fbm(sem(s, 14), 700f, 2, x, y) - 0.35f) * 3.3f);
            float h = est(lerp(hA, hB, wh * 0.65f), K_ALTURA);
            h += (fbm(sem(s, 15), 12f, 2, x, y) - 0.5f) * 0.12f;
            h = Mathf.clamp(h, 0f, 0.9999f);
            int p = TERRENO_E[Mathf.clamp((int)(h * 8f), 0, 7)];
            p = variante(s, x, y, p, true);

            if(dist > 45f){
                float jit = (fbm(sem(s, 70), 14f, 2, x, y) - 0.5f) * 0.07f;   // frontera irregular entre los biomas de Erekir
                float c = est(fbm(sem(s, 61), 210f, 4, wx, wy), 2.0f) + jit;
                if(c < 0.27f){
                    p = fbm(sem(s, 62), 45f, 3, x, y) < 0.44f ? P_CRYSTF : P_CRYST;
                }else{
                    float b = fbm(sem(s, 63), 190f, 4, wx, wy) + jit;
                    float r = fbm(sem(s, 66), 240f, 4, wx, wy) + jit;
                    if(b > 0.60f){
                        p = P_BERYL;
                        if(Math.abs(fbm(sem(s, 64), 40f, 4, x, y) - 0.5f) < 0.03f) p = P_ARKYIC;
                        if(fbm(sem(s, 65), 110f, 4, x, y) > 0.66f) p = P_ARKYCITE;
                    }else if(r > 0.64f){
                        p = fbm(sem(s, 67), 19f, 4, x, y) > 0.55f ? P_DENSERED : P_RED;
                        if(r > 0.70f) p = P_REDICE;
                    }
                }
                if(p == P_RHYO || p == P_YELLOW || p == P_REGO){
                    float sl = fbm(sem(s, 68), 260f, 5, x, y);
                    if(sl > 0.628f) p = P_SLAG;
                    else if(sl > 0.59f) p = P_YELLOW;
                }
                if(p == P_RHYO && fbm(sem(s, 69), 60f, 5, x, y) < 0.38f) p = P_ROUGH;
            }
            return p;
        }

        // ---- Tipo de piso a partir del piso local para cráteres de Erekir ----
        static int cratereDe(int p){
            if(p == P_RHYO || p == P_ROUGH) return P_RCRATER;
            if(p == P_BERYL || p == P_ARKYIC) return P_ARKYIC;
            if(p == P_YELLOW || p == P_REGO) return P_YPLATES;
            if(p == P_RED || p == P_DENSERED || p == P_REDICE) return P_RED;
            return p;
        }

        static int ventDe(int p){
            if(p == P_RHYO || p == P_ROUGH || p == P_RCRATER) return P_VRHYO;
            if(p == P_BERYL || p == P_ARKYIC) return P_VARKY;
            if(p == P_YELLOW || p == P_REGO || p == P_YPLATES) return P_VYELLOW;
            if(p == P_RED || p == P_DENSERED || p == P_REDICE) return P_VRED;
            if(p == P_CARBON) return P_VCARBON;
            return P_VRHYO;
        }

        // ---- Muros: crestas alargadas en varias orientaciones, pasillos, hilos de roca y rocas sueltas ----
        /** Camino despejado y sinuoso desde el spawn hasta cada veta esencial (nadie queda aislado de sus recursos). */
        static boolean camino(int s, Spawn sp, int x, int y){
            float[] cam = sp.cam;
            for(int k = 0; k < cam.length; k += 2){
                float ex = cam[k], ey = cam[k + 1];
                float len2 = ex * ex + ey * ey;
                if(len2 < 1f) continue;
                float len = (float)Math.sqrt(len2);
                float wob = (fbm(sem(s, 9700 + k), 70f, 2, x, y) - 0.5f) * 16f;
                float px = x + (-ey / len) * wob, py = y + (ex / len) * wob;
                float t = Mathf.clamp((px * ex + py * ey) / len2, 0f, 1f);
                float qx = px - ex * t, qy = py - ey * t;
                float w = 2.3f + (fbm(sem(s, 9710 + k), 18f, 2, x, y) - 0.5f) * 2.4f;
                if(qx * qx + qy * qy < w * w) return true;
            }
            return false;
        }

        static boolean hayMuro(int s, int x, int y, double wx, double wy, float dist, boolean ere, Spawn sp){
            // regiones abiertas y regiones rocosas
            float reg = fbm(sem(s, 9500), 650f, 3, x, y);
            float lim = (ere ? LIM_E : LIM_S) + (0.5f - reg) * 0.40f;
            if(dist < 70f) lim += suave(1f - dist / 70f) * sp.paredes;   // carácter del spawn (más o menos paredes)

            // segunda deformación del terreno: formas orgánicas, no manchas redondas
            double qx = wx + (fbm(sem(s, 52), 45f, 3, x, y) - 0.5) * 46.0;
            double qy = wy + (fbm(sem(s, 53), 45f, 3, x + 321.0, y - 123.0) - 0.5) * 46.0;
            float fa = fbmA(sem(s, 3), 70f, 2.0f, CA, SA, 4, qx, qy);
            float fb = fbmA(sem(s, 4), 70f, 2.0f, CB, SB, 4, qx, qy);
            float fc = fbm(sem(s, 5), 55f, 4, qx, qy);
            float w1 = suave((fbm(sem(s, 9601), 600f, 2, x, y) - 0.30f) * 2.6f);
            float w2 = suave((fbm(sem(s, 9602), 500f, 2, x + 1000.0, y) - 0.38f) * 2.6f);
            float f = lerp(lerp(fa, fb, w1), fc, w2 * 0.7f);

            float ancho = ANCHO_PASO + 0.040f * fbm(sem(s, 8), 260f, 2, x, y);
            if(dist < 70f && sp.paredes < 0.05f) ancho += 0.02f;
            boolean valle = Math.abs(fbm(sem(s, 7), 100f, 3, wx, wy) - 0.5f) < ancho;
            boolean masivo = f > lim && !valle;

            // hilos finos de roca que parten los espacios abiertos (con huecos)
            boolean hilo = Math.abs(fbm(sem(s, 9603), 52f, 3, qx, qy) - 0.5f) < 0.011f
                && fbm(sem(s, 9604), 26f, 2, x, y) > 0.44f && dist > sp.radioLibre + 10f;
            boolean roca = fbm(sem(s, 6), 28f, 2, x, y) > 0.745f && dist > sp.radioLibre + 25f;
            return masivo || hilo || roca;
        }

        // ---- Accesibilidad de las menas de pared ----
        // El taladro de haz (plasma bore) recorre la línea que tiene delante y se DETIENE en la primera casilla sólida: solo
        // extrae si esa casilla es la mena. Una mena rodeada de roca por todos lados es imposible de sacar. Por eso una mena
        // de pared solo existe si tiene una cara expuesta con hueco libre de 2x2 para colocar el taladro frente a ella.
        static final int[] DX4 = {1, 0, -1, 0}, DY4 = {0, 1, 0, -1};

        /** ¿Es sólida esta casilla? (aproximación barata del terreno final: sirve para decidir si una mena es alcanzable). */
        static boolean solidaAprox(int s, int x, int y, Spawn sp){
            float dist = (float)Math.sqrt((double)x * x + (double)y * y);
            if(dist < sp.radioLibre * 0.8f) return false;
            double wx = x + (fbm(sem(s, 50), 90f, 3, x, y) - 0.5) * 70.0;
            double wy = y + (fbm(sem(s, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;
            boolean ere = esE(s, x, y, sp);
            if(hayMuro(s, x, y, wx, wy, dist, ere, sp)) return true;
            // zonas de pared forzadas por depósitos de pared
            float[] tmp = new float[14];
            int dep = enDeposito(s, x, y, sp, tmp, ere, dist);
            if(dep < 0) return false;
            if(tmp[4] >= 2f) return true;
            // paredes de grafito de un depósito de grafito
            return dep / 1000 == D_GRAFITO && muroGrafito((int)tmp[2], tmp[0], tmp[1], s, x, y);
        }

        static boolean expuesto(int s, int x, int y, Spawn sp){
            for(int d = 0; d < 4; d++){
                int dx = DX4[d], dy = DY4[d], lx = -dy, ly = dx;
                if(solidaAprox(s, x + dx, y + dy, sp) || solidaAprox(s, x + 2 * dx, y + 2 * dy, sp)) continue;
                // el taladro mide 2x2: también debe estar libre una de las dos casillas laterales de esa pareja
                boolean a = !solidaAprox(s, x + dx + lx, y + dy + ly, sp) && !solidaAprox(s, x + 2 * dx + lx, y + 2 * dy + ly, sp);
                boolean b = !solidaAprox(s, x + dx - lx, y + dy - ly, sp) && !solidaAprox(s, x + 2 * dx - lx, y + 2 * dy - ly, sp);
                if(a || b) return true;
            }
            return false;
        }

        /**
         * Muestrea UNA celda del mundo (el spawn está en el origen virtual (0,0)).
         * out[0]=piso, out[1]=mena (overlay), out[2]=muro (0 nada, 1 muro del piso, 2 muro de grafito),
         * out[3]=prop (0 nada, 1 decoración del piso, 2 cristal, 3 cristal vibrante).
         * aux = buffer de 12 floats: [0..4] datos de la veta, [5..11] caché de la estructura de la celda actual
         * (aux[11] = 0 debe ponerse al empezar cada chunk).
         */
        static void muestrear(int s, int x, int y, Spawn sp, float[] aux, int[] out){
            double dx0 = x, dy0 = y;
            float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
            double wx = x + (fbm(sem(s, 50), 90f, 3, x, y) - 0.5) * 70.0;
            double wy = y + (fbm(sem(s, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;

            boolean ere = esE(s, x, y, sp);
            int p = ere ? pisoErekir(s, x, y, dist, wx, wy) : pisoSerpulo(s, x, y, dist, wx, wy);
            boolean spawn = dist < sp.radioLibre * (0.80f + 0.50f * fbm(sem(s, 9006), 14f, 2, x, y));
            if(spawn){
                // el spawn conserva el bioma que le toque; solo se quita lo peligroso o intransitable
                if(liquido(p)) p = ere ? P_REGO : P_SAND;
                if(p == P_MAGMA || p == P_SLAG || p == P_TAR || p == P_HOT) p = ere ? P_YELLOW : P_BASALT;
            }

            // --- estructuras (bases enemigas, ruinas): despejan el terreno y se construyen encima ---
            int hue = sp.dim >= 0 ? Estructuras.huella(s, sp, x, y, aux) : 0;
            if(hue > 0){
                if(liquido(p) || p == P_MAGMA || p == P_SLAG || p == P_HOT) p = ere ? P_REGO : P_STONE;
                if(hue == 2) p = P_COREZONE;
            }

            // --- pozos garantizados cerca del spawn (elípticos, orientados al azar) ---
            int pzPiso = -1;
            boolean pzLibre = false;
            float[] poz = sp.poz;
            if(hue == 0){
                for(int k = 0; k < poz.length; k += STRIDE_POZ){
                    float ddx = (float)(dx0 - poz[k + 1]), ddy = (float)(dy0 - poz[k + 2]), r = poz[k + 3];
                    if(ddx * ddx + ddy * ddy > r * r * 3.4f) continue;
                    float rot = poz[k + 4], razon = poz[k + 5];
                    float c = (float)Math.cos(rot), sn = (float)Math.sin(rot);
                    float ea = ddx * c + ddy * sn, eb = (-ddx * sn + ddy * c) / razon;
                    float d = (float)Math.sqrt(ea * ea + eb * eb) / r + (fbm(sem(s, 8800 + k), 6f, 2, x, y) - 0.5f) * 0.55f;
                    if(d >= 1.25f) continue;
                    pzLibre = true;            // halo despejado de muros
                    if(d >= 1f) continue;
                    switch((int)poz[k]){
                        case 0: pzPiso = d < 0.55f ? P_WATER : (d < 0.8f ? P_SANDW : P_SAND); break;
                        case 1: pzPiso = d < 0.62f ? P_TAR : P_DARKSAND; break;
                        case 2: pzPiso = d < 0.30f ? P_MAGMA : (d < 0.62f ? P_HOT : P_BASALT); break;
                        case 3: {
                            pzPiso = P_RCRATER;
                            float[][] vents = {{0f, 0f}, {4f, 1f}, {-3f, -3f}};
                            for(float[] o : vents){
                                if(Math.abs(ddx - o[0]) <= 1.5f && Math.abs(ddy - o[1]) <= 1.5f) pzPiso = P_VRHYO;
                            }
                            break;
                        }
                        case 4: pzPiso = d < 0.6f ? P_ARKYCITE : P_ARKYIC; break;
                        default: pzPiso = d < 0.55f ? P_SLAG : P_RCRATER;
                    }
                }
            }
            boolean enPozo = pzPiso >= 0;
            if(enPozo) p = pzPiso;

            int mena = 0, muro = 0, prop = 0;

            // --- cráteres ---
            boolean enCrater = false;
            if(enPozo || pzLibre){
                enCrater = true;
            }else if(hue > 0){
                enCrater = false;
            }else if(!ere){
                int c = crater(s, x, y, dist);
                if(c == 2){
                    if(seco(p)) p = P_BASALT;
                    enCrater = true;
                    if(rnd(x, y, sem(s, 5101)) < 0.18f) mena = O_CHATARRA;      // borde: chatarra de impacto
                }else if(c >= 1){
                    if(!profundo(p)) p = P_CRATERS;
                    enCrater = true;
                    int pct = ((c - 1) % 5000) / 10;
                    if(c >= 5000 && pct < 28 && dist > 150f) mena = O_TITANIO; // cráteres grandes: núcleo de titanio
                    else if(pct > 55 && rnd(x, y, sem(s, 5102)) < 0.10f) mena = O_CHATARRA;
                }
            }else{
                int c = craterVent(s, x, y, dist);
                if(c == 2){
                    if(seco(p)) p = ventDe(p);
                    enCrater = true;
                }else if(c == 1){
                    if(seco(p)) p = cratereDe(p);
                    enCrater = true;
                }
            }

            boolean liq = liquido(p);

            // --- vetas de menas ---
            int dep = (enPozo || pzLibre || hue > 0 || enCrater && mena != 0) ? -1 : enDeposito(s, x, y, sp, aux, ere, dist);
            boolean inicial = dep >= 0 && (aux[4] == 1f || aux[4] == 3f);
            if(liq){
                // las vetas INICIALES siempre quedan en seco; las demás no aparecen bajo el agua
                if(inicial){ p = ere ? P_REGO : P_SAND; liq = false; }
                else dep = -1;
            }
            int tipoDep = dep < 0 ? -1 : dep / 1000;
            int radN = dep < 0 ? 0 : dep % 1000;
            boolean depPared = dep >= 0 && aux[4] >= 2f;   // menas de pared (Erekir): la zona es roca con mena dentro

            // --- muros ---
            if(!liq && !spawn && !enCrater && hue == 0){
                if(hayMuro(s, x, y, wx, wy, dist, ere, sp)){
                    muro = 1;
                    if(dist < 130f && sp.cam.length > 0 && camino(s, sp, x, y)) muro = 0;   // pasillos hacia lo esencial
                }
            }

            // los depósitos iniciales de suelo nunca quedan tapados por muros
            if(inicial && !depPared && tipoDep != D_GRAFITO && radN < 100) muro = 0;
            // depósito de pared: la zona entera (mena + borde) es roca
            if(depPared && !liq && !spawn) muro = 1;

            // veta de grafito: forma variable (maciza, rota, doble, en tramos, dispersa o con costras)
            if(tipoDep == D_GRAFITO && !liq && !spawn){
                if(radN < 85 && fbm(sem(s, 9410), 12f, 2, x, y) > 0.35f) p = P_CARBON;
                muro = muroGrafito((int)aux[2], aux[0], aux[1], s, x, y) ? 2 : 0;
            }
            // arenal: garantiza arena cerca del spawn aunque el bioma no la tenga
            if(tipoDep == D_ARENA && !liq && radN < 92){
                p = fbm(sem(s, 9420), 9f, 2, x, y) > 0.4f ? P_SAND : P_DARKSAND;
                if(!spawn) muro = 0;
            }

            // --- menas ---
            if(tipoDep >= 0 && tipoDep != D_GRAFITO && tipoDep != D_ARENA && !liq){
                boolean enMuro = muro != 0;
                switch(tipoDep){
                    case D_COBRE: if(!enMuro) mena = O_COBRE; break;
                    case D_PLOMO: if(!enMuro) mena = O_PLOMO; break;
                    case D_CHATARRA: if(!enMuro) mena = O_CHATARRA; break;
                    case D_CARBON: if(!enMuro) mena = O_CARBON; break;
                    case D_TITANIO: if(!enMuro) mena = O_TITANIO; break;
                    case D_TORIO: if(!enMuro) mena = O_TORIO; break;
                    case D_BERILIO: mena = depPared ? (radN < 100 ? O_BERILIO_MURO : 0) : (enMuro ? O_BERILIO_MURO : O_BERILIO); break;
                    case D_TUNGSTENO: mena = depPared ? (radN < 100 ? O_TUNGSTENO_MURO : 0) : (enMuro ? O_TUNGSTENO_MURO : O_TUNGSTENO); break;
                    case D_TORIO_E: mena = depPared ? (radN < 100 ? O_TORIO_MURO : 0) : (enMuro ? O_TORIO_MURO : O_TORIO_CRISTAL); break;
                    default: break;
                }
            }

            // --- menas de pared: solo si un taladro puede llegar a ellas ---
            if(mena == O_BERILIO_MURO || mena == O_TUNGSTENO_MURO || mena == O_TORIO_MURO || muro == 2){
                if(!expuesto(s, x, y, sp)){
                    if(muro == 2) muro = 1;        // grafito enterrado: roca normal
                    if(mena >= O_TORIO_MURO) mena = 0;
                }
            }

            // --- decoración / props ---
            if(muro == 0 && mena == 0 && !liq && !spawn && !enPozo && !pzLibre && hue == 0){
                if((p == P_CRYST || p == P_CRYSTF) && rnd(x, y, sem(s, 5201)) < 0.012f){
                    prop = p == P_CRYSTF ? 3 : 2;
                }else if(rnd(x, y, sem(s, 5202)) < 0.011f){
                    prop = 1;
                }
            }

            out[0] = p;
            out[1] = mena;
            out[2] = muro;
            out[3] = prop;
        }
    }

    // ==================================================================
    // Paleta: traduce códigos a ids de bloque (se construye en el hilo principal)
    // ==================================================================
    static final class Paleta{
        final int[] idPiso = new int[Muestreo.N_PISOS];
        final int[] idMuroDePiso = new int[Muestreo.N_PISOS];
        final int[] idPropDePiso = new int[Muestreo.N_PISOS];
        final int[] idMena = new int[Muestreo.N_MENAS];
        int idGrafito, idCristal, idCristalVivo;

        static Block piso(Paleta p, int code, Block b){
            p.idPiso[code] = b.id;
            if(b instanceof Floor){
                Floor f = (Floor)b;
                int w = (f.wall == null || f.wall == Blocks.air) ? (code >= 32 ? Blocks.rhyoliteWall.id : Blocks.stoneWall.id) : f.wall.id;
                p.idMuroDePiso[code] = w;
                p.idPropDePiso[code] = f.decoration == null ? 0 : f.decoration.id;
            }
            return b;
        }

        static Paleta crear(){
            Paleta p = new Paleta();
            int dflt = Blocks.stone.id;
            Arrays.fill(p.idPiso, dflt);

            piso(p, Muestreo.P_DEEP, Blocks.deepwater);
            piso(p, Muestreo.P_WATER, Blocks.water);
            piso(p, Muestreo.P_SANDW, Blocks.sandWater);
            piso(p, Muestreo.P_DSW, Blocks.darksandWater);
            piso(p, Muestreo.P_DTW, Blocks.darksandTaintedWater);
            piso(p, Muestreo.P_TW, Blocks.taintedWater);
            piso(p, Muestreo.P_DEEPT, Blocks.deepTaintedWater);
            piso(p, Muestreo.P_SAND, Blocks.sand);
            piso(p, Muestreo.P_DARKSAND, Blocks.darksand);
            piso(p, Muestreo.P_SALT, Blocks.salt);
            piso(p, Muestreo.P_STONE, Blocks.stone);
            piso(p, Muestreo.P_BASALT, Blocks.basalt);
            piso(p, Muestreo.P_MOSS, Blocks.moss);
            piso(p, Muestreo.P_SPORE, Blocks.sporeMoss);
            piso(p, Muestreo.P_SNOW, Blocks.snow);
            piso(p, Muestreo.P_ICESNOW, Blocks.iceSnow);
            piso(p, Muestreo.P_ICE, Blocks.ice);
            piso(p, Muestreo.P_HOT, Blocks.hotrock);
            piso(p, Muestreo.P_MAGMA, Blocks.magmarock);
            piso(p, Muestreo.P_TAR, Blocks.tar);
            piso(p, Muestreo.P_CRATERS, Blocks.craters);
            piso(p, Muestreo.P_COREZONE, Blocks.coreZone);

            piso(p, Muestreo.P_REGO, Blocks.regolith);
            piso(p, Muestreo.P_YELLOW, Blocks.yellowStone);
            piso(p, Muestreo.P_RHYO, Blocks.rhyolite);
            piso(p, Muestreo.P_CARBON, Blocks.carbonStone);
            piso(p, Muestreo.P_CRYST, Blocks.crystallineStone);
            piso(p, Muestreo.P_CRYSTF, Blocks.crystalFloor);
            piso(p, Muestreo.P_BERYL, Blocks.beryllicStone);
            piso(p, Muestreo.P_ARKYIC, Blocks.arkyicStone);
            piso(p, Muestreo.P_ARKYCITE, Blocks.arkyciteFloor);
            piso(p, Muestreo.P_RED, Blocks.redStone);
            piso(p, Muestreo.P_DENSERED, Blocks.denseRedStone);
            piso(p, Muestreo.P_REDICE, Blocks.redIce);
            piso(p, Muestreo.P_SLAG, Blocks.slag);
            piso(p, Muestreo.P_YPLATES, Blocks.yellowStonePlates);
            piso(p, Muestreo.P_ROUGH, Blocks.roughRhyolite);
            piso(p, Muestreo.P_RCRATER, Blocks.rhyoliteCrater);
            piso(p, Muestreo.P_VRHYO, Blocks.rhyoliteVent);
            piso(p, Muestreo.P_VARKY, Blocks.arkyicVent);
            piso(p, Muestreo.P_VYELLOW, Blocks.yellowStoneVent);
            piso(p, Muestreo.P_VRED, Blocks.redStoneVent);
            piso(p, Muestreo.P_VCARBON, Blocks.carbonVent);

            p.idMena[Muestreo.O_COBRE] = Blocks.oreCopper.id;
            p.idMena[Muestreo.O_PLOMO] = Blocks.oreLead.id;
            p.idMena[Muestreo.O_CHATARRA] = Blocks.oreScrap.id;
            p.idMena[Muestreo.O_CARBON] = Blocks.oreCoal.id;
            p.idMena[Muestreo.O_TITANIO] = Blocks.oreTitanium.id;
            p.idMena[Muestreo.O_TORIO] = Blocks.oreThorium.id;
            p.idMena[Muestreo.O_BERILIO] = Blocks.oreBeryllium.id;
            p.idMena[Muestreo.O_TUNGSTENO] = Blocks.oreTungsten.id;
            p.idMena[Muestreo.O_TORIO_CRISTAL] = Blocks.oreCrystalThorium.id;
            p.idMena[Muestreo.O_TORIO_MURO] = Blocks.wallOreThorium.id;
            p.idMena[Muestreo.O_BERILIO_MURO] = Blocks.wallOreBeryllium.id;
            p.idMena[Muestreo.O_TUNGSTENO_MURO] = Blocks.wallOreTungsten.id;
            p.idGrafito = Blocks.graphiticWall.id;
            p.idCristal = Blocks.crystalCluster.id;
            p.idCristalVivo = Blocks.vibrantCrystalCluster.id;
            return p;
        }
    }

    // ==================================================================
    // Utilidades de carga (seguras también en modo headless)
    // ==================================================================
    static final class Carga{
        static void mostrar(String t){ if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.show(t); }
        static void texto(String t){ if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.setText(t); }
        static void ocultar(){ if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.hide(); }
    }

    // ==================================================================
    // Datos de un chunk calculado en segundo plano
    // ==================================================================
    static final class ChunkData{
        int cx, cy, epoca;
        final short[] piso = new short[TAM_CHUNK * TAM_CHUNK];
        final short[] mena = new short[TAM_CHUNK * TAM_CHUNK];
        final short[] bloque = new short[TAM_CHUNK * TAM_CHUNK];
        // buffers de trabajo del muestreo: viajan con el chunk (cero asignaciones por chunk)
        private final float[] aux = new float[14];
        private final int[] out = new int[4];

        // ---- Pool: ~6 KB por chunk. Sin esto cada chunk nuevo = 3 arrays + 2 buffers que el GC de Android tiene que recoger.
        private static final int POOL_MAX = 48;
        private static final ConcurrentLinkedQueue<ChunkData> POOL = new ConcurrentLinkedQueue<>();
        private static final AtomicInteger poolN = new AtomicInteger();

        private ChunkData(){}

        static ChunkData obtener(int cx, int cy, int epoca){
            ChunkData d = POOL.poll();
            if(d != null) poolN.decrementAndGet(); else d = new ChunkData();
            d.cx = cx;
            d.cy = cy;
            d.epoca = epoca;
            return d;
        }

        /** Devuelve el chunk al pool. Llamar cuando YA no se va a leer (tras aplicarlo al mundo o al descartarlo). */
        void liberar(){
            if(poolN.get() >= POOL_MAX) return;   // el exceso lo recoge el GC; es raro
            poolN.incrementAndGet();
            POOL.offer(this);
        }

        /**
         * Pura: no toca el mundo. Seguro en hilos secundarios.
         * (ox, oy) = origen VIRTUAL de la ventana: el terreno depende solo de coordenadas virtuales,
         * así que al deslizar la ventana el mundo es idéntico y sin costuras.
         * @param dim -1 = terreno mezclado (mundos antiguos), 0 = Serpulo, 1 = Erekir
         * IMPORTANTE: al venir de un pool, CADA celda se escribe aquí (también las que se saltan).
         */
        static ChunkData generar(Paleta pal, int semilla, int dim, int tam, int cx, int cy, int epoca, int ox, int oy){
            ChunkData d = obtener(cx, cy, epoca);
            int sd = Muestreo.semDim(semilla, dim);
            Muestreo.Spawn sp = Muestreo.spawn(sd, dim);   // el spawn del mundo está en el origen virtual (0,0)
            int bordePiso = pal.idPiso[Muestreo.P_STONE], bordeMuro = pal.idMuroDePiso[Muestreo.P_STONE];
            d.aux[11] = 0f;   // caché de estructura de la celda: vacía al empezar el chunk
            for(int ly = 0; ly < TAM_CHUNK; ly++){
                for(int lx = 0; lx < TAM_CHUNK; lx++){
                    int x = cx * TAM_CHUNK + lx, y = cy * TAM_CHUNK + ly;
                    int i = ly * TAM_CHUNK + lx;
                    d.mena[i] = 0;
                    d.bloque[i] = 0;
                    if(x >= tam || y >= tam){
                        d.piso[i] = (short)bordePiso;
                        continue;
                    }
                    int vx = ox + x, vy = oy + y;
                    if(vx < -LIMITE_MUNDO || vx > LIMITE_MUNDO || vy < -LIMITE_MUNDO || vy > LIMITE_MUNDO){
                        // más allá del borde del mundo (±30 000 000): muro sólido, como el world border de Minecraft
                        d.piso[i] = (short)bordePiso;
                        d.bloque[i] = (short)bordeMuro;
                        continue;
                    }
                    Muestreo.muestrear(sd, vx, vy, sp, d.aux, d.out);
                    int[] out = d.out;
                    d.piso[i] = (short)pal.idPiso[out[0]];
                    d.mena[i] = (short)pal.idMena[out[1]];
                    int b = 0;
                    if(out[2] == 1) b = pal.idMuroDePiso[out[0]];
                    else if(out[2] == 2) b = pal.idGrafito;
                    else if(out[3] == 1) b = pal.idPropDePiso[out[0]];
                    else if(out[3] == 2) b = pal.idCristal;
                    else if(out[3] == 3) b = pal.idCristalVivo;
                    d.bloque[i] = (short)b;
                }
            }
            return d;
        }
    }

    // ==================================================================
    // Registro de un edificio guardado (coordenadas VIRTUALES)
    // ==================================================================
    static final class Reg{
        int x, y, rot, ver, equipo;
        String bloque;
        byte[] datos = new byte[0];
        int cfgTipo;          // 0 nada, 1 Point2, 2 Point2[], 3 byte[]  (configs RELATIVAS: sobreviven a mover la ventana)
        int[] cfg;
        byte[] cfgBytes;
        double dt;            // ticks que estuvo FUERA de la ventana (no se guarda: se calcula al cargar)

        static Reg desde(Building b, int ox, int oy){
            Reg r = new Reg();
            r.x = ox + b.tile.x;
            r.y = oy + b.tile.y;
            r.bloque = b.block.name;
            r.equipo = b.team.id;
            r.rot = b.rotation;
            r.ver = b.version();
            try{
                ByteArrayOutputStream bb = new ByteArrayOutputStream();
                DataOutputStream bo = new DataOutputStream(bb);
                b.writeAll(Writes.get(bo));
                bo.flush();
                r.datos = bb.toByteArray();
            }catch(Throwable t){
                r.datos = new byte[0];
            }
            try{
                Object c = b.config();
                if(c instanceof Point2){
                    Point2 p = (Point2)c;
                    r.cfgTipo = 1;
                    r.cfg = new int[]{p.x, p.y};
                }else if(c instanceof Point2[]){
                    Point2[] ps = (Point2[])c;
                    r.cfgTipo = 2;
                    r.cfg = new int[ps.length * 2];
                    for(int i = 0; i < ps.length; i++){ r.cfg[i * 2] = ps[i].x; r.cfg[i * 2 + 1] = ps[i].y; }
                }else if(c instanceof byte[]){
                    r.cfgTipo = 3;
                    r.cfgBytes = (byte[])c;
                }
            }catch(Throwable ignored){}
            return r;
        }

        Object configObjeto(){
            if(cfgTipo == 1) return new Point2(cfg[0], cfg[1]);
            if(cfgTipo == 2){
                Point2[] ps = new Point2[cfg.length / 2];
                for(int i = 0; i < ps.length; i++) ps[i] = new Point2(cfg[i * 2], cfg[i * 2 + 1]);
                return ps;
            }
            if(cfgTipo == 3) return cfgBytes;
            return null;
        }

        void escribir(DataOutputStream out) throws java.io.IOException{
            out.writeInt(x);
            out.writeInt(y);
            out.writeUTF(bloque);
            out.writeByte(equipo);
            out.writeByte(rot);
            out.writeByte(ver);
            out.writeInt(datos.length);
            out.write(datos);
            out.writeByte(cfgTipo);
            if(cfgTipo == 1 || cfgTipo == 2){
                out.writeInt(cfg.length);
                for(int v : cfg) out.writeInt(v);
            }else if(cfgTipo == 3){
                out.writeInt(cfgBytes.length);
                out.write(cfgBytes);
            }
        }

        static Reg leer(DataInputStream in) throws java.io.IOException{
            Reg r = new Reg();
            r.x = in.readInt();
            r.y = in.readInt();
            r.bloque = in.readUTF();
            r.equipo = in.readUnsignedByte();
            r.rot = in.readByte();
            r.ver = in.readByte();
            r.datos = new byte[in.readInt()];
            in.readFully(r.datos);
            r.cfgTipo = in.readByte();
            if(r.cfgTipo == 1 || r.cfgTipo == 2){
                r.cfg = new int[in.readInt()];
                for(int i = 0; i < r.cfg.length; i++) r.cfg[i] = in.readInt();
            }else if(r.cfgTipo == 3){
                r.cfgBytes = new byte[in.readInt()];
                in.readFully(r.cfgBytes);
            }
            return r;
        }
    }

    // ==================================================================
    // Almacén de REGIONES (como los archivos .mca de Minecraft):
    // 16x16 chunks por archivo, indexados por coordenadas virtuales => tamaño de mundo ilimitado en disco.
    // ==================================================================
    static final class Regiones{
        static final class ChunkGuardado{
            final Seq<Reg> edificios = new Seq<>();
            final Seq<Estructuras.RegU> unidades = new Seq<>();   // unidades enemigas de guardia (bases y errantes)
            int[] niebla; // 32 enteros: una fila de 32 bits por fila del chunk (null = nada explorado)
            double tick;  // reloj del mundo cuando se guardó: sirve para saber cuánto tiempo estuvo ausente
        }

        static final class Region{
            final int rx, ry;
            final java.util.HashMap<Integer, ChunkGuardado> chunks = new java.util.HashMap<>();
            boolean sucia;
            Region(int rx, int ry){ this.rx = rx; this.ry = ry; }
        }

        static Meta meta;
        static final java.util.HashMap<Long, Region> regiones = new java.util.HashMap<>();

        static void iniciar(Meta m){
            meta = m;
            regiones.clear();
            enVuelo.clear();
            llegadas.clear();
            epoca++;
            if(m != null && m.dimensional()) Mapa.iniciar(Almacen.carpetaDatos(m).file());   // el minimapa guarda por dimensión
        }

        // ---- lectura anticipada: el hilo de E/S lee y descomprime; el juego solo recoge el resultado ----
        static final java.util.concurrent.ConcurrentLinkedQueue<Region> llegadas = new java.util.concurrent.ConcurrentLinkedQueue<>();
        static final java.util.HashSet<Long> enVuelo = new java.util.HashSet<>();
        static volatile int epoca;

        /** Pide en segundo plano la región (rx, ry) si existe en disco y no está ya en memoria. */
        static void precargar(int rx, int ry){
            long key = k(rx, ry);
            if(meta == null || regiones.containsKey(key) || enVuelo.contains(key)) return;
            final Fi f = archivo(rx, ry);
            if(!f.exists()) return;       // región vacía: no hay nada que leer
            enVuelo.add(key);
            final int ep = epoca;
            Disco.leer(() -> {
                Region r = new Region(rx, ry);
                leer(r, f);
                if(ep == epoca) llegadas.add(r);
            });
        }

        /** Hilo del juego, cada fotograma: incorpora las regiones que el hilo de E/S ya leyó. */
        static void sincronizar(){
            Region r;
            while((r = llegadas.poll()) != null){
                long key = k(r.rx, r.ry);
                enVuelo.remove(key);
                regiones.putIfAbsent(key, r);
            }
        }

        static long k(int rx, int ry){
            return ((long)rx << 32) ^ (ry & 0xffffffffL);
        }

        static Fi archivo(int rx, int ry){
            return Almacen.carpetaDatos(meta).child("regiones").child("r." + rx + "." + ry + ".dat");
        }

        static Region region(int rx, int ry){
            long key = k(rx, ry);
            Region r = regiones.get(key);
            if(r != null) return r;
            r = new Region(rx, ry);
            Fi f = archivo(rx, ry);
            if(f.exists()) leer(r, f);
            regiones.put(key, r);
            return r;
        }

        static int indice(int vcx, int vcy){
            return Math.floorMod(vcy, REGION_CHUNKS) * REGION_CHUNKS + Math.floorMod(vcx, REGION_CHUNKS);
        }

        static ChunkGuardado chunk(int vcx, int vcy, boolean crear){
            Region r = region(Math.floorDiv(vcx, REGION_CHUNKS), Math.floorDiv(vcy, REGION_CHUNKS));
            int idx = indice(vcx, vcy);
            ChunkGuardado c = r.chunks.get(idx);
            if(c == null && crear){
                c = new ChunkGuardado();
                r.chunks.put(idx, c);
            }
            if(crear) r.sucia = true;
            return c;
        }

        static void leer(Region r, Fi f){
            try{
                leerFlujo(r, new DataInputStream(new GZIPInputStream(f.read())));
            }catch(Throwable t){
                Log.err("[MundoInfinito] Región ilegible " + f.name(), t);
            }
        }

        /** Analiza los bytes SIN comprimir de una región (tal como los entrega bytes(Region)). */
        static void leerBytes(Region r, byte[] raw){
            try{
                leerFlujo(r, new DataInputStream(new java.io.ByteArrayInputStream(raw)));
            }catch(Throwable t){
                Log.err("[MundoInfinito] Región ilegible", t);
            }
        }

        static void leerFlujo(Region r, DataInputStream in) throws java.io.IOException{
            {
                int ver = in.readInt();
                int n = in.readInt();
                for(int i = 0; i < n; i++){
                    int idx = in.readShort();
                    ChunkGuardado c = new ChunkGuardado();
                    int nb = in.readInt();
                    for(int j = 0; j < nb; j++) c.edificios.add(Reg.leer(in));
                    if(in.readBoolean()){
                        c.niebla = new int[32];
                        for(int j = 0; j < 32; j++) c.niebla[j] = in.readInt();
                    }
                    if(ver >= 4){
                        int nu = in.readInt();
                        for(int j = 0; j < nu; j++) c.unidades.add(Estructuras.RegU.leer(in, ver));
                    }
                    if(ver >= 5) c.tick = in.readDouble();
                    r.chunks.put(idx, c);
                }
                in.close();
            }
        }

        static byte[] bytes(Region r){
            if(r.chunks.isEmpty()) return null; // región vacía => se borra el archivo
            try{
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bos);   // sin comprimir: lo comprime el hilo de E/S
                out.writeInt(6);
                out.writeInt(r.chunks.size());
                for(java.util.Map.Entry<Integer, ChunkGuardado> e : r.chunks.entrySet()){
                    out.writeShort(e.getKey());
                    ChunkGuardado c = e.getValue();
                    out.writeInt(c.edificios.size);
                    for(Reg x : c.edificios) x.escribir(out);
                    out.writeBoolean(c.niebla != null);
                    if(c.niebla != null) for(int v : c.niebla) out.writeInt(v);
                    out.writeInt(c.unidades.size);
                    for(Estructuras.RegU u : c.unidades) u.escribir(out);
                    out.writeDouble(c.tick);
                }
                out.flush();
                return bos.toByteArray();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo serializar región", t);
                return null;
            }
        }

        /** Hilo principal: serializa las regiones modificadas (rápido). La escritura a disco la hace otro hilo. */
        static java.util.HashMap<Fi, byte[]> serializarSucias(){
            java.util.HashMap<Fi, byte[]> r = new java.util.HashMap<>();
            for(Region reg : regiones.values()){
                if(!reg.sucia) continue;
                r.put(archivo(reg.rx, reg.ry), bytes(reg));
                reg.sucia = false;
            }
            return r;
        }

        /** Libera de RAM las regiones limpias que quedaron lejos de la ventana. */
        static void evictar(){
            if(Streamer.nch <= 0) return;
            int vcx0 = Streamer.ox / TAM_CHUNK, vcy0 = Streamer.oy / TAM_CHUNK;
            int minX = Math.floorDiv(vcx0, REGION_CHUNKS) - 1, maxX = Math.floorDiv(vcx0 + Streamer.nch, REGION_CHUNKS) + 1;
            int minY = Math.floorDiv(vcy0, REGION_CHUNKS) - 1, maxY = Math.floorDiv(vcy0 + Streamer.nch, REGION_CHUNKS) + 1;
            Seq<Long> borrar = new Seq<>();
            for(java.util.Map.Entry<Long, Region> e : regiones.entrySet()){
                Region r = e.getValue();
                if(!r.sucia && (r.rx < minX || r.rx > maxX || r.ry < minY || r.ry > maxY)) borrar.add(e.getKey());
            }
            for(Long key : borrar) regiones.remove(key);
        }
    }

    // ==================================================================
    // Estado pequeño de una dimensión (posición, inventario, unidades)
    // ==================================================================
    static final class Datos{
        int ox, oy;                 // origen virtual de la ventana al guardar
        double vx, vy;              // posición virtual del jugador (tiles)
        String tipoJugador = "";
        final Seq<String> itemsN = new Seq<>();
        final Seq<Integer> itemsC = new Seq<>();
        final Seq<RegUnidad> unidades = new Seq<>();

        static final class RegUnidad{
            String tipo;
            double vx, vy;
            float rot, vida;
        }

        /** Hilo principal: fotografía del estado vivo. */
        static Datos capturar(double vx, double vy){
            Datos d = new Datos();
            d.ox = Streamer.ox;
            d.oy = Streamer.oy;
            d.vx = vx;
            d.vy = vy;
            Unit pu = Vars.player == null ? null : Vars.player.unit();
            if(pu != null && !Vars.player.dead() && pu.type != null) d.tipoJugador = pu.type.name;

            Seq<CoreBlock.CoreBuild> nucs = Vars.state.teams.cores(Team.sharded);
            if(!nucs.isEmpty()){
                nucs.first().items.each((item, amount) -> {
                    if(amount > 0){
                        d.itemsN.add(item.name);
                        d.itemsC.add(amount);
                    }
                });
            }
            int cuenta = 0;
            for(Unit u : Groups.unit){
                if(u.team != Team.sharded || !u.isValid() || u.isPlayer() || u.type == null) continue;
                if(Mundos.UNIDADES_NUCLEO.contains(u.type.name)) continue; // avatares del jugador: nunca como seguidores
                RegUnidad r = new RegUnidad();
                r.tipo = u.type.name;
                r.vx = d.ox + u.x / 8.0;
                r.vy = d.oy + u.y / 8.0;
                r.rot = u.rotation;
                r.vida = u.health;
                d.unidades.add(r);
                if(++cuenta >= 300) break;
            }
            return d;
        }

        byte[] bytes(){
            try{
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
                out.writeInt(3);
                out.writeInt(ox);
                out.writeInt(oy);
                out.writeDouble(vx);
                out.writeDouble(vy);
                out.writeUTF(tipoJugador);
                out.writeInt(itemsN.size);
                for(int i = 0; i < itemsN.size; i++){
                    out.writeUTF(itemsN.get(i));
                    out.writeInt(itemsC.get(i));
                }
                out.writeInt(unidades.size);
                for(RegUnidad u : unidades){
                    out.writeUTF(u.tipo);
                    out.writeDouble(u.vx);
                    out.writeDouble(u.vy);
                    out.writeFloat(u.rot);
                    out.writeFloat(u.vida);
                }
                out.close();
                return bos.toByteArray();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo serializar el estado", t);
                return null;
            }
        }

        static void escribirArchivo(Fi f, byte[] bytes){
            try{
                f.parent().mkdirs();
                OutputStream os = f.write(false);
                os.write(bytes);
                os.close();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo escribir " + f.name(), t);
            }
        }

        static Datos leer(Fi f){
            try{
                DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
                Datos d = new Datos();
                in.readInt();
                d.ox = in.readInt();
                d.oy = in.readInt();
                d.vx = in.readDouble();
                d.vy = in.readDouble();
                d.tipoJugador = in.readUTF();
                int ni = in.readInt();
                for(int i = 0; i < ni; i++){
                    d.itemsN.add(in.readUTF());
                    d.itemsC.add(in.readInt());
                }
                int nu = in.readInt();
                for(int i = 0; i < nu; i++){
                    RegUnidad u = new RegUnidad();
                    u.tipo = in.readUTF();
                    u.vx = in.readDouble();
                    u.vy = in.readDouble();
                    u.rot = in.readFloat();
                    u.vida = in.readFloat();
                    d.unidades.add(u);
                }
                in.close();
                return d;
            }catch(Throwable t){
                Log.err("[MundoInfinito] Estado de dimensión ilegible", t);
                return null;
            }
        }
    }

    // ==================================================================
    // STREAMING DE CHUNKS (carga PASIVA: pocos chunks a la vez, trozos pequeños por frame)
    // ==================================================================
    static final class Streamer{
        static final long PRESUPUESTO_NS = 900_000L;             // 0.9 ms por frame (se reduce solo si el FPS baja)
        static final long PRESUPUESTO_RESTAURAR_NS = 9_000_000L; // con la pantalla de carga puesta se puede gastar más
        static final int R_JUGADOR = 3, R_UNIDAD = 2, R_EDIFICIO = 1;
        /** Chunks "en vuelo" a la vez (cola + calculando + esperando aplicar): crece con los hilos de generación. */
        static int maxEnCola(){
            return Math.max(8, Ajustes.hilos() * 6);
        }
        static final int TRAMO = 8;                              // tiles aplicados por paso

        static volatile boolean activo = false;
        static volatile int epoca = 0;

        static Meta meta;
        static Paleta paleta;
        static volatile int ox, oy;                      // origen VIRTUAL de la ventana (tiles, múltiplo de 32)
        static volatile int nch;                         // chunks por lado de la ventana
        static final IntSet generados = new IntSet();    // aplicados al mundo
        static final IntSet solicitados = new IntSet();  // en cola / calculando / esperando aplicar
        static final IntSet requeridos = new IntSet();   // chunks con edificios o zona explorada (hay que generarlos)
        static final ArrayDeque<Integer> cola = new ArrayDeque<>();
        static final ConcurrentLinkedQueue<ChunkData> listos = new ConcurrentLinkedQueue<>();
        static final ArrayDeque<Reg> colaRestaurar = new ArrayDeque<>();
        static final Seq<Reg> configsPendientes = new Seq<>();
        static final Seq<Estructuras.RegU> unidadesPend = new Seq<>();
        static final java.util.HashMap<Integer, int[]> fogPend = new java.util.HashMap<>();
        static boolean restaurando = false;
        static boolean rebaseEnSitio = false;
        static int totalRestaurar = 1;
        static Datos datosPendientes;
        static final Thread[] trabajadores = new Thread[8];
        static volatile int hilosObjetivo = 1;
        /** Precarga de terreno FUERA de la ventana (solo a la caché, sin aplicar): cola de baja prioridad, protegida por 'cola'. */
        static final ArrayDeque<Long> colaBaja = new ArrayDeque<>();
        static final java.util.HashSet<Long> pendBaja = new java.util.HashSet<>();
        static int cursorPlan;

        static ChunkData actual;
        static int paso;
        static float acumEscaneo, acumGuardado, muerto;
        static float jugadorX, jugadorY;                 // última posición válida LOCAL (tiles)

        static int clave(int cx, int cy){
            return cy * nch + cx;
        }

        static boolean enMapa(int cx, int cy){
            return cx >= 0 && cy >= 0 && cx < nch && cy < nch;
        }

        static void reiniciar(Meta m, Paleta p){
            meta = m;
            paleta = p;
            nch = m.chunksPorLado();
            generados.clear();
            solicitados.clear();
            requeridos.clear();
            motor.claves.clear();
            cursorPlan = 0;
            synchronized(cola){ cola.clear(); colaBaja.clear(); pendBaja.clear(); }
            CacheChunks.configurar(m.semilla, m.dimGen());
            vaciarListos();
            colaRestaurar.clear();
            configsPendientes.clear();
            unidadesPend.clear();
            Estructuras.reiniciarCola();
            Ausencia.reiniciar();
            fogPend.clear();
            restaurando = false;
            rebaseEnSitio = false;
            datosPendientes = null;
            actual = null;
            paso = 0;
            acumEscaneo = 0f;
            acumGuardado = 0f;
            muerto = 0f;
            ajustarHilos();
        }

        /** Arranca (o deja dormir) hilos de generación hasta igualar el ajuste del jugador. Seguro de llamar a cada escaneo. */
        static void ajustarHilos(){
            int obj = Ajustes.hilos();
            hilosObjetivo = obj;
            for(int i = 0; i < obj; i++){
                Thread t = trabajadores[i];
                if(t != null && t.isAlive()) continue;
                final int id = i;
                t = Threads.daemon("MundoInfinito-Chunks-" + i, () -> bucleTrabajador(id));
                try{ t.setPriority(Thread.MIN_PRIORITY + 1); }catch(Throwable ignored){}
                trabajadores[i] = t;
            }
            synchronized(cola){ cola.notifyAll(); }   // los hilos que sobraban y ahora hacen falta despiertan
        }

        /**
         * Un hilo de generación. Varios corren a la vez: ChunkData.generar es pura (solo lee la semilla y escribe en SU chunk).
         * Primero atienden lo que pide el juego (cola); si no hay nada, precargan terreno a la caché (colaBaja).
         */
        static void bucleTrabajador(int id){
            while(true){
                int key = -1, ep, vox, voy, n, dm, mid;
                Long baja = null;
                Meta m;
                Paleta p;
                synchronized(cola){
                    while(id >= hilosObjetivo || (cola.isEmpty() && colaBaja.isEmpty())){
                        try{ cola.wait(); }catch(InterruptedException e){ return; }
                    }
                    if(!cola.isEmpty()){
                        key = cola.pollFirst();
                    }else{
                        baja = colaBaja.pollFirst();
                        pendBaja.remove(baja);
                    }
                    ep = epoca;
                    m = meta;
                    dm = m == null ? -1 : m.dimGen();   // se captura junto con la época: un chunk viejo nunca mezcla dimensiones
                    p = paleta;
                    vox = ox;
                    voy = oy;
                    n = nch;
                    mid = CacheChunks.mundo();
                }
                try{
                    if(m == null || p == null || n <= 0) continue;
                    if(baja != null){
                        // precarga: chunk virtual suelto. Se genera con origen = su propia esquina, así que x, y locales = 0..31.
                        long vk = baja;
                        if(CacheChunks.tiene(mid, vk)) continue;
                        ChunkData cd = ChunkData.generar(p, m.semilla, dm, m.tam, 0, 0, ep, Coord.vcxDeClave(vk) * TAM_CHUNK, Coord.vcyDeClave(vk) * TAM_CHUNK);
                        CacheChunks.poner(mid, vk, cd);
                        cd.liberar();
                    }else{
                        int cx = key % n, cy = key / n;
                        long vk = Coord.claveVirtual(vox / TAM_CHUNK + cx, voy / TAM_CHUNK + cy);
                        ChunkData cd = CacheChunks.copiar(mid, vk, cx, cy, ep);   // ¿ya se generó antes? entonces no se recalcula
                        if(cd == null){
                            cd = ChunkData.generar(p, m.semilla, dm, m.tam, cx, cy, ep, vox, voy);
                            if((cx + 1) * TAM_CHUNK <= m.tam && (cy + 1) * TAM_CHUNK <= m.tam) CacheChunks.poner(mid, vk, cd);   // los de borde no: llevan relleno
                        }
                        listos.add(cd);
                    }
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error generando chunk", t);
                }
                // pausa mínima entre chunks: ningún hilo acapara un núcleo de la CPU (con varios hilos basta 1 ms)
                try{ Thread.sleep(1); }catch(InterruptedException e){ return; }
            }
        }

        /** Aplica un chunk COMPLETO (modo carga: Vars.world.isGenerating() == true, sin eventos por tile). */
        static void aplicarChunkCompleto(ChunkData d){
            aplicarTramo(d, 0, TAM_CHUNK * TAM_CHUNK);
            terminarChunk(d);
        }

        static void aplicarTramo(ChunkData d, int desde, int hasta){
            int tam = meta.tam;
            for(int i = desde; i < hasta; i++){
                int lx = i % TAM_CHUNK, ly = i / TAM_CHUNK;
                int x = d.cx * TAM_CHUNK + lx, y = d.cy * TAM_CHUNK + ly;
                if(x >= tam || y >= tam) continue;
                Tile t = Vars.world.rawTile(x, y);
                // Solo se escribe lo que CAMBIA: cada set* dispara eventos de render, y tras reubicar la ventana
                // muchas casillas ya tienen el piso correcto (biomas grandes). Se compara siempre, porque la casilla
                // puede venir de otra zona del mundo y traer su piso, mena o muro viejos.
                Block f = Vars.content.block(d.piso[i]);
                if(f instanceof Floor && t.floor() != f) t.setFloor((Floor)f);
                Block o = d.mena[i] == 0 ? Blocks.air : Vars.content.block(d.mena[i]);
                if(t.overlay() != o) t.setOverlay(o);
                if(t.build == null){   // sin edificio encima: lo que hay es terreno (aire, muro estático o roca) y se iguala al calculado
                    Block b = d.bloque[i] == 0 ? Blocks.air : Vars.content.block(d.bloque[i]);
                    if(t.block() != b){
                        if(b == Blocks.air) t.setAir(); else t.setBlock(b);
                    }
                }
            }
        }

        static void terminarChunk(ChunkData d){
            int k = clave(d.cx, d.cy);
            generados.add(k);
            solicitados.remove(k);
            d.liberar();   // ya está en el mundo: vuelve al pool para el siguiente chunk
        }

        /** Descarta los chunks calculados que nadie aplicó (cambio de época), devolviéndolos al pool. */
        static void vaciarListos(){
            ChunkData c;
            while((c = listos.poll()) != null) c.liberar();
        }

        /** Presupuesto por frame: baja solo si el juego va justo de FPS (así moverse nunca causa tirones). */
        static long presupuesto(){
            float dt = Core.graphics == null ? 0.016f : Core.graphics.getDeltaTime();
            long base = Ajustes.presupuestoNs();
            if(dt > 0.05f) return Math.min(base, 150_000L);
            if(dt > 0.03f) return Math.min(base, 400_000L);
            return base;
        }

        static void tick(){
            tickInterno();
            Mapa.vaciarColaDelJuego();   // el minimapa del juego no debe acumular los tiles que acabamos de cambiar
        }

        static void tickInterno(){
            if(!activo || !Vars.state.isPlaying()) return;
            if(Mundos.actual != null) Mundos.actual.reloj += Time.delta;
            Regiones.sincronizar();

            if(Mundos.rebase != null){ Mundos.avanzarRebase(); Mapa.vaciarColaDelJuego(); return; }   // reubicando la ventana: el streaming espera

            if(restaurando){
                long finR = System.nanoTime() + PRESUPUESTO_RESTAURAR_NS;
                while(System.nanoTime() < finR && !colaRestaurar.isEmpty()) Mundos.restaurarEdificio(colaRestaurar.pollFirst());
                if(colaRestaurar.isEmpty()){
                    restaurando = false;
                    Mundos.finalizarRestauracion();
                }else{
                    Carga.texto("Restaurando edificios... " + (100 * (totalRestaurar - colaRestaurar.size()) / totalRestaurar) + "%");
                    return;
                }
            }

            Estructuras.procesar(System.nanoTime() + 1_500_000L);   // coloca bases y ruinas de a poco
            Ausencia.tick(System.nanoTime() + 2_500_000L);          // las fábricas que estuvieron lejos se ponen al día
            Mapa.tick(Mathf.clamp((int)jugadorX / TAM_CHUNK, 0, nch - 1), Mathf.clamp((int)jugadorY / TAM_CHUNK, 0, nch - 1));

            long fin = System.nanoTime() + presupuesto();
            while(System.nanoTime() < fin){
                if(actual == null){
                    actual = listos.poll();
                    if(actual == null) break;
                    if(actual.epoca != epoca){ actual.liberar(); actual = null; continue; }
                    paso = 0;
                }
                aplicarTramo(actual, paso, paso + TRAMO);
                paso += TRAMO;
                if(paso >= TAM_CHUNK * TAM_CHUNK){
                    terminarChunk(actual);
                    actual = null;
                }
            }

            pedirMas();   // mantiene la tubería llena sin esperar al siguiente escaneo (antes: 5 chunks cada 0,5 s)

            acumEscaneo += Time.delta;
            if(acumEscaneo >= 30f){
                acumEscaneo = 0f;
                escanear();
            }
            acumGuardado += Time.delta;
            if(acumGuardado >= 5400f){ // ~90 s
                acumGuardado = 0f;
                Mundos.guardar(false);
            }

            // Red de seguridad: si el jugador sigue sin unidad, reaparecerlo en su sitio (nunca en la esquina del proxy).
            if(Vars.player != null && Vars.player.dead() && !Mundos.viajando){
                muerto += Time.delta;
                boolean soloProxy = Mundos.proxyPos != -1 && !Mundos.hayNucleoReal();
                if((soloProxy && muerto > 8f) || muerto > 90f){
                    muerto = 0f;
                    Mundos.spawnEnPosicion((int)jugadorX, (int)jugadorY, null);
                }
            }else{
                muerto = 0f;
            }
        }

        static void pedirRadio(IntSet deseados, int cx, int cy, int r){
            for(int dx = -r; dx <= r; dx++){
                for(int dy = -r; dy <= r; dy++){
                    int x = cx + dx, y = cy + dy;
                    if(enMapa(x, y)) deseados.add(clave(x, y));
                }
            }
        }

        // Buffers reutilizables del escaneo (antes: 3 IntSet + Seq + int[] por chunk, cada 0,5 s => basura constante en Android)
        static final IntSet deseados = new IntSet(), vistos = new IntSet(), vistosB = new IntSet();
        static final Motor motor = new Motor();
        static float ultimoEscaneo = 0f;
        static int rVista = 4;   // radio (en chunks) de lo que se ve en pantalla: lo usan el motor y las entidades
        static final IntSeq faltanK = new IntSeq();
        static final FloatSeq faltanS = new FloatSeq();

        /** Decide qué chunks necesita el mundo y, si el jugador se acerca al borde de la ventana, la reubica. */
        static void escanear(){
            deseados.clear();

            if(Vars.player != null && Vars.player.unit() != null && !Vars.player.dead()){
                Unit u = Vars.player.unit();
                jugadorX = u.x / 8f;
                jugadorY = u.y / 8f;
            }
            int pcx = Mathf.clamp((int)jugadorX / TAM_CHUNK, 0, nch - 1);
            int pcy = Mathf.clamp((int)jugadorY / TAM_CHUNK, 0, nch - 1);

            // Mundo infinito: al acercarse al borde, la ventana se recentra sobre el jugador (en sitio, sin menú).
            if(Vars.player != null && !Vars.player.dead()
                && (pcx < MARGEN_REBASE || pcy < MARGEN_REBASE || pcx >= nch - MARGEN_REBASE || pcy >= nch - MARGEN_REBASE)){
                Mundos.rebasar();
                return;
            }

            int cenX = pcx, cenY = pcy;
            if(Core.camera != null){
                cenX = Mathf.clamp((int)(Core.camera.position.x / 8f) / TAM_CHUNK, 0, nch - 1);
                cenY = Mathf.clamp((int)(Core.camera.position.y / 8f) / TAM_CHUNK, 0, nch - 1);
                // radio según lo que se ve en pantalla (con zoom alejado hacen falta más chunks)
                float mitad = Math.max(Core.camera.width, Core.camera.height) / 2f / 8f;
                rVista = Mathf.clamp((int)Math.ceil(mitad / TAM_CHUNK) + 1, 2, 7);
            }

            // MOTOR DE CHUNKS: decide qué hace falta y en qué orden según el JUGADOR y hacia dónde se dirige.
            // Ni las unidades ni los edificios piden terreno por su cuenta (antes se recorrían todos cada vez).
            motor.actualizar(jugadorX, jugadorY, Math.max(1f, Time.time - ultimoEscaneo));
            ultimoEscaneo = Time.time;
            motor.plan(nch, pcx, pcy, cenX, cenY, rVista, requeridos);

            Entidades.tick(pcx, pcy);     // las unidades aliadas lejanas duermen; las cercanas despiertan
            Mundos.limitarUnidades();
            Estructuras.revisar();

            ajustarHilos();
            cursorPlan = 0;
            precargarFuera(pcx, pcy);
            // lectura anticipada de las regiones del disco que el motor va a necesitar enseguida
            for(int i = 0, mirados = 0; i < motor.claves.size && mirados < 60; i++, mirados++){
                int k = motor.claves.items[i];
                Regiones.precargar(Math.floorDiv(Coord.vcx(k % nch), REGION_CHUNKS), Math.floorDiv(Coord.vcy(k / nch), REGION_CHUNKS));
            }
            pedirMas();
        }

        /** Pide a los hilos los siguientes chunks del plan que aún no están ni cargados ni en camino, hasta llenar la tubería. */
        static void pedirMas(){
            int libres = maxEnCola() - solicitados.size;
            if(libres <= 0 || cursorPlan >= motor.claves.size) return;
            synchronized(cola){
                int pedidos = 0;
                while(cursorPlan < motor.claves.size && pedidos < libres){
                    int k = motor.claves.items[cursorPlan++];
                    if(generados.contains(k) || solicitados.contains(k)) continue;
                    solicitados.add(k);
                    cola.addLast(k);
                    pedidos++;
                }
                if(pedidos > 0) cola.notifyAll();
            }
        }

        /**
         * Cerca del borde de la ventana, el terreno que quedará dentro tras reubicarla todavía es "virtual": se calcula ya, en
         * segundo plano y solo a la caché, para que al reubicar se copie en vez de calcularse (y no se vea terreno sin cargar).
         */
        static void precargarFuera(int pcx, int pcy){
            if(CacheChunks.maxChunks() <= 0) return;
            int dBorde = Math.min(Math.min(pcx, pcy), Math.min(nch - 1 - pcx, nch - 1 - pcy));
            if(dBorde > MARGEN_REBASE + 5) return;
            final int r = Math.max(Ajustes.carga(), rVista) + 2, mid = CacheChunks.mundo();
            final int vpx = Coord.vcx(pcx), vpy = Coord.vcy(pcy);
            synchronized(cola){
                if(colaBaja.size() > 96) return;
                int nuevos = 0;
                for(int ring = 0; ring <= r && nuevos < 64; ring++){        // de dentro hacia fuera
                    for(int dy = -ring; dy <= ring && nuevos < 64; dy++){
                        for(int dx = -ring; dx <= ring; dx++){
                            if(Math.max(Math.abs(dx), Math.abs(dy)) != ring || !Ajustes.dentro(dx, dy, r)) continue;
                            int lcx = pcx + dx, lcy = pcy + dy;
                            if(enMapa(lcx, lcy)) continue;                  // dentro de la ventana ya lo trae el flujo normal
                            long vk = Coord.claveVirtual(vpx + dx, vpy + dy);
                            if(CacheChunks.tiene(mid, vk) || !pendBaja.add(vk)) continue;
                            colaBaja.addLast(vk);
                            nuevos++;
                        }
                    }
                }
                if(nuevos > 0) cola.notifyAll();
            }
        }
    }

    // ==================================================================
    // HUD: coordenadas VIRTUALES (punto a punto, no se reinician al reubicar la ventana)
    // ==================================================================
    static final class Hud{
        static boolean creado = false;

        static void crear(){
            if(creado || Vars.headless || Core.scene == null) return;
            creado = true;
            Core.scene.add(new Table(t -> {
                t.setFillParent(true);
                t.top();
                t.touchable = arc.scene.event.Touchable.disabled;
                t.visible(() -> Streamer.activo && Vars.state.isGame());
                t.label(Hud::texto).padTop(46f).get().setFontScale(0.85f);
            }));
        }

        static String texto(){
            if(Streamer.meta == null) return "";
            double[] p = Mundos.posicionVirtual();
            String dim = Mundos.actual != null && Mundos.actual.dimensional() ? Mundos.actual.nombreDim() + "   " : "";
            return dim + "X " + (long)Math.floor(p[0]) + "   Y " + (long)Math.floor(p[1]);
        }
    }

    // ==================================================================
    // MUNDOS: abrir / guardar / reubicar la ventana en sitio (mundo infinito) / portales
    // ==================================================================
    static final class Mundos{
        static Meta actual;
        static boolean viajando = false;
        static float ultimoRebase = -100000f;
        static int proxyPos = -1;   // posición del núcleo "proxy" (inventario compartido cuando el núcleo real está lejos)

        /** Unidades "avatar" del jugador: nunca se guardan ni se restauran como seguidores (evita duplicados del dron). */
        static final java.util.HashSet<String> UNIDADES_NUCLEO = new java.util.HashSet<>(
            java.util.Arrays.asList("alpha", "beta", "gamma", "evoke", "incite", "emanate"));

        // ---------- creación / continuar ----------
        static void crearNuevo(String nombre, int nucleo, String semillaTxt){
            String t = semillaTxt == null ? "" : semillaTxt.trim();
            if(!t.isEmpty()){
                crearConSemilla(nombre, nucleo, Semillas.deTexto(t), t);
                return;
            }
            Carga.mostrar("Obteniendo semilla...");
            Semillas.obtener(semilla -> crearConSemilla(nombre, nucleo, semilla, String.valueOf(semilla)));
        }

        static void crearConSemilla(String nombre, int nucleo, int semilla, String semillaTxt){
            Meta m = new Meta();
            m.id = "w" + System.currentTimeMillis();
            m.nombre = nombre == null || nombre.trim().isEmpty() ? "Mundo " + (Almacen.listar().size + 1) : nombre.trim();
            m.semilla = semilla;
            m.semillaTxt = semillaTxt;
            m.tam = ventana();
            m.nucleo = nucleo;
            m.gen = GEN_ACTUAL;
            m.dim = m.dimInicial();
            m.creado = m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);
            Regiones.iniciar(m);
            Orbita.reiniciar();          // mundo nuevo: sin drones en tránsito...
            Estructuras.cargar(m);       // ...y sin estructuras ni checkpoints
            abrir(m, null, 0.0, 0.0);
        }

        static void continuar(Meta m){
            if(!m.compatible()) return;
            Fi f = Almacen.archivoEstado(m);
            Datos d = f.exists() ? Datos.leer(f) : null;
            Regiones.iniciar(m);
            Orbita.cargar(m);
            Estructuras.cargar(m);
            if(d != null) abrir(m, d, d.vx, d.vy);
            else abrir(m, null, 0.0, 0.0);
        }

        /** Mete en las colas lo guardado (edificios + niebla) de los chunks propios de la ventana actual. */
        static void cargarDesdeAlmacen(){
            int n = Streamer.nch, vcx0 = Streamer.ox / TAM_CHUNK, vcy0 = Streamer.oy / TAM_CHUNK;
            // Se ignora el anillo exterior de chunks: ahí un edificio grande podría quedar cortado por el borde.
            for(int cy = 1; cy <= n - 2; cy++){
                for(int cx = 1; cx <= n - 2; cx++){
                    Regiones.ChunkGuardado c = Regiones.chunk(vcx0 + cx, vcy0 + cy, false);
                    if(c == null) continue;
                    int key = Streamer.clave(cx, cy);
                    double ausente = c.tick > 0 ? Math.max(0.0, reloj() - c.tick) : 0.0;
                    for(Reg r : c.edificios){ r.dt = ausente; Streamer.colaRestaurar.addLast(r); }
                    for(Estructuras.RegU u : c.unidades) if(u.clase == 0) Streamer.unidadesPend.add(u);
                    if(!c.edificios.isEmpty() || !c.unidades.isEmpty()) Streamer.requeridos.add(key);
                    if(c.niebla != null){
                        Streamer.fogPend.put(key, c.niebla);
                        Streamer.requeridos.add(key);
                    }
                }
            }
            Streamer.totalRestaurar = Math.max(1, Streamer.colaRestaurar.size());
        }

        // ---------- SALTO ENTRE DIMENSIONES ----------
        /** true = las dos dimensiones comparten el inventario (se viaja con lo que se tiene). */
        static final boolean RECURSOS_GLOBALES = true;
        static Seq<String> resN;
        static Seq<Integer> resC;
        static boolean llegadaNueva = false;   // true = primera vez en esa dimensión: se coloca un silo de recepción junto al núcleo

        /** Dimensión actual (0 Serpulo / 1 Erekir). En mundos antiguos (mezclados) siempre 0. */
        static int dimActual(){
            return actual != null && actual.dimensional() ? actual.dim : DIM_SERPULO;
        }

        /**
         * Viaja a la otra dimensión: efecto de despegue, guarda la actual (regiones + estado + órbita + estructuras),
         * cambia de carpeta de datos, reglas y paleta, y aparece en el (0,0) de la nueva (o donde se quedó).
         * Una dimensión se reconstruye entera (logic.reset), así que aquí SÍ hay capa de carga; no ocurre al explorar.
         */
        static void saltar(int destino){
            if(viajando || actual == null || !actual.dimensional() || destino == actual.dim) return;
            if(!Streamer.activo || Streamer.restaurando || Vars.player == null || Vars.player.dead()) return;
            viajando = true;
            final Meta m = actual;
            Fx.launch.at(Vars.player.x, Vars.player.y);
            Carga.mostrar("Despegando hacia " + (destino == DIM_SERPULO ? "Serpulo" : "Erekir") + "...");
            Time.runTask(50f, () -> {
                try{
                    guardar(true);                       // síncrono: escribe en la carpeta de la dimensión de origen
                    if(RECURSOS_GLOBALES){
                        // el inventario es UNO solo para las dos dimensiones: se lleva consigo
                        Datos origen = Datos.leer(Almacen.archivoEstado(m));
                        if(origen != null){ resN = new Seq<>(origen.itemsN); resC = new Seq<>(origen.itemsC); }
                    }
                    m.dim = destino;                     // a partir de aquí todas las rutas apuntan a la nueva
                    Almacen.guardarMeta(m);
                    Regiones.iniciar(m);
                    Estructuras.cargar(m);
                    Fi f = Almacen.archivoEstado(m);
                    Datos d = f.exists() ? Datos.leer(f) : null;
                    llegadaNueva = d == null;
                    if(d != null) abrir(m, d, d.vx, d.vy);
                    else abrir(m, null, 0.0, 0.0);       // (0,0) del planeta nuevo
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error al saltar de dimensión", t);
                    Carga.ocultar();
                    viajando = false;
                }
            });
        }

        // ---------- apertura de la ventana ----------
        /**
         * Construye la ventana en RAM alrededor de una posición VIRTUAL (carga completa: entrar, continuar, portales).
         * @param datos  null = dimensión nueva (núcleo + portal); si no, se restaura inventario/unidades
         */
        static void abrir(Meta m, Datos datos, double vx, double vy){
            Carga.mostrar("Preparando mundo...");
            actual = m;
            m.tam = ventana();
            liberarMundo();

            final boolean nueva = datos == null;
            configurarReglas();

            final int tam = m.tam;
            final int n = tam / TAM_CHUNK;
            vx = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vx));
            vy = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vy));

            int nox, noy;
            boolean reusar = datos != null
                && vx - datos.ox > 60 && vx - datos.ox < tam - 60 && vy - datos.oy > 60 && vy - datos.oy < tam - 60;
            if(reusar){
                nox = datos.ox;
                noy = datos.oy;
            }else{
                nox = Math.floorDiv((int)Math.floor(vx) - tam / 2, TAM_CHUNK) * TAM_CHUNK;
                noy = Math.floorDiv((int)Math.floor(vy) - tam / 2, TAM_CHUNK) * TAM_CHUNK;
            }
            final int px = (int)Math.floor(vx) - nox, py = (int)Math.floor(vy) - noy; // posición LOCAL

            Paleta pal = Paleta.crear();
            Streamer.epoca++;
            Streamer.reiniciar(m, pal);
            Streamer.ox = nox;
            Streamer.oy = noy;
            final int ep = Streamer.epoca;
            proxyPos = -1;

            cargarDesdeAlmacen();

            final IntSet iniciales = new IntSet();
            Streamer.pedirRadio(iniciales, px / TAM_CHUNK, py / TAM_CHUNK, 3);

            Vars.world.beginMapLoad();       // generating = true: sin eventos por tile mientras se construye el mundo
            Vars.world.resize(tam, tam);

            Threads.daemon("MundoInfinito-Inicio", () -> {
                try{
                    Vars.world.tiles.fill();  // crea los objetos Tile (pesado: por eso va fuera del hilo principal)
                    Core.app.post(() -> Carga.texto("Generando terreno..."));

                    Seq<ChunkData> datosChunk = new Seq<>();
                    IntSet.IntSetIterator it = iniciales.iterator();
                    while(it.hasNext){
                        int k = it.next();
                        datosChunk.add(ChunkData.generar(pal, m.semilla, m.dimGen(), tam, k % n, k / n, ep, nox, noy));
                    }
                    Core.app.post(() -> finalizarApertura(m, datos, nueva, datosChunk, px, py, ep));
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error al abrir el mundo", t);
                    Core.app.post(() -> {
                        Carga.ocultar();
                        if(!Vars.headless && Vars.ui != null) Vars.ui.showException(t);
                        viajando = false;
                    });
                }
            });
        }

        static void finalizarApertura(Meta m, Datos datos, boolean nueva, Seq<ChunkData> chunks, int px, int py, int ep){
            if(ep != Streamer.epoca) return;
            Carga.texto("Construyendo mundo...");
            for(ChunkData d : chunks) Streamer.aplicarChunkCompleto(d);
            if(nueva) colocarNucleo(m, px, py);
            if(nueva && llegadaNueva) colocarSilo(px, py);
            llegadaNueva = false;

            Vars.world.endMapLoad();          // oscuridad de muros, proximidades, WorldLoadEvent (niebla, minimapa, indexador)
            Vars.logic.play();                // PlayEvent añade al jugador y reparte el loadout inicial

            Streamer.jugadorX = px;
            Streamer.jugadorY = py;
            Streamer.datosPendientes = datos;
            Streamer.restaurando = true;      // el primer tick restaura edificios y luego llama a finalizarRestauracion()
            Streamer.activo = true;

            m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);
        }

        /** Última fase de apertura: configs relativas, núcleo/proxy, inventario, jugador, unidades y niebla. */
        static void finalizarRestauracion(){
            final int ep = Streamer.epoca;
            Datos d = Streamer.datosPendientes;
            Streamer.datosPendientes = null;
            final boolean enSitio = Streamer.rebaseEnSitio;
            Streamer.rebaseEnSitio = false;
            int px = (int)Streamer.jugadorX, py = (int)Streamer.jugadorY;

            // 1) Puentes, nodos de energía, mass drivers y procesadores guardan enlaces RELATIVOS:
            //    se reaplican ahora que todos los edificios existen (así sobreviven a mover la ventana).
            for(Reg r : Streamer.configsPendientes){
                try{
                    Tile t = Vars.world.tile(r.x - Streamer.ox, r.y - Streamer.oy);
                    if(t != null && t.build != null) t.build.configured(null, r.configObjeto());
                }catch(Throwable ignored){}
            }
            Streamer.configsPendientes.clear();

            // 2) Si el núcleo real quedó fuera de la ventana, un núcleo "proxy" oculto (esquina, bajo la niebla)
            //    mantiene el inventario compartido del equipo para poder seguir construyendo.
            if(Vars.state.teams.cores(Team.sharded).isEmpty()){
                int c = 8;
                for(int dx = -4; dx <= 4; dx++){
                    for(int dy = -4; dy <= 4; dy++){
                        Tile t = Vars.world.tile(c + dx, c + dy);
                        if(t != null && t.block() != Blocks.air) t.setAir();
                    }
                }
                Tile t = Vars.world.tile(c, c);
                if(t != null){
                    t.setBlock(Streamer.meta.nucleoBlock(), Team.sharded);
                    proxyPos = t.pos();
                }
            }

            // 3) Inventario compartido (al cruzar de dimensión llega el inventario global; si no, el guardado)
            final boolean global = resN != null;
            final Seq<String> invN = global ? resN : (d != null ? d.itemsN : null);
            final Seq<Integer> invC = global ? resC : (d != null ? d.itemsC : null);
            resN = null;
            resC = null;
            if(invN != null && !invN.isEmpty()){
                Seq<CoreBlock.CoreBuild> nucs = Vars.state.teams.cores(Team.sharded);
                if(!nucs.isEmpty()){
                    CoreBlock.CoreBuild c = nucs.first();
                    c.items.clear();
                    for(int i = 0; i < invN.size; i++){
                        Item it = Vars.content.item(invN.get(i));
                        if(it != null) c.items.set(it, invC.get(i));
                    }
                }
            }

            // 3b) Unidades enemigas de guardia guardadas con los chunks (también tras mover la ventana)
            for(Estructuras.RegU ru : Streamer.unidadesPend) Estructuras.restaurarUnidad(ru);
            Streamer.unidadesPend.clear();

            if(!enSitio){
                // 4) Jugador: UN solo reaparecer, en su posición exacta (sin requestSpawn, que duplicaba al dron)
                spawnEnPosicion(px, py, d);
                if(Core.camera != null) Core.camera.position.set(px * 8f, py * 8f);

                // 5) Unidades propias (nunca los avatares del jugador)
                if(d != null){
                    for(Datos.RegUnidad ru : d.unidades){
                        try{
                            if(UNIDADES_NUCLEO.contains(ru.tipo)) continue;
                            UnitType t = Vars.content.unit(ru.tipo);
                            if(t == null) continue;
                            double lx = ru.vx - Streamer.ox, ly = ru.vy - Streamer.oy;
                            if(lx < 3 || ly < 3 || lx > Streamer.meta.tam - 3 || ly > Streamer.meta.tam - 3) continue;
                            Unit u = t.spawn(Team.sharded, (float)(lx * 8.0), (float)(ly * 8.0));
                            u.rotation = ru.rot;
                            u.health = Math.max(1f, ru.vida);
                        }catch(Throwable ignored){}
                    }
                }
            }

            // 6) Niebla explorada
            aplicarNiebla(ep, 0);

            Carga.ocultar();
            viajando = false;
        }

        /** Reaparece al jugador (si no tiene unidad) en una posición LOCAL, con su tipo de unidad o el del núcleo. */
        static void spawnEnPosicion(int px, int py, Datos d){
            try{
                if(Vars.player == null || !Vars.player.dead()) return;   // ya tiene unidad: nunca crear una segunda
                UnitType tipo = null;
                if(d != null && d.tipoJugador != null && !d.tipoJugador.isEmpty()) tipo = Vars.content.unit(d.tipoJugador);
                if(tipo == null){
                    CoreBlock.CoreBuild c = Vars.state.teams.closestCore(px * 8f, py * 8f, Team.sharded);
                    tipo = c != null ? ((CoreBlock)c.block).unitType : ((CoreBlock)Streamer.meta.nucleoBlock()).unitType;
                }
                if(!tipo.supportsEnv(Vars.state.rules.env)) tipo = UnitTypes.alpha;
                Unit u = tipo.spawn(Team.sharded, px * 8f, py * 8f);
                u.spawnedByCore = true;   // como los avatares reales: el límite de unidades no debe eliminarlo
                Vars.player.unit(u);
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo reaparecer al jugador", t);
            }
        }

        static boolean hayNucleoReal(){
            for(CoreBlock.CoreBuild c : Vars.state.teams.cores(Team.sharded)){
                if(c.tile.pos() != proxyPos) return true;
            }
            return false;
        }

        /** Borde del mundo virtual (±30 000 000): las unidades no pueden salir. */
        static void limitarUnidades(){
            float minX = (-LIMITE_MUNDO - (float)Streamer.ox) * 8f, maxX = (LIMITE_MUNDO - (float)Streamer.ox) * 8f;
            float minY = (-LIMITE_MUNDO - (float)Streamer.oy) * 8f, maxY = (LIMITE_MUNDO - (float)Streamer.oy) * 8f;
            if(minX > 0f && minY > 0f && maxX < Streamer.meta.tam * 8f && maxY < Streamer.meta.tam * 8f) return;
            for(Unit u : Groups.unit){
                if(u.team != Team.sharded) continue;
                if(u.x < minX) u.x = minX;
                if(u.x > maxX) u.x = maxX;
                if(u.y < minY) u.y = minY;
                if(u.y > maxY) u.y = maxY;
            }
        }

        // ---------- reglas ----------
        static void configurarReglas(){
            Rules r = Vars.state.rules;
            r.waves = false;
            r.attackMode = false;
            r.canGameOver = false;
            r.defaultTeam = Team.sharded;
            r.limitMapArea = false;
            r.borderDarkness = true;
            r.lighting = false;
            r.coreIncinerates = true;
            // Niebla de campaña: lo no explorado se oculta en pantalla Y en el minimapa.
            r.fog = true;
            r.staticFog = true;
            r.bannedBlocks.clear();
            if(actual != null && actual.dimensional()){
                // Dimensión: la UI nativa de Mindustry se adapta sola al planeta (bloques por entorno, ítems ocultos del otro planeta).
                Planet p = actual.dim == DIM_SERPULO ? Planets.serpulo : Planets.erekir;
                r.planet = p;
                r.env = p.defaultEnv;
                r.loadout = actual.dim == DIM_SERPULO
                    ? ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100, Items.graphite, 150)
                    : ItemStack.list(Items.beryllium, 200, Items.graphite, 150);
                // Todo se puede construir: sin árbol tecnológico ni bloques baneados.
                // Los equipos enemigos juegan "con trampa": sus torretas siempre tienen munición y sus bloques no piden energía.
                // Sin límite de unidades: el tope sale de los núcleos CARGADOS y, al alejarse de la base, valía 0 (morían al crearse).
                r.disableUnitCap = true;
                for(Team et : new Team[]{Team.crux, Team.malis}){
                    Rules.TeamRule tr = r.teams.get(et);
                    tr.cheat = true;
                    tr.aiCoreSpawn = false;
                }
            }else{
                // Mundos antiguos: menú de construcción con bloques de AMBOS planetas (planeta "sol" = sin filtro por planeta).
                r.planet = Planets.sun;
                r.env = ENTORNO_COMBINADO;
                r.loadout = ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100, Items.beryllium, 200, Items.graphite, 150);
            }
        }

        /** Reinicio completo (solo al entrar, continuar o cruzar un portal): vacía entidades y suelta el arreglo de tiles. */
        static void liberarMundo(){
            Streamer.activo = false;
            Streamer.epoca++;
            Vars.logic.reset();
            Vars.world.resize(1, 1);
            System.gc();
        }

        static void colocarNucleo(Meta m, int px, int py){
            for(int dx = -13; dx <= 13; dx++){
                for(int dy = -13; dy <= 13; dy++){
                    if(dx * dx + dy * dy > 169) continue;
                    Tile t = Vars.world.tile(px + dx, py + dy);
                    if(t != null && t.block() != Blocks.air) t.setAir();
                }
            }
            Tile tn = Vars.world.tile(px, py);
            if(tn == null) return;
            tn.setBlock(m.nucleoBlock(), Team.sharded);
        }

        /** Silo de recepción junto al núcleo de una dimensión recién visitada (para que los drones tengan dónde aterrizar). */
        static void colocarSilo(int px, int py){
            if(SiloInterdimensional.silo == null) return;
            Tile t = Vars.world.tile(px + 8, py);
            if(t != null) t.setBlock(SiloInterdimensional.silo, Team.sharded);
        }

        /** Reloj del mundo en ticks (solo avanza mientras se juega). */
        static double reloj(){
            return actual == null ? 0.0 : actual.reloj;
        }

        // ---------- restauración de edificios ----------
        static void restaurarEdificio(Reg r){
            try{
                Block b = Vars.content.block(r.bloque);
                Tile t = Vars.world.tile(r.x - Streamer.ox, r.y - Streamer.oy);
                if(b == null || t == null) return;
                t.setBlock(b, Team.get(r.equipo), r.rot);
                if(t.build == null) return;
                if(r.datos != null && r.datos.length > 0){
                    try{
                        t.build.readAll(Reads.get(new DataInputStream(new ByteArrayInputStream(r.datos))), (byte)r.ver);
                    }catch(Throwable e){
                        Log.warn("[MundoInfinito] Datos de @ no restaurados: @", r.bloque, e.getMessage());
                    }
                }
                if(r.cfgTipo != 0) Streamer.configsPendientes.add(r);
                if(r.dt > 0 && t.build != null && r.equipo == Team.sharded.id) Ausencia.agregar(t.build, r.dt);   // se pone al día
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo restaurar @", r.bloque);
            }
        }

        /** La niebla se crea un instante después de empezar a jugar; se reintenta unos frames. */
        static void aplicarNiebla(int ep, int intento){
            if(ep != Streamer.epoca) return;
            Bits bits = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
            if(bits == null){
                if(intento < 60) Time.runTask(10f, () -> aplicarNiebla(ep, intento + 1));
                return;
            }
            int w = Vars.world.width();
            int n = Streamer.nch;
            for(java.util.Map.Entry<Integer, int[]> e : Streamer.fogPend.entrySet()){
                int cx = e.getKey() % n, cy = e.getKey() / n;
                int[] filas = e.getValue();
                for(int ly = 0; ly < TAM_CHUNK; ly++){
                    int row = filas[ly];
                    if(row == 0) continue;
                    for(int lx = 0; lx < TAM_CHUNK; lx++){
                        if((row & (1 << lx)) != 0) bits.set((cx * TAM_CHUNK + lx) + (cy * TAM_CHUNK + ly) * w);
                    }
                }
            }
            Streamer.fogPend.clear();
            // fuerza al renderizador a volver a copiar la niebla desde la CPU
            try{
                if(!Vars.headless){
                    java.lang.reflect.Field f = Vars.renderer.fog.getClass().getDeclaredField("lastTeam");
                    f.setAccessible(true);
                    f.set(Vars.renderer.fog, null);
                }
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo refrescar la niebla guardada");
            }
        }

        // ---------- guardado ----------
        static double[] posicionVirtual(){
            Unit u = Vars.player == null ? null : Vars.player.unit();
            if(u != null && Vars.player != null && !Vars.player.dead()){
                return new double[]{Streamer.ox + u.x / 8.0, Streamer.oy + u.y / 8.0};
            }
            return new double[]{Streamer.ox + Streamer.jugadorX, Streamer.oy + Streamer.jugadorY};
        }

        /** Copia lo que hay en la ventana (edificios + niebla) al almacén de regiones, en coordenadas virtuales. */
        static void capturarEnMemoria(){
            int n = Streamer.nch, vcx0 = Streamer.ox / TAM_CHUNK, vcy0 = Streamer.oy / TAM_CHUNK;
            Bits desc = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
            int w = Vars.world.width();

            // 1) vaciar los chunks propios (se reescriben desde el estado vivo)
            java.util.HashMap<Integer, int[]> nieblaVieja = new java.util.HashMap<>();
            java.util.HashMap<Integer, Seq<Estructuras.RegU>> dormidas = new java.util.HashMap<>();   // unidades aliadas dormidas: NO se borran
            for(int cy = 1; cy <= n - 2; cy++){
                for(int cx = 1; cx <= n - 2; cx++){
                    Regiones.Region r = Regiones.region(Math.floorDiv(vcx0 + cx, REGION_CHUNKS), Math.floorDiv(vcy0 + cy, REGION_CHUNKS));
                    Regiones.ChunkGuardado viejo = r.chunks.remove(Regiones.indice(vcx0 + cx, vcy0 + cy));
                    if(viejo != null){
                        r.sucia = true;
                        if(viejo.niebla != null) nieblaVieja.put(Streamer.clave(cx, cy), viejo.niebla);
                        Seq<Estructuras.RegU> dorm = new Seq<>();
                        for(Estructuras.RegU u : viejo.unidades) if(u.clase == 1) dorm.add(u);
                        if(dorm.size > 0) dormidas.put(Streamer.clave(cx, cy), dorm);
                    }
                }
            }
            // 2) edificios
            // OJO: Groups.build NO incluye bloques que no actualizan (muros, nodos de energía...); TeamData.buildings sí.
            // (todos los equipos: el jugador, las bases enemigas y las ruinas abandonadas conservan su estado)
            for(Team eq : EQUIPOS_GUARDADO){
                for(Building b : Vars.state.teams.get(eq).buildings){
                    if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
                    if(b.tile.pos() == proxyPos) continue;
                    int cx = b.tile.x / TAM_CHUNK, cy = b.tile.y / TAM_CHUNK;
                    if(cx < 1 || cy < 1 || cx > n - 2 || cy > n - 2) continue;
                    Regiones.ChunkGuardado cg = Regiones.chunk(vcx0 + cx, vcy0 + cy, true);
                    cg.edificios.add(Reg.desde(b, Streamer.ox, Streamer.oy));
                    cg.tick = reloj();
                }
            }
            for(java.util.Map.Entry<Integer, Seq<Estructuras.RegU>> e : dormidas.entrySet()){
                int cx = e.getKey() % n, cy = e.getKey() / n;
                Regiones.chunk(vcx0 + cx, vcy0 + cy, true).unidades.addAll(e.getValue());
            }
            // unidades de guardia enemigas (se guardan junto al chunk donde están)
            for(Unit u : Groups.unit){
                if(!u.isValid() || !(u.controller() instanceof Estructuras.Guardia)) continue;
                int cx = u.tileX() / TAM_CHUNK, cy = u.tileY() / TAM_CHUNK;
                if(cx < 1 || cy < 1 || cx > n - 2 || cy > n - 2) continue;
                Regiones.ChunkGuardado cg = Regiones.chunk(vcx0 + cx, vcy0 + cy, true);
                cg.unidades.add(Estructuras.RegU.desde(u, Streamer.ox, Streamer.oy));
                cg.tick = reloj();
            }
            // 3) niebla explorada (si la niebla aún no existe, se conserva la anterior)
            for(int cy = 1; cy <= n - 2; cy++){
                for(int cx = 1; cx <= n - 2; cx++){
                    int[] m = null;
                    if(desc != null){
                        int[] filas = new int[32];
                        boolean alguno = false;
                        for(int ly = 0; ly < TAM_CHUNK; ly++){
                            int row = 0, base = cx * TAM_CHUNK + (cy * TAM_CHUNK + ly) * w;
                            for(int lx = 0; lx < TAM_CHUNK; lx++){
                                if(desc.get(base + lx)){ row |= 1 << lx; alguno = true; }
                            }
                            filas[ly] = row;
                        }
                        if(alguno) m = filas;
                    }else{
                        m = nieblaVieja.get(Streamer.clave(cx, cy));
                    }
                    if(m != null) Regiones.chunk(vcx0 + cx, vcy0 + cy, true).niebla = m;
                }
            }
        }

        static final Team[] EQUIPOS_GUARDADO = {Team.sharded, Team.crux, Team.malis, Team.derelict, Team.green, Team.blue};

        static void escribirAsync(Datos d, boolean sincrono){
            final Meta m = actual;
            m.ultimo = System.currentTimeMillis();
            final byte[] estado = d.bytes();
            final byte[] orbita = m.dimensional() ? Orbita.bytes() : null;
            final byte[] estructuras = m.dimensional() ? Estructuras.bytes() : null;
            final Fi fEstructuras = Almacen.archivoEstructuras(m);
            final java.util.HashMap<Fi, byte[]> regs = Regiones.serializarSucias();
            // Todo el acceso a disco lo hace el hilo de E/S: escritura atómica, coalescente y comprimida allí
            for(java.util.Map.Entry<Fi, byte[]> e : regs.entrySet()){
                if(e.getValue() == null) Disco.borrar(e.getKey().file());
                else Disco.escribir(e.getKey().file(), e.getValue(), true);
            }
            if(estado != null) Disco.escribir(Almacen.archivoEstado(m).file(), estado, false);
            if(orbita != null) Disco.escribir(Almacen.archivoOrbita(m).file(), orbita, false);
            if(estructuras != null) Disco.escribir(fEstructuras.file(), estructuras, false);
            Mapa.guardar();
            Disco.despues(() -> Almacen.guardarMeta(m));
            if(sincrono) Disco.vaciar(20000);
            Regiones.evictar();
        }

        static void guardar(boolean sincrono){
            if(actual == null || !Streamer.activo || Streamer.restaurando || rebase != null || Vars.world.width() < 10) return;
            double[] pos = posicionVirtual();
            Datos d = Datos.capturar(pos[0], pos[1]);
            capturarEnMemoria();
            escribirAsync(d, sincrono);
        }

        // ---------- MUNDO INFINITO: reubicar la ventana EN SITIO ----------
        /**
         * Se llama cuando el jugador se acerca al borde. NO reinicia la partida (nada de menú, ni logic.reset,
         * ni segundo dron): se muestra una capa de carga, se guarda en coordenadas virtuales y, dos frames
         * después, rebaseEnSitio() desplaza terreno, entidades y edificios.
         */
        // ---------- reubicación progresiva de la ventana (sin pantalla de carga) ----------
        static final class RebaseJob{
            int tam, n, dx, dy, dcx, dcy, vcx0, vcy0;
            short[] sf, so, sb;           // fotografía del terreno antes de mover la ventana
            boolean[] gen;                // qué chunks de ORIGEN estaban generados
            boolean[] enDisco;            // chunks de DESTINO que se reubican ahora (círculo alrededor del jugador); el resto se rehace al cargarse
            int[] orden;                  // chunks de DESTINO, del más cercano al jugador al más lejano
            int idx;
            boolean[] hecho, restaurado;
            final java.util.ArrayDeque<Reg> pend = new java.util.ArrayDeque<>();
            boolean nieblaSucia;
        }

        static RebaseJob rebase;
        static java.lang.reflect.Field campoNiebla;
        static short[] fotoF, fotoO, fotoB;   // fotografía del terreno: se reutiliza entre reubicaciones (antes: 5 MB nuevos cada vez)

        /** Copia un chunk de destino desde la fotografía (solo lo que cambia: cada cambio real cuesta eventos de render). */
        static void copiarChunk(RebaseJob j, int di){
            final int n = j.n, tam = j.tam;
            int cx0 = di % n, cy0 = di / n;
            if(!j.enDisco[di]){
                // fuera del círculo: no se toca ni una casilla de terreno. Conserva el de la posición anterior (no se ve: está lejos)
                // y se sobrescribe por el flujo normal cuando el jugador se acerque (Streamer.aplicarTramo iguala piso, mena y muro).
                // Solo se pone su niebla guardada (la del juego se vació al empezar) y cuenta como "hecho" para restaurar sus edificios.
                Bits bitsF = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
                Regiones.ChunkGuardado cg = Regiones.chunk(j.vcx0 + cx0, j.vcy0 + cy0, false);
                if(cg != null && cg.niebla != null && bitsF != null){
                    int wF = Vars.world.width();
                    for(int ly = 0; ly < TAM_CHUNK; ly++){
                        int row = cg.niebla[ly];
                        if(row == 0) continue;
                        for(int lx = 0; lx < TAM_CHUNK; lx++){
                            if((row & (1 << lx)) != 0) bitsF.set((cx0 * TAM_CHUNK + lx) + (cy0 * TAM_CHUNK + ly) * wF);
                        }
                    }
                    j.nieblaSucia = true;
                }
                j.hecho[di] = true;
                return;
            }
            int scx = cx0 + j.dcx, scy = cy0 + j.dcy;
            boolean ok = scx >= 0 && scy >= 0 && scx < n && scy < n && j.gen[scy * n + scx];
            Bits bits = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
            int w = Vars.world.width();
            for(int ly = 0; ly < TAM_CHUNK; ly++){
                int y = cy0 * TAM_CHUNK + ly;
                for(int lx = 0; lx < TAM_CHUNK; lx++){
                    int x = cx0 * TAM_CHUNK + lx;
                    Tile t = Vars.world.rawTile(x, y);
                    if(bits != null) bits.clear(x + y * w);   // la niebla vieja de esta casilla ya no corresponde
                    if(ok){
                        int si = (y + j.dy) * tam + (x + j.dx);
                        Block f = Vars.content.block(j.sf[si]);
                        if(f instanceof Floor && t.floor() != f) t.setFloor((Floor)f);
                        Block o = j.so[si] == 0 ? Blocks.air : Vars.content.block(j.so[si]);
                        if(t.overlay() != o) t.setOverlay(o);   // también BORRA la mena anterior
                        Block b = j.sb[si] == 0 ? Blocks.air : Vars.content.block(j.sb[si]);
                        if(t.block() != b){
                            if(b == Blocks.air) t.setAir(); else t.setBlock(b);
                        }
                    }else{
                        if(t.floor() != Blocks.air) t.setFloor((Floor)Blocks.air);
                        if(t.overlay() != Blocks.air) t.setOverlay(Blocks.air);
                        if(t.block() != Blocks.air) t.setAir();
                    }
                }
            }
            if(ok) Streamer.generados.add(Streamer.clave(cx0, cy0));
            // niebla explorada de este chunk (desde el almacén de regiones)
            Regiones.ChunkGuardado c = Regiones.chunk(j.vcx0 + cx0, j.vcy0 + cy0, false);
            if(c != null && c.niebla != null && bits != null){
                for(int ly = 0; ly < TAM_CHUNK; ly++){
                    int row = c.niebla[ly];
                    if(row == 0) continue;
                    for(int lx = 0; lx < TAM_CHUNK; lx++){
                        if((row & (1 << lx)) != 0) bits.set((cx0 * TAM_CHUNK + lx) + (cy0 * TAM_CHUNK + ly) * w);
                    }
                }
            }
            if(bits != null) j.nieblaSucia = true;
            j.hecho[di] = true;
        }

        static boolean vecindarioListo(RebaseJob j, int cx, int cy){
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    int x = cx + dx, y = cy + dy;
                    if(x < 0 || y < 0 || x >= j.n || y >= j.n) continue;
                    if(!j.hecho[y * j.n + x]) return false;
                }
            }
            return true;
        }

        /** Los edificios de un chunk se restauran cuando él y sus 8 vecinos ya tienen su terreno (un edificio grande cruza bordes). */
        static void encolarEntidades(RebaseJob j, int cx, int cy){
            int n = j.n;
            if(cx < 1 || cy < 1 || cx > n - 2 || cy > n - 2) return;   // el anillo exterior se ignora, como al abrir el mundo
            int di = cy * n + cx;
            if(j.restaurado[di] || !vecindarioListo(j, cx, cy)) return;
            j.restaurado[di] = true;
            Regiones.ChunkGuardado c = Regiones.chunk(j.vcx0 + cx, j.vcy0 + cy, false);
            if(c == null) return;
            double ausente = c.tick > 0 ? Math.max(0.0, reloj() - c.tick) : 0.0;
            for(Reg r : c.edificios){ r.dt = ausente; j.pend.addLast(r); }
            for(Estructuras.RegU u : c.unidades) if(u.clase == 0) Estructuras.restaurarUnidad(u);
            Entidades.despertarEnChunk(j.vcx0 + cx, j.vcy0 + cy);   // las aliadas que dormían aquí vuelven con su vida
            if(!c.edificios.isEmpty() || !c.unidades.isEmpty()) Streamer.requeridos.add(Streamer.clave(cx, cy));
        }

        /** Un trozo de la reubicación por fotograma. Primero lo que ve el jugador; el resto, en segundo plano. */
        static void avanzarRebase(){
            RebaseJob j = rebase;
            if(j == null) return;
            try{
                long fin = System.nanoTime() + (j.idx < 40 ? 12_000_000L : 4_000_000L);
                while(System.nanoTime() < fin){
                    if(!j.pend.isEmpty()){
                        restaurarEdificio(j.pend.pollFirst());
                    }else if(j.idx < j.orden.length){
                        int di = j.orden[j.idx++];
                        copiarChunk(j, di);
                        int cx = di % j.n, cy = di / j.n;
                        for(int dy = -1; dy <= 1; dy++){
                            for(int dx = -1; dx <= 1; dx++) encolarEntidades(j, cx + dx, cy + dy);
                        }
                    }else{
                        rebase = null;
                        refrescarNiebla();
                        finalizarRestauracion();    // enlaces, núcleo proxy, inventario; Carga.ocultar y viajando = false
                        return;
                    }
                }
                if(j.nieblaSucia){
                    j.nieblaSucia = false;
                    refrescarNiebla();
                }
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error en la reubicación progresiva", t);
                rebase = null;
                viajando = false;
            }
        }

        static void refrescarNiebla(){
            try{
                if(Vars.headless || Vars.renderer == null) return;
                if(campoNiebla == null){
                    campoNiebla = Vars.renderer.fog.getClass().getDeclaredField("lastTeam");
                    campoNiebla.setAccessible(true);
                }
                campoNiebla.set(Vars.renderer.fog, null);   // fuerza al renderizador a volver a copiar la niebla desde la CPU
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo refrescar la niebla");
            }
        }

        static void rebasar(){
            if(viajando || rebase != null || actual == null || !Streamer.activo || Streamer.restaurando) return;
            if(Time.time - ultimoRebase < 600f) return;
            if(Vars.player == null || Vars.player.dead()) return;
            viajando = true;
            ultimoRebase = Time.time;
            // Sin pantalla de carga: el terreno se mueve en segundo plano, por chunks, empezando por los que rodean al jugador.
            Core.app.post(Mundos::rebaseEnSitio);
        }

        /**
         * Desplaza la ventana: el terreno ya generado se COPIA a su nueva posición local (así lo explorado no se
         * pierde ni se ve negro), las entidades se mueven el mismo desplazamiento, y los edificios se vuelven a
         * colocar desde el almacén de regiones. Solo el borde nuevo de la ventana se genera de cero (pasivamente).
         */
        static void rebaseEnSitio(){
            try{
                if(actual == null || !Streamer.activo){ viajando = false; Carga.ocultar(); return; }
                final int tam = Streamer.meta.tam, n = Streamer.nch;
                double[] pos = posicionVirtual();
                int nox = Math.floorDiv((int)Math.floor(pos[0]) - tam / 2, TAM_CHUNK) * TAM_CHUNK;
                int noy = Math.floorDiv((int)Math.floor(pos[1]) - tam / 2, TAM_CHUNK) * TAM_CHUNK;
                final int dx = nox - Streamer.ox, dy = noy - Streamer.oy;
                if(dx == 0 && dy == 0){ viajando = false; Carga.ocultar(); return; }
                final int dcx = dx / TAM_CHUNK, dcy = dy / TAM_CHUNK;

                // 0) las unidades aliadas se duermen (con vida y posición) y despiertan cuando su chunk vuelva a estar listo:
                //    antes, las que quedaban fuera de la ventana, o sobre terreno viejo, morían ("explotaban")
                Entidades.hibernarTodas();

                // 1) persistir lo vivo en coordenadas virtuales
                Datos d = Datos.capturar(pos[0], pos[1]);
                capturarEnMemoria();
                escribirAsync(d, false);

                // 2) qué chunks se reubican ahora: un CÍRCULO alrededor del jugador (con su nueva posición local), no toda la ventana.
                //    Tocar los ~900 chunks de la ventana era lo que producía el tirón cada ~300 tiles.
                float njx = Streamer.jugadorX - dx, njy = Streamer.jugadorY - dy;
                int npcx = Mathf.clamp((int)njx / TAM_CHUNK, 0, n - 1), npcy = Mathf.clamp((int)njy / TAM_CHUNK, 0, n - 1);
                final boolean circ = Ajustes.rebaseCircular();
                final int rd = Math.max(Ajustes.carga(), Streamer.rVista) + 2;
                boolean[] enDisco = new boolean[n * n];
                for(int k = 0; k < enDisco.length; k++) enDisco[k] = !circ || Ajustes.dentro(k % n - npcx, k / n - npcy, rd);
                boolean[] gen = new boolean[n * n];
                IntSet.IntSetIterator gi = Streamer.generados.iterator();
                while(gi.hasNext) gen[gi.next()] = true;

                // 3a) fotografía del terreno (pisos, menas y bloques estáticos) SOLO de los chunks de origen que van a copiarse
                int total = tam * tam;
                if(fotoF == null || fotoF.length < total){ fotoF = new short[total]; fotoO = new short[total]; fotoB = new short[total]; }
                short[] sf = fotoF, so = fotoO, sb = fotoB;
                boolean[] necesaria = new boolean[n * n];
                for(int k = 0; k < enDisco.length; k++){
                    if(!enDisco[k]) continue;
                    int scx = k % n + dcx, scy = k / n + dcy;
                    if(scx >= 0 && scy >= 0 && scx < n && scy < n && gen[scy * n + scx]) necesaria[scy * n + scx] = true;
                }
                for(int sc = 0; sc < necesaria.length; sc++){
                    if(!necesaria[sc]) continue;
                    int x0 = (sc % n) * TAM_CHUNK, y0 = (sc / n) * TAM_CHUNK;
                    for(int y = y0; y < y0 + TAM_CHUNK; y++){
                        for(int x = x0; x < x0 + TAM_CHUNK; x++){
                            Tile t = Vars.world.rawTile(x, y);
                            int i = y * tam + x;
                            sf[i] = (short)t.floor().id;
                            so[i] = (short)t.overlay().id;
                            sb[i] = t.build == null ? (short)t.block().id : 0;
                        }
                    }
                }

                // 3) quitar los edificios de TODOS los equipos (se restauran desde el almacén en la nueva posición). Antes bastaba con los
                //    del jugador porque la copia de toda la ventana borraba el resto; con la reubicación circular los chunks de fuera no
                //    se tocan, y un edificio enemigo o abandonado que se quedara ahí aparecería en una coordenada virtual equivocada.
                Seq<Building> edificios = new Seq<>();
                for(var td : Vars.state.teams.getActive()) edificios.addAll(td.buildings);
                for(Building b : edificios){
                    if(b.tile != null && b.tile.build == b) b.tile.setAir();
                }

                // 4) entidades: mismo desplazamiento que el mundo
                float ox8 = dx * 8f, oy8 = dy * 8f;
                for(Unit u : Groups.unit){ u.x -= ox8; u.y -= oy8; }
                for(Bullet b : Groups.bullet){ b.x -= ox8; b.y -= oy8; }
                // las unidades de guardia se vuelven a crear desde el almacén (ya se guardaron en el paso 1)
                Seq<Unit> guardias = new Seq<>();
                for(Unit u : Groups.unit) if(u.controller() instanceof Estructuras.Guardia) guardias.add(u);
                for(Unit u : guardias) u.remove();

                // 5) reiniciar el streaming y copiar el terreno a su nueva posición local
                Streamer.epoca++;
                synchronized(Streamer.cola){ Streamer.cola.clear(); }
                Streamer.vaciarListos();
                Streamer.solicitados.clear();
                Streamer.generados.clear();
                Streamer.requeridos.clear();
                Streamer.motor.claves.clear();   // el plan viejo está en coordenadas de la ventana anterior
                Streamer.cursorPlan = 0;
                Streamer.colaRestaurar.clear();
                Streamer.configsPendientes.clear();
                Streamer.unidadesPend.clear();
                Estructuras.reiniciarCola();
                Ausencia.reiniciar();
                Streamer.fogPend.clear();
                Bits nieblaTodo = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
                if(nieblaTodo != null) nieblaTodo.clear();   // ya se guardó en el paso 1; cada chunk recupera la suya al reubicarse
                Streamer.actual = null;
                Streamer.paso = 0;
                Streamer.ox = nox;
                Streamer.oy = noy;
                proxyPos = -1;

                // La copia NO se hace aquí: se reparte en fotogramas (avanzarRebase), primero los chunks cercanos al jugador.
                // Así no hay beginMapLoad/endMapLoad (lo que congelaba el juego) ni pantalla de carga.
                RebaseJob job = new RebaseJob();
                job.tam = tam; job.n = n; job.dx = dx; job.dy = dy; job.dcx = dcx; job.dcy = dcy;
                job.vcx0 = nox / TAM_CHUNK; job.vcy0 = noy / TAM_CHUNK;
                job.sf = sf; job.so = so; job.sb = sb; job.gen = gen; job.enDisco = enDisco;
                job.hecho = new boolean[n * n];
                job.restaurado = new boolean[n * n];
                Streamer.jugadorX -= dx;
                Streamer.jugadorY -= dy;
                int pcx = Mathf.clamp((int)Streamer.jugadorX / TAM_CHUNK, 0, n - 1), pcy = Mathf.clamp((int)Streamer.jugadorY / TAM_CHUNK, 0, n - 1);
                Integer[] orden = new Integer[n * n];
                for(int k = 0; k < orden.length; k++) orden[k] = k;
                java.util.Arrays.sort(orden, (a, b) -> {
                    int da = (a % n - pcx) * (a % n - pcx) + (a / n - pcy) * (a / n - pcy);
                    int db = (b % n - pcx) * (b % n - pcx) + (b / n - pcy) * (b / n - pcy);
                    return Integer.compare(da, db);
                });
                job.orden = new int[orden.length];
                for(int k = 0; k < orden.length; k++) job.orden[k] = orden[k];
                Unit pu = Vars.player.unit();
                if(Core.camera != null && !Vars.player.dead()) Core.camera.position.set(pu.x, pu.y);
                Streamer.datosPendientes = d;
                Streamer.rebaseEnSitio = true;
                rebase = job;
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error al reubicar la ventana", t);
                Carga.ocultar();
                viajando = false;
            }
        }
    }

    // ==================================================================
    // INTERFAZ: botón del menú + lista de mundos + nuevo mundo
    // ==================================================================
    static final class MenuUI{
        static boolean inyectado = false;

        static void inyectarBoton(){
            if(inyectado) return;
            Table menu = buscarTablaMenu();
            if(menu != null){
                TextButton b = new TextButton("Mundo Infinito", Styles.cleart);
                b.clicked(MenuUI::abrirLista);
                menu.row();
                menu.add(b).width(240f).center().row();
            }else{
                Core.scene.add(new Table(t -> {
                    t.setFillParent(true);
                    t.bottom();
                    t.visible(() -> Vars.state.isMenu());
                    t.button("Mundo Infinito", Styles.cleart, MenuUI::abrirLista).width(240f).padBottom(12f);
                }));
                Log.warn("[MundoInfinito] getMenuTable() no disponible; botón en capa alternativa.");
            }
            inyectado = true;
        }

        static Table buscarTablaMenu(){
            try{
                Object frag = Vars.ui.menufrag;
                Object r = frag.getClass().getMethod("getMenuTable").invoke(frag);
                return r instanceof Table ? (Table)r : null;
            }catch(Throwable t){
                return null;
            }
        }

        static String hace(long t){
            if(t <= 0) return "—";
            long min = (System.currentTimeMillis() - t) / 60000L;
            if(min < 1) return "hace un momento";
            if(min < 60) return "hace " + min + " min";
            if(min < 60 * 24) return "hace " + (min / 60) + " h";
            return "hace " + (min / (60 * 24)) + " d";
        }

        /** Lista de mundos guardados: Jugar / Borrar. */
        static void abrirLista(){
            BaseDialog d = new BaseDialog("Mundo Infinito");
            Table lista = new Table();
            Runnable[] rellenar = new Runnable[1];
            rellenar[0] = () -> {
                lista.clear();
                Seq<Meta> metas = Almacen.listar();
                if(metas.isEmpty()){
                    lista.add("Aún no tienes mundos. ¡Crea el primero!").pad(20f).row();
                    return;
                }
                for(Meta m : metas){
                    Table fila = new Table();
                    boolean ok = m.compatible();
                    String detalle = ok
                        ? m.nombreDim() + " · núcleo " + (m.nucleoBlock() == Blocks.coreShard ? "Shard" : "Bastion") + " · semilla " + m.semillaTexto() + " · " + hace(m.ultimo)
                        : "[scarlet]Generador antiguo: ya no es compatible, solo se puede borrar[]";
                    fila.add("[accent]" + m.nombre + "[]\n" + detalle)
                        .left().growX().pad(8f).minWidth(260f);
                    if(ok) fila.button("Jugar", () -> { d.hide(); Mundos.continuar(m); }).size(110f, 52f).pad(4f);
                    fila.button("Borrar", () -> Vars.ui.showConfirm("Borrar mundo", "¿Borrar \"" + m.nombre + "\" para siempre?", () -> {
                        Almacen.borrar(m.id);
                        rellenar[0].run();
                    })).size(110f, 52f).pad(4f);
                    lista.add(fila).growX().row();
                    lista.image().color(arc.graphics.Color.gray).height(2f).growX().row();
                }
            };
            rellenar[0].run();

            d.cont.pane(lista).grow().minWidth(520f);
            d.buttons.button("Nuevo mundo", () -> { d.hide(); abrirNuevo(); }).size(210f, 64f);
            d.addCloseButton();
            d.show();
        }

        /** Nuevo mundo: nombre, semilla (opcional) y dimensión inicial. Cada mundo tiene dos dimensiones: Serpulo y Erekir. */
        static void abrirNuevo(){
            BaseDialog d = new BaseDialog("Nuevo mundo");
            final String[] nombre = {"Mundo " + (Almacen.listar().size + 1)};
            final String[] semilla = {""};
            final int[] nuc = {0};
            final String[] etiquetas = {"Empezar en: según la semilla", "Empezar en: Serpulo (Shard)", "Empezar en: Erekir (Bastion)"};

            d.cont.add("Nombre del mundo").padBottom(6f).row();
            d.cont.field(nombre[0], t -> nombre[0] = t).width(320f).padBottom(12f).row();

            d.cont.add("Semilla (vacío = aleatoria; sirve cualquier número o texto)").padBottom(6f).row();
            d.cont.field("", t -> semilla[0] = t).width(320f).padBottom(14f).row();

            TextButton nb = d.cont.button(etiquetas[0], () -> {}).size(320f, 56f).padBottom(16f).get();
            nb.clicked(() -> {
                nuc[0] = (nuc[0] + 1) % etiquetas.length;
                nb.setText(etiquetas[nuc[0]]);
            });
            d.cont.row();

            d.cont.button("Crear mundo", () -> { d.hide(); Mundos.crearNuevo(nombre[0], nuc[0], semilla[0]); }).size(320f, 64f).pad(6f).row();
            d.addCloseButton();
            d.show();
        }
    }
}
