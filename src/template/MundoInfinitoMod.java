package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Http;
import arc.util.Log;
import arc.util.Threads;
import arc.util.Time;
import arc.util.noise.Simplex;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.Team;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.meta.Env;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mod "Mundo Infinito": sandbox por chunks con dos dimensiones (Serpulo / Erekir)
 * conectadas por portales. Archivo único con clases estáticas internas para que
 * sea fácil de subir a un fork de plantilla; si luego quieres separarlo en
 * varios archivos, cada clase interna puede moverse tal cual a su propio .java.
 *
 * Java puro, sin JNI. Todo el trabajo pesado va en hilos daemon; todo lo que toca
 * Groups / Buildings / UI se hace en el hilo principal con Core.app.post().
 *
 * Clases de Mindustry/Arc usadas (resumen):
 *  - Mod: clase base de todo mod; el constructor se ejecuta al cargar el mod.
 *  - Events + ClientLoadEvent: bus de eventos de Arc; este evento salta cuando el cliente terminó de cargar.
 *  - Vars: acceso global (ui, world, state, logic, player...).
 *  - World / Tiles / Tile: mapa de juego. Tile guarda piso (floor), overlay (menas) y bloque (muros, edificios).
 *  - Logic: controla el estado de la partida (reset / play).
 *  - Http: cliente HTTP asíncrono de Arc. Jval: JSON ligero de Arc.
 *  - Simplex: ruido simplex de Arc. Threads.daemon: hilos en segundo plano.
 *  - BaseDialog / Styles: diálogos y estilos nativos de UI.
 */
public class MundoInfinitoMod extends Mod{

    // ------------------------------------------------------------------
    // Constantes globales
    // ------------------------------------------------------------------
    public static final int TAM_MAPA = 1000;                                   // mapa en memoria (tiles)
    public static final int TAM_CHUNK = 32;                                    // tiles por lado de chunk
    public static final int N_CHUNKS = (TAM_MAPA + TAM_CHUNK - 1) / TAM_CHUNK; // chunks por lado (32)
    public static final int RADIO_INICIAL = 3;                                 // 3 => área de 7x7 chunks
    public static final String URL_SEMILLAS = "https://raw.githubusercontent.com/TU_USUARIO/mundo-infinito-semillas/main/semillas/actual.json"; // TODO: URL real
    public static final int TIMEOUT_HTTP_MS = 4000;

    public MundoInfinitoMod(){
        // 1) Inyección en el menú principal una vez cargado el cliente.
        Events.on(ClientLoadEvent.class, e -> Time.runTask(10f, MenuUI::inyectarBoton));

        // 2) Registrar bloques que el jugador construye/destruye (para guardarlos al cambiar de dimensión).
        Events.on(BlockBuildEndEvent.class, e -> {
            if(e.tile != null && e.team == Team.sharded) Mundos.marcar(e.tile);
        });

        // 3) Tocar el portal (bloque marcador) con el jugador cerca activa el viaje.
        Events.on(TapEvent.class, e -> {
            if(e.tile == null || !Vars.state.isGame() || Vars.player == null) return;
            if(e.tile.block() != Mundos.bloquePortal() || e.tile.team() != Team.sharded) return;
            int jx = Vars.player.tileX(), jy = Vars.player.tileY();
            if(Mathf.dst(jx, jy, e.tile.x, e.tile.y) > 14f) return; // el jugador debe estar cerca
            Mundos.viajarPorPortal(jx, jy);
        });
    }

    // ==================================================================
    // Dimensiones
    // ==================================================================
    public enum Dimension{
        SERPULO, EREKIR;

        public Dimension otra(){
            return this == SERPULO ? EREKIR : SERPULO;
        }

        /** Núcleo inicial de cada planeta. */
        public Block nucleo(){
            return this == SERPULO ? Blocks.coreShard : Blocks.coreBastion;
        }

        /** Banderas de entorno (iluminación, clima, ambiente) del planeta. */
        public int entorno(){
            return this == SERPULO
                ? (Env.terrestrial | Env.spores | Env.groundOil | Env.groundWater | Env.oxygen)
                : (Env.terrestrial | Env.scorching);
        }
    }

    // ==================================================================
    // 1. Interfaz: botón en el menú + selector de origen
    // ==================================================================
    static class MenuUI{
        private static boolean inyectado = false;

