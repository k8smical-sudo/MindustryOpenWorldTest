package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Cell;
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
import mindustry.gen.Icon;
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
    /**
     * Lado (en tiles) del anillo de casillas del motor (Tiles.ring): solo dimensiona arrays por casilla; NO es un límite del mundo ni
     * una ventana. Los chunks se cargan y se sueltan uno a uno (ver Anillo).
     */
    public static int ventana(){
        return Streamer.ANILLO_CHUNKS * TAM_CHUNK;
    }
    /** Borde del mundo virtual: el motor guarda Tile.x/y en un short (±32 768 tiles); se deja un margen de 768 tiles para la carga. */
    public static final int LIMITE_MUNDO = 32_000;
    public static final int REGION_CHUNKS = 16;             // 16x16 chunks por archivo de región (512x512 tiles)
    public static final String URL_SEMILLAS = "https://raw.githubusercontent.com/TU_USUARIO/mundo-infinito-semillas/main/semillas/actual.json"; // TODO: URL real
    public static final int TIMEOUT_HTTP_MS = 4000;
    public static final int GEN_ACTUAL = 4;                 // 4 = generación revisada (Parte A del plan): respiraderos con disco de Poisson, roca caliente y magma en Serpulo.
    public static final int GEN_ANTERIOR = 3;               // 3 = dos dimensiones (Serpulo/Erekir) con la generación anterior: conserva su terreno exacto.
    public static final int GEN_REVISADA = 4;               // primera generación que activa los generadores del mundo base (FenomenosBase)
    public static final int GEN_MEZCLADO = 2;               // 2 = terreno mezclado (mundos antiguos): sigue jugable tal cual.
    public static final int DIM_SERPULO = 0, DIM_EREKIR = 1;

    public MundoInfinitoMod(){
        // Generadores del propio mundo base: usan la misma API que los mods (Parte A del plan). Solo actúan en mundos de generación >= GEN_REVISADA.
        FenomenosBase.registrar();
        GameRulesBase.registrar();   // gamerules del mod base (misma API que las de otros mods)
        Visuales.registrar();   // dibuja el cuerpo de los bloques drawCached (puentes, muros, nodos...) que el modo anillo deja sin caché
        VisualArc.registrar();  // capa visual (fase 9): registro de dibujos, shaders y pasadas con calidad y aislamiento de fallos

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
        Events.on(ResetEvent.class, e -> { Streamer.activo = false; Vision.restaurar(); Disco.vaciar(20000); });
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
        /** Límite del terreno en tiles (ancho total X, alto total Y), centrado en el punto de aparición (0,0). 0 = infinito. */
        int limX, limY;
        /** true = mundo creado sin generador de enemigos ni estructuras. Los mundos antiguos (false) conservan su contenido guardado, salvo lo enemigo/abandonado que generaba el mod. */
        boolean limpio;
        /** Mods activos al crear el mundo (nombres separados por coma): permite avisar si falta alguno al volver a abrirlo. */
        String mods = "";
        /** Perfil del mundo (fase 4): el valor de cada ajuste (gamerules y parámetros de generación). Lo que falta usa el valor por defecto. */
        PerfilMundo perfil = new PerfilMundo();

        /** Valor de una gamerule en este mundo. */
        boolean gameRule(GameRule g){
            return perfil.bool(g.id, g.porDefecto);
        }

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
            j.put("limX", limX);
            j.put("limY", limY);
            j.put("limpio", limpio);
            j.put("mods", mods == null ? "" : mods);
            j.put("ajustes", perfil.aJson());
            j.put("gamerules", perfil.aJsonReglas());   // espejo para versiones anteriores del mod
            return j;
        }

        boolean limitado(){
            return limX > 0 || limY > 0;
        }

        // límites en TILES (inclusive). Integer.MIN_VALUE / MAX_VALUE = sin límite en ese eje.
        int minTX(){ return limX > 0 ? -(limX / 2) : Integer.MIN_VALUE; }
        int maxTX(){ return limX > 0 ? minTX() + limX - 1 : Integer.MAX_VALUE; }
        int minTY(){ return limY > 0 ? -(limY / 2) : Integer.MIN_VALUE; }
        int maxTY(){ return limY > 0 ? minTY() + limY - 1 : Integer.MAX_VALUE; }

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
            m.limX = Math.max(0, j.getInt("limX", 0));
            m.limY = Math.max(0, j.getInt("limY", 0));
            m.limpio = j.getBool("limpio", false);
            m.mods = j.getString("mods", "");
            m.perfil = PerfilMundo.desde(j);   // mundos antiguos: sin perfil = valores por defecto
            return m;
        }

        boolean compatible(){
            return gen == GEN_ACTUAL || gen == GEN_ANTERIOR || gen == GEN_MEZCLADO;
        }

        /** true = mundo con dos dimensiones separadas; false = mundo antiguo de terreno mezclado. */
        boolean dimensional(){
            return gen >= GEN_ANTERIOR;
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
            if(dimensional() && dim >= MundoRegistrado.DIM_MINIMA){
                final MundoRegistrado w = Extensiones.mundoPorDim(dim);
                final Block b = w == null ? null : Vars.content.block(w.nucleo);
                return b instanceof CoreBlock ? b : Blocks.coreShard;
            }
            if(dimensional()) return dim == DIM_SERPULO ? Blocks.coreShard : Blocks.coreBastion;
            int t = nucleo;
            if(t == 0) t = 1 + (Muestreo.sem(semilla, 77) & 1);
            return t == 1 ? Blocks.coreShard : Blocks.coreBastion;
        }

        String nombreDim(){
            if(dimensional() && dim >= MundoRegistrado.DIM_MINIMA){
                final MundoRegistrado w = Extensiones.mundoPorDim(dim);
                return w == null ? "Mundo desconocido" : w.nombre;
            }
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
            // Serpulo: pisos que antes no se usaban (suelos de vegetación y de roca volcánica, y las alfombras alienígenas)
            P_GRASS = 22, P_DIRT = 23, P_MUD = 24, P_DACITE = 25, P_CHARR = 26, P_SHALE = 27, P_REDMAT = 28, P_BLUEMAT = 29,
            P_REGO = 32, P_YELLOW = 33, P_RHYO = 34, P_CARBON = 35, P_CRYST = 36, P_CRYSTF = 37, P_BERYL = 38,
            P_ARKYIC = 39, P_ARKYCITE = 40, P_RED = 41, P_DENSERED = 42, P_REDICE = 43, P_SLAG = 44,
            P_YPLATES = 45, P_ROUGH = 46, P_RCRATER = 47, P_VRHYO = 48, P_VARKY = 49, P_VYELLOW = 50,
            P_VRED = 51, P_VCARBON = 52,
            // Erekir: pisos que antes no se usaban
            P_FERRIC = 53, P_FCRATER = 54, P_VCRYST = 55, P_CRYOFLUID = 56;
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
        static final float K_ALTURA = 2.3f, K_TEMP = 2.3f, K_TEMP_S = 3.1f, K_HUM_S = 2.7f;       // estiramiento del ruido a [0,1]
        static final float LIM_S = 0.522f, LIM_E = 0.522f;       // umbral base de muros (más alto = menos muros)
        static final float ANCHO_PASO = 0.018f;                  // anchura base de los pasillos que cortan los macizos
        static final float K_VENA = 0.42f;                       // grosor general de las vetas globales
        static final int STRIDE_DEP = 10, STRIDE_POZ = 6;
        // Dos orientaciones fijas para los macizos alargados (constantes: no se deforman lejos del origen)
        static final float CA = 0.9004f, SA = 0.4350f, CB = -0.5048f, SB = 0.8632f;

        // ---- Tabla de biomas Serpulo (copiada de la generación de campaña: filas = temperatura, columnas = altura) ----
        /**
         * Diagrama de Whittaker de Serpulo: filas = temperatura (0 helado -> 7 abrasador), columnas = humedad (0 árido -> 7 pantano).
         * Cada casilla de la tabla es compatible con sus 8 vecinas (todo par que se toca existe en la campaña: nieve con piedra, arena
         * con hierba por la tierra, musgo con esporas, carbonizado con basalto...), así que al difuminar la frontera entre celdas
         * con ruido nunca aparece un cambio brusco de bioma. La altura enfría y seca (la montaña sube de fila).
         */
        static final int[][] CLIMA_S = {
            {P_SNOW,  P_SNOW,  P_ICESNOW, P_ICESNOW, P_ICE,      P_ICE,      P_BLUEMAT, P_BLUEMAT},
            {P_STONE, P_SNOW,  P_SNOW,    P_ICESNOW, P_ICESNOW,  P_ICESNOW,  P_BLUEMAT, P_MOSS},
            {P_STONE, P_SHALE, P_SNOW,    P_SNOW,    P_DIRT,     P_MOSS,     P_MOSS,    P_BLUEMAT},
            {P_DACITE, P_SHALE, P_DIRT,   P_GRASS,   P_GRASS,    P_MOSS,     P_MOSS,    P_MOSS},
            {P_SHALE, P_SAND,  P_DIRT,    P_GRASS,   P_GRASS,    P_MOSS,     P_MUD,     P_SPORE},
            {P_SAND,  P_SAND,  P_SAND,    P_DIRT,    P_DIRT,     P_MOSS,     P_SPORE,   P_SPORE},
            {P_SAND,  P_SAND,  P_DARKSAND, P_DARKSAND, P_DARKSAND, P_DARKSAND, P_REDMAT, P_REDMAT},
            {P_DARKSAND, P_DARKSAND, P_DARKSAND, P_CHARR, P_BASALT, P_CHARR, P_REDMAT, P_REDMAT}
        };
        // Los 3 respiraderos de un pozo de respiraderos: desplazamientos ENTEROS del centro (bloques de 3x3 separados, nunca solapados)
        static final int[][] VENTS_POZO = {{0, 0}, {4, 1}, {-3, -3}};   // generaciones anteriores a GEN_REVISADA

        /**
         * Respiraderos de un pozo de respiraderos, generación revisada. Entre 2 y 4 por pozo (según semilla y pozo), con ángulos IRREGULARES
         * (huecos angulares aleatorios, no 120° fijos) y radios propios de cada uno, de modo que no formen figuras regulares. Separación
         * mínima entre bloques 3x3 de 4 casillas (Chebyshev). Si son tres y casi equiláteros, se aleja uno: el pozo nunca sale como triángulo.
         */
        static int[][] ventsPozo(int s, int k, float r){
            final float tau = 6.2831853f;
            final int n = 2 + (int)(rnd(k, 40, sem(s, 8899)) * 3f);                 // 2, 3 o 4 respiraderos
            final float a0 = rnd(k, 41, sem(s, 8900)) * tau;
            final float[] hueco = new float[n];
            float tot = 0f;
            for(int i = 0; i < n; i++){
                hueco[i] = 0.45f + 1.1f * rnd(k, 44 + i, sem(s, 8903));              // huecos angulares irregulares
                tot += hueco[i];
            }
            final int[][] o = new int[n][2];
            final float extra = Math.max(0f, r - 6.5f) * 0.35f;                      // pozos grandes tienen respiraderos más separados
            float a = a0;
            for(int i = 0; i < n; i++){
                float rho = 3.5f + 3.5f * rnd(k, 50 + i, sem(s, 8904)) + extra * (0.5f + rnd(k, 56 + i, sem(s, 8905)));
                for(int rep = 0; rep < 4; rep++){
                    o[i][0] = Math.round((float)Math.cos(a) * rho);
                    o[i][1] = Math.round((float)Math.sin(a) * rho);
                    boolean ok = true;
                    for(int q = 0; q < i; q++){
                        if(Math.max(Math.abs(o[i][0] - o[q][0]), Math.abs(o[i][1] - o[q][1])) < 4){ ok = false; break; }
                    }
                    if(ok) break;
                    rho += 3f;
                }
                a += hueco[i] / tot * tau;
            }
            if(n == 3){
                final double d01 = Math.hypot(o[0][0] - o[1][0], o[0][1] - o[1][1]);
                final double d12 = Math.hypot(o[1][0] - o[2][0], o[1][1] - o[2][1]);
                final double d02 = Math.hypot(o[0][0] - o[2][0], o[0][1] - o[2][1]);
                final double mx = Math.max(d01, Math.max(d12, d02)), mn = Math.min(d01, Math.min(d12, d02));
                if(mn > 0 && mx / mn < 1.3){                                          // casi equilátero: se aleja uno
                    o[2][0] = Math.round(o[2][0] * 1.5f);
                    o[2][1] = Math.round(o[2][1] * 1.5f);
                }
            }
            return o;
        }

        // Terreno base de Erekir (campaña): regolito domina, luego yellowStone, riolita y carbón.
        /** Terreno base de Erekir según la altura (de bajo a alto). El suelo ferroso (ferricStone) llena el escalón entre amarillo y riolita. */
        static final int[] TERRENO_E = {P_REGO, P_REGO, P_REGO, P_YELLOW, P_YELLOW, P_FERRIC, P_RHYO, P_RHYO, P_ROUGH, P_CARBON};

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
                || p == P_ARKYCITE || p == P_SLAG || p == P_TAR || p == P_CRYOFLUID;
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
            // ---- arquetipo de arranque: cada semilla elige la forma del claro, un bioma local propio y cómo se reparten los recursos ----
            int arq;                 // disposición de los recursos: 0 anillo, 1 abanico, 2 dos lados, 3 eje, 4 compacto
            boolean ere0;            // el origen es de tipo Erekir (solo importa en mundos mezclados)
            float razonClaro = 1f;   // razón entre ejes del claro inicial (< 1 = alargado)
            float cClaro = 1f, sClaro = 0f;   // coseno y seno de la orientación del claro
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

            // Claro inicial: ya no es siempre un círculo; puede ser alargado y orientado al azar
            sp.razonClaro = 0.70f + 0.30f * rnd(31, 1, sem(s, 9060));
            float rotClaro = rnd(31, 2, sem(s, 9061)) * 3.1415927f;
            sp.cClaro = (float)Math.cos(rotClaro);
            sp.sClaro = (float)Math.sin(rotClaro);
            // Bioma de arranque: casi siempre uno propio (de los que la campaña mezcla), a veces el natural del origen
            sp.ere0 = esE(s, 0.0, 0.0, sp);
            // (el claro inicial ya no impone un bioma propio: antes una zona de ~70 casillas con un suelo fijo formaba un corte brusco con el
            //  terreno natural; ahora el spawn es terreno natural y solo se le quita lo peligroso)

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
            // disposición: anillo (clásica), abanico (todo hacia un lado), dos lados opuestos, eje (una línea a cada lado) o compacto
            sp.arq = Math.min(4, (int)(rnd(21, 21, sem(s, 9050)) * 5f));
            float escD = sp.arq == 4 ? 0.80f : 0.88f + 0.27f * rnd(21, 22, sem(s, 9051));
            float minD = sp.radioLibre * 1.2f + 5f;   // nunca dentro del claro inicial
            float[] px = new float[n], py = new float[n], pr = new float[n], hl = new float[n], hw = new float[n], cv = new float[n], rm = new float[n];
            int[] morfs = new int[n];
            float[] par = new float[3];
            for(int k = 0; k < n; k++){
                float[] c = cosas.get(k);
                float jit = rnd(k, 8, sem(s, 9032)) - 0.5f;
                float ang;
                switch(sp.arq){
                    case 1: ang = a0 + (n > 1 ? (k / (float)(n - 1) - 0.5f) * 3.6f : 0f) + jit * 0.35f; break;
                    case 2: ang = a0 + (k % 2 == 0 ? 0f : 3.1415927f) + ((k / 2) / (float)Math.max(1, n / 2) - 0.5f) * 2.3f + jit * 0.3f; break;
                    case 3: ang = a0 + (k % 2 == 0 ? 0f : 3.1415927f) + jit * 1.1f; break;
                    default: ang = a0 + (k + jit * 0.7f) * (6.2831853f / n);
                }
                float d = Math.max(minD, (c[2] + rnd(k, 9, sem(s, 9033)) * (c[3] - c[2])) * escD);
                rm[k] = c[3] * escD * 1.2f + 10f;   // tope de lo que el anti-solape puede alejarlo: lo esencial nunca se va lejos
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
                        if(rj * k2 > rm[j]){
                            // ya no se aleja más: se gira alrededor del spawn para buscar hueco (así no queda tapada por otra veta)
                            float cr = 0.8776f, sr = 0.4794f;   // 0,5 rad
                            float nx = px[j] * cr - py[j] * sr;
                            py[j] = px[j] * sr + py[j] * cr;
                            px[j] = nx;
                            continue;
                        }
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
                    // provincias minerales: cada tipo de mena es más o menos común según la región (celdas de 480 tiles), así hay zonas
                    // ricas en cobre, cinturones de carbón, etc., en vez de la misma mezcla uniforme en todas partes
                    final int pvi = Math.floorDiv((int)cx0, 480), pvj = Math.floorDiv((int)cy0, 480);
                    float tot = 0f;
                    for(float[] g : tabla) if(dsp >= g[1]) tot += g[2] * (0.30f + 1.55f * rnd(pvi, pvj, sem(s, 7100 + (int)g[0])));
                    if(tot <= 0f) continue;
                    float pick = rnd(i, j, sem(s, 7003)) * tot, acc = 0f;
                    int tipo = (int)tabla[0][0];
                    for(float[] g : tabla){
                        if(dsp < g[1]) continue;
                        acc += g[2] * (0.30f + 1.55f * rnd(pvi, pvj, sem(s, 7100 + (int)g[0])));
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

        static final long SIN_VENT = Long.MIN_VALUE;

        /**
         * ¿(x, y) cae dentro del bloque 3x3 de un respiradero de cráter? @return el centro empaquetado (x en los 32 bits altos, y en los bajos)
         * o SIN_VENT. Los centros salen de una celda de 72 casillas y están a >= 28 de distancia: los bloques nunca se tocan.
         */
        static long ventCentro(int s, int x, int y){
            int ci = Math.floorDiv(x, 72), cj = Math.floorDiv(y, 72);
            for(int di = -1; di <= 1; di++){
                for(int dj = -1; dj <= 1; dj++){
                    int i = ci + di, j = cj + dj;
                    if(rnd(i, j, sem(s, 6001)) > 0.30f) continue;
                    int px = i * 72 + 14 + (int)(rnd(i, j, sem(s, 6002)) * 44f), py = j * 72 + 14 + (int)(rnd(i, j, sem(s, 6003)) * 44f);
                    if(Math.abs(x - px) <= 1 && Math.abs(y - py) <= 1) return ((long)px << 32) | (py & 0xffffffffL);
                }
            }
            return SIN_VENT;
        }

        /**
         * Piso de respiradero que le toca a TODAS las casillas del bloque 3x3 centrado en (px, py), o -1 si ahí no puede haber uno.
         * Se decide una sola vez con el centro (antes cada casilla elegía el suyo y el bloque salía partido: sin respiradero o con varios).
         */
        static int ventPisoCentro(int s, int px, int py, Spawn sp){
            float dist = (float)Math.sqrt((double)px * px + (double)py * py);
            if(dist < Math.max(40f, sp.radioLibre * 1.8f)) return -1;
            if(!esE(s, px, py, sp)) return -1;
            double wx = px + (fbm(sem(s, 50), 90f, 3, px, py) - 0.5) * 70.0;
            double wy = py + (fbm(sem(s, 51), 90f, 3, px + 500.0, py + 500.0) - 0.5) * 70.0;
            int p = pisoBase(s, px, py, dist, wx, wy, true, sp);
            switch(p){
                // solo sobre suelos "lisos" de Erekir; en cristal, hielo rojo o líquidos un respiradero no pega con el bioma
                case P_RHYO: case P_ROUGH: case P_BERYL: case P_ARKYIC: case P_YELLOW: case P_REGO: case P_RED: case P_DENSERED: case P_CARBON:
                    return ventDe(p);
                default:
                    return -1;
            }
        }

        /** Piso base del bioma (con el bioma de arranque aplicado cerca del origen). Única fuente de verdad: muestrear() y los respiraderos la comparten. */
        static int pisoBase(int s, int x, int y, float dist, double wx, double wy, boolean ere, Spawn sp){
            int p = ere ? pisoErekir(s, x, y, dist, wx, wy) : pisoSerpulo(s, x, y, dist, wx, wy);
            return p;
        }

        /** Los cráteres de impacto de Serpulo solo existen en suelos secos de roca, arena o musgo (no en nieve, hielo ni agua). */
        static boolean craterBioma(int p){
            return p == P_SAND || p == P_DARKSAND || p == P_SALT || p == P_STONE || p == P_BASALT || p == P_MOSS || p == P_SPORE
                || p == P_DIRT || p == P_GRASS || p == P_DACITE || p == P_SHALE || p == P_CHARR;
        }

        /** Piso seco que sustituye a un líquido dentro del claro inicial, acorde al líquido que había. */
        static int secoDe(int p){
            return (p == P_DSW || p == P_DTW || p == P_DEEPT || p == P_TW || p == P_TAR) ? P_DARKSAND : (p == P_ICE ? P_SNOW : P_SAND);
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

        // ---- Pisos base ----
        /** Suelo sin agua en un punto: tabla climática (temperatura x humedad) con la altura enfriando y secando. */
        static int climaSerpulo(int s, int x, int y, double wx, double wy, float h, float t, float m){
            // la altura enfría y seca: la montaña "sube de fila" (nieve en cumbres templadas, roca en las secas)
            float alt = Math.max(0f, h - 0.45f);
            float tt = t - 0.55f * alt, mm = m - 0.45f * alt;
            // difuminado: una perturbación SUAVE y de gran tamaño mueve la frontera entre celdas de la tabla; al ser todas
            // las celdas vecinas compatibles, la mezcla se ve natural (nunca "sal y pimienta" de pisos sin relación)
            float eu = fbm(sem(s, 15), 46f, 2, x, y) - 0.5f, ev = fbm(sem(s, 16), 52f, 2, x + 700.0, y - 400.0) - 0.5f;
            float u = Mathf.clamp(tt, 0f, 0.9999f) * 8f + eu * 1.5f, v = Mathf.clamp(mm, 0f, 0.9999f) * 8f + ev * 1.5f;
            int i = Mathf.clamp((int)Math.floor(u), 0, 7), j = Mathf.clamp((int)Math.floor(v), 0, 7);
            return CLIMA_S[i][j];
        }

        static int pisoSerpulo(int s, int x, int y, float dist, double wx, double wy){
            // campos LENTOS (relieve, temperatura, humedad): los biomas ocupan cientos de casillas
            float hA = fbm(sem(s, 1), 190f, 4, wx, wy);
            float hB = fbm(sem(s, 2), 100f, 3, wx, wy);
            float wh = suave((fbm(sem(s, 14), 700f, 2, x, y) - 0.35f) * 3.3f);
            float h = est(lerp(hA, hB, wh * 0.60f), K_ALTURA);
            float t = est(fbm(sem(s, 11), 520f, 3, wx, wy), K_TEMP_S);       // más estirado que antes: todas las filas de la tabla tienen su zona
            float m = est(fbm(sem(s, 12), 430f, 3, wx + 900.0, wy - 300.0), K_HUM_S);

            int p = climaSerpulo(s, x, y, wx, wy, h, t, m);
            final float alt = Math.max(0f, h - 0.45f);
            final float tt = t - 0.55f * alt, mm = m - 0.45f * alt;

            // ---- salinas: manchas amplias de sal en la arena ----
            if(p == P_SAND && fbm(sem(s, 90), 90f, 2, x, y) > 0.70f) p = P_SALT;

            // ---- campos volcánicos: núcleo de magma rodeado de roca caliente, sobre basalto o carbonizado (zonas muy cálidas) ----
            if((p == P_BASALT || p == P_CHARR) && tt > 0.55f){
                float vol = fbm(sem(s, 43), 120f, 3, x, y);
                if(vol > 0.715f) p = P_MAGMA;
                else if(vol > 0.645f) p = P_HOT;
            }

            // ---- brea: pozas en suelo oscuro de zonas cálidas ----
            if(dist > 40f && (p == P_DARKSAND || p == P_CHARR) && tt > 0.5f){
                if(fbm(sem(s, 41), 110f, 3, x, y) > 0.715f) p = P_TAR;
            }

            // ---- agua: océanos (relieve bajo) y lagos interiores, con anillos de profundidad y orilla ----
            float agua = suave((0.215f - h) / 0.215f);                          // 0 tierra .. 1 mar abierto
            if(dist > 60f){
                float lk = fbm(sem(s, 44), 210f, 4, x, y);
                agua = Math.max(agua, Mathf.clamp((lk - 0.62f) / 0.12f, 0f, 1f) * 0.78f);   // los lagos nunca llegan a "mar profundo"
            }
            final boolean manchada = tt > 0.58f && mm > 0.42f;                   // agua cenagosa (tainted) en zonas cálidas y húmedas
            final boolean fria = p == P_SNOW || p == P_ICESNOW || p == P_ICE || p == P_BLUEMAT;   // la costa depende del suelo que rodea el agua
            if(agua > 0.10f){
                final boolean arenoso = p == P_SAND || p == P_SALT || p == P_DACITE || p == P_SHALE || p == P_STONE;
                if(agua > 0.80f) p = manchada ? P_DEEPT : P_DEEP;
                else if(agua > 0.34f) p = manchada ? P_TW : P_WATER;
                else if(manchada) p = P_DTW;
                else if(fria) p = P_ICE;                                          // costa helada: la orilla de un lago frío es hielo
                else p = arenoso ? P_SANDW : P_DSW;
            }else if(agua > 0.025f){
                // PLAYA: franja de tierra junto al agua con un suelo que combina con la orilla (barro en la vegetación, arena oscura en la roca)
                if(p == P_GRASS || p == P_DIRT || p == P_MOSS || p == P_SPORE || p == P_MUD) p = tt < 0.38f ? P_DIRT : P_MUD;
                else if(p == P_BASALT || p == P_CHARR || p == P_REDMAT || p == P_HOT || p == P_MAGMA || p == P_TAR) p = P_DARKSAND;
                else if(p == P_BLUEMAT) p = P_ICESNOW;
            }
            return p;
        }

        /** Terreno de Erekir: temperatura -> cristal (frío) ... carbón ... terreno por altura ... regolito ... piedra roja (cálido), con rampas. */
        static int pisoErekir(int s, int x, int y, float dist, double wx, double wy){
            float hA = fbm(sem(s, 1), 170f, 4, wx, wy);
            float hB = fbm(sem(s, 2), 90f, 3, wx, wy);
            float wh = suave((fbm(sem(s, 14), 700f, 2, x, y) - 0.35f) * 3.3f);
            float h = est(lerp(hA, hB, wh * 0.60f), K_ALTURA);
            h = Mathf.clamp(h * 1.05f, 0f, 0.9999f);
            int p = TERRENO_E[Mathf.clamp((int)(h * TERRENO_E.length), 0, TERRENO_E.length - 1)];

            // borde natural: una sola perturbación suave (unas 5 casillas)
            final float jit = (fbm(sem(s, 70), 22f, 2, x, y) - 0.5f) * 0.06f;
            // temperatura de Erekir: cuanto más baja, más cristalino
            final float c = est(fbm(sem(s, 61), 230f, 3, wx, wy), 2.0f) + jit;

            // campos de las zonas especiales (se calculan siempre: las rampas de unas excluyen a las otras)
            final float b = fbm(sem(s, 63), 200f, 3, wx, wy) + jit;
            final float r = fbm(sem(s, 66), 250f, 3, wx, wy) + jit;
            final float sl = fbm(sem(s, 68), 270f, 3, x, y);

            if(c < 0.30f){
                // ZONA CRISTALINA: núcleo de crystalFloor, anillo de crystallineStone y, en el hueco más frío, lagos de criofluido
                float cf = fbm(sem(s, 62), 55f, 3, x, y);
                p = (c < 0.19f && cf < 0.52f) || (c < 0.26f && cf < 0.34f) ? P_CRYSTF : P_CRYST;   // el borde exterior es siempre crystallineStone
                if(c < 0.23f && fbm(sem(s, 71), 120f, 3, x, y) > 0.69f) p = P_CRYOFLUID;
            }else if(c < 0.40f){
                // rampa de carbón entre el cristal y el terreno normal (vanilla: el lado frío es carbonStone)
                p = P_CARBON;
            }else{
                if(c < 0.46f) p = (p == P_ROUGH) ? p : P_RHYO;                     // rampa de riolita: carbón -> riolita -> terreno
                else if(p == P_CARBON) p = P_RHYO;                                  // el carbón de altura solo vive pegado al cristal

                // las zonas especiales APARECEN poco a poco al alejarse de la rampa: su umbral baja con 'fade', así que siempre hay una
                // franja de transición entre la riolita de la rampa y el corazón de la zona (nunca un corte directo)
                final float fade = suave((c - 0.42f) / 0.14f);
                final float bb = b - (1f - fade) * 0.20f, rr = r - (1f - fade) * 0.20f, ss = sl - (1f - fade) * 0.20f;

                if(bb > 0.53f){
                    // BERÍLICA: franja amarilla, piedra berílica y, DENTRO, vetas de arkyic y pozas de arkycite (con su anillo de arkyic)
                    p = P_YELLOW;
                    if(bb > 0.60f){
                        p = P_BERYL;
                        if(bb > 0.635f){
                            if(Math.abs(fbm(sem(s, 64), 46f, 3, x, y) - 0.5f) < 0.035f) p = P_ARKYIC;
                            float ar = fbm(sem(s, 65), 120f, 3, x, y);
                            if(ar > 0.665f) p = P_ARKYCITE;
                            else if(ar > 0.615f) p = P_ARKYIC;
                        }
                    }
                }else if(rr > 0.54f){
                    // ZONA ROJA: franja de regolito, piedra roja y, en el núcleo, roja densa y hielo rojo
                    p = P_REGO;
                    if(rr > 0.615f){
                        p = P_RED;
                        if(rr > 0.665f && fbm(sem(s, 67), 26f, 3, x, y) > 0.55f) p = P_DENSERED;
                        if(rr > 0.72f) p = P_REDICE;
                    }
                }else if(dist > 45f && bb < 0.48f && rr < 0.50f){
                    // LAGOS DE ESCORIA lejos de las otras zonas, con anillo de placas amarillas y luego amarillo (vanilla: blend(slag, yellowStonePlates))
                    if(ss > 0.60f) p = P_SLAG;
                    else if(ss > 0.56f) p = P_YPLATES;
                    else if(ss > 0.50f) p = P_YELLOW;
                }
            }
            if((p == P_RHYO) && fbm(sem(s, 69), 70f, 4, x, y) < 0.38f) p = P_ROUGH;
            return p;
        }

        // ---- Tipo de piso a partir del piso local para cráteres de Erekir ----
        static int cratereDe(int p){
            if(p == P_RHYO || p == P_ROUGH) return P_RCRATER;
            if(p == P_FERRIC) return P_FCRATER;
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
            if(p == P_CRYST || p == P_CRYSTF) return P_VCRYST;
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

        /**
         * ¿Está (x, y) ENTERRADO en roca gruesa? Sondea una malla de puntos (cada 3 casillas) en [-r, r] alrededor: si TODOS son pared
         * ahí no cabe nada útil, ni se ve (la oscuridad de los muros llega a 4 casillas de profundidad). Pura: solo depende de
         * (s, x, y, sp), así que da lo mismo desde cualquier hilo y en cualquier momento.
         */
        static boolean enterrado(int s, int x, int y, Spawn sp, int r){
            for(int dy = -r; dy <= r; dy += 3){
                for(int dx = -r; dx <= r; dx += 3){
                    if(!solidaAprox(s, x + dx, y + dy, sp)) return false;
                }
            }
            return true;
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
            // Generación revisada (GEN_REVISADA): los respiraderos de Erekir y la roca caliente los ponen FenomenosBase (disco de Poisson);
            // los mundos anteriores conservan los respiraderos en rejilla y el pozo de respiraderos fijo, para no cambiar su terreno guardado.
            final boolean nueva = Extensiones.generacionNueva();
            double dx0 = x, dy0 = y;
            float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
            double wx = x + (fbm(sem(s, 50), 90f, 3, x, y) - 0.5) * 70.0;
            double wy = y + (fbm(sem(s, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;

            boolean ere = esE(s, x, y, sp);
            int p = pisoBase(s, x, y, dist, wx, wy, ere, sp);
            // claro inicial: elipse orientada (no siempre un círculo) con borde irregular
            float ecx = (float)(dx0 * sp.cClaro + dy0 * sp.sClaro), ecy = (float)(-dx0 * sp.sClaro + dy0 * sp.cClaro) / sp.razonClaro;
            float dClaro = (float)Math.sqrt(ecx * ecx + ecy * ecy);
            boolean spawn = dClaro < sp.radioLibre * (0.80f + 0.50f * fbm(sem(s, 9006), 14f, 2, x, y));
            if(spawn){
                // el spawn conserva el bioma que le toque; solo se quita lo peligroso o intransitable
                if(liquido(p)) p = ere ? P_REGO : secoDe(p);
                if(p == P_MAGMA || p == P_SLAG || p == P_TAR || p == P_HOT) p = ere ? P_YELLOW : P_BASALT;
            }

            // (ya no hay estructuras generadas por el mod: el terreno nunca se despeja para bases ni ruinas)
            final int hue = 0;

            // --- respiradero de cráter: el bloque 3x3 entero toma el mismo piso (decidido con su centro) ---
            int ventP = -1;
            if(hue == 0 && !nueva){
                long vc = ventCentro(s, x, y);
                if(vc != SIN_VENT) ventP = ventPisoCentro(s, (int)(vc >> 32), (int)vc, sp);
            }

            // --- pozos garantizados cerca del spawn (elípticos, orientados al azar) ---
            int pzPiso = -1;
            boolean pzLibre = false;
            float[] poz = sp.poz;
            if(hue == 0){
                for(int k = 0; k < poz.length; k += STRIDE_POZ){
                    float ddx = (float)(dx0 - poz[k + 1]), ddy = (float)(dy0 - poz[k + 2]), r = poz[k + 3];
                    if(ddx * ddx + ddy * ddy > r * r * 3.4f) continue;
                    if((int)poz[k] == 3){
                        // pozo de respiraderos: 3 bloques de 3x3 EXACTOS sobre casillas enteras (antes se comparaban floats contra un centro
                        // fraccionario y salían bloques de 3x3, 3x4 o 4x4: un sprite por cada 3x3 completo = grietas repetidas)
                        int bx = Math.round(poz[k + 1]), by = Math.round(poz[k + 2]);
                        boolean ev = false;
                        for(int[] o : (nueva ? ventsPozo(s, k, r) : VENTS_POZO)){
                            if(Math.abs(x - (bx + o[0])) <= 1 && Math.abs(y - (by + o[1])) <= 1){ ev = true; break; }
                        }
                        if(ev){ pzPiso = P_VRHYO; pzLibre = true; break; }
                    }
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
                        case 3: pzPiso = P_RCRATER; break;   // los 3 respiraderos se colocan arriba, con casillas enteras
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
            if(ventP >= 0){
                p = ventP;                  // bloque 3x3 de respiradero completo, sin muros, menas ni rocas encima
                enCrater = true;
            }else if(enPozo || pzLibre){
                enCrater = true;
            }else if(hue > 0){
                enCrater = false;
            }else if(!ere){
                int c = craterBioma(p) ? crater(s, x, y, dist) : 0;
                if(c == 2){
                    if(seco(p)) p = (p == P_GRASS || p == P_DIRT || p == P_MOSS || p == P_SPORE || p == P_MUD) ? P_DIRT : P_BASALT;   // borde de impacto
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
                int c = nueva ? 0 : craterVent(s, x, y, dist);
                if(c == 2){
                    if(seco(p)) p = cratereDe(p);   // el centro 3x3 sin respiradero válido queda como suelo de cráter
                    enCrater = true;
                }else if(c == 1){
                    if(seco(p)) p = cratereDe(p);
                    enCrater = true;
                }
            }

            boolean liq = liquido(p);

            // --- vetas de menas ---
            int dep = (enPozo || pzLibre || hue > 0 || ventP >= 0 || enCrater && mena != 0) ? -1 : enDeposito(s, x, y, sp, aux, ere, dist);
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
            if(muro == 0 && mena == 0 && !liq && !spawn && !enPozo && !pzLibre && hue == 0 && ventP < 0){
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
            piso(p, Muestreo.P_GRASS, Blocks.grass);
            piso(p, Muestreo.P_DIRT, Blocks.dirt);
            piso(p, Muestreo.P_MUD, Blocks.mud);
            piso(p, Muestreo.P_DACITE, Blocks.dacite);
            piso(p, Muestreo.P_CHARR, Blocks.charr);
            piso(p, Muestreo.P_SHALE, Blocks.shale);
            piso(p, Muestreo.P_REDMAT, Blocks.redmat);
            piso(p, Muestreo.P_BLUEMAT, Blocks.bluemat);

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
            piso(p, Muestreo.P_FERRIC, Blocks.ferricStone);
            piso(p, Muestreo.P_FCRATER, Blocks.ferricCraters);
            piso(p, Muestreo.P_VCRYST, Blocks.crystallineVent);
            piso(p, Muestreo.P_CRYOFLUID, Blocks.cryofluid);

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
        /** Casillas ya construidas (hilo de fondo) para publicar en el anillo del motor; null una vez publicadas. Ver Anillo.construir. */
        Tile[] tiles;
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
            tiles = null;
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
            final int vx0 = ox + cx * TAM_CHUNK, vy0 = oy + cy * TAM_CHUNK;
            final int vcx = Math.floorDiv(vx0, TAM_CHUNK), vcy = Math.floorDiv(vy0, TAM_CHUNK);
            // ¿el chunk toca el borde del mundo? Los generadores añadidos no se ejecutan ahí (no deben tocar el muro del borde)
            final boolean tocaBorde = vx0 < -LIMITE_MUNDO || vx0 + TAM_CHUNK - 1 > LIMITE_MUNDO || vy0 < -LIMITE_MUNDO || vy0 + TAM_CHUNK - 1 > LIMITE_MUNDO;
            int sd = Muestreo.semDim(semilla, dim);
            int bordePiso = pal.idPiso[Muestreo.P_STONE], bordeMuro = pal.idMuroDePiso[Muestreo.P_STONE];
            if(dim >= MundoRegistrado.DIM_MINIMA){
                // Mundo registrado por un mod: su generador rellena el chunk entero (no hay terreno base que muestrear)
                if(!tocaBorde){
                    Extensiones.generarMundo(d, pal, semilla, dim, vcx, vcy);
                    Extensiones.aplicarTerreno(d, pal, semilla, dim, vcx, vcy);
                }else{
                    Extensiones.generarMundo(d, pal, semilla, dim, vcx, vcy);
                    for(int ly = 0; ly < TAM_CHUNK; ly++){
                        for(int lx = 0; lx < TAM_CHUNK; lx++){
                            final int vx = vx0 + lx, vy = vy0 + ly;
                            if(vx < -LIMITE_MUNDO || vx > LIMITE_MUNDO || vy < -LIMITE_MUNDO || vy > LIMITE_MUNDO){
                                final int i = ly * TAM_CHUNK + lx;
                                d.piso[i] = (short)bordePiso;
                                d.mena[i] = 0;
                                d.bloque[i] = (short)bordeMuro;
                            }
                        }
                    }
                }
                return d;
            }
            // El terreno base se pide al núcleo (Nucleo.terreno): mismas casillas, mismo orden y mismo resultado que el bucle que había aquí.
            // El aporte trae códigos de Muestreo; aquí solo se traducen a ids de bloque con la paleta y se pone el muro del borde del mundo.
            final AporteTerreno ap = Nucleo.terreno(semilla, dim, ox + cx * TAM_CHUNK, oy + cy * TAM_CHUNK, vcx, vcy);
            for(int i = 0; i < AporteTerreno.N; i++){
                final int pc = ap.piso[i];
                if(pc == AporteTerreno.FUERA){
                    // más allá del borde del mundo: muro sólido, como el world border de Minecraft
                    d.piso[i] = (short)bordePiso;
                    d.mena[i] = 0;
                    d.bloque[i] = (short)bordeMuro;
                    continue;
                }
                d.piso[i] = (short)pal.idPiso[pc];
                d.mena[i] = (short)pal.idMena[ap.mena[i]];
                final int mu = ap.muro[i], pr = ap.prop[i];
                int b = 0;
                if(mu == 1) b = pal.idMuroDePiso[pc];
                else if(mu == 2) b = pal.idGrafito;
                else if(pr == 1) b = pal.idPropDePiso[pc];
                else if(pr == 2) b = pal.idCristal;
                else if(pr == 3) b = pal.idCristalVivo;
                d.bloque[i] = (short)b;
            }
            // Fenómenos, terreno de mods y escuchas (hilo de fondo, sobre el terreno base ya completo del chunk)
            if(!tocaBorde) Extensiones.aplicarTerreno(d, pal, semilla, dim, vcx, vcy);
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
            /** Datos propios de los mods (AlmacenModulo), por id de módulo. null = ninguno. Los arrays nunca se modifican en sitio. */
            java.util.TreeMap<String, byte[]> modulos;
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

        /**
         * ¿Está la región de este chunk virtual en memoria (o se sabe que no tiene archivo)? Si no, pide su lectura en el hilo
         * de E/S y devuelve false: el llamador lo reintenta luego. Leer un archivo en el hilo del juego congelaba el juego.
         */
        static boolean lista(int vcx, int vcy){
            int rx = Math.floorDiv(vcx, REGION_CHUNKS), ry = Math.floorDiv(vcy, REGION_CHUNKS);
            long key = k(rx, ry);
            if(regiones.containsKey(key)) return true;
            if(meta == null || enVuelo.contains(key)) return false;
            if(!archivo(rx, ry).exists()){
                regiones.put(key, new Region(rx, ry));
                return true;
            }
            precargar(rx, ry);
            return false;
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
                    if(ver >= 7){
                        int nm = in.readInt();
                        if(nm > 0){
                            c.modulos = new java.util.TreeMap<>();
                            for(int j = 0; j < nm; j++){
                                String id = in.readUTF();
                                byte[] b = new byte[in.readInt()];
                                in.readFully(b);
                                c.modulos.put(id, b);
                            }
                        }
                    }
                    r.chunks.put(idx, c);
                }
                in.close();
            }
        }

        /**
         * Copia SUPERFICIAL (solo referencias) de un chunk guardado. Reg y RegU no se modifican una vez creados (solo se añaden o se
         * quitan de las listas), así que el hilo de E/S puede serializarlos mientras el juego sigue mutando las listas originales.
         */
        static final class InstantaneaChunk{
            int idx;
            Reg[] edificios;
            Estructuras.RegU[] unidades;
            int[] niebla;
            double tick;
            java.util.Map<String, byte[]> modulos;
        }

        /** Hilo del juego: barato (copia de referencias), sin escribir ni un byte. */
        static java.util.ArrayList<InstantaneaChunk> instantanea(Region r){
            java.util.ArrayList<InstantaneaChunk> lista = new java.util.ArrayList<>(r.chunks.size());
            for(java.util.Map.Entry<Integer, ChunkGuardado> e : r.chunks.entrySet()){
                ChunkGuardado c = e.getValue();
                InstantaneaChunk x = new InstantaneaChunk();
                x.idx = e.getKey();
                x.edificios = new Reg[c.edificios.size];
                for(int i = 0; i < x.edificios.length; i++) x.edificios[i] = c.edificios.get(i);
                x.unidades = new Estructuras.RegU[c.unidades.size];
                for(int i = 0; i < x.unidades.length; i++) x.unidades[i] = c.unidades.get(i);
                x.niebla = c.niebla;   // se reemplaza entero, nunca se modifica en sitio
                x.tick = c.tick;
                x.modulos = c.modulos == null ? null : new java.util.TreeMap<>(c.modulos);   // copia de referencias: el juego sustituye arrays, no los modifica
                lista.add(x);
            }
            return lista;
        }

        /** Serializa una instantánea (SIN comprimir: lo comprime el hilo de E/S). null = región vacía => se borra el archivo. */
        static byte[] bytes(java.util.List<InstantaneaChunk> lista){
            if(lista.isEmpty()) return null;
            try{
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bos);
                out.writeInt(7);
                out.writeInt(lista.size());
                for(InstantaneaChunk c : lista){
                    out.writeShort(c.idx);
                    out.writeInt(c.edificios.length);
                    for(Reg x : c.edificios) x.escribir(out);
                    out.writeBoolean(c.niebla != null);
                    if(c.niebla != null) for(int v : c.niebla) out.writeInt(v);
                    out.writeInt(c.unidades.length);
                    for(Estructuras.RegU u : c.unidades) u.escribir(out);
                    out.writeDouble(c.tick);
                    out.writeInt(c.modulos == null ? 0 : c.modulos.size());
                    if(c.modulos != null){
                        for(java.util.Map.Entry<String, byte[]> e : c.modulos.entrySet()){
                            out.writeUTF(e.getKey());
                            out.writeInt(e.getValue().length);
                            out.write(e.getValue());
                        }
                    }
                }
                out.flush();
                return bos.toByteArray();
            }catch(Throwable t){
                Log.err("[MundoInfinito] No se pudo serializar región", t);
                return null;
            }
        }

        static byte[] bytes(Region r){
            return bytes(instantanea(r));
        }

        /**
         * Hilo principal: toma una instantánea de cada región modificada (barato). La serialización Y la escritura a disco las hace el
         * hilo de E/S. Antes se serializaba aquí todo, en el fotograma del guardado/reubicación: decenas de ms con una base grande.
         */
        static java.util.HashMap<Fi, arc.func.Prov<byte[]>> serializarSucias(){
            java.util.HashMap<Fi, arc.func.Prov<byte[]>> r = new java.util.HashMap<>();
            for(Region reg : regiones.values()){
                if(!reg.sucia) continue;
                final java.util.ArrayList<InstantaneaChunk> snap = instantanea(reg);
                r.put(archivo(reg.rx, reg.ry), () -> bytes(snap));
                reg.sucia = false;
            }
            return r;
        }

        /** Libera de RAM las regiones limpias que quedaron lejos del jugador. */
        static void evictar(){
            final int pcx = Streamer.pcx(), pcy = Streamer.pcy(), r = Streamer.ANILLO_CHUNKS;
            int minX = Math.floorDiv(pcx - r, REGION_CHUNKS) - 1, maxX = Math.floorDiv(pcx + r, REGION_CHUNKS) + 1;
            int minY = Math.floorDiv(pcy - r, REGION_CHUNKS) - 1, maxY = Math.floorDiv(pcy + r, REGION_CHUNKS) + 1;
            Seq<Long> borrar = new Seq<>();
            for(java.util.Map.Entry<Long, Region> e : regiones.entrySet()){
                Region rg = e.getValue();
                if(!rg.sucia && (rg.rx < minX || rg.rx > maxX || rg.ry < minY || rg.ry > maxY)) borrar.add(e.getKey());
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
        static final long PISO_APLICAR_NS = 450_000L;            // lo mínimo por fotograma para publicar terreno aunque el reparto esté gastado
        static final int R_JUGADOR = 3, R_UNIDAD = 2, R_EDIFICIO = 1;
        /** Mundo en anillo (motor MX-Test): 2^ANILLO_LOG2 chunks por lado = 1024 casillas. Es el hueco de cada chunk, no una ventana. */
        static final int ANILLO_LOG2 = 5;
        static final int ANILLO_CHUNKS = 1 << ANILLO_LOG2;
        /** Chunks \"en vuelo\" a la vez (cola + calculando + esperando publicar): crece con los hilos de generación. */
        static int maxEnCola(){
            return Math.max(8, Ajustes.hilos() * 6);
        }

        static volatile boolean activo = false;
        static volatile int epoca = 0;

        static Meta meta;
        static Paleta paleta;
        /** Ya no hay ventana: coordenadas locales == virtuales. Se conservan (siempre 0) para no tocar todo el código de Coord. */
        static final int ox = 0, oy = 0;
        static volatile int nch = ANILLO_CHUNKS;         // lado del anillo en chunks (solo dimensiona arrays; NO es un límite del mundo)
        static final IntSet generados = new IntSet();    // chunks publicados en el mundo
        static final IntSet solicitados = new IntSet();  // en cola / calculando / esperando publicar
        static final ArrayDeque<Integer> cola = new ArrayDeque<>();
        static final ConcurrentLinkedQueue<ChunkData> listos = new ConcurrentLinkedQueue<>();
        /** Chunks ya construidos que esperan a que se suelte el chunk que ocupa su hueco del anillo. */
        static final ArrayDeque<ChunkData> esperaHueco = new ArrayDeque<>();
        static final ArrayDeque<Reg> colaRestaurar = new ArrayDeque<>();
        static final Seq<Reg> configsPendientes = new Seq<>();
        static final Seq<Estructuras.RegU> unidadesPend = new Seq<>();
        static boolean restaurando = false;
        static float restauroDesde = -1f;   // Time.time en que empezó la restauración inicial (-1 = no empezó)
        static int totalRestaurar = 1;
        static Datos datosPendientes;
        static final Thread[] trabajadores = new Thread[8];
        static volatile int hilosObjetivo = 1;
        /** Precarga de terreno a la caché (sin publicar): cola de baja prioridad, protegida por 'cola'. */
        static final ArrayDeque<Long> colaBaja = new ArrayDeque<>();
        static final java.util.HashSet<Long> pendBaja = new java.util.HashSet<>();
        static int cursorPlan;

        static float acumEscaneo, acumGuardado, muerto;
        static float jugadorX, jugadorY;                 // última posición válida (tiles, virtual)

        // ---- claves de chunk del anillo: (cx << 16) | (cy & 0xFFFF), con cx, cy en ±1024 (coordenadas VIRTUALES)
        static int clave(int cx, int cy){
            return (cx << 16) | (cy & 0xFFFF);
        }

        static int cxDe(int k){
            return k >> 16;
        }

        static int cyDe(int k){
            return (short)k;
        }

        /** ¿Existe el chunk en el mundo (tiene sus casillas)? */
        static boolean enMapa(int cx, int cy){
            return Vars.world != null && Vars.world.tiles != null && Vars.world.tiles.ring && Vars.world.tiles.chunkLoaded(cx, cy);
        }

        /** Chunk del jugador (virtual). */
        static int pcx(){
            return Math.floorDiv((int)Math.floor(jugadorX), TAM_CHUNK);
        }

        static int pcy(){
            return Math.floorDiv((int)Math.floor(jugadorY), TAM_CHUNK);
        }

        /** Fija los límites de terreno del mundo (en chunks) para el hilo del motor. Sin límite = valores extremos. */
        static void aplicarLimites(Meta m){
            Motor.limCxMin = m == null || m.limX <= 0 ? Integer.MIN_VALUE : Math.floorDiv(m.minTX(), TAM_CHUNK);
            Motor.limCxMax = m == null || m.limX <= 0 ? Integer.MAX_VALUE : Math.floorDiv(m.maxTX(), TAM_CHUNK);
            Motor.limCyMin = m == null || m.limY <= 0 ? Integer.MIN_VALUE : Math.floorDiv(m.minTY(), TAM_CHUNK);
            Motor.limCyMax = m == null || m.limY <= 0 ? Integer.MAX_VALUE : Math.floorDiv(m.maxTY(), TAM_CHUNK);
        }

        /** ¿Se puede generar este chunk según el límite del mundo? */
        static boolean permitido(int cx, int cy){
            return Motor.permitido(cx, cy);
        }

        /**
         * Mantiene TODAS las unidades dentro del límite de terreno (los chunks fuera del límite no existen: no hay suelo ni casillas).
         * Se llama cada fotograma; solo recorre las unidades si el mundo tiene límite.
         */
        static void limitarUnidades(){
            final Meta m = meta;
            if(m == null || !m.limitado() || !activo) return;
            // El terreno se genera por chunks ENTEROS (Motor.limCxMin..limCxMax): el borde jugable es el del último chunk generado, no el
            // límite exacto en tiles. Antes el dron se frenaba en minTX..maxTX y quedaba un trozo de terreno ya generado inalcanzable
            // (con límites pequeños, hasta la mitad del área). Tile t ocupa [t*8-4, t*8+4]; el chunk c abarca los tiles c*32..c*32+31.
            final float x0 = m.limX > 0 ? Motor.limCxMin * (float)(TAM_CHUNK * 8) - 4f : -Float.MAX_VALUE;
            final float x1 = m.limX > 0 ? (Motor.limCxMax * (float)TAM_CHUNK + (TAM_CHUNK - 1)) * 8f + 4f : Float.MAX_VALUE;
            final float y0 = m.limY > 0 ? Motor.limCyMin * (float)(TAM_CHUNK * 8) - 4f : -Float.MAX_VALUE;
            final float y1 = m.limY > 0 ? (Motor.limCyMax * (float)TAM_CHUNK + (TAM_CHUNK - 1)) * 8f + 4f : Float.MAX_VALUE;
            for(Unit u : Groups.unit){
                if(u == null) continue;
                if(u.x < x0){ u.x = x0; u.vel.x = Math.max(0f, u.vel.x); }
                else if(u.x > x1){ u.x = x1; u.vel.x = Math.min(0f, u.vel.x); }
                if(u.y < y0){ u.y = y0; u.vel.y = Math.max(0f, u.vel.y); }
                else if(u.y > y1){ u.y = y1; u.vel.y = Math.min(0f, u.vel.y); }
            }
        }

        static void reiniciar(Meta m, Paleta p){
            meta = m;
            paleta = p;
            aplicarLimites(m);
            nch = ANILLO_CHUNKS;
            generados.clear();
            solicitados.clear();
            motor.claves.clear();
            motor.olvidar();
            Vista.limpiar();
            Anillo.reiniciar();
            cursorPlan = 0;
            synchronized(cola){ cola.clear(); colaBaja.clear(); pendBaja.clear(); }
            Extensiones.alReiniciarMundo(m);   // cierra el registro, fija la versión de generación y limpia el estado por mundo
            Nucleo.alReiniciarMundo(m.perfil.parametros());   // cierra los registros y fija los parámetros de generación del perfil de este mundo
            CacheChunks.configurar(m.semilla, m.dimGen());
            vaciarListos();
            colaRestaurar.clear();
            configsPendientes.clear();
            unidadesPend.clear();
            Ausencia.reiniciar();
            restaurando = false;
            restauroDesde = -1f;
            datosPendientes = null;
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
                try{ t.setPriority(Thread.NORM_PRIORITY); }catch(Throwable ignored){}   // en Android, prioridad < 5 = nice >= 10 = grupo de segundo plano (núcleos LITTLE, poca CPU)
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
                int key = -1, ep, dm, mid;
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
                    mid = CacheChunks.mundo();
                }
                try{
                    if(m == null || p == null) continue;
                    if(baja != null){
                        long vk = baja;
                        if(CacheChunks.tiene(mid, vk)) continue;
                        final long tg = Perfil.t();
                        ChunkData cd = ChunkData.generar(p, m.semilla, dm, m.tam, 0, 0, ep, Coord.vcxDeClave(vk) * TAM_CHUNK, Coord.vcyDeClave(vk) * TAM_CHUNK);
                        Perfil.generacion(dm, tg);
                        CacheChunks.poner(mid, vk, cd);
                        cd.liberar();
                    }else{
                        int cx = cxDe(key), cy = cyDe(key);
                        long vk = Coord.claveVirtual(cx, cy);
                        ChunkData cd = CacheChunks.copiar(mid, vk, cx, cy, ep);   // ¿ya se generó antes? entonces no se recalcula
                        if(cd == null){
                            final long tg = Perfil.t();
                            cd = ChunkData.generar(p, m.semilla, dm, m.tam, cx, cy, ep, 0, 0);
                            Perfil.generacion(dm, tg);
                            CacheChunks.poner(mid, vk, cd);
                        }
                        Anillo.construir(cd, mid, m, p, dm, ep);   // casillas + oscuridad de muros, en el hilo de fondo
                        listos.add(cd);
                    }
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error generando chunk", t);
                }
                // pausa mínima entre chunks: ningún hilo acapara un núcleo de la CPU (con varios hilos basta 1 ms)
                try{ Thread.sleep(1); }catch(InterruptedException e){ return; }
            }
        }

        /** Descarta los chunks calculados que nadie publicó (cambio de época), devolviéndolos al pool. */
        static void vaciarListos(){
            ChunkData c;
            while((c = listos.poll()) != null) c.liberar();
            while((c = esperaHueco.poll()) != null) c.liberar();
        }

        /** Presupuesto por frame: baja solo si el juego va justo de FPS (así moverse nunca causa tirones). */
        static long presupuesto(){
            float dt = Core.graphics == null ? 0.016f : Core.graphics.getDeltaTime();
            long base = Ajustes.presupuestoNs();
            if(dt > 0.05f) return Math.min(base, 450_000L);   // antes 150 µs: con el juego lento el terreno casi no avanzaba y se veía negro/congelado
            if(dt > 0.03f) return Math.min(base, 700_000L);
            // sobrado de FPS y con terreno esperando: se aprovecha (el terreno que falta es lo que más se nota)
            if(dt < 0.0185f && !listos.isEmpty()) return base * 2L;
            return base;
        }

        static void tick(){
            limitarUnidades();           // el límite de terreno del mundo (si lo tiene) también vale para las unidades
            tickInterno();
            Mapa.vaciarColaDelJuego();   // el minimapa del juego no debe acumular los tiles que acabamos de cambiar
            Suelo.vigilarTirones();      // si hubo un tirón largo, repara la caché de suelo del renderizador
            Perfil.cierre();             // escribe una línea [Perfil] si este fotograma se pasó de tiempo
        }

        private static final ArrayDeque<ChunkData> reintento = new ArrayDeque<>();

        static void tickInterno(){
            if(!activo || !Vars.state.isPlaying()) return;
            if(Mundos.actual != null) Mundos.actual.reloj += Time.delta;
            Regiones.sincronizar();
            Vision.tick();   // niebla activada/desactivada y radio de visión del dron, según Ajustes (cada 30 ticks)

            Presupuesto.abrir();   // UN reparto de tiempo por fotograma para todos los sistemas de abajo

            if(restaurando){
                long tRes = Perfil.t();
                Anillo.procesarPendientes(64);
                long finR = System.nanoTime() + PRESUPUESTO_RESTAURAR_NS;
                while(System.nanoTime() < finR && !colaRestaurar.isEmpty()) Mundos.restaurarEdificio(colaRestaurar.pollFirst());
                Perfil.fin(Perfil.S_RESTAURAR, tRes);
                if(restauroDesde < 0f) restauroDesde = Time.time;
                // Tope de espera: mientras 'restaurando' el streaming está parado (no se piden ni publican chunks), así que un chunk cercano
                // que espera a un vecino que aún no se ha cargado se esperaba para siempre: el jugador nunca aparecía y no se veía nada.
                // Pasado el tope se termina igual: lo pendiente se restaura solo, en el ciclo normal, cuando lleguen los vecinos.
                final boolean agotado = Time.time - restauroDesde > 240f;
                if(colaRestaurar.isEmpty() && (agotado || !Anillo.hayPendientesCerca(pcx(), pcy(), 2))){
                    if(agotado) Log.warn("[MundoInfinito] Restauración inicial sin terminar tras ~4 s (pendientes cerca: @, generados: @); se continúa.", Anillo.hayPendientesCerca(pcx(), pcy(), 2), generados.size);
                    restauroDesde = -1f;
                    restaurando = false;
                    Mundos.finalizarRestauracion();
                }else{
                    Carga.texto("Restaurando edificios... " + (100 * (totalRestaurar - colaRestaurar.size()) / Math.max(1, totalRestaurar)) + "%");
                    return;
                }
            }

            // Orden de prioridad: lo que el jugador ve (estructuras, bases) antes que lo que puede esperar (ausencia).
            long tS = Perfil.t();
            tS = Perfil.t();
            Ausencia.tick(Presupuesto.hasta(2_500_000L));          // las fábricas que estuvieron lejos se ponen al día
            Perfil.fin(Perfil.S_AUSENCIA, tS);
            tS = Perfil.t();
            Mapa.tick(pcx(), pcy());
            Perfil.fin(Perfil.S_MAPA, tS);

            // ---- soltar (guardando) los chunks fuera de la vista, CADA fotograma y con tiempo propio: libera sus huecos del anillo ANTES de
            // publicar (un chunk nuevo solo cabe en un hueco libre). Con chunks ya construidos esperando hueco, la descarga es urgente.
            if(Anillo.hayDescargasPendientes()){
                tS = Perfil.t();
                final boolean urgente = !esperaHueco.isEmpty();
                Anillo.procesarDescargas(Presupuesto.hasta(urgente ? 2_000_000L : 800_000L), urgente ? Anillo.MAX_DESCARGAS_URGENTE : Anillo.MAX_DESCARGAS);
                Perfil.fin(Perfil.S_VENTANA, tS);
            }

            // ---- publicar los chunks que los hilos de fondo ya construyeron (32 arraycopy + eventos de índice cada uno)
            tS = Perfil.t();
            final int pcx = pcx(), pcy = pcy(), carga = Ajustes.carga();
            long fin = Presupuesto.hastaConPiso(presupuesto(), PISO_APLICAR_NS);
            int hechos = 0;
            reintento.clear();
            while(true){
                if(hechos > 0 && System.nanoTime() >= fin) break;
                ChunkData d = esperaHueco.pollFirst();
                if(d == null) d = listos.poll();
                if(d == null) break;
                final int k = clave(d.cx, d.cy);
                if(d.epoca != epoca || !Vista.dentro(d.cx, d.cy, pcx, pcy, carga, Anillo.HISTERESIS)){   // ya no hace falta
                    solicitados.remove(k);
                    d.tiles = null;
                    d.liberar();
                    continue;
                }
                if(!Anillo.publicar(d)){          // su hueco lo ocupa otro chunk que todavía se está soltando
                    reintento.addLast(d);
                    if(reintento.size() > 16) break;
                    continue;
                }
                solicitados.remove(k);
                d.liberar();
                hechos++;
            }
            while(!reintento.isEmpty()) esperaHueco.addLast(reintento.pollFirst());
            Perfil.fin(Perfil.S_APLICAR, tS);

            // ---- restaurar lo guardado (edificios) de los chunks recién cargados, poco a poco
            tS = Perfil.t();
            Anillo.procesarPendientes(16);
            long finR = Presupuesto.hasta(2_000_000L);
            while(System.nanoTime() < finR && !colaRestaurar.isEmpty()) Mundos.restaurarEdificio(colaRestaurar.pollFirst());
            if(colaRestaurar.isEmpty()){
                if(!configsPendientes.isEmpty()) Mundos.aplicarConfigs();
                unidadesPend.clear();   // (sin guardias que restaurar; las unidades aliadas dormidas las despierta Entidades)
            }
            Perfil.fin(Perfil.S_RESTAURAR, tS);

            Extensiones.tick();   // estructuras de mods pendientes (una sola vez por ancla, con presupuesto propio)

            tS = Perfil.t();
            pedirMas();   // mantiene la tubería llena sin esperar al siguiente escaneo
            Perfil.fin(Perfil.S_PEDIR, tS);

            acumEscaneo += Time.delta;
            if(acumEscaneo >= 30f){
                acumEscaneo = 0f;
                tS = Perfil.t();
                escanear();
                Perfil.fin(Perfil.S_ESCANEO, tS);
            }
            acumGuardado += Time.delta;
            if(acumGuardado >= 5400f){ // ~90 s
                acumGuardado = 0f;
                tS = Perfil.t();
                Mundos.guardar(false);
                Perfil.fin(Perfil.S_GUARDAR, tS);
                Perfil.evento("autoguardado", tS);
            }

            // Red de seguridad: si el jugador sigue sin unidad, reaparecerlo en su sitio (nunca en el chunk del proxy).
            if(Vars.player != null && Vars.player.dead() && !Mundos.viajando){
                muerto += Time.delta;
                boolean soloProxy = Mundos.proxyPos != -1 && !Mundos.hayNucleoReal();
                if((soloProxy && muerto > 8f) || muerto > 90f){
                    muerto = 0f;
                    Mundos.spawnEnPosicion((int)Math.floor(jugadorX), (int)Math.floor(jugadorY), null);
                }
            }else{
                muerto = 0f;
            }
        }

        // Buffers reutilizables del escaneo
        static final Motor motor = new Motor();
        static float ultimoEscaneo = 0f;
        static int rVista = 4;   // radio (en chunks) del disco del jugador (NO de la cámara): lo usan las entidades
        static final IntSeq faltanK = new IntSeq();
        static final FloatSeq faltanS = new FloatSeq();

        /** Decide qué chunks necesita el mundo (jugador + unidades aliadas), suelta los que sobran y vigila el núcleo proxy. */
        static void escanear(){
            if(Vars.player != null && Vars.player.unit() != null && !Vars.player.dead()){
                Unit u = Vars.player.unit();
                jugadorX = u.x / 8f;
                jugadorY = u.y / 8f;
            }
            final int pcx = pcx(), pcy = pcy();

            rVista = Mathf.clamp(Ajustes.carga(), 2, Ajustes.maxCarga());
            Vista.actualizar(pcx, pcy);   // chunks de las unidades aliadas: su entorno también se mantiene cargado

            // MOTOR DE CHUNKS: decide qué hace falta y en qué orden según el JUGADOR y hacia dónde se dirige.
            motor.actualizar(jugadorX, jugadorY, Math.max(1f, Time.time - ultimoEscaneo));
            ultimoEscaneo = Time.time;
            motor.solicitar(pcx, pcy, Ajustes.carga(), Ajustes.estricto(), epoca);

            Entidades.tick(pcx, pcy);     // las unidades aliadas lejanas duermen; las cercanas despiertan
            Anillo.planificarDescarga(pcx, pcy);   // qué chunks quedaron fuera de la vista (se sueltan en tickInterno, cada fotograma)
            Anillo.vigilarProxy(pcx, pcy);

            ajustarHilos();
            pedirMas();
        }

        /** Pide a los hilos los siguientes chunks del plan que aún no están ni cargados ni en camino, hasta llenar la tubería. */
        static void pedirMas(){
            if(motor.recoger(epoca)){
                cursorPlan = 0;
                // lectura anticipada de TODAS las regiones de disco que el plan necesita (nunca se leen en este hilo)
                for(int i = 0; i < motor.regX.size; i++) Regiones.precargar(motor.regX.items[i], motor.regY.items[i]);
            }
            int libres = maxEnCola() - solicitados.size;
            if(libres <= 0) return;
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
        static int proxyPos = -1;   // posición del núcleo "proxy" (inventario compartido cuando el núcleo real está lejos)

        /** Unidades "avatar" del jugador: nunca se guardan ni se restauran como seguidores (evita duplicados del dron). */
        static final java.util.HashSet<String> UNIDADES_NUCLEO = new java.util.HashSet<>(
            java.util.Arrays.asList("alpha", "beta", "gamma", "evoke", "incite", "emanate"));

        // ---------- creación / continuar ----------
        /** limX / limY: límite de terreno en tiles (ancho / alto totales, centrados en el punto de aparición). 0 = infinito. */
        static void crearNuevo(String nombre, int nucleo, String semillaTxt, int limX, int limY){
            crearNuevo(nombre, nucleo, semillaTxt, limX, limY, null);
        }

        /** gamerules: valor elegido por el jugador para cada GameRule (id -> valor); null o ausentes = valor por defecto de la regla. */
        static void crearNuevo(String nombre, int nucleo, String semillaTxt, int limX, int limY, java.util.Map<String, Boolean> gamerules){
            String t = semillaTxt == null ? "" : semillaTxt.trim();
            if(!t.isEmpty()){
                crearConSemilla(nombre, nucleo, Semillas.deTexto(t), t, limX, limY, gamerules);
                return;
            }
            Carga.mostrar("Obteniendo semilla...");
            Semillas.obtener(semilla -> crearConSemilla(nombre, nucleo, semilla, String.valueOf(semilla), limX, limY, gamerules));
        }

        static void crearConSemilla(String nombre, int nucleo, int semilla, String semillaTxt, int limX, int limY, java.util.Map<String, Boolean> gamerules){
            Meta m = new Meta();
            m.id = "w" + System.currentTimeMillis();
            m.nombre = nombre == null || nombre.trim().isEmpty() ? "Mundo " + (Almacen.listar().size + 1) : nombre.trim();
            m.semilla = semilla;
            m.semillaTxt = semillaTxt;
            m.tam = ventana();
            m.nucleo = nucleo;
            m.gen = GEN_ACTUAL;
            m.dim = m.dimInicial();
            m.limX = Math.max(0, limX);
            m.limY = Math.max(0, limY);
            m.limpio = true;                       // mundo base: sin generador de enemigos ni de estructuras
            m.mods = OpenWorldAPI.firmaMods();
            if(gamerules != null) for(java.util.Map.Entry<String, Boolean> e : gamerules.entrySet()) m.perfil.fijar(e.getKey(), Boolean.TRUE.equals(e.getValue()) ? 1.0 : 0.0);
            m.perfil.sellarGeneracion(semilla);    // resuelve las propuestas de los mods; los parámetros de generación quedan guardados con el mundo y ya no cambian
            m.creado = m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);
            Regiones.iniciar(m);
            Orbita.reiniciar();          // mundo nuevo: sin drones en tránsito...
            Estructuras.cargar(m);       // ...y sin estructuras ni checkpoints
            abrir(m, null, 0.0, 0.0);
        }

        static void continuar(Meta m){
            if(!m.compatible()) return;
            avisarModsDistintos(m);
            Fi f = Almacen.archivoEstado(m);
            Datos d = f.exists() ? Datos.leer(f) : null;
            Regiones.iniciar(m);
            Orbita.cargar(m);
            Estructuras.cargar(m);
            if(d != null) abrir(m, d, d.vx, d.vy);
            else abrir(m, null, 0.0, 0.0);
        }

        /** Si el mundo se creó con otros mods activos que ahora faltan (o sobran), lo avisa: lo que esos mods pusieron puede no funcionar. */
        static void avisarModsDistintos(Meta m){
            try{
                final java.util.HashSet<String> antes = new java.util.HashSet<>(), ahora = new java.util.HashSet<>();
                if(m.mods != null && !m.mods.isEmpty()) for(String x : m.mods.split(",")) antes.add(x.trim());
                final String f = OpenWorldAPI.firmaMods();
                if(!f.isEmpty()) for(String x : f.split(",")) ahora.add(x.trim());
                final java.util.HashSet<String> faltan = new java.util.HashSet<>(antes), sobran = new java.util.HashSet<>(ahora);
                faltan.removeAll(ahora);
                sobran.removeAll(antes);
                if(faltan.isEmpty() && sobran.isEmpty()) return;
                final String txt = (faltan.isEmpty() ? "" : "Faltan mods con los que se creó este mundo: " + String.join(", ", faltan) + ". ")
                    + (sobran.isEmpty() ? "" : "Mods nuevos activos: " + String.join(", ", sobran) + ".");
                Log.warn("[MundoInfinito] @", txt);
                if(!Vars.headless && Vars.ui != null) Time.runTask(60f, () -> Vars.ui.showInfoToast("[accent]" + txt, 8f));
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo comparar la lista de mods: @", t.toString());
            }
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
            Carga.mostrar("Despegando hacia " + (destino == DIM_SERPULO ? "Serpulo" : destino == DIM_EREKIR ? "Erekir" : (Extensiones.mundoPorDim(destino) == null ? "otro mundo" : Extensiones.mundoPorDim(destino).nombre)) + "...");
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
                    else{
                        // primera visita: el núcleo aparece en el punto de llegada del mundo registrado, o en (0,0) en Serpulo y Erekir
                        final MundoRegistrado w = Extensiones.mundoPorDim(destino);
                        abrir(m, null, w == null ? 0.0 : w.llegadaX, w == null ? 0.0 : w.llegadaY);
                    }
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
            actual = m;
            m.tam = ventana();
            liberarMundo();
            OpenWorldAPI.registrarEnLog();

            final boolean nueva = datos == null;
            configurarReglas();

            vx = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vx));
            vy = Math.max(-LIMITE_MUNDO, Math.min(LIMITE_MUNDO, vy));
            // Ya no hay ventana: la posición del mundo ES la virtual.
            final int px = (int)Math.floor(vx), py = (int)Math.floor(vy);

            Paleta pal = Paleta.crear();
            Streamer.epoca++;
            Streamer.reiniciar(m, pal);
            Anillo.iniciar();
            final int ep = Streamer.epoca;
            proxyPos = -1;

            // Mundo EN ANILLO (motor MX-Test): vacío; una casilla existe solo mientras su chunk esté publicado.
            Vars.world.openRing(Streamer.ANILLO_LOG2);
            Streamer.jugadorX = px;
            Streamer.jugadorY = py;
            final int pcx0 = Math.floorDiv(px, TAM_CHUNK), pcy0 = Math.floorDiv(py, TAM_CHUNK);
            final int dm = m.dimGen();

            Threads.daemon("MundoInfinito-Inicio", () -> {
                try{
                    Core.app.post(() -> Carga.texto("Generando terreno..."));
                    final int mid = CacheChunks.mundo();
                    Seq<ChunkData> datosChunk = new Seq<>();
                    final int r = Math.min(Streamer.R_JUGADOR, Ajustes.carga() + 1);
                    for(int dy = -r; dy <= r; dy++){
                        for(int dx = -r; dx <= r; dx++){
                            if(!Streamer.permitido(pcx0 + dx, pcy0 + dy)) continue;   // fuera del límite de terreno del mundo
                            ChunkData d = ChunkData.generar(pal, m.semilla, dm, m.tam, pcx0 + dx, pcy0 + dy, ep, 0, 0);
                            CacheChunks.poner(mid, Coord.claveVirtual(pcx0 + dx, pcy0 + dy), d);
                            Anillo.construir(d, mid, m, pal, dm, ep);
                            datosChunk.add(d);
                        }
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
            Hilos.vigilarPrincipal("Streamer.finalizarApertura");   // publica chunks en el mundo: solo el hilo principal
            Carga.texto("Construyendo mundo...");
            final IntSeq cargados = new IntSeq();
            for(ChunkData d : chunks){
                if(Vars.world.publishChunk(d.cx, d.cy, d.tiles)){
                    Streamer.generados.add(Streamer.clave(d.cx, d.cy));
                    cargados.add(Streamer.clave(d.cx, d.cy));
                }
                d.tiles = null;
                d.liberar();
            }
            if(nueva) colocarNucleo(m, px, py);
            if(nueva && llegadaNueva) colocarSilo(px, py);
            llegadaNueva = false;

            // núcleo proxy: mantiene el inventario compartido cuando el núcleo real se descarga (el jugador se aleja de su base)
            Anillo.trasladarProxy(Math.floorDiv(px, TAM_CHUNK), Math.floorDiv(py, TAM_CHUNK), false);

            Vars.logic.play();                // PlayEvent añade al jugador y reparte el loadout inicial

            Streamer.jugadorX = px;
            Streamer.jugadorY = py;
            Streamer.datosPendientes = datos;
            Streamer.restaurando = true;      // el primer tick restaura edificios y luego llama a finalizarRestauracion()
            Streamer.activo = true;
            for(int i = 0; i < cargados.size; i++) Anillo.alCargar(Streamer.cxDe(cargados.items[i]), Streamer.cyDe(cargados.items[i]));
            Streamer.totalRestaurar = Math.max(1, Streamer.colaRestaurar.size());

            Log.info("[MundoInfinito] Mundo abierto: límite @x@ tiles, chunks permitidos X [@..@] Y [@..@], jugador en chunk @,@, publicados @ de @",
                m.limX, m.limY, Motor.limCxMin, Motor.limCxMax, Motor.limCyMin, Motor.limCyMax,
                Math.floorDiv(px, TAM_CHUNK), Math.floorDiv(py, TAM_CHUNK), cargados.size, chunks.size);
            m.ultimo = System.currentTimeMillis();
            Almacen.guardarMeta(m);
            OpenWorldAPI.mundoAbierto(m, nueva);
        }

        /** Fuerza al renderizador de niebla a volver a copiar lo explorado desde la CPU (tras restaurar niebla guardada o cambiar el ajuste). */
        static void refrescarNiebla(){
            try{
                if(!Vars.headless && Vars.renderer != null && Vars.renderer.fog != null){
                    java.lang.reflect.Field f = Vars.renderer.fog.getClass().getDeclaredField("lastTeam");
                    f.setAccessible(true);
                    f.set(Vars.renderer.fog, null);
                }
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo refrescar la niebla guardada");
            }
        }

        /** Reaplica las configuraciones RELATIVAS (puentes, nodos, mass drivers, procesadores) cuando todos sus edificios ya existen. */
        static void aplicarConfigs(){
            for(Reg r : Streamer.configsPendientes){
                try{
                    Tile t = Vars.world.tile(r.x, r.y);
                    if(t != null && t.build != null) t.build.configured(null, r.configObjeto());
                }catch(Throwable ignored){}
            }
            Streamer.configsPendientes.clear();
        }

        /** Última fase de apertura: configs relativas, núcleo/proxy, inventario, jugador, unidades y niebla. */
        static void finalizarRestauracion(){
            final int ep = Streamer.epoca;
            Datos d = Streamer.datosPendientes;
            Streamer.datosPendientes = null;
            int px = (int)Streamer.jugadorX, py = (int)Streamer.jugadorY;

            // 1) Enlaces relativos de puentes, nodos, mass drivers y procesadores (todos los edificios ya existen)
            aplicarConfigs();

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

            Streamer.unidadesPend.clear();

            {
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
                            if(Vars.world.tile((int)Math.floor(ru.vx), (int)Math.floor(ru.vy)) == null) continue;   // chunk no cargado: se queda dormida
                            Unit u = t.spawn(Team.sharded, (float)(ru.vx * 8.0), (float)(ru.vy * 8.0));
                            u.rotation = ru.rot;
                            u.health = Math.max(1f, ru.vida);
                        }catch(Throwable ignored){}
                    }
                }
            }

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
            // Niebla de campaña según el ajuste del jugador (Vision la mantiene al día en vivo): lo no explorado se oculta, o se ve todo.
            r.fog = Ajustes.niebla();
            r.staticFog = r.fog;
            r.bannedBlocks.clear();
            if(actual != null && actual.dimensional()){
                // Dimensión: la UI nativa de Mindustry se adapta sola al planeta (bloques por entorno, ítems ocultos del otro planeta).
                final MundoRegistrado mundoReg = actual.dim >= MundoRegistrado.DIM_MINIMA ? Extensiones.mundoPorDim(actual.dim) : null;
                final boolean serpulo = mundoReg != null ? !"erekir".equalsIgnoreCase(mundoReg.planeta) : actual.dim == DIM_SERPULO;
                Planet p = serpulo ? Planets.serpulo : Planets.erekir;
                r.planet = p;
                r.env = p.defaultEnv;
                r.loadout = mundoReg != null && !mundoReg.inicio.isEmpty() ? mundoReg.inicio
                    : serpulo
                    ? ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100, Items.graphite, 150)
                    : ItemStack.list(Items.beryllium, 200, Items.graphite, 150);
                if(mundoReg != null && mundoReg.reglas != null){
                    try{
                        mundoReg.reglas.get(r);
                    }catch(Throwable t){
                        Log.err("[MundoInfinito][API] El ajuste de reglas del mundo \"@\" falló", mundoReg.id, t);
                    }
                }
                // Todo se puede construir: sin árbol tecnológico ni bloques baneados.
                // Sin límite de unidades: el tope sale de los núcleos CARGADOS y, al alejarse de la base, valía 0 (morían al crearse).
                r.disableUnitCap = true;
            }else{
                // Mundos antiguos: menú de construcción con bloques de AMBOS planetas (planeta "sol" = sin filtro por planeta).
                r.planet = Planets.sun;
                r.env = ENTORNO_COMBINADO;
                r.loadout = ItemStack.list(Items.copper, 400, Items.lead, 250, Items.sand, 100, Items.beryllium, 200, Items.graphite, 150);
            }
            Extensiones.aplicarGameRules(actual, r);   // gamerules elegidas al crear el mundo (las del base y las de otros mods)
            OpenWorldAPI.aplicarReglas(r);   // los mods que dependen de este ajustan las reglas aquí (enemigos, oleadas, equipos...)
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
                // Mundos ANTIGUOS (creados cuando el mod generaba bases enemigas y ruinas): lo enemigo o abandonado que quedó guardado se
                // descarta, porque ya no hay generador que lo justifique. En mundos nuevos (limpio) se conserva TODO: lo que cree un mod
                // (equipos propios, estructuras, enemigos) debe sobrevivir al guardado.
                if(Streamer.meta != null && !Streamer.meta.limpio && (r.equipo == Team.crux.id || r.equipo == Team.malis.id || r.equipo == Team.derelict.id)) return;
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
                if(t.build != null) OpenWorldAPI.edificioRestaurado_(t.build);
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo restaurar @", r.bloque);
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

        static final Team[] EQUIPOS_GUARDADO = {Team.sharded, Team.crux, Team.malis, Team.derelict, Team.green, Team.blue};

        /** Equipos que siempre se guardan: los del base más los que un mod registró con OpenWorldAPI.registrarEquipo. */
        static Team[] equiposGuardado(){
            return Extensiones.equiposGuardado(EQUIPOS_GUARDADO);
        }

        static void escribirAsync(Datos d, boolean sincrono){
            final Meta m = actual;
            m.ultimo = System.currentTimeMillis();
            final byte[] estado = d.bytes();
            final byte[] orbita = m.dimensional() ? Orbita.bytes() : null;
            final byte[] estructuras = m.dimensional() ? Estructuras.bytes() : null;
            final Fi fEstructuras = Almacen.archivoEstructuras(m);
            final java.util.HashMap<Fi, arc.func.Prov<byte[]>> regs = Regiones.serializarSucias();
            // Todo el acceso a disco lo hace el hilo de E/S: serialización, compresión y escritura atómica y coalescente allí
            for(java.util.Map.Entry<Fi, arc.func.Prov<byte[]>> e : regs.entrySet()){
                Disco.escribirDiferido(e.getKey().file(), e.getValue(), true);   // si la región quedó vacía, la fuente da null y se borra el archivo
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
            if(actual == null || !Streamer.activo || Streamer.restaurando || !Vars.world.isRing()) return;
            long t0 = Perfil.t();
            double[] pos = posicionVirtual();
            Datos d = Datos.capturar(pos[0], pos[1]);
            Anillo.capturarTodo();   // edificios + niebla de los chunks cargados, por chunk virtual
            escribirAsync(d, sincrono);
            Perfil.evento("guardar (capturar+serializar)", t0);
        }
    }

    // ==================================================================
    // INTERFAZ: botón del menú + lista de mundos + nuevo mundo
    // ==================================================================
    static final class MenuUI{
        static boolean inyectado = false;

        static final String NOMBRE_BOTON = "ow-mundo-abierto";
        static final String TEXTO_BOTON = "Mundo abierto";

        /**
         * Pone el botón "Mundo abierto" JUNTO al de "Campaña" con su mismo aspecto (mismo icono, tamaño y estilo; solo cambia el texto).
         * MenuFragment reconstruye sus botones en cada ResizeEvent, así que se vuelve a poner después de cada reconstrucción
         * (este listener se registra después del suyo y por tanto se ejecuta después).
         */
        static void inyectarBoton(){
            if(inyectado) return;
            inyectado = true;
            try{
                if(Vars.mobile){
                    inyectarMovil();
                    Events.on(mindustry.game.EventType.ResizeEvent.class, e -> {
                        try{ inyectarMovil(); }catch(Throwable t){ Log.warn("[MundoInfinito] Botón de menú (móvil): @", t.toString()); }
                    });
                }else{
                    inyectarEscritorio();
                }
            }catch(Throwable t){
                Log.warn("[MundoInfinito] No se pudo poner el botón junto a Campaña (@): se usa una capa alternativa.", t.toString());
                botonAlternativo();
            }
        }

        private static Object campo(Object o, String nombre) throws Exception{
            java.lang.reflect.Field f = o.getClass().getDeclaredField(nombre);
            f.setAccessible(true);
            return f.get(o);
        }

        /** Móvil: el botón se añade al final de la cuadrícula del menú con un MobileButton idéntico (sin tocar el orden interno de celdas). */
        @SuppressWarnings({"unchecked", "rawtypes"})
        static void inyectarMovil() throws Exception{
            final Table cont = (Table)campo(Vars.ui.menufrag, "container");
            if(cont == null || cont.find(NOMBRE_BOTON) != null) return;
            final Seq<Cell> celdas = cont.getCells();
            if(celdas.isEmpty()) return;
            final mindustry.ui.MobileButton boton = new mindustry.ui.MobileButton(Icon.play, TEXTO_BOTON, MenuUI::abrirLista);
            boton.name = NOMBRE_BOTON;
            // Se añade con la API normal y se queda donde cae (al final de la última fila). NO se reordena la lista de celdas a mano:
            // Table solo recalcula 'columns' en endRow(), y una celda movida conserva su column/row antiguos. Tras cada ResizeEvent
            // (rotar la pantalla) eso dejaba una celda con column == columns y Table.computeSize lanzaba ArrayIndexOutOfBoundsException
            // (length=5; index=5) en el hilo de GL: cierre del juego.
            cont.add(boton);                           // hereda size/pad de container.defaults(), igual que los demás
            cont.invalidateHierarchy();
        }

        /** Escritorio: entrada nueva en el submenú de "Jugar", justo debajo de "Campaña". */
        static void inyectarEscritorio() throws Exception{
            final mindustry.ui.fragments.MenuFragment mf = Vars.ui.menufrag;
            if(mf.desktopButtons == null) throw new IllegalStateException("desktopButtons aún no existe");
            for(mindustry.ui.fragments.MenuFragment.MenuButton b : mf.desktopButtons){
                if(b == null || b.submenu == null) continue;
                int i = -1;
                for(int k = 0; k < b.submenu.size; k++){
                    mindustry.ui.fragments.MenuFragment.MenuButton h = b.submenu.get(k);
                    if(h != null && "@campaign".equals(h.text)){ i = k; break; }
                }
                if(i < 0) continue;
                b.submenu.insert(i + 1, new mindustry.ui.fragments.MenuFragment.MenuButton(TEXTO_BOTON, Icon.play, MenuUI::abrirLista));
                // reconstruir la tabla de botones con la nueva entrada
                java.lang.reflect.Method m = mf.getClass().getDeclaredMethod("buildDesktop");
                m.setAccessible(true);
                m.invoke(mf);
                return;
            }
            throw new IllegalStateException("no se encontró el submenú de Campaña");
        }

        static void botonAlternativo(){
            Core.scene.add(new Table(t -> {
                t.setFillParent(true);
                t.bottom();
                t.visible(() -> Vars.state.isMenu());
                t.button(TEXTO_BOTON, Styles.cleart, MenuUI::abrirLista).width(240f).padBottom(12f);
            }));
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
                        ? m.nombreDim() + " · núcleo " + (m.nucleoBlock() == Blocks.coreShard ? "Shard" : "Bastion") + " · semilla " + m.semillaTexto()
                            + (m.limitado() ? " · límite " + (m.limX > 0 ? m.limX : "∞") + "×" + (m.limY > 0 ? m.limY : "∞") : "") + " · " + hace(m.ultimo)
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

        /** Tope de cada límite de terreno en la casilla de texto: el motor guarda coordenadas en un short y el mundo virtual llega a ±32 000. */
        static final int LIMITE_MAX_TILES = 2 * LIMITE_MUNDO - 2 * TAM_CHUNK;
        /** Tope del deslizador (arrastrar); en la casilla se puede escribir más. */
        static final int LIMITE_SLIDER_MAX = 4096;

        /**
         * Una fila "etiqueta + deslizador + casilla numérica" sincronizados. valor[0] = tiles totales en ese eje; 0 = infinito.
         * El deslizador va de 0 a 4096 en pasos de un chunk (32); la casilla acepta cualquier número hasta LIMITE_MAX_TILES.
         */
        static void filaLimite(Table t, String titulo, int[] valor){
            final arc.scene.ui.Slider sl = new arc.scene.ui.Slider(0f, LIMITE_SLIDER_MAX, TAM_CHUNK, false);
            final arc.scene.ui.TextField tf = new arc.scene.ui.TextField("0");
            final arc.scene.ui.Label info = new arc.scene.ui.Label("");
            final boolean[] ocupado = {false};
            tf.setFilter(arc.scene.ui.TextField.TextFieldFilter.digitsOnly);
            tf.setMaxLength(6);

            final Runnable refrescarInfo = () -> info.setText(valor[0] <= 0
                ? "[lightgray]infinito[]"
                : "[accent]" + valor[0] + " tiles[] · " + ((valor[0] + TAM_CHUNK - 1) / TAM_CHUNK) + " chunks");

            sl.moved(v -> {
                if(ocupado[0]) return;
                ocupado[0] = true;
                valor[0] = (int)v;
                tf.setText(String.valueOf(valor[0]));
                refrescarInfo.run();
                ocupado[0] = false;
            });
            tf.changed(() -> {
                if(ocupado[0]) return;
                ocupado[0] = true;
                int v = 0;
                try{
                    v = tf.getText().isEmpty() ? 0 : Integer.parseInt(tf.getText());
                }catch(NumberFormatException ignorado){
                    v = 0;
                }
                v = Mathf.clamp(v, 0, LIMITE_MAX_TILES);
                valor[0] = v;
                sl.setValue(Math.min(v, LIMITE_SLIDER_MAX));
                refrescarInfo.run();
                ocupado[0] = false;
            });
            refrescarInfo.run();

            t.add(titulo).left().padRight(8f).width(120f);
            t.add(sl).width(160f).padRight(8f);
            t.add(tf).width(86f).padRight(8f);
            t.add(info).left().width(150f).row();
        }

        /** Lista de gamerules con un interruptor por regla. Edita {@code valores} (id -> valor) en el sitio; lo que no se toque queda en su valor por defecto. */
        static void abrirGameRules(final java.util.TreeMap<String, Boolean> valores){
            final BaseDialog g = new BaseDialog("Gamerules");
            final Table t = new Table();
            final Seq<GameRule> reglas = Extensiones.gameRules();
            t.add("[lightgray]Reglas del mundo. Los mods que añadan las suyas las mostrarán aquí.[]").width(420f).wrap().padBottom(12f).row();
            for(final GameRule r : reglas){
                final boolean actual = valores.containsKey(r.id) ? valores.get(r.id) : r.porDefecto;
                t.check(r.nombre, actual, v -> valores.put(r.id, v)).left().padBottom(2f).row();
                if(!r.descripcion.isEmpty()) t.add("[lightgray]" + r.descripcion + "[]").width(420f).wrap().left().padBottom(12f).row();
            }
            if(reglas.isEmpty()) t.add("[lightgray]No hay gamerules registradas.[]").row();
            g.cont.pane(t).grow();
            g.addCloseButton();
            g.show();
        }

        /** Nuevo mundo: nombre, semilla (opcional), dimensión inicial, gamerules y límite de terreno opcional (0 = infinito) en cada eje. */
        static void abrirNuevo(){
            BaseDialog d = new BaseDialog("Nuevo mundo");
            final Table c = new Table();   // dentro de un panel con scroll: en móvil (pantalla baja) el formulario no cabe entero
            final String[] nombre = {"Mundo " + (Almacen.listar().size + 1)};
            final String[] semilla = {""};
            final int[] nuc = {0};
            final int[] limX = {0}, limY = {0};
            final String[] etiquetas = {"Empezar en: según la semilla", "Empezar en: Serpulo (Shard)", "Empezar en: Erekir (Bastion)"};

            c.add("Nombre del mundo").padBottom(6f).row();
            c.field(nombre[0], t -> nombre[0] = t).width(320f).padBottom(12f).row();

            c.add("Semilla (vacío = aleatoria; sirve cualquier número o texto)").padBottom(6f).row();
            c.field("", t -> semilla[0] = t).width(320f).padBottom(14f).row();

            TextButton nb = c.button(etiquetas[0], () -> {}).size(320f, 56f).padBottom(16f).get();
            nb.clicked(() -> {
                nuc[0] = (nuc[0] + 1) % etiquetas.length;
                nb.setText(etiquetas[nuc[0]]);
            });
            c.row();

            // Gamerules: el botón abre una lista con las reglas del mod base y las que añadan otros mods (OpenWorldAPI.registrarGameRule)
            final java.util.TreeMap<String, Boolean> gamerules = new java.util.TreeMap<>();
            c.button("Gamerules", () -> abrirGameRules(gamerules)).size(320f, 56f).padBottom(16f).row();

            c.add("Límite de terreno (0 = infinito)").padBottom(2f).row();
            c.add("[lightgray]Centrado en el punto de aparición. Más allá del límite no se genera nada.[]").padBottom(8f).row();
            c.table(t -> {
                filaLimite(t, "Horizontal (X)", limX);
                filaLimite(t, "Vertical (Y)", limY);
            }).padBottom(14f).row();

            final String mods = OpenWorldAPI.resumenMods();
            if(!mods.isEmpty()){
                c.add("[accent]Mods que alteran el mundo:[] " + mods).width(420f).wrap().padBottom(10f).row();
            }

            c.button("Crear mundo", () -> { d.hide(); Mundos.crearNuevo(nombre[0], nuc[0], semilla[0], limX[0], limY[0], gamerules); }).size(320f, 64f).pad(6f).row();
            d.cont.pane(c).grow();
            d.addCloseButton();
            d.show();
        }
    }
}
