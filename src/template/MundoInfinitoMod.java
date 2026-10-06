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

    public MundoInfinitoMod(){
        // Botón en el menú principal cuando el cliente terminó de cargar.
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, () -> { MenuUI.inyectarBoton(); Hud.crear(); }));

        // Tocar el portal (bloque marcador) cerca del jugador = viajar.
        Events.on(TapEvent.class, e -> {
            if(e.tile == null || !Streamer.activo || Vars.player == null) return;
            if(e.tile.block() != Mundos.bloquePortal() || e.tile.team() != Team.sharded) return;
            Unit u = Vars.player.unit();
            if(u == null) return;
            int jx = u.tileX(), jy = u.tileY();
            if(Mathf.dst(jx, jy, e.tile.x, e.tile.y) > 14f) return;
            Mundos.viajarPorPortal(jx, jy);
        });

        // Bucle de streaming: se ejecuta cada frame (presupuesto de tiempo propio).
        Events.run(Trigger.update, Streamer::tick);

        // Guardar al abrir el menú de pausa (así se guarda antes de salir al menú).
        Events.on(StateChangeEvent.class, e -> {
            if(e.to == State.paused && Streamer.activo) Mundos.guardar(false);
        });
        Events.on(ResetEvent.class, e -> Streamer.activo = false);
    }

    // ==================================================================
    // Dimensiones
    // ==================================================================
    public enum Dimension{
        SERPULO, EREKIR;

        public Dimension otra(){
            return this == SERPULO ? EREKIR : SERPULO;
        }

        public Block nucleo(){
            return this == SERPULO ? Blocks.coreShard : Blocks.coreBastion;
        }

        public String nombre(){
            return this == SERPULO ? "Serpulo" : "Erekir";
        }
    }

    /** Entorno combinado: así el menú de construcción ofrece bloques de AMBOS planetas. */
    static final int ENTORNO_COMBINADO = Env.terrestrial | Env.spores | Env.groundOil | Env.groundWater | Env.oxygen | Env.scorching;

    // ==================================================================
    // Meta de un mundo guardado
    // ==================================================================
    static class Meta{
        String id, nombre;
        int semilla, tam;
        Dimension dim = Dimension.SERPULO;
        long creado, ultimo;

        Jval aJson(){
            Jval j = Jval.newObject();
            j.put("nombre", nombre);
            j.put("semilla", semilla);
            j.put("tam", tam);
            j.put("dim", dim.name());
            j.put("creado", creado);
            j.put("ultimo", ultimo);
            return j;
        }

        static Meta desde(String id, Jval j){
            Meta m = new Meta();
            m.id = id;
            m.nombre = j.getString("nombre", id);
            m.semilla = j.getInt("semilla", 1);
            m.tam = j.getInt("tam", 800);
            try{ m.dim = Dimension.valueOf(j.getString("dim", "SERPULO")); }catch(Throwable t){ m.dim = Dimension.SERPULO; }
            m.creado = j.getLong("creado", 0);
            m.ultimo = j.getLong("ultimo", 0);
            return m;
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

        static Fi archivoDim(Meta m, Dimension d){
            return carpeta(m.id).child(d.name() + ".dat");
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

        /** Como Minecraft: un número se usa tal cual y cualquier otro texto se convierte con su hash. */
        static int deTexto(String t){
            try{
                long v = Long.parseLong(t.trim());
                return (int)(v ^ (v >>> 32));
            }catch(Throwable e){
                return t.trim().hashCode();
            }
        }

        static int semillaOffline(){
            return Mathf.random(1, Integer.MAX_VALUE - 1);
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
            D_BERILIO = 10, D_TUNGSTENO = 11, D_TORIO_E = 12, D_GRAFITO = 13;

        // ---- Afinación (todo en un sitio para calibrar) ----
        static final float K_ALTURA = 2.3f, K_TEMP = 2.3f;       // estiramiento del ruido a [0,1]
        static final float RADIO_SPAWN_LIBRE = 20f;             // sin muros ni líquidos
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
            int h = x * 374761393 + y * 668265263 + s * 1442695041;
            h = (h ^ (h >>> 13)) * 1274126177;
            return h ^ (h >>> 16);
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

        // ---- Depósitos iniciales: garantizan variedad útil cerca del spawn ----
        /** Devuelve [tipo, x, y, radio] * N para esta semilla (se calcula una vez por chunk). */
        static float[] iniciales(boolean ere, int s, int cx, int cy){
            float a0 = rnd(7, 11, s) * 6.2831853f;
            float[][] lista = ere
                ? new float[][]{{D_BERILIO, 24f, 4.2f}, {D_GRAFITO, 46f, 6f}, {D_BERILIO, 70f, 4.6f}, {D_TUNGSTENO, 118f, 4.2f}}
                : new float[][]{{D_COBRE, 24f, 4.2f}, {D_PLOMO, 32f, 4.2f}, {D_CHATARRA, 46f, 3f}, {D_CARBON, 68f, 4f}, {D_COBRE, 80f, 5f}, {D_PLOMO, 88f, 5f}};
            float[] r = new float[lista.length * 4];
            for(int k = 0; k < lista.length; k++){
                float ang = a0 + k * (6.2831853f / lista.length) + (rnd(k, 3, s) - 0.5f) * 0.6f;
                r[k * 4] = lista[k][0];
                r[k * 4 + 1] = cx + (float)Math.cos(ang) * lista[k][1];
                r[k * 4 + 2] = cy + (float)Math.sin(ang) * lista[k][1];
                r[k * 4 + 3] = lista[k][2];
            }
            return r;
        }

        // ---- Pozos garantizados cerca del spawn (1 a 3): fuente de líquidos y energía ----
        /**
         * Serpulo: 0 = estanque de agua (bombas), 1 = pozo de alquitrán/petróleo, 2 = fuente geotérmica (roca caliente/magma).
         * Erekir : 0 = campo de respiraderos, 1 = estanque de arkycita, 2 = pozo de escoria.
         * Devuelve [tipo, x, y, radio] * n relativo al spawn. Siempre n >= 1 y n <= 3, y cada pozo es de un tipo distinto.
         */
        static float[] pozos(boolean ere, int s){
            int n = Math.min(3, 1 + (int)(rnd(5, 9, s) * 3f));
            int base = Math.min(2, (int)(rnd(6, 1, s) * 3f));
            float a0 = rnd(2, 8, s) * 6.2831853f;
            float[] r = new float[n * 4];
            for(int k = 0; k < n; k++){
                float ang = a0 + k * 2.0944f + (rnd(k, 4, s) - 0.5f) * 0.5f;
                float d = 44f + k * 16f + rnd(k, 5, s) * 14f;
                r[k * 4] = (base + k) % 3;
                r[k * 4 + 1] = (float)Math.cos(ang) * d;
                r[k * 4 + 2] = (float)Math.sin(ang) * d;
                r[k * 4 + 3] = 6.5f + rnd(k, 6, s) * 3f;
            }
            return r;
        }

        // [tipo, distMin, peso, rMin, rMax]
        static final float[][] REGLAS_S = {
            {D_COBRE, 0, 3.0f, 3.5f, 6.5f}, {D_PLOMO, 0, 3.0f, 3.5f, 6.5f}, {D_CHATARRA, 45, 1.2f, 3f, 5f},
            {D_CARBON, 75, 2.0f, 4f, 7f}, {D_TITANIO, 150, 1.4f, 3f, 6f}, {D_TORIO, 320, 0.6f, 2.5f, 4.5f}
        };
        static final float[][] REGLAS_E = {
            {D_BERILIO, 0, 3.0f, 3.5f, 6.5f}, {D_GRAFITO, 30, 1.8f, 4.5f, 8f}, {D_TUNGSTENO, 110, 1.4f, 3f, 6f}, {D_TORIO_E, 260, 0.7f, 2.5f, 4.5f}
        };

        /** @return tipo*1000 + radio normalizado*100 (0..999), o -1 si no hay depósito. */
        static int enDeposito(boolean ere, int s, int x, int y, int cx, int cy, float[] ini){
            // iniciales
            for(int k = 0; k < ini.length; k += 4){
                float dx = (float)(x - (double)ini[k + 1]), dy = (float)(y - (double)ini[k + 2]), r = ini[k + 3];
                float d2 = dx * dx + dy * dy;
                if(d2 > r * r * 2.2f) continue;
                float rr = r * (0.8f + 0.45f * fbm(s + 700 + k, 7f, 2, x, y));
                float d = (float)Math.sqrt(d2);
                if(d < rr) return (int)ini[k] * 1000 + Math.min(999, (int)(d / rr * 100f));
            }
            // cuadrícula
            int ci = Math.floorDiv(x, CELDA_MENA), cj = Math.floorDiv(y, CELDA_MENA);
            float[][] reglas = ere ? REGLAS_E : REGLAS_S;
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    float prx = 10f + rnd(i, j, s + 7001) * (CELDA_MENA - 20f);
                    float pry = 10f + rnd(i, j, s + 7002) * (CELDA_MENA - 20f);
                    float dx = (x - i * CELDA_MENA) - prx, dy = (y - j * CELDA_MENA) - pry; // relativo a la celda: exacto a cualquier distancia
                    if(dx * dx + dy * dy > 150f) continue; // radio máx ~ 12
                    double pxv = (double)i * CELDA_MENA + prx, pyv = (double)j * CELDA_MENA + pry;
                    float dsp = (float)Math.hypot(pxv - cx, pyv - cy);
                    if(dsp < 40f) continue;
                    float riqueza = fbm(s + 81, 500f, 2, pxv, pyv);
                    float p = (0.28f + 0.55f * riqueza) * (0.35f + 0.65f * Math.min(1f, (dsp - 40f) / 140f));
                    if(rnd(i, j, s + 7003) > p) continue;
                    // tipo por pesos entre los desbloqueados a esa distancia
                    float total = 0f;
                    for(float[] g : reglas) if(dsp >= g[1]) total += g[2];
                    float pick = rnd(i, j, s + 7004) * total, acc = 0f;
                    float[] elegido = reglas[0];
                    for(float[] g : reglas){
                        if(dsp < g[1]) continue;
                        acc += g[2];
                        if(pick <= acc){ elegido = g; break; }
                    }
                    float r = elegido[3] + rnd(i, j, s + 7005) * (elegido[4] - elegido[3]);
                    float rr = r * (0.8f + 0.45f * fbm(s + 790, 7f, 2, x, y));
                    float d = (float)Math.sqrt(dx * dx + dy * dy);
                    if(d < rr) return (int)elegido[0] * 1000 + Math.min(999, (int)(d / rr * 100f));
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
                    if(rnd(i, j, s + 5001) > 0.30f) continue;
                    float prx = 12f + rnd(i, j, s + 5002) * 40f, pry = 12f + rnd(i, j, s + 5003) * 40f;
                    float r = 5f + rnd(i, j, s + 5004) * 6.5f;
                    float dx = (x - i * 64) - prx, dy = (y - j * 64) - pry;
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(s + 5005, 5f, 2, x, y) - 0.5f) * 2f;
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
                    if(rnd(i, j, s + 6001) > 0.30f) continue;
                    int px = i * 72 + 14 + (int)(rnd(i, j, s + 6002) * 44f), py = j * 72 + 14 + (int)(rnd(i, j, s + 6003) * 44f);
                    float r = 3.6f + rnd(i, j, s + 6004) * 2.6f;
                    int dx = x - px, dy = y - py;
                    if(Math.abs(dx) <= 1 && Math.abs(dy) <= 1) return 2;
                    float d = (float)Math.sqrt(dx * dx + dy * dy) + (fbm(s + 6005, 4f, 2, x, y) - 0.5f) * 1.6f;
                    if(d < r) return 1;
                }
            }
            return 0;
        }

        // ---- Pisos base ----
        static int pisoSerpulo(int s, int x, int y, float dist, double wx, double wy){
            float h = est(fbm(s, 170f, 5, wx, wy), K_ALTURA);
            float t = est(fbm(s + 11, 420f, 4, wx, wy), K_TEMP);
            float w = suave(1f - dist / 140f);
            h = lerp(h, 0.5f, w * 0.8f);
            t = lerp(t, 0.08f, w * 0.9f);
            int row = Mathf.clamp((int)(Math.pow(t, 1.7) * 13f), 0, 12);
            int col = Mathf.clamp((int)(h * 13f), 0, 12);
            int p = TABLA_S[row][col];

            // mezcla de suelos secos para que la arena no domine todo el mapa (blending de campaña)
            if(p == P_SAND && dist > 30f){
                float v = fbm(s + 46, 70f, 3, x, y);
                if(v > 0.66f) p = P_STONE;
                else if(v > 0.57f) p = P_DARKSAND;
            }
            if(p == P_DARKSAND && dist > 40f){
                if(Math.abs(0.5f - fbm(s + 41, 80f, 2, x, y)) > 0.17f && Math.abs(0.5f - fbm(s + 42, 60f, 1, x, y)) > 0.31f) p = P_TAR;
            }
            if(p == P_HOT){
                float n = Math.abs(0.5f - fbm(s + 43, 80f, 4, x, y));
                if(n > 0.04f) p = P_BASALT;
                else if(n < 0.012f) p = P_MAGMA;
            }
            if(dist > 60f && (p == P_SAND || p == P_DARKSAND || p == P_SALT || p == P_STONE || p == P_MOSS || p == P_SPORE)){
                float lk = fbm(s + 44, 200f, 5, x, y);
                if(lk > 0.655f){
                    boolean arena = p == P_SAND || p == P_SALT;
                    if(lk > 0.69f) p = arena ? P_SANDW : P_DTW;
                    else if(lk > 0.672f) p = arena ? P_SANDW : P_DSW;
                    else p = arena ? P_SAND : P_DARKSAND;
                }
            }
            if(dist > 25f && seco(p) && p != P_SNOW && p != P_ICE && p != P_ICESNOW && p != P_BASALT && p != P_HOT && p != P_MAGMA){
                if(Math.abs(fbm(s + 45, 150f, 2, x, y) - 0.5f) < 0.0045f) p = (p == P_SAND || p == P_SALT) ? P_SANDW : P_DSW;
            }
            return p;
        }

        static int pisoErekir(int s, int x, int y, float dist, double wx, double wy){
            float h = est(fbm(s, 170f, 5, wx, wy), K_ALTURA);
            float w = suave(1f - dist / 120f);
            h = lerp(h, 0.2f, w * 0.9f);
            int p = TERRENO_E[Mathf.clamp((int)(h * 8f), 0, 7)];

            if(dist > 45f){
                float c = est(fbm(s + 61, 210f, 4, wx, wy), 2.0f);
                if(c < 0.27f){
                    p = fbm(s + 62, 45f, 3, x, y) < 0.44f ? P_CRYSTF : P_CRYST;
                }else{
                    float b = fbm(s + 63, 190f, 4, wx, wy);
                    float r = fbm(s + 66, 240f, 4, wx, wy);
                    if(b > 0.60f){
                        p = P_BERYL;
                        if(Math.abs(fbm(s + 64, 40f, 4, x, y) - 0.5f) < 0.03f) p = P_ARKYIC;
                        if(fbm(s + 65, 110f, 4, x, y) > 0.66f) p = P_ARKYCITE;
                    }else if(r > 0.64f){
                        p = fbm(s + 67, 19f, 4, x, y) > 0.55f ? P_DENSERED : P_RED;
                        if(r > 0.70f) p = P_REDICE;
                    }
                }
                if(p == P_RHYO || p == P_YELLOW || p == P_REGO){
                    float sl = fbm(s + 68, 260f, 5, x, y);
                    if(sl > 0.628f) p = P_SLAG;
                    else if(sl > 0.59f) p = P_YELLOW;
                }
                if(p == P_RHYO && fbm(s + 69, 60f, 5, x, y) < 0.38f) p = P_ROUGH;
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
         * Muestrea UNA celda del mundo.
         * out[0]=piso, out[1]=mena (overlay), out[2]=muro (0 nada, 1 muro del piso, 2 muro de grafito),
         * out[3]=prop (0 nada, 1 decoración del piso, 2 cristal, 3 cristal vibrante).
         */
        static void muestrear(boolean ere, int s, int x, int y, int cx, int cy, float[] ini, float[] poz, int[] out){
            double dx0 = (double)x - cx, dy0 = (double)y - cy;
            float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
            double wx = x + (fbm(s + 50, 90f, 3, x, y) - 0.5) * 70.0;
            double wy = y + (fbm(s + 51, 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;

            int p = ere ? pisoErekir(s, x, y, dist, wx, wy) : pisoSerpulo(s, x, y, dist, wx, wy);
            boolean spawn = dist < RADIO_SPAWN_LIBRE;
            if(spawn && liquido(p)) p = ere ? P_REGO : P_SAND;

            // --- pozos garantizados cerca del spawn ---
            int pzPiso = -1;
            boolean pzLibre = false;
            for(int k = 0; k < poz.length; k += 4){
                float ddx = (float)(dx0 - poz[k + 1]), ddy = (float)(dy0 - poz[k + 2]), r = poz[k + 3];
                if(ddx * ddx + ddy * ddy > r * r * 2.5f) continue;
                float d = (float)Math.sqrt(ddx * ddx + ddy * ddy) / r + (fbm(s + 8800 + k, 6f, 2, x, y) - 0.5f) * 0.35f;
                if(d >= 1.25f) continue;
                pzLibre = true;            // halo despejado de muros
                if(d >= 1f) continue;
                int tipo = (int)poz[k];
                if(!ere){
                    if(tipo == 0) pzPiso = d < 0.55f ? P_WATER : (d < 0.8f ? P_SANDW : P_SAND);
                    else if(tipo == 1) pzPiso = d < 0.62f ? P_TAR : P_DARKSAND;
                    else pzPiso = d < 0.30f ? P_MAGMA : (d < 0.62f ? P_HOT : P_BASALT);
                }else{
                    if(tipo == 0){
                        pzPiso = P_RCRATER;
                        float[][] vents = {{0f, 0f}, {4f, 1f}, {-3f, -3f}};
                        for(float[] o : vents){
                            if(Math.abs(ddx - o[0]) <= 1.5f && Math.abs(ddy - o[1]) <= 1.5f) pzPiso = P_VRHYO;
                        }
                    }else if(tipo == 1) pzPiso = d < 0.6f ? P_ARKYCITE : P_ARKYIC;
                    else pzPiso = d < 0.55f ? P_SLAG : P_RCRATER;
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
                    if(rnd(x, y, s + 5101) < 0.18f) mena = O_CHATARRA;      // borde: chatarra de impacto
                }else if(c >= 1){
                    if(!profundo(p)) p = P_CRATERS;
                    enCrater = true;
                    int pct = ((c - 1) % 5000) / 10;
                    if(c >= 5000 && pct < 28 && dist > 150f) mena = O_TITANIO; // cráteres grandes: núcleo de titanio
                    else if(pct > 55 && rnd(x, y, s + 5102) < 0.10f) mena = O_CHATARRA;
                }
            }else{
                int c = craterVent(s, x, y, dist);
                if(c == 2){
                    if(seco(p)) p = ventDe(p);
                    enCrater = true;
                }else if(c == 1){
                    if(seco(p)){ p = cratereDe(p); }
                    enCrater = true;
                }
            }

            boolean liq = liquido(p);

            // --- depósitos de menas ---
            int dep = (liq || enPozo || pzLibre || enCrater && mena != 0) ? -1 : enDeposito(ere, s, x, y, cx, cy, ini);
            int tipoDep = dep < 0 ? -1 : dep / 1000;
            int radN = dep < 0 ? 0 : dep % 1000;

            // --- muros ---
            if(!liq && !spawn && !enCrater){
                float lim = ere ? UMBRAL_MASIVO_E : UMBRAL_MASIVO_S;
                if(dist < 90f) lim += (1f - dist / 90f) * 0.28f;
                float masivo = fbm(s + 3, 85f, 4, wx, wy);
                boolean valle = Math.abs(fbm(s + 7, 110f, 3, wx, wy) - 0.5f) < ANCHO_PASO;
                boolean roca = fbm(s + 5, 28f, 2, x, y) > 0.745f && dist > 45f;
                if((masivo > lim && !valle) || roca) muro = 1;
            }

            // afloramientos de grafito (Erekir): macizo de roca de carbón con muros de grafito
            if(ere && tipoDep == D_GRAFITO && !liq && !spawn){
                p = P_CARBON;
                if(radN < 62) muro = 2; else muro = 0;
            }

            // --- menas ---
            if(tipoDep >= 0 && tipoDep != D_GRAFITO && !liq){
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
                if(ere && (p == P_CRYST || p == P_CRYSTF) && rnd(x, y, s + 5201) < 0.012f){
                    prop = p == P_CRYSTF ? 3 : 2;
                }else if(rnd(x, y, s + 5202) < 0.011f){
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
        final int cx, cy, epoca;
        final short[] piso = new short[TAM_CHUNK * TAM_CHUNK];
        final short[] mena = new short[TAM_CHUNK * TAM_CHUNK];
        final short[] bloque = new short[TAM_CHUNK * TAM_CHUNK];

        ChunkData(int cx, int cy, int epoca){
            this.cx = cx;
            this.cy = cy;
            this.epoca = epoca;
        }

        /**
         * Pura: no toca el mundo. Seguro en hilos secundarios.
         * (ox, oy) = origen VIRTUAL de la ventana: el terreno depende solo de coordenadas virtuales,
         * así que al deslizar la ventana el mundo es idéntico y sin costuras.
         */
        static ChunkData generar(Paleta pal, boolean ere, int semilla, int tam, int cx, int cy, int epoca, int ox, int oy){
            ChunkData d = new ChunkData(cx, cy, epoca);
            float[] ini = Muestreo.iniciales(ere, semilla, 0, 0);   // el spawn del mundo está en el origen virtual (0,0)
            float[] poz = Muestreo.pozos(ere, semilla);
            int[] out = new int[4];
            int bordePiso = pal.idPiso[Muestreo.P_STONE], bordeMuro = pal.idMuroDePiso[Muestreo.P_STONE];
            for(int ly = 0; ly < TAM_CHUNK; ly++){
                for(int lx = 0; lx < TAM_CHUNK; lx++){
                    int x = cx * TAM_CHUNK + lx, y = cy * TAM_CHUNK + ly;
                    if(x >= tam || y >= tam) continue;
                    int vx = ox + x, vy = oy + y;
                    int i = ly * TAM_CHUNK + lx;
                    if(vx < -LIMITE_MUNDO || vx > LIMITE_MUNDO || vy < -LIMITE_MUNDO || vy > LIMITE_MUNDO){
                        // más allá del borde del mundo (±30 000 000): muro sólido, como el world border de Minecraft
                        d.piso[i] = (short)bordePiso;
                        d.bloque[i] = (short)bordeMuro;
                        continue;
                    }
                    Muestreo.muestrear(ere, semilla, vx, vy, 0, 0, ini, poz, out);
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
        static Dimension dim;
        static final java.util.HashMap<Long, Region> regiones = new java.util.HashMap<>();

        static void iniciar(Meta m, Dimension d){
            meta = m;
            dim = d;
            regiones.clear();
        }

        static long k(int rx, int ry){
            return ((long)rx << 32) ^ (ry & 0xffffffffL);
        }

        static Fi archivo(int rx, int ry){
            return Almacen.carpeta(meta.id).child(dim.name()).child("r." + rx + "." + ry + ".dat");
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
        static Dimension dim;
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

        static void reiniciar(Meta m, Dimension d, Paleta p){
            meta = m;
            dim = d;
            paleta = p;
            nch = m.chunksPorLado();
            generados.clear();
            solicitados.clear();
            requeridos.clear();
            synchronized(cola){ cola.clear(); }
            listos.clear();
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
                    int key, ep, vox, voy, n;
                    Meta m;
                    Paleta p;
                    Dimension d;
                    synchronized(cola){
                        while(cola.isEmpty()){
                            try{ cola.wait(); }catch(InterruptedException e){ return; }
                        }
                        key = cola.pollFirst();
                        ep = epoca;
                        m = meta;
                        p = paleta;
                        d = dim;
                        vox = ox;
                        voy = oy;
                        n = nch;
                    }
                    try{
                        if(m == null || p == null || n <= 0) continue;
                        ChunkData cd = ChunkData.generar(p, d == Dimension.EREKIR, m.semilla, m.tam, key % n, key / n, ep, vox, voy);
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
                    if(actual.epoca != epoca){ actual = null; continue; }
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

        /** Decide qué chunks necesita el mundo y, si el jugador se acerca al borde de la ventana, la reubica. */
        static void escanear(){
            IntSet deseados = new IntSet();

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

            IntSet vistos = new IntSet();
            int nUnidades = 0;
            for(Unit u : Groups.unit){
                if(u.team != Team.sharded || !u.isValid()) continue;
                int cx = u.tileX() / TAM_CHUNK, cy = u.tileY() / TAM_CHUNK;
                if(!enMapa(cx, cy) || !vistos.add(clave(cx, cy))) continue;
                pedirRadio(deseados, cx, cy, R_UNIDAD);
                if(++nUnidades >= 48) break;
            }
            IntSet vistosB = new IntSet();
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

            Seq<int[]> faltan = new Seq<>();
            IntSet.IntSetIterator it = deseados.iterator();
            while(it.hasNext){
                int k = it.next();
                if(generados.contains(k) || solicitados.contains(k)) continue;
                int cx = k % nch, cy = k / nch;
                int d2 = (cx - cenX) * (cx - cenX) + (cy - cenY) * (cy - cenY);
                faltan.add(new int[]{k, d2});
            }
            if(faltan.isEmpty()) return;
            faltan.sort((a, b) -> Integer.compare(a[1], b[1]));
            synchronized(cola){
                for(int i = 0; i < faltan.size && i < libres; i++){
                    int k = faltan.get(i)[0];
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
        }

        static String texto(){
            if(Streamer.meta == null) return "";
            double[] p = Mundos.posicionVirtual();
            return "X " + (long)Math.floor(p[0]) + "   Y " + (long)Math.floor(p[1]) + "   " + Streamer.dim.nombre();
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

        /** Bloque marcador del portal (procesador avanzado nativo, provisional). */
        static Block bloquePortal(){
            return Blocks.hyperProcessor;
        }

        // ---------- creación / continuar ----------
        static void crearNuevo(String nombre, Dimension dim, String semillaTxt){
            String t = semillaTxt == null ? "" : semillaTxt.trim();
            if(!t.isEmpty()){
                crearConSemilla(nombre, dim, Semillas.deTexto(t));
                return;
            }
            Carga.mostrar("Obteniendo semilla...");
            Semillas.obtener(semilla -> crearConSemilla(nombre, dim, semilla));
        }

        static void crearConSemilla(String nombre, Dimension dim, int semilla){
            Meta m = new Meta();
            m.id = "w" + System.currentTimeMillis();
            m.nombre = nombre == null || nombre.trim().isEmpty() ? "Mundo " + (Almacen.listar().size + 1) : nombre.trim();
            m.semilla = semilla;
            m.tam = ventana();
            m.dim = dim;
            m.creado = m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);
            Regiones.iniciar(m, dim);
            abrir(m, dim, null, 0.0, 0.0, false);
        }

        static void continuar(Meta m){
            Fi f = Almacen.archivoDim(m, m.dim);
            Datos d = f.exists() ? Datos.leer(f) : null;
            Regiones.iniciar(m, m.dim);
            if(d != null) abrir(m, m.dim, d, d.vx, d.vy, false);
            else abrir(m, m.dim, null, 0.0, 0.0, false);
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

        // ---------- apertura de la ventana ----------
        /**
         * Construye la ventana en RAM alrededor de una posición VIRTUAL (carga completa: entrar, continuar, portales).
         * @param datos  null = dimensión nueva (núcleo + portal); si no, se restaura inventario/unidades
         */
        static void abrir(Meta m, Dimension dim, Datos datos, double vx, double vy, boolean rebase){
            Carga.mostrar(rebase ? "Reubicando el mundo..." : "Preparando mundo...");
            actual = m;
            m.dim = dim;
            m.tam = ventana();
            liberarMundo();

            final boolean ere = dim == Dimension.EREKIR;
            final boolean nueva = datos == null;
            configurarReglas(dim);

            final int tam = m.tam;
            final int n = tam / TAM_CHUNK;
            vx = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vx));
            vy = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vy));

            int nox, noy;
            boolean reusar = datos != null && !rebase
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
            Streamer.reiniciar(m, dim, pal);
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
                        datosChunk.add(ChunkData.generar(pal, ere, m.semilla, tam, k % n, k / n, ep, nox, noy));
                    }
                    Core.app.post(() -> finalizarApertura(m, dim, datos, nueva, datosChunk, px, py, ep));
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

        static void finalizarApertura(Meta m, Dimension dim, Datos datos, boolean nueva, Seq<ChunkData> chunks, int px, int py, int ep){
            if(ep != Streamer.epoca) return;
            Carga.texto("Construyendo mundo...");
            for(ChunkData d : chunks) Streamer.aplicarChunkCompleto(d);
            if(nueva) colocarNucleoYPortal(dim, px, py);

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
            Dimension dim = Streamer.dim;
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
                    t.setBlock(dim.nucleo(), Team.sharded);
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
                    tipo = c != null ? ((CoreBlock)c.block).unitType : ((CoreBlock)Streamer.dim.nucleo()).unitType;
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
        static void configurarReglas(Dimension dim){
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
            // Menú de construcción con bloques de AMBOS planetas (planeta "sol" = sin filtro por planeta).
            r.planet = Planets.sun;
            r.env = ENTORNO_COMBINADO;
            r.bannedBlocks.clear();
            r.loadout = dim == Dimension.SERPULO
                ? ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100)
                : ItemStack.list(Items.beryllium, 300, Items.graphite, 150, Items.copper, 200);
        }

        /** Reinicio completo (solo al entrar, continuar o cruzar un portal): vacía entidades y suelta el arreglo de tiles. */
        static void liberarMundo(){
            Streamer.activo = false;
            Streamer.epoca++;
            Vars.logic.reset();
            Vars.world.resize(1, 1);
            System.gc();
        }

        static void colocarNucleoYPortal(Dimension dim, int px, int py){
            for(int dx = -13; dx <= 13; dx++){
                for(int dy = -13; dy <= 13; dy++){
                    if(dx * dx + dy * dy > 169) continue;
                    Tile t = Vars.world.tile(px + dx, py + dy);
                    if(t != null && t.block() != Blocks.air) t.setAir();
                }
            }
            Tile tn = Vars.world.tile(px, py), tp = Vars.world.tile(px + 8, py);
            if(tn == null || tp == null) return;
            tn.setBlock(dim.nucleo(), Team.sharded);
            tp.setBlock(bloquePortal(), Team.sharded);
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
            final Dimension dim = m.dim;
            m.ultimo = System.currentTimeMillis();
            final byte[] estado = d.bytes();
            final java.util.HashMap<Fi, byte[]> regs = Regiones.serializarSucias();
            Runnable escribir = () -> {
                for(java.util.Map.Entry<Fi, byte[]> e : regs.entrySet()){
                    if(e.getValue() == null){ if(e.getKey().exists()) e.getKey().delete(); }
                    else Datos.escribirArchivo(e.getKey(), e.getValue());
                }
                if(estado != null) Datos.escribirArchivo(Almacen.archivoDim(m, dim), estado);
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
                Streamer.listos.clear();
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

        // ---------- portales ----------
        static void viajarPorPortal(int jugadorX, int jugadorY){
            if(viajando || actual == null) return;
            viajando = true;
            Carga.mostrar("Cruzando el portal...");

            // 1-2) guardar estado y posición exacta de la dimensión actual (coordenadas virtuales)
            double[] pos = posicionVirtual();
            Datos actualD = Datos.capturar(pos[0], pos[1]);
            capturarEnMemoria();
            escribirAsync(actualD, true);

            Meta m = actual;
            Dimension destino = m.dim.otra();
            Regiones.iniciar(m, destino);      // almacén propio de la otra dimensión
            Fi f = Almacen.archivoDim(m, destino);
            Datos d = f.exists() ? Datos.leer(f) : null;

            // 3-6) limpiar, conmutar y regenerar con la MISMA semilla pero otro planeta, en las mismas coordenadas virtuales
            if(d != null) abrir(m, destino, d, d.vx, d.vy, false);
            else abrir(m, destino, null, pos[0], pos[1], false);
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
                    fila.add("[accent]" + m.nombre + "[]\n" + m.dim.nombre() + " · semilla " + m.semilla + " · " + hace(m.ultimo))
                        .left().growX().pad(8f).minWidth(260f);
                    fila.button("Jugar", () -> { d.hide(); Mundos.continuar(m); }).size(110f, 52f).pad(4f);
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

        /** Selector de origen: nombre, semilla (opcional) y planeta. */
        static void abrirNuevo(){
            BaseDialog d = new BaseDialog("Selector de Origen");
            final String[] nombre = {"Mundo " + (Almacen.listar().size + 1)};
            final String[] semilla = {""};

            d.cont.add("Nombre del mundo").padBottom(6f).row();
            d.cont.field(nombre[0], t -> nombre[0] = t).width(320f).padBottom(12f).row();

            d.cont.add("Semilla (vacío = aleatoria)").padBottom(6f).row();
            d.cont.field("", t -> semilla[0] = t).width(320f).padBottom(16f).row();

            d.cont.add("Elige el planeta donde comienza tu aventura").padBottom(10f).row();
            d.cont.button("Serpulo", () -> { d.hide(); Mundos.crearNuevo(nombre[0], Dimension.SERPULO, semilla[0]); }).size(320f, 64f).pad(6f).row();
            d.cont.button("Erekir", () -> { d.hide(); Mundos.crearNuevo(nombre[0], Dimension.EREKIR, semilla[0]); }).size(320f, 64f).pad(6f).row();
            d.addCloseButton();
            d.show();
        }
    }
}