        static void inyectarBoton(){
            if(inyectado) return;
            Table menu = buscarTablaMenu();

            if(menu != null){
                // Botón nativo (Styles.cleart) con el ancho estándar de los botones del menú.
                TextButton b = new TextButton("Mundo Infinito", Styles.cleart);
                b.clicked(MenuUI::abrirSelector);
                menu.row();
                menu.add(b).width(240f).center().row();
                inyectado = true;
            }else{
                // Plan B: si esta build no expone la tabla del menú, se usa una capa propia
                // anclada abajo-centro y visible solo en el menú principal.
                Core.scene.add(new Table(t -> {
                    t.setFillParent(true);
                    t.bottom();
                    t.visible(() -> Vars.state.isMenu());
                    t.button("Mundo Infinito", Styles.cleart, MenuUI::abrirSelector).width(240f).padBottom(12f);
                }));
                inyectado = true;
                Log.warn("[MundoInfinito] No se encontró getMenuTable(); se usó capa alternativa.");
            }
        }

        /** MindustryX puede exponer getMenuTable(); por reflexión compila también con Mindustry vanilla. */
        static Table buscarTablaMenu(){
            try{
                Object frag = Vars.ui.menufrag;
                Object r = frag.getClass().getMethod("getMenuTable").invoke(frag);
                return r instanceof Table ? (Table)r : null;
            }catch(Throwable t){
                return null;
            }
        }

        /** Diálogo "Selector de Origen": elegir Serpulo o Erekir. */
        static void abrirSelector(){
            BaseDialog d = new BaseDialog("Selector de Origen");
            d.cont.add("Elige el planeta donde comienza tu aventura").padBottom(16f).row();
            d.cont.button("Serpulo", () -> { d.hide(); Mundos.iniciarPartida(Dimension.SERPULO); }).size(240f, 64f).pad(6f).row();
            d.cont.button("Erekir", () -> { d.hide(); Mundos.iniciarPartida(Dimension.EREKIR); }).size(240f, 64f).pad(6f).row();
            d.addCloseButton();
            d.show();
        }
    }

    // ==================================================================
    // 2. Semillas: online (GitHub) con respaldo offline
    // ==================================================================
    static class Semillas{

        /** Parámetros del terreno leídos del JSON (o valores por defecto). */
        static class Parametros{
            int semilla;
            float umbralMuro = 0.66f;  // más alto => menos muros
            float escalaMuro = 26f;    // tamaño de las formaciones rocosas

            static Parametros desde(Jval j){
                Parametros p = new Parametros();
                p.semilla = j.getInt("semilla", Mathf.random(1, Integer.MAX_VALUE - 1));
                p.umbralMuro = Mathf.clamp(j.getFloat("umbralMuro", p.umbralMuro), 0.55f, 0.85f);
                p.escalaMuro = Mathf.clamp(j.getFloat("escalaMuro", p.escalaMuro), 10f, 80f);
                return p;
            }
        }

        /**
         * Pide la semilla a GitHub con timeout de 4 s. Ante cualquier fallo (sin internet,
         * timeout, JSON inválido) cae en Modo Offline. El callback se ejecuta UNA sola vez
         * y siempre en el hilo principal.
         */
        static void obtener(Cons<Parametros> alListo){
            AtomicBoolean resuelto = new AtomicBoolean(false);
            Cons<Jval> entregar = j -> {
                if(!resuelto.compareAndSet(false, true)) return;
                Core.app.post(() -> alListo.get(Parametros.desde(j)));
            };

            // Respaldo de seguridad por si el timeout nativo no dispara (red colgada).
            Time.runTask(60f * 5f, () -> entregar.get(semillaOffline()));

            try{
                Http.get(URL_SEMILLAS)
                    .timeout(TIMEOUT_HTTP_MS)
                    .error(err -> {
                        Log.warn("[MundoInfinito] Fallo HTTP (@): modo offline.", err.getMessage());
                        entregar.get(semillaOffline());
                    })
                    .submit(res -> {
                        try{
                            Jval j = Jval.read(res.getResultAsString());
                            if(!j.isObject() || !j.has("semilla")) throw new IllegalStateException("JSON sin 'semilla'");
                            entregar.get(j);
                        }catch(Throwable t){
                            Log.warn("[MundoInfinito] JSON inválido (@): modo offline.", t.getMessage());
                            entregar.get(semillaOffline());
                        }
                    });
            }catch(Throwable t){
                entregar.get(semillaOffline());
            }
        }

