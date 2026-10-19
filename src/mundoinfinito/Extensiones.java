package mundoinfinito;

import arc.Events;
import arc.files.Fi;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.mod.Mods.LoadedMod;
import mundoinfinito.MundoInfinitoMod.ChunkData;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Muestreo;
import mundoinfinito.MundoInfinitoMod.Paleta;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * REGISTRO CENTRAL de los puntos de extensión de {@link OpenWorldAPI} (versión 2) y orquestación de las dos fases de generación:
 *
 *   FASE 1, hilos de fondo, pura: terreno base -> fenómenos -> terreno de mods -> evento TerrenoGenerado. Todo sobre las 1024 casillas del
 *     chunk (ContextoTerreno), sin tocar Vars.world.
 *   FASE 2, hilo del juego, desde una cola: estructuras (edificios y unidades), una sola vez por ancla.
 *
 * Reglas que garantiza este registro:
 *   - DETERMINISMO: los generadores se ordenan por (prioridad, id), nunca por orden de carga de los mods, y cada uno recibe una semilla
 *     derivada de su id. El cierre del registro (al abrir el primer mundo) evita que un generador tardío deje chunks a medias.
 *   - AISLAMIENTO: una excepción en un generador lo desactiva para el mundo en curso, queda en el registro y el chunk se publica igual.
 *   - La caché de terreno se invalida si cambia el conjunto de generadores ({@link #firma()}).
 */
final class Extensiones{
    static final String LOG = "[MundoInfinito][API] ";
    /** Capacidades que ofrece esta versión de la API (las que un mod puede pedir en su mod.json). */
    static final String[] CAPACIDADES = {"terreno", "fenomeno", "estructura", "entidades", "mundos", "almacen", "eventos", "gamerules"};

    private Extensiones(){}

    // ================================================================== estado del registro
    private static final Object CERROJO = new Object();
    private static volatile GeneradorTerreno[] terreno = new GeneradorTerreno[0];
    private static volatile GeneradorFenomeno[] fenomenos = new GeneradorFenomeno[0];
    private static volatile GeneradorEstructura[] estructuras = new GeneradorEstructura[0];
    private static final CopyOnWriteArrayList<PoliticaSueno> politicas = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Team> equipos = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, MundoRegistrado> mundosPorId = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, MundoRegistrado> mundosPorDim = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Escucha> escuchasTerreno = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, GameRule> gameRules = new ConcurrentHashMap<>();

    /** true tras abrir el primer mundo: ya no se admiten generadores ni mundos nuevos (cambiarían chunks ya generados). */
    private static volatile boolean cerrado;

    /** Versión de generación del mundo abierto (Meta.gen). Los mundos creados antes de la revisión de Parte A conservan su terreno. */
    static volatile int generacion = MundoInfinitoMod.GEN_ACTUAL;

    /** Generadores desactivados por un error, en el mundo en curso. */
    private static final ConcurrentHashMap<String, Boolean> desactivados = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> lentosAvisados = new ConcurrentHashMap<>();

    private static final class Escucha{
        final String id;
        final Cons<OpenWorldAPI.TerrenoGenerado> cb;

        Escucha(String id, Cons<OpenWorldAPI.TerrenoGenerado> cb){
            this.id = id;
            this.cb = cb;
        }
    }

    private static final Comparator<Object> ORDEN = Comparator
        .comparingInt((Object o) -> prioridad(o))
        .thenComparing(o -> id(o));

    private static int prioridad(Object o){
        if(o instanceof GeneradorTerreno) return ((GeneradorTerreno)o).prioridad();
        if(o instanceof GeneradorFenomeno) return ((GeneradorFenomeno)o).prioridad();
        return ((GeneradorEstructura)o).prioridad();
    }

    private static String id(Object o){
        if(o instanceof GeneradorTerreno) return ((GeneradorTerreno)o).id();
        if(o instanceof GeneradorFenomeno) return ((GeneradorFenomeno)o).id();
        return ((GeneradorEstructura)o).id();
    }

    // ================================================================== registro
    private static boolean idValido(String id, String que){
        if(id == null || id.indexOf(':') <= 0 || id.indexOf(':') == id.length() - 1 || id.indexOf(' ') >= 0){
            Log.err(LOG + "@ rechazado: el id debe tener el formato \"idmod:nombre\" y no tiene espacios (recibido: @)", que, id);
            return false;
        }
        return true;
    }

    private static boolean puedeRegistrarTerreno(String id, String que){
        if(!idValido(id, que)) return false;
        if(cerrado){
            Log.err(LOG + "@ \"@\" rechazado: ya se abrió un mundo. Hay que registrarlo al cargar el mod (constructor, init o loadContent).", que, id);
            return false;
        }
        return true;
    }

    static boolean registrarTerreno(GeneradorTerreno g){
        if(g == null || !puedeRegistrarTerreno(g.id(), "Generador de terreno")) return false;
        synchronized(CERROJO){
            for(GeneradorTerreno x : terreno) if(x.id().equals(g.id())){ Log.err(LOG + "Generador de terreno duplicado: @", g.id()); return false; }
            final GeneradorTerreno[] n = Arrays.copyOf(terreno, terreno.length + 1);
            n[n.length - 1] = g;
            Arrays.sort(n, ORDEN);
            terreno = n;
        }
        Log.info(LOG + "Generador de terreno registrado: @ (prioridad @)", g.id(), g.prioridad());
        return true;
    }

    static boolean registrarFenomeno(GeneradorFenomeno g){
        if(g == null || !puedeRegistrarTerreno(g.id(), "Generador de fenómeno")) return false;
        if(g.celda() < 8 || g.distanciaMinima() < 1 || g.radio() < 0 || g.intentos() < 1){
            Log.err(LOG + "Generador de fenómeno \"@\" rechazado: celda >= 8, distanciaMinima >= 1, radio >= 0 e intentos >= 1", g.id());
            return false;
        }
        synchronized(CERROJO){
            for(GeneradorFenomeno x : fenomenos) if(x.id().equals(g.id())){ Log.err(LOG + "Generador de fenómeno duplicado: @", g.id()); return false; }
            final GeneradorFenomeno[] n = Arrays.copyOf(fenomenos, fenomenos.length + 1);
            n[n.length - 1] = g;
            Arrays.sort(n, ORDEN);
            fenomenos = n;
        }
        Log.info(LOG + "Generador de fenómeno registrado: @ (celda @, distancia mínima @)", g.id(), g.celda(), g.distanciaMinima());
        return true;
    }

    static boolean registrarEstructura(GeneradorEstructura g){
        if(g == null || !idValido(g.id(), "Generador de estructura")) return false;
        if(g.celda() < 16 || g.radio() < 0){
            Log.err(LOG + "Generador de estructura \"@\" rechazado: celda >= 16 y radio >= 0", g.id());
            return false;
        }
        synchronized(CERROJO){
            for(GeneradorEstructura x : estructuras) if(x.id().equals(g.id())){ Log.err(LOG + "Generador de estructura duplicado: @", g.id()); return false; }
            final GeneradorEstructura[] n = Arrays.copyOf(estructuras, estructuras.length + 1);
            n[n.length - 1] = g;
            Arrays.sort(n, ORDEN);
            estructuras = n;
        }
        Log.info(LOG + "Generador de estructura registrado: @", g.id());
        return true;
    }

    static boolean registrarPolitica(PoliticaSueno p){
        if(p == null || !idValido(p.id(), "Política de sueño")) return false;
        for(PoliticaSueno x : politicas) if(x.id().equals(p.id())){ Log.err(LOG + "Política de sueño duplicada: @", p.id()); return false; }
        politicas.add(p);
        Log.info(LOG + "Política de sueño registrada: @", p.id());
        return true;
    }

    static boolean registrarEquipo(Team t){
        if(t == null) return false;
        if(equipos.addIfAbsent(t)) Log.info(LOG + "Equipo guardado siempre: @", t.name);
        return true;
    }

    static boolean registrarMundo(MundoRegistrado m){
        if(m == null || m.generador == null || m.id == null || m.id.trim().isEmpty()){
            Log.err(LOG + "Mundo rechazado: necesita id y generador");
            return false;
        }
        if(cerrado){
            Log.err(LOG + "Mundo \"@\" rechazado: ya se abrió un mundo. Hay que registrarlo al cargar el mod.", m.id);
            return false;
        }
        synchronized(CERROJO){
            if(mundosPorId.containsKey(m.id)){ Log.err(LOG + "Mundo duplicado: @", m.id); return false; }
            // la dimensión sale del id: estable entre sesiones y entre órdenes de carga (se guarda en meta.json y da nombre a la carpeta d<N>)
            final int dim = MundoRegistrado.DIM_MINIMA + (Muestreo.mix(m.id.hashCode()) & 0x7FFFFF);
            if(mundosPorDim.containsKey(dim)){ Log.err(LOG + "Mundo \"@\" rechazado: su dimensión coincide con la de \"@\". Cambia el id.", m.id, mundosPorDim.get(dim).id); return false; }
            m.dimension = dim;
            mundosPorId.put(m.id, m);
            mundosPorDim.put(dim, m);
        }
        Log.info(LOG + "Mundo registrado: @ (dimensión @)", m.id, m.dimension);
        try{
            Events.fire(new OpenWorldAPI.MundoRegistradoEvento(m));
        }catch(Throwable t){
            Log.err(LOG + "Un mod falló en MundoRegistradoEvento", t);
        }
        return true;
    }

    /** Una gamerule no afecta al terreno ya generado, así que se puede registrar en cualquier momento (el diálogo de nuevo mundo lee la lista al abrirse). */
    static boolean registrarGameRule(GameRule g){
        if(g == null || g.nombre == null || g.nombre.trim().isEmpty() || g.aplicacion == null){
            Log.err(LOG + "Gamerule rechazada: necesita id, nombre y aplicación");
            return false;
        }
        if(!idValido(g.id, "Gamerule")) return false;
        if(gameRules.putIfAbsent(g.id, g) != null){
            Log.err(LOG + "Gamerule duplicada: @", g.id);
            return false;
        }
        RegistroAjustes.registrar(AjusteMundo.deGameRule(g));   // una gamerule es un ajuste booleano de ámbito JUEGO (fase 4)
        Log.info(LOG + "Gamerule registrada: @ (por defecto: @)", g.id, g.porDefecto);
        return true;
    }

    static GameRule gameRule(String id){
        return id == null ? null : gameRules.get(id);
    }

    /** Todas las gamerules: primero las del mod base, después las de los demás mods; dentro de cada grupo, por id (orden estable). */
    static Seq<GameRule> gameRules(){
        final Seq<GameRule> r = new Seq<>();
        for(GameRule g : gameRules.values()) r.add(g);
        r.sort((a, b) -> {
            final boolean ba = a.id.startsWith(OpenWorldAPI.ID_MOD + ":"), bb = b.id.startsWith(OpenWorldAPI.ID_MOD + ":");
            return ba != bb ? (ba ? -1 : 1) : a.id.compareTo(b.id);
        });
        return r;
    }

    /** Aplica sobre las reglas de Mindustry el valor que el mundo guardó para cada gamerule (o su valor por defecto). Hilo del juego. */
    static void aplicarGameRules(MundoInfinitoMod.Meta m, mindustry.game.Rules r){
        if(m == null) return;
        final int dim = m.dimensional() ? m.dim : -1;
        for(GameRule g : gameRules()){
            try{
                g.aplicacion.aplicar(r, m.gameRule(g), dim);
            }catch(Throwable t){
                Log.err(LOG + "La gamerule \"@\" falló al aplicarse", g.id, t);
            }
        }
    }

    static void alTerrenoGenerado(String id, Cons<OpenWorldAPI.TerrenoGenerado> cb){
        if(cb == null || !idValido(id, "Escucha de TerrenoGenerado")) return;
        if(cerrado){
            Log.err(LOG + "Escucha de TerrenoGenerado \"@\" rechazada: ya se abrió un mundo.", id);
            return;
        }
        escuchasTerreno.add(new Escucha(id, cb));
    }

    // ================================================================== consultas
    static MundoRegistrado mundoPorDim(int dim){
        return dim < MundoRegistrado.DIM_MINIMA ? null : mundosPorDim.get(dim);
    }

    static MundoRegistrado mundoPorId(String id){
        return mundosPorId.get(id);
    }

    static Seq<MundoRegistrado> mundos(){
        final Seq<MundoRegistrado> r = new Seq<>();
        for(MundoRegistrado m : mundosPorId.values()) r.add(m);
        r.sort((a, b) -> a.id.compareTo(b.id));
        return r;
    }

    /** Equipos del mundo base más los registrados por mods: todos se guardan y se restauran siempre. */
    static Team[] equiposGuardado(Team[] base){
        if(equipos.isEmpty()) return base;
        final ArrayList<Team> l = new ArrayList<>(Arrays.asList(base));
        for(Team t : equipos) if(!l.contains(t)) l.add(t);
        return l.toArray(new Team[0]);
    }

    /** Huella del conjunto de generadores: si cambia, la caché de terreno ya no vale. */
    static int firma(){
        int h = 0x1F3D5B79;
        for(GeneradorTerreno g : terreno) h = Muestreo.mix(h ^ g.id().hashCode() ^ (g.prioridad() * 31));
        for(GeneradorFenomeno g : fenomenos) h = Muestreo.mix(h ^ g.id().hashCode() ^ (g.prioridad() * 31) ^ (g.celda() * 7) ^ (g.distanciaMinima() * 13));
        for(MundoRegistrado m : mundosPorId.values()) h ^= Muestreo.mix(m.id.hashCode());   // XOR: no depende del orden del mapa
        h = Muestreo.mix(h ^ generacion);
        return h;
    }

    // ================================================================== ciclo de vida del mundo
    /** Al (re)iniciar el streaming de un mundo: se cierra el registro y se vuelve a empezar el estado por mundo. */
    static void alReiniciarMundo(MundoInfinitoMod.Meta m){
        cerrado = true;
        generacion = m == null ? MundoInfinitoMod.GEN_ACTUAL : m.gen;
        desactivados.clear();
        lentosAvisados.clear();
        Fenomenos.reiniciar();
        synchronized(pendientes){
            pendientes.clear();
            enCola.clear();
        }
    }

    /** ¿Usa este mundo la generación revisada (Parte A)? Los mundos de gen 3 guardados antes conservan su terreno exacto. */
    static boolean generacionNueva(){
        return generacion >= MundoInfinitoMod.GEN_REVISADA;
    }

    // ================================================================== FASE 1: terreno (hilos de fondo)
    private static void fallo(String id, Throwable t){
        if(desactivados.put(id, Boolean.TRUE) == null){
            Log.err(LOG + "El generador \"@\" falló y queda desactivado en este mundo. El chunk se publica sin él.", id, t);
        }
    }

    private static void vigilar(String id, int presupuestoMs, long t0){
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        if(ms > Math.max(1, presupuestoMs) && lentosAvisados.putIfAbsent(id, Boolean.TRUE) == null){
            Log.warn(LOG + "El generador \"@\" tardó @ ms en un chunk (presupuesto orientativo: @ ms). Cada generador suma tiempo a la carga del terreno.", id, ms, presupuestoMs);
        }
    }

    /** Hilo de fondo. Aplica fenómenos, terreno de mods y escuchas sobre el chunk virtual (vcx, vcy) ya relleno por el terreno base o el mundo. */
    static void aplicarTerreno(ChunkData d, Paleta pal, int semilla, int dim, int vcx, int vcy){
        final GeneradorFenomeno[] fs = fenomenos;
        final GeneradorTerreno[] ts = terreno;
        if(fs.length == 0 && ts.length == 0 && escuchasTerreno.isEmpty()) return;

        for(GeneradorFenomeno g : fs){
            final String id = g.id();
            if(desactivados.containsKey(id) || !g.aplicaA(dim)) continue;
            final long t0 = System.nanoTime();
            final ContextoTerreno ctx = new ContextoTerreno(d.piso, d.mena, d.bloque, pal, semilla, dim, vcx, vcy, id);
            try{
                Fenomenos.aplicar(g, ctx);
            }catch(Throwable t){
                fallo(id, t);
            }finally{
                ctx.cerrar();
            }
            vigilar(id, g.presupuestoMs(), t0);
        }

        for(GeneradorTerreno g : ts){
            final String id = g.id();
            if(desactivados.containsKey(id) || !g.aplicaA(dim)) continue;
            final long t0 = System.nanoTime();
            final ContextoTerreno ctx = new ContextoTerreno(d.piso, d.mena, d.bloque, pal, semilla, dim, vcx, vcy, id);
            try{
                g.generar(ctx);
            }catch(Throwable t){
                fallo(id, t);
            }finally{
                ctx.cerrar();
            }
            vigilar(id, g.presupuestoMs(), t0);
        }

        for(Escucha e : escuchasTerreno){
            if(desactivados.containsKey(e.id)) continue;
            final ContextoTerreno ctx = new ContextoTerreno(d.piso, d.mena, d.bloque, pal, semilla, dim, vcx, vcy, e.id);
            try{
                e.cb.get(new OpenWorldAPI.TerrenoGenerado(ctx));
            }catch(Throwable t){
                fallo(e.id, t);
            }finally{
                ctx.cerrar();
            }
        }
    }

    /** Hilo de fondo. Rellena el chunk de un mundo registrado: piedra por defecto y después su generador. */
    static void generarMundo(ChunkData d, Paleta pal, int semilla, int dim, int vcx, int vcy){
        Arrays.fill(d.piso, (short)Blocks.stone.id);
        Arrays.fill(d.mena, (short)0);
        Arrays.fill(d.bloque, (short)0);
        final MundoRegistrado w = mundoPorDim(dim);
        if(w == null){
            if(lentosAvisados.putIfAbsent("sin-mundo:" + dim, Boolean.TRUE) == null){
                Log.err(LOG + "El mundo guardado usa la dimensión @, que ningún mod registra ahora (¿falta el mod?). Se genera solo piedra.", dim);
            }
            return;
        }
        final String id = "mundo:" + w.id;
        if(desactivados.containsKey(id)) return;
        final long t0 = System.nanoTime();
        final ContextoTerreno ctx = new ContextoTerreno(d.piso, d.mena, d.bloque, pal, semilla, dim, vcx, vcy, w.id);
        try{
            w.generador.generar(ctx);
        }catch(Throwable t){
            fallo(id, t);
        }finally{
            ctx.cerrar();
        }
        vigilar(id, 40, t0);
    }

    // ================================================================== FASE 2: estructuras (hilo del juego)
    private static final class Pendiente{
        final GeneradorEstructura g;
        final int ax, ay, cx, cy;
        final String clave;

        Pendiente(GeneradorEstructura g, int ax, int ay){
            this.g = g;
            this.ax = ax;
            this.ay = ay;
            this.cx = Math.floorDiv(ax, MundoInfinitoMod.TAM_CHUNK);
            this.cy = Math.floorDiv(ay, MundoInfinitoMod.TAM_CHUNK);
            this.clave = g.id() + "@" + ax + "," + ay;
        }
    }

    private static final ArrayList<Pendiente> pendientes = new ArrayList<>();
    private static final HashSet<String> enCola = new HashSet<>();

    /**
     * Hilo del juego. Llamado cuando un chunk acaba de resolverse para restaurar (su región ya está en memoria): busca las anclas de estructura que
     * caen EN ese chunk y que aún no se colocaron, y las encola. Cada ancla pertenece a un único chunk, así que nunca se encola dos veces.
     */
    static void alRestaurarChunk(int cx, int cy){
        final GeneradorEstructura[] es = estructuras;
        if(es.length == 0 || Streamer.meta == null || Streamer.paleta == null) return;
        final int dim = Streamer.meta.dimGen(), semilla = Streamer.meta.semilla, T = MundoInfinitoMod.TAM_CHUNK;
        final int x0 = cx * T, y0 = cy * T;
        final int[] out = new int[2];
        for(GeneradorEstructura g : es){
            final String id = g.id();
            if(desactivados.containsKey(id) || !g.aplicaA(dim)) continue;
            try{
                final ContextoFenomeno c = new ContextoFenomeno(Streamer.paleta, semilla, dim, id);
                final int celda = g.celda();
                for(int j = Math.floorDiv(y0, celda); j <= Math.floorDiv(y0 + T - 1, celda); j++){
                    for(int i = Math.floorDiv(x0, celda); i <= Math.floorDiv(x0 + T - 1, celda); i++){
                        if(!g.ancla(c, i, j, out)) continue;
                        if(Math.floorDiv(out[0], T) != cx || Math.floorDiv(out[1], T) != cy) continue;   // el ancla es de otro chunk
                        final Pendiente p = new Pendiente(g, out[0], out[1]);
                        if(AlmacenModulo.generado(cx, cy, p.clave)) continue;
                        synchronized(pendientes){
                            if(enCola.add(p.clave)) pendientes.add(p);
                        }
                    }
                }
            }catch(Throwable t){
                fallo(id, t);
            }
        }
    }

    /** Hilo del juego, cada fotograma. Coloca las estructuras cuyo terreno y edificios guardados ya están listos, dentro del presupuesto. */
    static void tick(){
        synchronized(pendientes){
            if(pendientes.isEmpty()) return;
        }
        if(Streamer.meta == null || Streamer.restaurando || Mundos.viajando || !Streamer.colaRestaurar.isEmpty()) return;
        final long fin = Presupuesto.hasta(1_500_000L);
        final int dim = Streamer.meta.dimGen(), semilla = Streamer.meta.semilla, T = MundoInfinitoMod.TAM_CHUNK;
        boolean primera = true;
        final ArrayList<Pendiente> copia;
        synchronized(pendientes){
            copia = new ArrayList<>(pendientes);
        }
        for(Pendiente p : copia){
            if(!primera && System.nanoTime() >= fin) break;
            // el chunk del ancla se descargó: se vuelve a encolar cuando se cargue de nuevo
            if(!Streamer.generados.contains(Streamer.clave(p.cx, p.cy))){
                quitar(p);
                continue;
            }
            if(desactivados.containsKey(p.g.id())){
                quitar(p);
                continue;
            }
            if(!cubierta(p, T)) continue;
            if(!AlmacenModulo.listo(p.cx, p.cy)) continue;
            primera = false;
            // se marca ANTES de colocar: si colocar falla no se reintenta en bucle; si la región no está lista no se ha hecho nada todavía
            if(!AlmacenModulo.marcarGenerado(p.cx, p.cy, p.clave)) continue;
            quitar(p);
            try{
                p.g.colocar(new ContextoColocacion(p.g.id(), dim, semilla, p.ax, p.ay));
            }catch(Throwable t){
                fallo(p.g.id(), t);
            }
        }
    }

    private static void quitar(Pendiente p){
        synchronized(pendientes){
            pendientes.remove(p);
            enCola.remove(p.clave);
        }
    }

    /** ¿Están cargados y con sus edificios guardados ya restaurados todos los chunks que toca la plantilla? (los que el límite del mundo impide cargar cuentan como listos) */
    private static boolean cubierta(Pendiente p, int T){
        final int r = p.g.radio();
        for(int cy = Math.floorDiv(p.ay - r, T); cy <= Math.floorDiv(p.ay + r, T); cy++){
            for(int cx = Math.floorDiv(p.ax - r, T); cx <= Math.floorDiv(p.ax + r, T); cx++){
                if(!Streamer.permitido(cx, cy)) continue;
                final int k = Streamer.clave(cx, cy);
                if(!Streamer.generados.contains(k) || !Anillo.restaurados.contains(k)) return false;
            }
        }
        return true;
    }

    // ================================================================== entidades
    /** 2 = una política de mod manda dormir a esta unidad; 0 = no. */
    static int claseSueno(Unit u){
        if(politicas.isEmpty()) return 0;
        for(PoliticaSueno p : politicas){
            try{
                if(p.duerme(u)) return 2;
            }catch(Throwable t){
                politicas.remove(p);
                Log.err(LOG + "La política de sueño \"@\" falló y se desactiva", p.id(), t);
            }
        }
        return 0;
    }

    // ================================================================== versión y capacidades (mod.json)
    private static boolean verificado;

    /**
     * Lee de cada mod activo su declaración propia (Mindustry no valida versiones de dependencias):
     *     "openWorld": { "api": 2, "capacidades": ["terreno", "fenomeno"] }
     * y deja en el registro, con el prefijo de la API, todo lo que pida y esta versión no ofrezca. No desactiva el mod: solo avisa con claridad.
     */
    static void verificarMods(){
        if(verificado || Vars.mods == null) return;
        verificado = true;
        for(LoadedMod m : Vars.mods.list()){
            if(m == null || !m.enabled() || OpenWorldAPI.ID_MOD.equals(m.name)) continue;
            try{
                Fi f = m.root.child("mod.json");
                if(!f.exists()) f = m.root.child("mod.hjson");
                if(!f.exists()) continue;
                final Jval j = Jval.read(f.readString());
                if(!j.isObject() || !j.has("openWorld")) continue;
                final Jval ow = j.get("openWorld");
                if(ow == null || !ow.isObject()) continue;
                final int api = ow.getInt("api", 1);
                if(api > OpenWorldAPI.VERSION){
                    Log.err(LOG + "El mod \"@\" pide la API v@ del mundo abierto y esta versión es la v@. Actualiza Mundo Infinito: lo que use de la API nueva no funcionará.", m.name, api, OpenWorldAPI.VERSION);
                }
                final Jval caps = ow.get("capacidades");
                if(caps != null && caps.isArray()){
                    for(Jval c : caps.asArray()){
                        final String nombre = c.asString();
                        if(!Arrays.asList(CAPACIDADES).contains(nombre)){
                            Log.err(LOG + "El mod \"@\" pide la capacidad \"@\", que la API v@ no ofrece (ofrece: @).", m.name, nombre, OpenWorldAPI.VERSION, Arrays.toString(CAPACIDADES));
                        }
                    }
                }
            }catch(Throwable t){
                Log.warn(LOG + "No se pudo leer \"openWorld\" del mod.json de @: @", m.name, t.toString());
            }
        }
    }

    /** Resumen de lo registrado, para el registro al abrir un mundo. */
    static void registrarEnLog(){
        verificarMods();
        Log.info(LOG + "Registro: @ de terreno, @ de fenómenos, @ de estructuras, @ políticas de sueño, @ mundos, @ equipos propios, @ gamerules.",
            terreno.length, fenomenos.length, estructuras.length, politicas.size(), mundosPorId.size(), equipos.size(), gameRules.size());
    }
}
