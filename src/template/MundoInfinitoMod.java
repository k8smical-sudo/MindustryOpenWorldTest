package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Mathf;
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
import mindustry.core.GameState.State;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.mod.Mod;
import mindustry.type.Item;
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
    public static final int[] TAMANOS = {500, 800, 1000};
    public static final String[] NOMBRES_TAM = {"Pequeño (500x500)", "Mediano (800x800)", "Grande (1000x1000)"};
    public static final String URL_SEMILLAS = "https://raw.githubusercontent.com/TU_USUARIO/mundo-infinito-semillas/main/semillas/actual.json"; // TODO: URL real
    public static final int TIMEOUT_HTTP_MS = 4000;

    public MundoInfinitoMod(){
        // Botón en el menú principal cuando el cliente terminó de cargar.
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, MenuUI::inyectarBoton));

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
        static float perlin(float x, float y, int s){
            int x0 = (int)Math.floor(x), y0 = (int)Math.floor(y);
            float fx = x - x0, fy = y - y0;
            float u = quintica(fx), v = quintica(fy);
            float a = grad(x0, y0, s, fx, fy), b = grad(x0 + 1, y0, s, fx - 1f, fy);
            float c = grad(x0, y0 + 1, s, fx, fy - 1f), d = grad(x0 + 1, y0 + 1, s, fx - 1f, fy - 1f);
            float l1 = a + (b - a) * u, l2 = c + (d - c) * u;
            return l1 + (l2 - l1) * v;
        }

        /** fBm normalizado a [0,1]. escala = tamaño de la mancha principal en tiles. Cada octava se rota para evitar rejillas. */
        static float fbm(int s, float escala, int oct, float x, float y){
            float f = 1f / escala, amp = 1f, sum = 0f, tot = 0f, cx = x, cy = y;
            for(int i = 0; i < oct; i++){
                sum += perlin(cx * f + i * 31.7f, cy * f + i * 17.3f, s + i * 131) * amp;
                tot += amp;
                amp *= 0.5f;
                f *= 2f;
                float nx = cx * 0.8f - cy * 0.6f;
                cy = cx * 0.6f + cy * 0.8f;
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
                float dx = x - ini[k + 1], dy = y - ini[k + 2], r = ini[k + 3];
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
                    float px = i * CELDA_MENA + 10f + rnd(i, j, s + 7001) * (CELDA_MENA - 20f);
                    float py = j * CELDA_MENA + 10f + rnd(i, j, s + 7002) * (CELDA_MENA - 20f);
                    float dx = x - px, dy = y - py;
                    if(dx * dx + dy * dy > 150f) continue; // radio máx ~ 12
                    float dsp = (float)Math.hypot(px - cx, py - cy);
                    if(dsp < 40f) continue;
                    float riqueza = fbm(s + 81, 500f, 2, px, py);
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
                    float px = i * 64 + 12f + rnd(i, j, s + 5002) * 40f, py = j * 64 + 12f + rnd(i, j, s + 5003) * 40f;
                    float r = 5f + rnd(i, j, s + 5004) * 6.5f;
                    float dx = x - px, dy = y - py;
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
        static int pisoSerpulo(int s, int x, int y, float dist, float wx, float wy){
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

        static int pisoErekir(int s, int x, int y, float dist, float wx, float wy){
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
        static void muestrear(boolean ere, int s, int x, int y, int cx, int cy, float[] ini, int[] out){
            float dx0 = x - cx, dy0 = y - cy;
            float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
            float wx = x + (fbm(s + 50, 90f, 3, x, y) - 0.5f) * 70f;
            float wy = y + (fbm(s + 51, 90f, 3, x + 500f, y + 500f) - 0.5f) * 70f;

            int p = ere ? pisoErekir(s, x, y, dist, wx, wy) : pisoSerpulo(s, x, y, dist, wx, wy);
            boolean spawn = dist < RADIO_SPAWN_LIBRE;
            if(spawn && liquido(p)) p = ere ? P_REGO : P_SAND;

            int mena = 0, muro = 0, prop = 0;

            // --- cráteres ---
            boolean enCrater = false;
            if(!ere){
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
            int dep = (liq || enCrater && mena != 0) ? -1 : enDeposito(ere, s, x, y, cx, cy, ini);
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
            if(muro == 0 && mena == 0 && !liq && !spawn){
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

        /** Pura: no toca el mundo. Seguro en hilos secundarios. */
        static ChunkData generar(Paleta pal, boolean ere, int semilla, int tam, int cx, int cy, int epoca){
            ChunkData d = new ChunkData(cx, cy, epoca);
            int centro = tam / 2;
            float[] ini = Muestreo.iniciales(ere, semilla, centro, centro);
            int[] out = new int[4];
            for(int ly = 0; ly < TAM_CHUNK; ly++){
                for(int lx = 0; lx < TAM_CHUNK; lx++){
                    int x = cx * TAM_CHUNK + lx, y = cy * TAM_CHUNK + ly;
                    if(x >= tam || y >= tam) continue;
                    Muestreo.muestrear(ere, semilla, x, y, centro, centro, ini, out);
                    int i = ly * TAM_CHUNK + lx;
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
    // Persistencia de una dimensión (edificios + núcleos + niebla + chunks)
    // ==================================================================
    static final class Datos{
        float px, py;
        int[] chunks = new int[0];
        final Seq<Reg> edificios = new Seq<>();
        final Seq<RegNucleo> nucleos = new Seq<>();
        byte[] niebla;
        int nieblaW, nieblaH;

        static final class Reg{
            int x, y, rot, ver;
            String bloque;
            int equipo;
            byte[] datos;
        }

        static final class RegNucleo{
            int x, y;
            final Seq<String> items = new Seq<>();
            final Seq<Integer> cantidades = new Seq<>();
        }

        /** Hilo principal: lee el mundo vivo y lo convierte en bytes. */
        static byte[] serializar(float px, float py){
            try{
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
                out.writeInt(1);
                out.writeFloat(px);
                out.writeFloat(py);

                // chunks generados
                out.writeInt(Streamer.generados.size);
                IntSet.IntSetIterator it = Streamer.generados.iterator();
                while(it.hasNext) out.writeInt(it.next());

                // edificios del jugador
                Seq<Building> lista = new Seq<>();
                for(Building b : Groups.build){
                    if(b.team != Team.sharded || !b.isValid() || b.tile == null || b.tile.build != b) continue;
                    lista.add(b);
                }
                out.writeInt(lista.size);
                for(Building b : lista){
                    out.writeShort(b.tile.x);
                    out.writeShort(b.tile.y);
                    out.writeUTF(b.block.name);
                    out.writeByte(b.team.id);
                    out.writeByte(b.rotation);
                    out.writeByte(b.version());
                    ByteArrayOutputStream bb = new ByteArrayOutputStream();
                    byte[] datos;
                    try{
                        DataOutputStream bo = new DataOutputStream(bb);
                        b.writeAll(Writes.get(bo));
                        bo.flush();
                        datos = bb.toByteArray();
                    }catch(Throwable t){
                        datos = new byte[0];
                    }
                    out.writeInt(datos.length);
                    out.write(datos);
                }

                // inventario de núcleos (logic.play() lo vacía; se restaura después)
                Seq<CoreBlock.CoreBuild> nucs = Vars.state.teams.cores(Team.sharded);
                out.writeInt(nucs.size);
                for(CoreBlock.CoreBuild c : nucs){
                    out.writeShort(c.tile.x);
                    out.writeShort(c.tile.y);
                    Seq<String> nombres = new Seq<>();
                    Seq<Integer> cants = new Seq<>();
                    c.items.each((item, amount) -> {
                        if(amount > 0){
                            nombres.add(item.name);
                            cants.add(amount);
                        }
                    });
                    out.writeInt(nombres.size);
                    for(int i = 0; i < nombres.size; i++){
                        out.writeUTF(nombres.get(i));
                        out.writeInt(cants.get(i));
                    }
                }

                // niebla descubierta (RLE)
                Bits desc = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
                int w = Vars.world.width(), h = Vars.world.height();
                if(desc == null){
                    out.writeInt(0);
                }else{
                    ByteArrayOutputStream fb = new ByteArrayOutputStream();
                    int size = w * h, pos = 0;
                    while(pos < size){
                        boolean cur = desc.get(pos);
                        int consec = 0;
                        while(consec < 127 && pos < size && desc.get(pos) == cur){
                            consec++;
                            pos++;
                        }
                        fb.write((cur ? 0x80 : 0) | consec);
                    }
                    byte[] fbytes = fb.toByteArray();
                    out.writeInt(fbytes.length);
                    out.writeShort(w);
                    out.writeShort(h);
                    out.write(fbytes);
                }

                out.close();
                return bos.toByteArray();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo serializar", t);
                return null;
            }
        }

        static Datos leer(Fi f){
            try{
                InputStream in = f.read();
                DataInputStream din = new DataInputStream(new GZIPInputStream(in));
                Datos d = new Datos();
                din.readInt(); // versión
                d.px = din.readFloat();
                d.py = din.readFloat();
                int nc = din.readInt();
                d.chunks = new int[nc];
                for(int i = 0; i < nc; i++) d.chunks[i] = din.readInt();

                int nb = din.readInt();
                for(int i = 0; i < nb; i++){
                    Reg r = new Reg();
                    r.x = din.readShort();
                    r.y = din.readShort();
                    r.bloque = din.readUTF();
                    r.equipo = din.readUnsignedByte();
                    r.rot = din.readByte();
                    r.ver = din.readByte();
                    int len = din.readInt();
                    r.datos = new byte[len];
                    din.readFully(r.datos);
                    d.edificios.add(r);
                }

                int nn = din.readInt();
                for(int i = 0; i < nn; i++){
                    RegNucleo n = new RegNucleo();
                    n.x = din.readShort();
                    n.y = din.readShort();
                    int ni = din.readInt();
                    for(int k = 0; k < ni; k++){
                        n.items.add(din.readUTF());
                        n.cantidades.add(din.readInt());
                    }
                    d.nucleos.add(n);
                }

                int fl = din.readInt();
                if(fl > 0){
                    d.nieblaW = din.readShort();
                    d.nieblaH = din.readShort();
                    d.niebla = new byte[fl];
                    din.readFully(d.niebla);
                }
                din.close();
                return d;
            }catch(Throwable t){
                Log.err("[MundoInfinito] Archivo de dimensión ilegible", t);
                return null;
            }
        }

        static void escribirArchivo(Fi f, byte[] bytes){
            try{
                OutputStream os = f.write(false);
                os.write(bytes);
                os.close();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo escribir " + f.name(), t);
            }
        }
    }

    // ==================================================================
    // STREAMING DE CHUNKS (equivalente a la carga de chunks de Minecraft)
    // ==================================================================
    static final class Streamer{
        static final long PRESUPUESTO_NS = 2_500_000L;  // 2.5 ms por frame para aplicar chunks
        static final int R_JUGADOR = 3, R_CAMARA = 2, R_UNIDAD = 2, R_EDIFICIO = 1;
        static final int MAX_PETICIONES = 14;           // chunks nuevos pedidos por escaneo

        static volatile boolean activo = false;
        static volatile int epoca = 0;

        static Meta meta;
        static Dimension dim;
        static Paleta paleta;
        static int nch;                                  // chunks por lado
        static final IntSet generados = new IntSet();    // ya aplicados al mundo
        static final IntSet solicitados = new IntSet();  // en cola / calculando / esperando aplicar
        static final ArrayDeque<Integer> cola = new ArrayDeque<>();
        static final ConcurrentLinkedQueue<ChunkData> listos = new ConcurrentLinkedQueue<>();
        static final java.util.HashMap<Integer, Seq<Datos.Reg>> pendientes = new java.util.HashMap<>();
        static Thread trabajador;

        static int[] guardados = new int[0];            // chunks explorados en la sesión anterior
        static ChunkData actual;
        static int fila;
        static float acumEscaneo, acumGuardado;
        static float jugadorX, jugadorY;                 // última posición válida (tiles)

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
            synchronized(cola){ cola.clear(); }
            listos.clear();
            pendientes.clear();
            actual = null;
            guardados = new int[0];
            fila = 0;
            acumEscaneo = 0f;
            acumGuardado = 0f;
            iniciarTrabajador();
        }

        static void iniciarTrabajador(){
            if(trabajador != null && trabajador.isAlive()) return;
            trabajador = Threads.daemon("MundoInfinito-Chunks", () -> {
                while(true){
                    int key;
                    int ep;
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
                    }
                    try{
                        if(m == null || p == null) continue;
                        int n = m.chunksPorLado();
                        ChunkData cd = ChunkData.generar(p, d == Dimension.EREKIR, m.semilla, m.tam, key % n, key / n, ep);
                        listos.add(cd);
                    }catch(Throwable t){
                        Log.err("[MundoInfinito] Error generando chunk", t);
                    }
                }
            });
            try{ trabajador.setPriority(Thread.MIN_PRIORITY + 1); }catch(Throwable ignored){}
        }

        /** Aplica un chunk COMPLETO al mundo (modo carga: Vars.world.isGenerating() == true, sin eventos). */
        static void aplicarChunkCompleto(ChunkData d){
            for(int ly = 0; ly < TAM_CHUNK; ly++) aplicarFila(d, ly);
            terminarChunk(d);
        }

        static void aplicarFila(ChunkData d, int ly){
            int tam = meta.tam;
            for(int lx = 0; lx < TAM_CHUNK; lx++){
                int x = d.cx * TAM_CHUNK + lx, y = d.cy * TAM_CHUNK + ly;
                if(x >= tam || y >= tam) continue;
                Tile t = Vars.world.rawTile(x, y);
                int i = ly * TAM_CHUNK + lx;
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
            Seq<Datos.Reg> regs = pendientes.remove(k);
            if(regs != null) for(Datos.Reg r : regs) Mundos.restaurarEdificio(r);
        }

        static void tick(){
            if(!activo || !Vars.state.isPlaying()) return;

            long fin = System.nanoTime() + PRESUPUESTO_NS;
            while(System.nanoTime() < fin){
                if(actual == null){
                    actual = listos.poll();
                    if(actual == null) break;
                    if(actual.epoca != epoca){ actual = null; continue; }
                    fila = 0;
                }
                aplicarFila(actual, fila++);
                if(fila >= TAM_CHUNK){
                    terminarChunk(actual);
                    actual = null;
                }
            }

            acumEscaneo += Time.delta;
            if(acumEscaneo >= 20f){
                acumEscaneo = 0f;
                escanear();
            }
            acumGuardado += Time.delta;
            if(acumGuardado >= 2700f){ // ~45 s
                acumGuardado = 0f;
                Mundos.guardar(false);
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

        /** Decide qué chunks necesita el mundo: alrededor del jugador, la cámara, las unidades y los edificios. */
        static void escanear(){
            IntSet deseados = new IntSet();
            int pcx = -1, pcy = -1;

            if(Vars.player != null && Vars.player.unit() != null && !Vars.player.dead()){
                Unit u = Vars.player.unit();
                jugadorX = u.x / 8f;
                jugadorY = u.y / 8f;
            }
            pcx = Mathf.clamp((int)jugadorX / TAM_CHUNK, 0, nch - 1);
            pcy = Mathf.clamp((int)jugadorY / TAM_CHUNK, 0, nch - 1);
            pedirRadio(deseados, pcx, pcy, R_JUGADOR);

            int ccx = (int)(Core.camera.position.x / 8f) / TAM_CHUNK, ccy = (int)(Core.camera.position.y / 8f) / TAM_CHUNK;
            if(enMapa(ccx, ccy)) pedirRadio(deseados, ccx, ccy, R_CAMARA);

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
            for(Building b : Groups.build){
                if(b.team != Team.sharded) continue;
                int cx = b.tile.x / TAM_CHUNK, cy = b.tile.y / TAM_CHUNK;
                if(!enMapa(cx, cy) || !vistosB.add(clave(cx, cy))) continue;
                pedirRadio(deseados, cx, cy, R_EDIFICIO);
            }

            // pendientes de restaurar y chunks ya explorados antes: deben existir
            for(Integer k : pendientes.keySet()) deseados.add(k);
            for(int k : guardados) deseados.add(k);

            // faltantes ordenados por cercanía al jugador
            Seq<int[]> faltan = new Seq<>();
            IntSet.IntSetIterator it = deseados.iterator();
            while(it.hasNext){
                int k = it.next();
                if(generados.contains(k) || solicitados.contains(k)) continue;
                int cx = k % nch, cy = k / nch;
                int d2 = (cx - pcx) * (cx - pcx) + (cy - pcy) * (cy - pcy);
                faltan.add(new int[]{k, d2});
            }
            if(faltan.isEmpty()) return;
            faltan.sort((a, b) -> Integer.compare(a[1], b[1]));
            synchronized(cola){
                for(int i = 0; i < faltan.size && i < MAX_PETICIONES; i++){
                    int k = faltan.get(i)[0];
                    solicitados.add(k);
                    cola.addLast(k);
                }
                cola.notifyAll();
            }
        }
    }

    // ==================================================================
    // MUNDOS: abrir / guardar / portales
    // ==================================================================
    static final class Mundos{
        static Meta actual;
        static boolean viajando = false;

        /** Bloque marcador del portal (procesador avanzado nativo, provisional). */
        static Block bloquePortal(){
            return Blocks.hyperProcessor;
        }

        // ---------- creación ----------
        static void crearNuevo(String nombre, Dimension dim, int tam){
            Vars.ui.loadfrag.show("Obteniendo semilla...");
            Semillas.obtener(semilla -> {
                Meta m = new Meta();
                m.id = "w" + System.currentTimeMillis();
                m.nombre = nombre == null || nombre.trim().isEmpty() ? "Mundo " + (Almacen.listar().size + 1) : nombre.trim();
                m.semilla = semilla;
                m.tam = tam;
                m.dim = dim;
                m.creado = m.ultimo = System.currentTimeMillis();
                Almacen.guardarMeta(m);
                abrir(m, dim, null, m.tam / 2, m.tam / 2);
            });
        }

        static void continuar(Meta m){
            Datos d = null;
            Fi f = Almacen.archivoDim(m, m.dim);
            if(f.exists()) d = Datos.leer(f);
            int px = m.tam / 2, py = m.tam / 2;
            if(d != null){
                px = (int)d.px;
                py = (int)d.py;
            }
            abrir(m, m.dim, d, px, py);
        }

        // ---------- apertura ----------
        /**
         * @param datos null = dimensión nueva (se coloca núcleo + portal); si no, se restaura lo guardado.
         */
        static void abrir(Meta m, Dimension dim, Datos datos, int px, int py){
            Vars.ui.loadfrag.show("Preparando mundo...");
            actual = m;
            m.dim = dim;
            liberarMundo();

            final boolean ere = dim == Dimension.EREKIR;
            final boolean nuevo = datos == null;
            configurarReglas(dim);

            final int tam = m.tam;
            px = Mathf.clamp(px, 40, tam - 40);
            py = Mathf.clamp(py, 40, tam - 40);
            final int fpx = px, fpy = py;

            Paleta pal = Paleta.crear();
            Streamer.epoca++;
            Streamer.reiniciar(m, dim, pal);
            final int ep = Streamer.epoca;

            // los edificios guardados se reparten por chunk; se crean cuando su chunk existe
            if(!nuevo){
                for(Datos.Reg r : datos.edificios){
                    int k = Streamer.clave(r.x / TAM_CHUNK, r.y / TAM_CHUNK);
                    Seq<Datos.Reg> l = Streamer.pendientes.get(k);
                    if(l == null){ l = new Seq<>(); Streamer.pendientes.put(k, l); }
                    l.add(r);
                }
            }

            // chunks iniciales: 7x7 alrededor del jugador + 3x3 alrededor de cada núcleo guardado
            final IntSet iniciales = new IntSet();
            Streamer.pedirRadio(iniciales, fpx / TAM_CHUNK, fpy / TAM_CHUNK, 3);
            if(!nuevo){
                for(Datos.RegNucleo n : datos.nucleos) Streamer.pedirRadio(iniciales, n.x / TAM_CHUNK, n.y / TAM_CHUNK, 1);
            }
            final Datos fdatos = datos;
            if(datos != null) Streamer.guardados = datos.chunks; // se cargan en segundo plano, nearest-first, vía escanear()

            Vars.world.beginMapLoad();       // generating = true: sin eventos por tile mientras se construye el mundo
            Vars.world.resize(tam, tam);

            Threads.daemon("MundoInfinito-Inicio", () -> {
                try{
                    Vars.world.tiles.fill();  // crea los objetos Tile (pesado: por eso va fuera del hilo principal)
                    Core.app.post(() -> Vars.ui.loadfrag.setText("Generando terreno..."));

                    Seq<ChunkData> datosChunk = new Seq<>();
                    IntSet.IntSetIterator it = iniciales.iterator();
                    while(it.hasNext){
                        int k = it.next();
                        datosChunk.add(ChunkData.generar(pal, ere, m.semilla, tam, k % Streamer.nch, k / Streamer.nch, ep));
                    }

                    Core.app.post(() -> finalizarApertura(m, dim, fdatos, datosChunk, fpx, fpy, ep));
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error al abrir el mundo", t);
                    Core.app.post(() -> {
                        Vars.ui.loadfrag.hide();
                        Vars.ui.showException(t);
                        viajando = false;
                    });
                }
            });
        }

        static void finalizarApertura(Meta m, Dimension dim, Datos datos, Seq<ChunkData> chunks, int px, int py, int ep){
            if(ep != Streamer.epoca) return;
            Vars.ui.loadfrag.setText("Construyendo mundo...");
            for(ChunkData d : chunks) Streamer.aplicarChunkCompleto(d);

            boolean ponerBase = datos == null || datos.nucleos.isEmpty();
            if(ponerBase) colocarNucleoYPortal(dim, px, py);

            Vars.world.endMapLoad();          // oscuridad de muros, proximidades, WorldLoadEvent (niebla, minimapa, indexador)
            Vars.ui.loadfrag.hide();
            Vars.logic.play();                // PlayEvent añade al jugador
            Streamer.activo = true;
            Streamer.jugadorX = px;
            Streamer.jugadorY = py;
            viajando = false;

            m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);

            // Todo lo que depende de que el juego ya esté en marcha:
            Time.runTask(8f, () -> {
                if(ep != Streamer.epoca) return;
                CoreBlock.CoreBuild n = Vars.state.teams.closestCore(px * 8f, py * 8f, Team.sharded);
                if(n != null) n.requestSpawn(Vars.player);
                Core.camera.position.set(px * 8f, py * 8f);
                if(datos != null) restaurarNucleos(datos);
            });
            if(datos != null && datos.niebla != null) restaurarNiebla(datos, ep, 0);
        }

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
            r.hiddenBuildItems.clear();
            r.bannedBlocks.clear();
            // Recursos iniciales (solo se aplican en mundo nuevo; los guardados restauran el inventario real).
            r.loadout = dim == Dimension.SERPULO
                ? ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100)
                : ItemStack.list(Items.beryllium, 300, Items.graphite, 150, Items.copper, 200);
        }

        /** Equivalente a "world.clear()": reinicia la lógica (elimina edificios y unidades) y suelta el arreglo de tiles. */
        static void liberarMundo(){
            Streamer.activo = false;
            Streamer.epoca++;
            Vars.logic.reset();
            Vars.world.resize(1, 1);
            System.gc();
        }

        static void colocarNucleoYPortal(Dimension dim, int px, int py){
            // despeja un disco de 13 tiles + el pasillo del portal
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

        // ---------- restauración ----------
        static void restaurarEdificio(Datos.Reg r){
            try{
                Block b = Vars.content.block(r.bloque);
                Tile t = Vars.world.tile(r.x, r.y);
                if(b == null || t == null) return;
                t.setBlock(b, Team.get(r.equipo), r.rot);
                if(t.build != null && r.datos != null && r.datos.length > 0){
                    try{
                        t.build.readAll(Reads.get(new DataInputStream(new ByteArrayInputStream(r.datos))), (byte)r.ver);
                    }catch(Throwable e){
                        Log.warn("[MundoInfinito] Datos de @ no restaurados: @", r.bloque, e.getMessage());
                    }
                }
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo restaurar @", r.bloque);
            }
        }

        static void restaurarNucleos(Datos datos){
            for(Datos.RegNucleo n : datos.nucleos){
                Tile t = Vars.world.tile(n.x, n.y);
                if(t == null || !(t.build instanceof CoreBlock.CoreBuild)) continue;
                CoreBlock.CoreBuild c = (CoreBlock.CoreBuild)t.build;
                c.items.clear();
                for(int i = 0; i < n.items.size; i++){
                    Item it = Vars.content.item(n.items.get(i));
                    if(it != null) c.items.set(it, n.cantidades.get(i));
                }
            }
        }

        /** La niebla se crea un instante después de empezar a jugar; se reintenta unos frames. */
        static void restaurarNiebla(Datos d, int ep, int intento){
            if(ep != Streamer.epoca) return;
            Bits bits = Vars.fogControl == null ? null : Vars.fogControl.getDiscovered(Team.sharded);
            if(bits == null){
                if(intento < 40) Time.runTask(10f, () -> restaurarNiebla(d, ep, intento + 1));
                return;
            }
            if(d.nieblaW != Vars.world.width() || d.nieblaH != Vars.world.height()) return;
            int len = d.nieblaW * d.nieblaH, pos = 0;
            for(byte b : d.niebla){
                int v = b & 0xff;
                int consec = v & 0x7f;
                if((v & 0x80) != 0) bits.set(pos, Math.min(len, pos + consec));
                pos += consec;
                if(pos >= len) break;
            }
            // fuerza al renderizador a volver a copiar la niebla desde la CPU
            try{
                java.lang.reflect.Field f = Vars.renderer.fog.getClass().getDeclaredField("lastTeam");
                f.setAccessible(true);
                f.set(Vars.renderer.fog, null);
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo refrescar la niebla guardada");
            }
        }

        // ---------- guardado ----------
        static void guardar(boolean sincrono){
            if(actual == null || !Streamer.activo || Vars.world.width() < 10) return;
            float px = Streamer.jugadorX, py = Streamer.jugadorY;
            if(Vars.player != null && Vars.player.unit() != null && !Vars.player.dead()){
                px = Vars.player.unit().x / 8f;
                py = Vars.player.unit().y / 8f;
            }
            byte[] bytes = Datos.serializar(px, py);
            if(bytes == null) return;
            final Meta m = actual;
            final Dimension dim = m.dim;
            m.ultimo = System.currentTimeMillis();
            Runnable escribir = () -> {
                Datos.escribirArchivo(Almacen.archivoDim(m, dim), bytes);
                Almacen.guardarMeta(m);
            };
            if(sincrono) escribir.run();
            else Threads.daemon("MundoInfinito-Guardado", escribir);
        }

        // ---------- portales ----------
        static void viajarPorPortal(int jugadorX, int jugadorY){
            if(viajando || actual == null) return;
            viajando = true;
            Vars.ui.loadfrag.show("Cruzando el portal...");

            // 1-2) guardar estado y posición exacta de la dimensión actual
            Streamer.jugadorX = jugadorX;
            Streamer.jugadorY = jugadorY;
            guardar(true);

            Meta m = actual;
            Dimension destino = m.dim.otra();
            Fi f = Almacen.archivoDim(m, destino);
            Datos d = f.exists() ? Datos.leer(f) : null;
            int px = d != null ? (int)d.px : jugadorX;
            int py = d != null ? (int)d.py : jugadorY;

            // 3-6) limpiar, conmutar y regenerar con la MISMA semilla pero otro planeta
            abrir(m, destino, d, px, py);
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
                    fila.add("[accent]" + m.nombre + "[]\n" + m.dim.nombre() + " · " + m.tam + "x" + m.tam + " · " + hace(m.ultimo))
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

        /** Selector de origen: nombre, tamaño y planeta. */
        static void abrirNuevo(){
            BaseDialog d = new BaseDialog("Selector de Origen");
            final String[] nombre = {"Mundo " + (Almacen.listar().size + 1)};
            final int[] idx = {1};

            d.cont.add("Nombre del mundo").padBottom(6f).row();
            d.cont.field(nombre[0], t -> nombre[0] = t).width(320f).padBottom(14f).row();

            TextButton tamBtn = d.cont.button(NOMBRES_TAM[idx[0]], () -> {}).size(320f, 56f).padBottom(14f).get();
            tamBtn.clicked(() -> {
                idx[0] = (idx[0] + 1) % TAMANOS.length;
                tamBtn.setText(NOMBRES_TAM[idx[0]]);
            });
            d.cont.row();

            d.cont.add("Elige el planeta donde comienza tu aventura").padBottom(10f).row();
            d.cont.button("Serpulo", () -> { d.hide(); Mundos.crearNuevo(nombre[0], Dimension.SERPULO, TAMANOS[idx[0]]); }).size(320f, 64f).pad(6f).row();
            d.cont.button("Erekir", () -> { d.hide(); Mundos.crearNuevo(nombre[0], Dimension.EREKIR, TAMANOS[idx[0]]); }).size(320f, 64f).pad(6f).row();
            d.addCloseButton();
            d.show();
        }
    }
}