        /** Modo Offline: estructura Jval sintética en memoria con semilla pseudoaleatoria local. */
        static Jval semillaOffline(){
            Jval j = Jval.newObject();
            j.put("semilla", Mathf.random(1, Integer.MAX_VALUE - 1));
            j.put("origen", "offline");
            return j;
        }
    }

    // ==================================================================
    // 4. Generador de terreno (ruido simplex, determinista por semilla)
    // ==================================================================
    static class Terreno{

        /** Ruido normalizado a [0,1] (se asume que Simplex.noise2d devuelve ~[-1,1]). */
        static float n(int semilla, double escala, int octavas, double x, double y){
            double r = Simplex.noise2d(semilla, octavas, 0.5, 1.0 / escala, x, y);
            return (float)Mathf.clamp(0.5 + 0.5 * r, 0.0, 1.0);
        }

        /**
         * Genera un chunk completo. Es seguro llamarlo desde un hilo secundario:
         * solo escribe piso / overlay / muros estáticos (no crea edificios).
         *
         * @param centroX,centroY punto de aparición (tiles): se mantiene despejado de muros.
         */
        static void generarChunk(Dimension dim, Semillas.Parametros p, int cx, int cy, int centroX, int centroY){
            int s = p.semilla;
            for(int lx = 0; lx < TAM_CHUNK; lx++){
                for(int ly = 0; ly < TAM_CHUNK; ly++){
                    int x = cx * TAM_CHUNK + lx, y = cy * TAM_CHUNK + ly;
                    Tile t = Vars.world.tile(x, y);
                    if(t == null) continue;

                    // --- Capas base de ruido (misma semilla en ambas dimensiones) ---
                    float elev = n(s, 110, 3, x, y);       // relieve general
                    float hum = n(s + 101, 70, 2, x, y);   // humedad / tipo de roca
                    float dist = Mathf.dst(x, y, centroX, centroY);

                    Block piso, muro = Blocks.air;
                    boolean liquido;
                    if(dim == Dimension.SERPULO){
                        if(elev < 0.20f){ piso = Blocks.deepwater; liquido = true; }
                        else if(elev < 0.28f){ piso = Blocks.water; liquido = true; }
                        else if(elev < 0.31f){ piso = Blocks.sandWater; liquido = true; }
                        else if(hum < 0.35f){ piso = Blocks.sand; muro = Blocks.sandWall; liquido = false; }
                        else if(hum < 0.50f){ piso = Blocks.darksand; muro = Blocks.duneWall; liquido = false; }
                        else if(hum < 0.72f){ piso = Blocks.stone; muro = Blocks.stoneWall; liquido = false; }
                        else{ piso = Blocks.grass; muro = Blocks.dirtWall; liquido = false; }
                    }else{
                        if(elev < 0.18f){ piso = Blocks.arkyciteFloor; liquido = true; }
                        else if(hum < 0.35f){ piso = Blocks.regolith; muro = Blocks.regolithWall; liquido = false; }
                        else if(hum < 0.55f){ piso = Blocks.rhyolite; muro = Blocks.rhyoliteWall; liquido = false; }
                        else if(hum < 0.75f){ piso = Blocks.carbonStone; muro = Blocks.carbonWall; liquido = false; }
                        else{ piso = Blocks.beryllicStone; muro = Blocks.beryllicStoneWall; liquido = false; }
                    }

                    t.setFloor((mindustry.world.blocks.environment.Floor)piso);
                    if(liquido) continue; // sin muros ni menas sobre líquido

                    // --- Muros orgánicos NO lineales ---
                    // (a) Manchas rocosas de baja frecuencia: islas que se rodean, no pasillos.
                    // (b) Vetas finas: líneas cortas de roca (cruce de ruido ~0.5).
                    // (c) Máscara de apertura: en ~40% del plano se prohíben muros, lo que
                    //     garantiza que el espacio libre siempre esté conectado (no hay laberintos).
                    // (d) Radio despejado alrededor del punto de aparición.
                    float mancha = n(s + 3, p.escalaMuro, 3, x, y);
                    boolean veta = Math.abs(n(s + 5, 45, 1, x, y) - 0.5f) < 0.012f;
                    boolean abierto = n(s + 7, 38, 2, x, y) < 0.40f;
                    boolean esMuro = (mancha > p.umbralMuro || veta) && !abierto && dist > 14f;

                    if(esMuro){
                        t.setBlock(muro);
                        continue;
                    }

                    // --- Menas por umbrales de ruido (prioridad en orden) ---
                    Block mena = elegirMena(dim, s, x, y, dist);
                    if(mena != null) t.setOverlay(mena);
                }
            }
        }

