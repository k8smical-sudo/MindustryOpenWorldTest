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
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, () -> { MenuUI.inyectarBoton(); Hud.crear(); }));

        // Bucle de streaming: se ejecuta cada frame (presupuesto de tiempo propio).
        Events.run(Trigger.update, Streamer::tick);
        // Drones de carga orbital: puro dato, avanza aunque el jugador esté en la otra dimensión.
        Events.run(Trigger.update, Orbita::tick);

        // Guardar al abrir el menú de pausa (así se guarda antes de salir al menú).
        Events.on(StateChangeEvent.class, e -> {
            if(e.to == State.paused && Streamer.activo) Mundos.guardar(false);
        });
        Events.on(ResetEvent.class, e -> Streamer.activo = false);
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
            c.child("meta.json").writeString(m.aJson().toString());
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

        static Fi archivoTecnologia(Meta m){
            return carpeta(m.id).child("tecnologia.dat");
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
        static final float UMBRAL_MASIVO_S = 0.598f, UMBRAL_MASIVO_E = 0.584f; // muros grandes (más alto = menos)
        static final float ANCHO_PASO = 0.030f;                  // anchura de los valles que cortan los macizos
        static final int CELDA_MENA = 44;

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
            float radioLibre;        // radio sin muros ni líquidos (14..26)
            float paredes;           // desplazamiento del umbral de muros cerca del spawn (+ abierto, - rocoso)
            double domX, domY;       // desplazamiento del campo de dominio (qué bioma toca en el origen)
            float[] dep = new float[0];  // [tipo, x, y, radio, forma, rot] * n   (depósitos iniciales)
            float[] poz = new float[0];  // [tipo, x, y, radio] * n               (pozos de líquidos y energía)
        }

        static volatile Spawn cacheSpawn;

        static Spawn spawn(int s, int dim){
            Spawn c = cacheSpawn;
            if(c != null && c.semilla == s && c.dim == dim) return c;
            Spawn sp = crearSpawn(s, dim);
            cacheSpawn = sp;
            return sp;
        }

        /** Formas de la veta de grafito: macizo, anillo abierto (C), anillo cerrado, banda, dos lóbulos, dispersa. */
        static int formaGrafito(float u){
            if(u < 0.18f) return 0;
            if(u < 0.40f) return 1;
            if(u < 0.52f) return 2;
            if(u < 0.68f) return 3;
            if(u < 0.80f) return 4;
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

        static Spawn crearSpawn(int s, int dim){
            Spawn sp = new Spawn();
            sp.semilla = s;
            sp.dim = dim;
            sp.radioLibre = 14f + rnd(1, 1, sem(s, 9001)) * 12f;
            float[] paredes = {0.30f, 0.22f, 0.14f, 0.06f, -0.02f};
            sp.paredes = paredes[Math.min(paredes.length - 1, (int)(rnd(2, 2, sem(s, 9002)) * paredes.length))];
            sp.domX = (rnd(3, 3, sem(s, 9003)) - 0.5) * 6000.0;
            sp.domY = (rnd(4, 4, sem(s, 9004)) - 0.5) * 6000.0;

            // Cosas que se reparten alrededor del spawn: {clase (0 depósito, 1 pozo), tipo, dMin, dMax, rMin, rMax}
            Seq<float[]> cosas = new Seq<>();
            // imprescindibles: SIEMPRE hay con qué empezar, pero en sitios, tamaños y formas distintos cada vez
            cosas.add(new float[]{0, D_COBRE, 24, 62, 3.2f, 5.8f});
            cosas.add(new float[]{0, D_PLOMO, 24, 66, 3.2f, 5.8f});
            cosas.add(new float[]{0, D_BERILIO, 26, 72, 3.2f, 5.6f});
            cosas.add(new float[]{0, D_GRAFITO, 40, 96, 5f, 11f});
            cosas.add(new float[]{0, D_ARENA, 22, 64, 7f, 12f});
            // extras con probabilidad
            if(rnd(5, 1, sem(s, 9010)) < 0.65f) cosas.add(new float[]{0, D_CHATARRA, 40, 92, 2.8f, 4.5f});
            if(rnd(5, 2, sem(s, 9010)) < 0.45f) cosas.add(new float[]{0, D_CARBON, 60, 120, 3.6f, 6f});
            if(rnd(5, 3, sem(s, 9010)) < 0.50f) cosas.add(new float[]{0, D_COBRE, 55, 120, 3.4f, 6.4f});
            if(rnd(5, 4, sem(s, 9010)) < 0.40f) cosas.add(new float[]{0, D_PLOMO, 55, 120, 3.4f, 6.4f});
            if(rnd(5, 5, sem(s, 9010)) < 0.50f) cosas.add(new float[]{0, D_BERILIO, 60, 125, 3.4f, 6f});
            if(rnd(5, 6, sem(s, 9010)) < 0.12f) cosas.add(new float[]{0, D_TITANIO, 95, 150, 3f, 5f});
            if(rnd(5, 7, sem(s, 9010)) < 0.12f) cosas.add(new float[]{0, D_TUNGSTENO, 100, 150, 3f, 5f});
            // pozos: 1 a 3, de tipos distintos entre 6 (agua, alquitrán, geotérmica, respiraderos, arkycita, escoria)
            int nPozos = Math.min(3, 1 + (int)(rnd(5, 8, sem(s, 9020)) * 3f));
            int[] tipos = {0, 1, 2, 3, 4, 5};
            for(int i = 5; i > 0; i--){
                int j = (int)(rnd(i, 3, sem(s, 9021)) * (i + 1));
                int t = tipos[i]; tipos[i] = tipos[j]; tipos[j] = t;
            }
            for(int k = 0; k < nPozos; k++) cosas.add(new float[]{1, tipos[k], 44, 98, 6.5f, 9.5f});

            if(dim >= 0){
                // Dimensión propia: se quita todo lo del otro planeta (el resto de la lógica no cambia)...
                for(int i = cosas.size - 1; i >= 0; i--){
                    float[] c = cosas.get(i);
                    if(!permitido((int)c[0], (int)c[1], dim)) cosas.remove(i);
                }
                // ...y se garantiza lo imprescindible de ese planeta, que el filtro pudo quitar
                if(dim == 0){
                    cosas.add(new float[]{0, D_CARBON, 45, 100, 3.6f, 6f});
                    cosas.add(new float[]{1, 0, 44, 98, 6.5f, 9.5f});     // agua
                    if(rnd(5, 9, sem(s, 9010)) < 0.5f) cosas.add(new float[]{1, 1, 50, 100, 6.5f, 9.5f}); // alquitrán
                }else{
                    cosas.add(new float[]{0, D_TUNGSTENO, 60, 110, 3.4f, 5.6f});
                    cosas.add(new float[]{1, 3, 44, 98, 6.5f, 9.5f});     // respiraderos
                    if(rnd(5, 9, sem(s, 9010)) < 0.5f) cosas.add(new float[]{1, 4, 50, 100, 6.5f, 9.5f}); // arkycita
                }
            }

            // orden angular aleatorio
            for(int i = cosas.size - 1; i > 0; i--){
                int j = (int)(rnd(i, 6, sem(s, 9030)) * (i + 1));
                float[] t = cosas.get(i); cosas.set(i, cosas.get(j)); cosas.set(j, t);
            }
            int n = cosas.size;
            float a0 = rnd(7, 7, sem(s, 9031)) * 6.2831853f;
            // posiciones tentativas
            float[] px = new float[n], py = new float[n], pr = new float[n];
            for(int k = 0; k < n; k++){
                float[] c = cosas.get(k);
                float ang = a0 + (k + (rnd(k, 8, sem(s, 9032)) - 0.5f) * 0.7f) * (6.2831853f / n);
                float d = c[2] + rnd(k, 9, sem(s, 9033)) * (c[3] - c[2]);
                pr[k] = c[4] + rnd(k, 10, sem(s, 9034)) * (c[5] - c[4]);
                px[k] = (float)Math.cos(ang) * d;
                py[k] = (float)Math.sin(ang) * d;
            }
            // que no se pisen: si dos se solapan, el segundo se aleja del spawn
            for(int it = 0; it < 6; it++){
                for(int i = 0; i < n; i++){
                    for(int j = i + 1; j < n; j++){
                        float ddx = px[j] - px[i], ddy = py[j] - py[i];
                        float dd = (float)Math.sqrt(ddx * ddx + ddy * ddy);
                        float need = pr[i] * 1.3f + pr[j] * 1.3f + 3f;
                        if(dd >= need) continue;
                        float rj = (float)Math.sqrt(px[j] * px[j] + py[j] * py[j]);
                        if(rj < 1f) continue;
                        float k2 = (rj + (need - dd) + 1f) / rj;
                        px[j] *= k2;
                        py[j] *= k2;
                    }
                }
            }
            Seq<Float> deps = new Seq<>(), pozs = new Seq<>();
            for(int k = 0; k < n; k++){
                float[] c = cosas.get(k);
                if(c[0] == 0){
                    int forma = (int)c[1] == D_GRAFITO ? formaGrafito(rnd(k, 11, sem(s, 9035))) : 0;
                    float rot = rnd(k, 12, sem(s, 9036)) * 6.2831853f;
                    deps.add(c[1]); deps.add(px[k]); deps.add(py[k]); deps.add(pr[k]); deps.add((float)forma); deps.add(rot);
                }else{
                    pozs.add(c[1]); pozs.add(px[k]); pozs.add(py[k]); pozs.add(pr[k]);
                }
            }
            sp.dep = new float[deps.size];
            for(int i = 0; i < deps.size; i++) sp.dep[i] = deps.get(i);
            sp.poz = new float[pozs.size];
            for(int i = 0; i < pozs.size; i++) sp.poz[i] = pozs.get(i);
            return sp;
        }

        // [tipo, distMin, peso, rMin, rMax]
        static final float[][] REGLAS_S = {
            {D_COBRE, 0, 3.0f, 3.5f, 6.5f}, {D_PLOMO, 0, 3.0f, 3.5f, 6.5f}, {D_CHATARRA, 45, 1.2f, 3f, 5f},
            {D_CARBON, 75, 2.0f, 4f, 7f}, {D_TITANIO, 150, 1.4f, 3f, 6f}, {D_TORIO, 320, 0.6f, 2.5f, 4.5f}
        };
        static final float[][] REGLAS_E = {
            {D_BERILIO, 0, 3.0f, 3.5f, 6.5f}, {D_GRAFITO, 30, 1.8f, 4.5f, 8f}, {D_TUNGSTENO, 110, 1.4f, 3f, 6f}, {D_TORIO_E, 260, 0.7f, 2.5f, 4.5f}
        };

        /** Paredes de grafito según la forma: a veces macizas, a veces un anillo abierto, una banda, lóbulos o dispersas. */
        static boolean muroGrafito(int forma, float ux, float uy, float rot, int s, int x, int y){
            float r = (float)Math.sqrt(ux * ux + uy * uy);
            float c = (float)Math.cos(rot), sn = (float)Math.sin(rot);
            float a = ux * c + uy * sn, b = -ux * sn + uy * c;
            switch(forma){
                case 0: return r < 0.62f;
                case 1: {
                    if(r < 0.42f || r > 0.9f) return false;
                    float ang = (float)Math.atan2(uy, ux) - rot;
                    while(ang > 3.14159265f) ang -= 6.2831853f;
                    while(ang < -3.14159265f) ang += 6.2831853f;
                    return Math.abs(ang) > 0.75f;   // hueco de ~86 grados
                }
                case 2: return r > 0.45f && r < 0.82f;
                case 3: return Math.abs(b) < 0.26f && Math.abs(a) < 1f;
                case 4: return Math.hypot(a - 0.5f, b) < 0.42f || Math.hypot(a + 0.5f, b) < 0.42f;
                default: return r < 1f && fbm(sem(s, 9400), 6f, 2, x, y) > 0.52f;
            }
        }

        /** @return tipo*1000 + radio normalizado*100 (0..999), o -1 si no hay depósito. aux = {ux, uy, forma, rot}. */
        static int enDeposito(int s, int x, int y, Spawn sp, float[] aux){
            // depósitos iniciales del spawn
            float[] dep = sp.dep;
            for(int k = 0; k < dep.length; k += 6){
                float dx = (float)(x - (double)dep[k + 1]), dy = (float)(y - (double)dep[k + 2]), r = dep[k + 3];
                float d2 = dx * dx + dy * dy;
                if(d2 > r * r * 2.6f) continue;
                float rr = r * (0.8f + 0.45f * fbm(sem(s, 700 + k), 7f, 2, x, y));
                float d = (float)Math.sqrt(d2);
                if(d < rr){
                    aux[0] = dx / rr; aux[1] = dy / rr; aux[2] = dep[k + 4]; aux[3] = dep[k + 5]; aux[4] = 1f;
                    return (int)dep[k] * 1000 + Math.min(999, (int)(d / rr * 100f));
                }
            }
            // cuadrícula de depósitos del resto del mundo
            int ci = Math.floorDiv(x, CELDA_MENA), cj = Math.floorDiv(y, CELDA_MENA);
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    float prx = 10f + rnd(i, j, sem(s, 7001)) * (CELDA_MENA - 20f);
                    float pry = 10f + rnd(i, j, sem(s, 7002)) * (CELDA_MENA - 20f);
                    float dx = (x - i * CELDA_MENA) - prx, dy = (y - j * CELDA_MENA) - pry; // relativo a la celda: exacto a cualquier distancia
                    if(dx * dx + dy * dy > 150f) continue;
                    double pxv = (double)i * CELDA_MENA + prx, pyv = (double)j * CELDA_MENA + pry;
                    float dsp = (float)Math.hypot(pxv, pyv);
                    if(dsp < 40f) continue;
                    float riqueza = fbm(sem(s, 81), 500f, 2, pxv, pyv);
                    float p = (0.28f + 0.55f * riqueza) * (0.35f + 0.65f * Math.min(1f, (dsp - 40f) / 140f));
                    if(rnd(i, j, sem(s, 7003)) > p) continue;
                    boolean e = esE(s, pxv, pyv, sp);
                    if(sp.dim < 0 && rnd(i, j, sem(s, 7010)) < 0.12f) e = !e;      // (solo mundos mezclados) a veces aparecen menas del otro "planeta"
                    float[][] reglas = e ? REGLAS_E : REGLAS_S;
                    float total = 0f;
                    for(float[] g : reglas) if(dsp >= g[1]) total += g[2];
                    float pick = rnd(i, j, sem(s, 7004)) * total, acc = 0f;
                    float[] elegido = reglas[0];
                    for(float[] g : reglas){
                        if(dsp < g[1]) continue;
                        acc += g[2];
                        if(pick <= acc){ elegido = g; break; }
                    }
                    float r = elegido[3] + rnd(i, j, sem(s, 7005)) * (elegido[4] - elegido[3]);
                    float rr = r * (0.8f + 0.45f * fbm(sem(s, 790), 7f, 2, x, y));
                    float d = (float)Math.sqrt(dx * dx + dy * dy);
                    if(d < rr){
                        int tipo = (int)elegido[0];
                        aux[0] = dx / rr; aux[1] = dy / rr;
                        aux[2] = tipo == D_GRAFITO ? formaGrafito(rnd(i, j, sem(s, 7011))) : 0;
                        aux[3] = rnd(i, j, sem(s, 7012)) * 6.2831853f;
                        aux[4] = 0f;
                        return tipo * 1000 + Math.min(999, (int)(d / rr * 100f));
                    }
                }
            }
            return -1;
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
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(sem(s, 5005), 5f, 2, x, y) - 0.5f) * 2f;
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
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(sem(s, 6005), 4f, 2, x, y) - 0.5f) * 1.6f;
                    if(d < r) return 1;
                }
            }
            return 0;
        }

        // ---- Pisos base ----
        static int pisoSerpulo(int s, int x, int y, float dist, double wx, double wy){
            float h = est(fbm(sem(s, 1), 170f, 5, wx, wy), K_ALTURA);
            float t = est(fbm(sem(s, 11), 420f, 4, wx, wy), K_TEMP);
            int row = Mathf.clamp((int)(Math.pow(t, 1.7) * 13f), 0, 12);
            int col = Mathf.clamp((int)(h * 13f), 0, 12);
            int p = TABLA_S[row][col];

            // mezcla de suelos secos para que la arena no domine todo el mapa (blending de campaña)
            if(p == P_SAND && dist > 30f){
                float v = fbm(sem(s, 46), 70f, 3, x, y);
                if(v > 0.66f) p = P_STONE;
                else if(v > 0.57f) p = P_DARKSAND;
            }
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
            float h = est(fbm(sem(s, 1), 170f, 5, wx, wy), K_ALTURA);
            int p = TERRENO_E[Mathf.clamp((int)(h * 8f), 0, 7)];

            if(dist > 45f){
                float c = est(fbm(sem(s, 61), 210f, 4, wx, wy), 2.0f);
                if(c < 0.27f){
                    p = fbm(sem(s, 62), 45f, 3, x, y) < 0.44f ? P_CRYSTF : P_CRYST;
                }else{
                    float b = fbm(sem(s, 63), 190f, 4, wx, wy);
                    float r = fbm(sem(s, 66), 240f, 4, wx, wy);
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

        /**
         * Muestrea UNA celda del mundo (el spawn está en el origen virtual (0,0)).
         * out[0]=piso, out[1]=mena (overlay), out[2]=muro (0 nada, 1 muro del piso, 2 muro de grafito),
         * out[3]=prop (0 nada, 1 decoración del piso, 2 cristal, 3 cristal vibrante). aux = buffer de 4 floats.
         */
        static void muestrear(int s, int x, int y, Spawn sp, float[] aux, int[] out){
            double dx0 = x, dy0 = y;
            float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
            double wx = x + (fbm(sem(s, 50), 90f, 3, x, y) - 0.5) * 70.0;
            double wy = y + (fbm(sem(s, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;

            boolean ere = esE(s, x, y, sp);
            int p = ere ? pisoErekir(s, x, y, dist, wx, wy) : pisoSerpulo(s, x, y, dist, wx, wy);
            boolean spawn = dist < sp.radioLibre;
            if(spawn){
                // el spawn conserva el bioma que le toque; solo se quita lo peligroso o intransitable
                if(liquido(p)) p = ere ? P_REGO : P_SAND;
                if(p == P_MAGMA || p == P_SLAG || p == P_TAR || p == P_HOT) p = ere ? P_YELLOW : P_BASALT;
            }

            // --- pozos garantizados cerca del spawn ---
            int pzPiso = -1;
            boolean pzLibre = false;
            float[] poz = sp.poz;
            for(int k = 0; k < poz.length; k += 4){
                float ddx = (float)(dx0 - poz[k + 1]), ddy = (float)(dy0 - poz[k + 2]), r = poz[k + 3];
                if(ddx * ddx + ddy * ddy > r * r * 2.5f) continue;
                float d = (float)Math.sqrt(ddx * ddx + ddy * ddy) / r + (fbm(sem(s, 8800 + k), 6f, 2, x, y) - 0.5f) * 0.35f;
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
            boolean enPozo = pzPiso >= 0;
            if(enPozo) p = pzPiso;

            int mena = 0, muro = 0, prop = 0;

            // --- cráteres ---
            boolean enCrater = false;
            if(enPozo || pzLibre){
                enCrater = true;
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

            // --- depósitos de menas ---
            int dep = (enPozo || pzLibre || enCrater && mena != 0) ? -1 : enDeposito(s, x, y, sp, aux);
            boolean inicial = dep >= 0 && aux[4] == 1f;
            if(liq){
                // los depósitos INICIALES siempre quedan en seco; los demás no aparecen bajo el agua
                if(inicial){ p = ere ? P_REGO : P_SAND; liq = false; }
                else dep = -1;
            }
            int tipoDep = dep < 0 ? -1 : dep / 1000;
            int radN = dep < 0 ? 0 : dep % 1000;

            // --- muros ---
            if(!liq && !spawn && !enCrater){
                float lim = ere ? UMBRAL_MASIVO_E : UMBRAL_MASIVO_S;
                lim += (fbm(sem(s, 9500), 900f, 2, x, y) - 0.5f) * 0.10f;      // regiones abiertas y regiones rocosas
                if(dist < 110f) lim += suave(1f - dist / 110f) * sp.paredes;   // carácter del spawn (más o menos paredes)
                float masivo = fbm(sem(s, 3), 85f, 4, wx, wy);
                float ancho = ANCHO_PASO + ((dist < 110f && sp.paredes < 0.1f) ? 0.025f : 0f);
                boolean valle = Math.abs(fbm(sem(s, 7), 110f, 3, wx, wy) - 0.5f) < ancho;
                boolean roca = fbm(sem(s, 5), 28f, 2, x, y) > 0.745f && dist > sp.radioLibre + 25f;
                if((masivo > lim && !valle) || roca) muro = 1;
            }

            // los depósitos iniciales de menas de suelo nunca quedan tapados por muros
            if(inicial && tipoDep != D_GRAFITO && tipoDep != D_BERILIO && tipoDep != D_TUNGSTENO && tipoDep != D_TORIO_E && radN < 100) muro = 0;

            // veta de grafito: forma variable (a veces maciza, a veces abierta, en banda, en lóbulos o dispersa)
            if(tipoDep == D_GRAFITO && !liq && !spawn){
                if(radN < 85 && fbm(sem(s, 9410), 12f, 2, x, y) > 0.35f) p = P_CARBON;
                muro = muroGrafito((int)aux[2], aux[0], aux[1], aux[3], s, x, y) ? 2 : 0;
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
                    case D_BERILIO: mena = enMuro ? O_BERILIO_MURO : O_BERILIO; break;
                    case D_TUNGSTENO: mena = enMuro ? O_TUNGSTENO_MURO : O_TUNGSTENO; break;
                    case D_TORIO_E: mena = enMuro ? O_TORIO_MURO : O_TORIO_CRISTAL; break;
                    default: break;
                }
            }

            // --- decoración / props ---
            if(muro == 0 && mena == 0 && !liq && !spawn && !enPozo && !pzLibre){
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
        private final float[] aux = new float[5];
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
            int[] niebla; // 32 enteros: una fila de 32 bits por fila del chunk (null = nada explorado)
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
                DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
                in.readInt(); // versión
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
                    r.chunks.put(idx, c);
                }
                in.close();
            }catch(Throwable t){
                Log.err("[MundoInfinito] Región ilegible " + f.name(), t);
            }
        }

        static byte[] bytes(Region r){
            if(r.chunks.isEmpty()) return null; // región vacía => se borra el archivo
            try{
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
                out.writeInt(3);
                out.writeInt(r.chunks.size());
                for(java.util.Map.Entry<Integer, ChunkGuardado> e : r.chunks.entrySet()){
                    out.writeShort(e.getKey());
                    ChunkGuardado c = e.getValue();
                    out.writeInt(c.edificios.size);
                    for(Reg x : c.edificios) x.escribir(out);
                    out.writeBoolean(c.niebla != null);
                    if(c.niebla != null) for(int v : c.niebla) out.writeInt(v);
                }
                out.close();
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
        static final int MAX_EN_COLA = 5;                        // chunks "en vuelo" a la vez (cola + calculando + esperando aplicar)
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
        static final java.util.HashMap<Integer, int[]> fogPend = new java.util.HashMap<>();
        static boolean restaurando = false;
        static boolean rebaseEnSitio = false;
        static int totalRestaurar = 1;
        static Datos datosPendientes;
        static Thread trabajador;

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
            synchronized(cola){ cola.clear(); }
            vaciarListos();
            colaRestaurar.clear();
            configsPendientes.clear();
            fogPend.clear();
            restaurando = false;
            rebaseEnSitio = false;
            datosPendientes = null;
            actual = null;
            paso = 0;
            acumEscaneo = 0f;
            acumGuardado = 0f;
            muerto = 0f;
            iniciarTrabajador();
        }

        static void iniciarTrabajador(){
            if(trabajador != null && trabajador.isAlive()) return;
            trabajador = Threads.daemon("MundoInfinito-Chunks", () -> {
                while(true){
                    int key, ep, vox, voy, n, dm;
                    Meta m;
                    Paleta p;
                    synchronized(cola){
                        while(cola.isEmpty()){
                            try{ cola.wait(); }catch(InterruptedException e){ return; }
                        }
                        key = cola.pollFirst();
                        ep = epoca;
                        m = meta;
                        dm = m == null ? -1 : m.dimGen();   // se captura junto con la época: un chunk viejo nunca mezcla dimensiones
                        p = paleta;
                        vox = ox;
                        voy = oy;
                        n = nch;
                    }
                    try{
                        if(m == null || p == null || n <= 0) continue;
                        ChunkData cd = ChunkData.generar(p, m.semilla, dm, m.tam, key % n, key / n, ep, vox, voy);
                        listos.add(cd);
                    }catch(Throwable t){
                        Log.err("[MundoInfinito] Error generando chunk", t);
                    }
                    // pausa entre chunks: el trabajador nunca acapara un núcleo de la CPU
                    try{ Thread.sleep(6); }catch(InterruptedException e){ return; }
                }
            });
            try{ trabajador.setPriority(Thread.MIN_PRIORITY + 1); }catch(Throwable ignored){}
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
                Block f = Vars.content.block(d.piso[i]);
                if(f instanceof Floor) t.setFloor((Floor)f);
                if(d.mena[i] != 0) t.setOverlay(Vars.content.block(d.mena[i]));
                if(d.bloque[i] != 0 && t.build == null && t.block() == Blocks.air){
                    t.setBlock(Vars.content.block(d.bloque[i]));
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
            if(dt > 0.05f) return 150_000L;
            if(dt > 0.03f) return 400_000L;
            return PRESUPUESTO_NS;
        }

        static void tick(){
            if(!activo || !Vars.state.isPlaying()) return;

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
                int rVista = Mathf.clamp((int)Math.ceil(mitad / TAM_CHUNK) + 1, 2, 7);
                pedirRadio(deseados, cenX, cenY, rVista);
            }
            pedirRadio(deseados, pcx, pcy, R_JUGADOR);

            vistos.clear();
            int nUnidades = 0;
            for(Unit u : Groups.unit){
                if(u.team != Team.sharded || !u.isValid()) continue;
                int cx = u.tileX() / TAM_CHUNK, cy = u.tileY() / TAM_CHUNK;
                if(!enMapa(cx, cy) || !vistos.add(clave(cx, cy))) continue;
                pedirRadio(deseados, cx, cy, R_UNIDAD);
                if(++nUnidades >= 48) break;
            }
            vistosB.clear();
            for(Building b : Vars.state.teams.get(Team.sharded).buildings){
                int cx = b.tile.x / TAM_CHUNK, cy = b.tile.y / TAM_CHUNK;
                if(!enMapa(cx, cy) || !vistosB.add(clave(cx, cy))) continue;
                pedirRadio(deseados, cx, cy, R_EDIFICIO);
            }

            // zona ya explorada / con edificios guardados: debe existir (prioridad baja: va al final por distancia)
            IntSet.IntSetIterator rq = requeridos.iterator();
            while(rq.hasNext) deseados.add(rq.next());

            Mundos.limitarUnidades();

            int libres = MAX_EN_COLA - solicitados.size;
            if(libres <= 0) return;

            // Prioridad: cerca del JUGADOR y, sobre todo, hacia donde se MUEVE (el terreno de delante llega antes que el de atrás).
            float dirx = 0f, diry = 0f;
            if(Vars.player != null && !Vars.player.dead() && Vars.player.unit() != null){
                Unit pu = Vars.player.unit();
                float l = (float)Math.sqrt(pu.vel.x * pu.vel.x + pu.vel.y * pu.vel.y);
                if(l > 0.2f){ dirx = pu.vel.x / l; diry = pu.vel.y / l; }
            }
            faltanK.clear();
            faltanS.clear();
            IntSet.IntSetIterator it = deseados.iterator();
            while(it.hasNext){
                int k = it.next();
                if(generados.contains(k) || solicitados.contains(k)) continue;
                int cx = k % nch, cy = k / nch;
                float ddx = cx - pcx, ddy = cy - pcy;
                faltanK.add(k);
                faltanS.add(ddx * ddx + ddy * ddy - 6f * (ddx * dirx + ddy * diry));
            }
            if(faltanK.size == 0) return;
            synchronized(cola){
                // se eligen los 'libres' mejores por selección directa (libres <= 5): sin ordenar ni asignar nada
                for(int n = 0; n < libres && n < faltanK.size; n++){
                    int mejor = -1;
                    float ms = Float.MAX_VALUE;
                    for(int i = 0; i < faltanK.size; i++){
                        float sc = faltanS.items[i];
                        if(sc < ms){ ms = sc; mejor = i; }
                    }
                    if(mejor < 0) break;
                    int k = faltanK.items[mejor];
                    faltanS.items[mejor] = Float.MAX_VALUE;
                    solicitados.add(k);
                    cola.addLast(k);
                }
                cola.notifyAll();
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
            // Botón de tecnología (solo en mundos dimensionales): en la esquina, fuera del camino del joystick.
            Core.scene.add(new Table(t -> {
                t.setFillParent(true);
                t.top().left();
                t.visible(() -> Streamer.activo && Vars.state.isGame() && Mundos.actual != null && Mundos.actual.dimensional());
                t.button("Tecnología", Styles.cleart, Tecnologia::abrirDialogo).size(150f, 44f).padTop(46f).padLeft(8f);
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
            Tecnologia.reiniciar();      // ...y sin investigación
            abrir(m, null, 0.0, 0.0);
        }

        static void continuar(Meta m){
            if(!m.compatible()) return;
            Fi f = Almacen.archivoEstado(m);
            Datos d = f.exists() ? Datos.leer(f) : null;
            Regiones.iniciar(m);
            Orbita.cargar(m);
            Tecnologia.cargar(m);
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
                    for(Reg r : c.edificios) Streamer.colaRestaurar.addLast(r);
                    if(!c.edificios.isEmpty()) Streamer.requeridos.add(key);
                    if(c.niebla != null){
                        Streamer.fogPend.put(key, c.niebla);
                        Streamer.requeridos.add(key);
                    }
                }
            }
            Streamer.totalRestaurar = Math.max(1, Streamer.colaRestaurar.size());
        }

        // ---------- SALTO ENTRE DIMENSIONES ----------
        static boolean llegadaNueva = false;   // true = primera vez en esa dimensión: se coloca un silo de recepción junto al núcleo

        /** Dimensión actual (0 Serpulo / 1 Erekir). En mundos antiguos (mezclados) siempre 0. */
        static int dimActual(){
            return actual != null && actual.dimensional() ? actual.dim : DIM_SERPULO;
        }

        /**
         * Viaja a la otra dimensión: efecto de despegue, guarda la actual (regiones + estado + órbita + tecnología),
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
                    m.dim = destino;                     // a partir de aquí todas las rutas apuntan a la nueva
                    Almacen.guardarMeta(m);
                    Regiones.iniciar(m);
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

            // 3) Inventario compartido
            if(d != null && !d.itemsN.isEmpty()){
                Seq<CoreBlock.CoreBuild> nucs = Vars.state.teams.cores(Team.sharded);
                if(!nucs.isEmpty()){
                    CoreBlock.CoreBuild c = nucs.first();
                    c.items.clear();
                    for(int i = 0; i < d.itemsN.size; i++){
                        Item it = Vars.content.item(d.itemsN.get(i));
                        if(it != null) c.items.set(it, d.itemsC.get(i));
                    }
                }
            }

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
            r.hiddenBuildItems.clear();
            if(actual != null && actual.dimensional()){
                // Dimensión: la UI nativa de Mindustry se adapta sola al planeta (bloques por entorno, ítems ocultos del otro planeta).
                Planet p = actual.dim == DIM_SERPULO ? Planets.serpulo : Planets.erekir;
                r.planet = p;
                r.env = p.defaultEnv;
                r.hiddenBuildItems.addAll(p.hiddenItems);
                r.loadout = actual.dim == DIM_SERPULO
                    ? ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100, Items.graphite, 150)
                    : ItemStack.list(Items.beryllium, 200, Items.graphite, 150);
                Tecnologia.aplicarBloqueos(r);   // planos aún no investigados en ESTE mundo
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
            for(int cy = 1; cy <= n - 2; cy++){
                for(int cx = 1; cx <= n - 2; cx++){
                    Regiones.Region r = Regiones.region(Math.floorDiv(vcx0 + cx, REGION_CHUNKS), Math.floorDiv(vcy0 + cy, REGION_CHUNKS));
                    Regiones.ChunkGuardado viejo = r.chunks.remove(Regiones.indice(vcx0 + cx, vcy0 + cy));
                    if(viejo != null){
                        r.sucia = true;
                        if(viejo.niebla != null) nieblaVieja.put(Streamer.clave(cx, cy), viejo.niebla);
                    }
                }
            }
            // 2) edificios
            // OJO: Groups.build NO incluye bloques que no actualizan (muros, nodos de energía...); TeamData.buildings sí.
            for(Building b : Vars.state.teams.get(Team.sharded).buildings){
                if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
                if(b.tile.pos() == proxyPos) continue;
                int cx = b.tile.x / TAM_CHUNK, cy = b.tile.y / TAM_CHUNK;
                if(cx < 1 || cy < 1 || cx > n - 2 || cy > n - 2) continue;
                Regiones.chunk(vcx0 + cx, vcy0 + cy, true).edificios.add(Reg.desde(b, Streamer.ox, Streamer.oy));
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

        static void escribirAsync(Datos d, boolean sincrono){
            final Meta m = actual;
            m.ultimo = System.currentTimeMillis();
            final byte[] estado = d.bytes();
            final byte[] orbita = m.dimensional() ? Orbita.bytes() : null;
            final byte[] tecnologia = m.dimensional() ? Tecnologia.bytes() : null;
            final java.util.HashMap<Fi, byte[]> regs = Regiones.serializarSucias();
            Runnable escribir = () -> {
                for(java.util.Map.Entry<Fi, byte[]> e : regs.entrySet()){
                    if(e.getValue() == null){ if(e.getKey().exists()) e.getKey().delete(); }
                    else Datos.escribirArchivo(e.getKey(), e.getValue());
                }
                if(estado != null) Datos.escribirArchivo(Almacen.archivoEstado(m), estado);
                if(orbita != null) Datos.escribirArchivo(Almacen.archivoOrbita(m), orbita);
                if(tecnologia != null) Datos.escribirArchivo(Almacen.archivoTecnologia(m), tecnologia);
                Almacen.guardarMeta(m);
            };
            if(sincrono) escribir.run();
            else Threads.daemon("MundoInfinito-Guardado", escribir);
            Regiones.evictar();
        }

        static void guardar(boolean sincrono){
            if(actual == null || !Streamer.activo || Streamer.restaurando || Vars.world.width() < 10) return;
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
        static void rebasar(){
            if(viajando || actual == null || !Streamer.activo || Streamer.restaurando) return;
            if(Time.time - ultimoRebase < 600f) return;
            if(Vars.player == null || Vars.player.dead()) return;
            viajando = true;
            ultimoRebase = Time.time;
            Carga.mostrar("Reubicando región...");
            Core.app.post(() -> Core.app.post(Mundos::rebaseEnSitio)); // dos frames: deja dibujar la capa antes del trabajo pesado
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

                // 1) persistir lo vivo en coordenadas virtuales
                Datos d = Datos.capturar(pos[0], pos[1]);
                capturarEnMemoria();
                escribirAsync(d, false);

                // 2) fotografía del terreno (pisos, menas y bloques estáticos; los edificios se restauran aparte)
                int total = tam * tam;
                short[] sf = new short[total], so = new short[total], sb = new short[total];
                for(int y = 0; y < tam; y++){
                    for(int x = 0; x < tam; x++){
                        Tile t = Vars.world.rawTile(x, y);
                        int i = y * tam + x;
                        sf[i] = (short)t.floor().id;
                        so[i] = (short)t.overlay().id;
                        sb[i] = t.build == null ? (short)t.block().id : 0;
                    }
                }
                boolean[] gen = new boolean[n * n];
                IntSet.IntSetIterator gi = Streamer.generados.iterator();
                while(gi.hasNext) gen[gi.next()] = true;

                // 3) quitar los edificios del jugador (se restauran desde el almacén en la nueva posición)
                Seq<Building> edificios = new Seq<>(Vars.state.teams.get(Team.sharded).buildings);
                for(Building b : edificios){
                    if(b.tile != null && b.tile.build == b) b.tile.setAir();
                }

                // 4) entidades: mismo desplazamiento que el mundo
                float ox8 = dx * 8f, oy8 = dy * 8f;
                for(Unit u : Groups.unit){ u.x -= ox8; u.y -= oy8; }
                for(Bullet b : Groups.bullet){ b.x -= ox8; b.y -= oy8; }

                // 5) reiniciar el streaming y copiar el terreno a su nueva posición local
                Streamer.epoca++;
                synchronized(Streamer.cola){ Streamer.cola.clear(); }
                Streamer.vaciarListos();
                Streamer.solicitados.clear();
                Streamer.generados.clear();
                Streamer.requeridos.clear();
                Streamer.colaRestaurar.clear();
                Streamer.configsPendientes.clear();
                Streamer.fogPend.clear();
                Streamer.actual = null;
                Streamer.paso = 0;
                Streamer.ox = nox;
                Streamer.oy = noy;
                proxyPos = -1;

                Vars.world.beginMapLoad();   // sin eventos por tile durante la copia
                for(int y = 0; y < tam; y++){
                    for(int x = 0; x < tam; x++){
                        Tile t = Vars.world.rawTile(x, y);
                        int sx = x + dx, sy = y + dy;
                        boolean ok = sx >= 0 && sy >= 0 && sx < tam && sy < tam && gen[(sy / TAM_CHUNK) * n + (sx / TAM_CHUNK)];
                        if(ok){
                            int si = sy * tam + sx;
                            Block f = Vars.content.block(sf[si]);
                            if(f instanceof Floor) t.setFloor((Floor)f);
                            if(so[si] != 0) t.setOverlay(Vars.content.block(so[si]));
                            Block b = sb[si] == 0 ? Blocks.air : Vars.content.block(sb[si]);
                            if(t.block() != b){
                                if(b == Blocks.air) t.setAir(); else t.setBlock(b);
                            }
                        }else{
                            t.setFloor((Floor)Blocks.air);
                            if(t.block() != Blocks.air) t.setAir();
                        }
                    }
                }
                for(int cy0 = 0; cy0 < n; cy0++){
                    for(int cx0 = 0; cx0 < n; cx0++){
                        if(!gen[cy0 * n + cx0]) continue;
                        int ncx = cx0 - dcx, ncy = cy0 - dcy;
                        if(Streamer.enMapa(ncx, ncy)) Streamer.generados.add(Streamer.clave(ncx, ncy));
                    }
                }
                Vars.world.endMapLoad();     // oscuridad de muros, WorldLoadEvent (reinicia niebla/minimapa/indexador)

                // 6) cámara y restauración de edificios + niebla desde el almacén
                Streamer.jugadorX -= dx;
                Streamer.jugadorY -= dy;
                Unit pu = Vars.player.unit();
                if(Core.camera != null && !Vars.player.dead()) Core.camera.position.set(pu.x, pu.y);
                cargarDesdeAlmacen();
                Streamer.datosPendientes = d;
                Streamer.rebaseEnSitio = true;
                Streamer.restaurando = true;
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