        static Block elegirMena(Dimension dim, int s, int x, int y, float dist){
            if(dim == Dimension.SERPULO){
                if(n(s + 21, 14, 2, x, y) > 0.78f) return Blocks.oreCopper;
                if(n(s + 22, 14, 2, x, y) > 0.79f) return Blocks.oreLead;
                if(n(s + 23, 12, 2, x, y) > 0.81f) return Blocks.oreScrap;
                if(dist > 60f && n(s + 24, 13, 2, x, y) > 0.81f) return Blocks.oreCoal;
                if(dist > 120f && n(s + 25, 13, 2, x, y) > 0.83f) return Blocks.oreTitanium;
                if(dist > 260f && n(s + 26, 12, 2, x, y) > 0.85f) return Blocks.oreThorium;
            }else{
                if(n(s + 31, 14, 2, x, y) > 0.78f) return Blocks.oreBeryllium;
                if(dist > 150f && n(s + 32, 13, 2, x, y) > 0.82f) return Blocks.oreTungsten;
                if(dist > 280f && n(s + 33, 12, 2, x, y) > 0.85f) return Blocks.oreCrystalThorium;
            }
            return null;
        }
    }

    // ==================================================================
    // 3 y 5. Mundos: ciclo de vida, generación asíncrona, dimensiones y portales
    // ==================================================================
    static class Mundos{
        // Estado de dimensión actual
        static Dimension actual = Dimension.SERPULO;
        static Semillas.Parametros params;

        // Dos "dimensiones" en RAM: clave "x,y" -> {b: bloque, t: equipo, r: rotación}
        static final ObjectMap<String, Jval> mapaSerpulo = new ObjectMap<>();
        static final ObjectMap<String, Jval> mapaErekir = new ObjectMap<>();
        static final ObjectMap<Dimension, Vec2> posiciones = new ObjectMap<>();
        static final ObjectSet<Dimension> visitadas = new ObjectSet<>();

        // Posiciones (tile empaquetada) modificadas por el jugador en la dimensión actual
        static final IntSet marcados = new IntSet();
        // Índice chunk -> claves guardadas, para restaurar al generar cada chunk
        static IntMap<Seq<String>> indiceChunk = new IntMap<>();

        static volatile int epoca = 0;     // invalida hilos de un mundo anterior
        static boolean viajando = false;

        static ObjectMap<String, Jval> mapa(Dimension d){
            return d == Dimension.SERPULO ? mapaSerpulo : mapaErekir;
        }

        /** Bloque marcador del portal (procesador avanzado nativo, provisional). */
        static Block bloquePortal(){
            return Blocks.hyperProcessor;
        }

        static void marcar(Tile t){
            Tile c = t.build != null ? t.build.tile : t;
            marcados.add(c.pos());
        }

        // ---------------- Inicio de partida nueva ----------------

        static void iniciarPartida(Dimension origen){
            Vars.ui.loadfrag.show("Obteniendo semilla...");
            Semillas.obtener(p -> {
                params = p;
                mapaSerpulo.clear();
                mapaErekir.clear();
                posiciones.clear();
                visitadas.clear();
                actual = origen;
                visitadas.add(origen);
                cargarIndice(origen);
                int c = TAM_MAPA / 2;
                arrancarGeneracion(origen, c, c, true);
            });
        }

        // ---------------- Generación asíncrona por chunks ----------------

        /**
         * Reinicia el mundo y genera en dos fases:
         *  1) Anillo inicial (7x7 chunks) -> se oculta la pantalla de carga y se juega.
         *  2) Anillos exteriores en segundo plano con pausas de 5 ms.
         *
         * @param px,py       posición (tiles) donde aparecerá el jugador
         * @param colocarBase true = colocar núcleo + portal (primera visita); false = se restauran del guardado
         */
        static void arrancarGeneracion(Dimension dim, int px, int py, boolean colocarBase){
            Vars.ui.loadfrag.show("Generando mundo...");
            liberarMundo();

            // Reglas: sin oleadas, entorno del planeta correspondiente.
            Vars.state.rules.waves = false;
            Vars.state.rules.defaultTeam = Team.sharded;
            Vars.state.rules.env = dim.entorno();

            final int mia = ++epoca;
            final int ccx = Mathf.clamp(px / TAM_CHUNK, 0, N_CHUNKS - 1);
            final int ccy = Mathf.clamp(py / TAM_CHUNK, 0, N_CHUNKS - 1);
            final Semillas.Parametros pr = params;

            Vars.world.beginMapLoad();          // marca "generando": evita eventos por tile
            Vars.world.resize(TAM_MAPA, TAM_MAPA);

            Threads.daemon("MundoInfinito-Inicial", () -> {
                try{
                    Vars.world.tiles.fill();    // crea los objetos Tile (~1M)

                    // Fase 1: anillo inicial
                    for(int dx = -RADIO_INICIAL; dx <= RADIO_INICIAL; dx++){
                        for(int dy = -RADIO_INICIAL; dy <= RADIO_INICIAL; dy++){
                            if(epoca != mia) return;
                            int cx = ccx + dx, cy = ccy + dy;
                            if(!enMapa(cx, cy)) continue;
                            Terreno.generarChunk(dim, pr, cx, cy, px, py);
                        }
                    }

                    Core.app.post(() -> {
                        if(epoca != mia) return;
                        // Restaurar lo guardado en los chunks iniciales
                        for(int dx = -RADIO_INICIAL; dx <= RADIO_INICIAL; dx++){
                            for(int dy = -RADIO_INICIAL; dy <= RADIO_INICIAL; dy++){
                                if(enMapa(ccx + dx, ccy + dy)) restaurar(ccx + dx, ccy + dy);
                            }
                        }
                        if(colocarBase) colocarNucleoYPortal(dim, px, py);

                        Vars.world.endMapLoad();     // oclusión de muros, WorldLoadEvent
                        Vars.ui.loadfrag.hide();
                        Vars.logic.play();           // inicia la partida (PlayEvent añade al jugador)
                        viajando = false;

                        Time.runTask(10f, () -> {
                            CoreBuild n = Vars.state.teams.closestCore(px * 8f, py * 8f, Team.sharded);
                            if(n != null) n.requestSpawn(Vars.player);
                            Core.camera.position.set(px * 8f, py * 8f);
                        });
                    });

                    // Fase 2: pre-generación en segundo plano, anillo por anillo
                    int maxR = N_CHUNKS;
                    for(int r = RADIO_INICIAL + 1; r <= maxR; r++){
                        for(int dx = -r; dx <= r; dx++){
                            for(int dy = -r; dy <= r; dy++){
                                if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue; // solo el borde del anillo
                                int cx = ccx + dx, cy = ccy + dy;
                                if(!enMapa(cx, cy)) continue;
                                if(epoca != mia) return;
                                Terreno.generarChunk(dim, pr, cx, cy, px, py);
                                Core.app.post(() -> {
                                    if(epoca != mia) return;
                                    restaurar(cx, cy);
                                });
                                try{ Thread.sleep(5); }catch(InterruptedException e){ return; }
                            }
                        }
                    }
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error generando", t);
                    Core.app.post(() -> {
                        Vars.ui.loadfrag.hide();
                        Vars.ui.showException(t);
                        viajando = false;
                    });
                }
            });
        }

        static boolean enMapa(int cx, int cy){
            return cx >= 0 && cy >= 0 && cx < N_CHUNKS && cy < N_CHUNKS;
        }

        /** Despeja un área de 9x9 y coloca el núcleo y un portal a 7 tiles. Solo en el hilo principal. */
        static void colocarNucleoYPortal(Dimension dim, int px, int py){
            for(int dx = -5; dx <= 12; dx++){
                for(int dy = -5; dy <= 5; dy++){
                    Tile t = Vars.world.tile(px + dx, py + dy);
                    if(t != null && t.block() != Blocks.air) t.setAir();
                }
            }
            Tile tn = Vars.world.tile(px, py);
            Tile tp = Vars.world.tile(px + 7, py);
            if(tn == null || tp == null) return;

            tn.setBlock(dim.nucleo(), Team.sharded);
            if(tn.build != null){
                // Recursos iniciales
                tn.build.items.add(dim == Dimension.SERPULO ? Items.copper : Items.beryllium, 400);
            }
            tp.setBlock(bloquePortal(), Team.sharded);
            marcar(tn);
            marcar(tp);
        }

        // ---------------- Persistencia en RAM por dimensión ----------------

        /** Serializa los bloques modificados de la dimensión actual a su mapa en memoria. */
        static void guardarEstado(){
            ObjectMap<String, Jval> m = mapa(actual);
            for(IntSet.IntSetIterator it = marcados.iterator(); it.hasNext;){
                int pos = it.next();
                int x = Point2.x(pos), y = Point2.y(pos);
                Tile t = Vars.world.tile(x, y);
                if(t == null) continue;
                String clave = x + "," + y;

                if(t.build != null && t.isCenter()){
                    Jval j = Jval.newObject();
                    j.put("b", t.block().name);
                    j.put("t", t.team().id);
                    j.put("r", t.build.rotation);
                    m.put(clave, j);
                }else if(t.block() == Blocks.air){
                    // Algo que existía/estaba en el guardado fue destruido
                    Jval j = Jval.newObject();
                    j.put("b", "air");
                    m.put(clave, j);
                }
                // Partes no-centrales de bloques grandes: ya las cubre el tile central
            }
        }

        /** Carga el mapa guardado de una dimensión en `marcados` e `indiceChunk`. */
        static void cargarIndice(Dimension d){
            marcados.clear();
            indiceChunk = new IntMap<>();
            for(ObjectMap.Entry<String, Jval> e : mapa(d)){
                String[] xy = e.key.split(",");
                int x = Integer.parseInt(xy[0]), y = Integer.parseInt(xy[1]);
                marcados.add(Point2.pack(x, y));
                int k = (y / TAM_CHUNK) * N_CHUNKS + (x / TAM_CHUNK);
                Seq<String> l = indiceChunk.get(k);
                if(l == null){ l = new Seq<>(); indiceChunk.put(k, l); }
                l.add(e.key);
            }
        }

        /** Reaplica los bloques guardados que caen dentro de un chunk recién generado. Hilo principal. */
        static void restaurar(int cx, int cy){
            Seq<String> claves = indiceChunk.get(cy * N_CHUNKS + cx);
            if(claves == null) return;
            ObjectMap<String, Jval> m = mapa(actual);
            for(String clave : claves){
                Jval j = m.get(clave);
                if(j == null) continue;
                String[] xy = clave.split(",");
                Tile t = Vars.world.tile(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
                if(t == null) continue;

                String nombre = j.getString("b", "air");
                if(nombre.equals("air")){ t.setAir(); continue; }
                Block b = Vars.content.block(nombre);
                if(b == null) continue;
                t.setBlock(b, Team.get(j.getInt("t", Team.sharded.id)), j.getInt("r", 0));
            }
        }

        // ---------------- Liberación de memoria ----------------

        /**
         * Equivalente al "world.clear()": reinicia la lógica (elimina edificios y unidades)
         * y reduce el mapa a 1x1 para soltar el arreglo de ~1M tiles del planeta anterior.
         */
        static void liberarMundo(){
            epoca++;                    // cancela cualquier hilo de generación vivo
            Vars.logic.reset();
            Vars.world.resize(1, 1);
            System.gc();                // sugerencia al GC (en Android ayuda tras liberar ~50 MB)
        }

        // ---------------- Portales ----------------

        /**
         * Viaja a la otra dimensión.
         * @param jugadorX,jugadorY posición del jugador en tiles al activar el portal
         */
        static void viajarPorPortal(int jugadorX, int jugadorY){
            if(viajando) return;
            viajando = true;
            Vars.ui.loadfrag.show("Cruzando el portal...");

            // 1) Guardar bloques modificados  2) guardar posición exacta
            guardarEstado();
            posiciones.put(actual, new Vec2(jugadorX, jugadorY));

            // 3) Limpieza total  4) conmutar dimensión
            Dimension destino = actual.otra();
            actual = destino;
            boolean visitada = visitadas.contains(destino);
            visitadas.add(destino);
            cargarIndice(destino);

            // 5) Misma semilla, interpretada con los assets del nuevo planeta
            // 6) Primera visita: núcleo + portal de retorno junto al jugador. Si ya se visitó,
            //    todo se restaura desde el guardado y se reaparece donde se dejó.
            Vec2 pos = visitada && posiciones.containsKey(destino)
                ? posiciones.get(destino)
                : new Vec2(jugadorX, jugadorY);
            int px = Mathf.clamp((int)pos.x, 40, TAM_MAPA - 40);
            int py = Mathf.clamp((int)pos.y, 40, TAM_MAPA - 40);
            arrancarGeneracion(destino, px, py, !visitada);
        }
    }
}
