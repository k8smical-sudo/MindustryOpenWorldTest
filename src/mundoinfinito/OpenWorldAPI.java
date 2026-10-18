package mundoinfinito;

import arc.Events;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.mod.Mods.LoadedMod;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * API PÚBLICA del mundo abierto para otros mods.
 *
 * El mod es un mundo abierto BASE: terreno infinito por chunks, guardado, niebla, minimapa y dimensiones. No trae enemigos, estructuras
 * ni abandonados: eso lo añaden los mods que dependan de él. Un mod se declara dependiente poniendo en su mod.json:
 *
 *     "dependencies": ["mundo-infinito"]            (o "softDependencies" si funciona también sin él)
 *
 * y se engancha con los eventos de Arc (Events.on) o con los registros de esta clase. TODO se llama en el hilo del juego, así que un mod
 * puede tocar casillas, edificios y unidades con normalidad.
 *
 *   Events.on(OpenWorldAPI.ChunkCargado.class, e -> { ... e.cx, e.cy ... });     // chunk publicado y edificios guardados ya en cola
 *   Events.on(OpenWorldAPI.ChunkDescargado.class, e -> { ... });                 // justo después de guardar y soltar el chunk
 *   Events.on(OpenWorldAPI.MundoAbierto.class, e -> { ... });                    // al crear o continuar un mundo (o cambiar de dimensión)
 *   OpenWorldAPI.registrarReglas(r -> r.waveTimer = false);                       // se aplica a las reglas de cada mundo al abrirlo
 *
 * Desde la v2 hay además puntos de extensión (registrarTerreno, registrarFenomeno, registrarEstructura, registrarPolitica, registrarEquipo,
 * registrarMundo, alTerrenoGenerado, viajarA) y las clases públicas de Generador*, Contexto*, PoliticaSueno, MundoRegistrado y AlmacenModulo.
 *
 * Lo que un mod coloque en un chunk (edificios de cualquier equipo, incluidos equipos nuevos) se guarda y se restaura con él: en mundos
 * nuevos el mod no descarta nada. Lo que un mod ponga en el mundo NO se regenera: el terreno sí es una función pura de la semilla.
 */
public final class OpenWorldAPI{
    private OpenWorldAPI(){}

    /**
     * Versión de esta API. 2 = puntos de extensión (generadores de terreno, fenómenos y estructuras, políticas de sueño, mundos registrados,
     * almacén por módulo, equipos propios y eventos nuevos). Todo lo de la versión 1 sigue funcionando igual.
     * Un mod declara la mínima que necesita en su mod.json:  "openWorld": { "api": 2, "capacidades": ["terreno", "fenomeno"] }
     */
    public static final int VERSION = 2;
    /** Nombre interno del mod: lo que va en "dependencies" de otro mod. */
    public static final String ID_MOD = "mundo-infinito";

    // ------------------------------------------------------------------ eventos

    /** Se creó o continuó un mundo, o se cambió de dimensión. Aún se están cargando los chunks iniciales. */
    public static class MundoAbierto{
        public final String id, nombre;
        public final int semilla, dimension, limiteX, limiteY;
        public final boolean nuevo;

        MundoAbierto(Meta m, boolean nuevo){
            this.id = m.id;
            this.nombre = m.nombre;
            this.semilla = m.semilla;
            this.dimension = m.dim;
            this.limiteX = m.limX;
            this.limiteY = m.limY;
            this.nuevo = nuevo;
        }
    }

    /** Un chunk acaba de publicarse en el mundo (sus casillas ya existen). Coordenadas de chunk VIRTUALES (tile = chunk * 32 + 0..31). */
    public static class ChunkCargado{
        public final int cx, cy;

        ChunkCargado(int cx, int cy){
            this.cx = cx;
            this.cy = cy;
        }
    }

    /** Un chunk acaba de guardarse y soltarse (sus casillas ya no existen). */
    public static class ChunkDescargado{
        public final int cx, cy;

        ChunkDescargado(int cx, int cy){
            this.cx = cx;
            this.cy = cy;
        }
    }

    /** (v2) Un chunk está a punto de publicarse. HILO DE FONDO: el contexto solo vale dentro de la llamada. Ver {@link GeneradorTerreno}. */
    public static class TerrenoGenerado{
        public final ContextoTerreno ctx;

        TerrenoGenerado(ContextoTerreno ctx){
            this.ctx = ctx;
        }
    }

    /** (v2) Un edificio guardado acaba de restaurarse al cargar su chunk. Hilo del juego. Solo para reaccionar: no se puede cancelar. */
    public static class EdificioRestaurado{
        public final mindustry.gen.Building edificio;

        EdificioRestaurado(mindustry.gen.Building b){
            this.edificio = b;
        }
    }

    /** (v2) Una unidad de un mod salió del sueño (ver {@link PoliticaSueno}). Hilo del juego. */
    public static class EntidadDespertada{
        public final mindustry.gen.Unit unidad;

        EntidadDespertada(mindustry.gen.Unit u){
            this.unidad = u;
        }
    }

    /** (v2) Se registró un mundo (ver {@link #registrarMundo}). Hilo del juego. El mundo ya tiene su dimensión asignada. */
    public static class MundoRegistradoEvento{
        public final MundoRegistrado mundo;

        MundoRegistradoEvento(MundoRegistrado m){
            this.mundo = m;
        }
    }

    // ------------------------------------------------------------------ (v2) puntos de extensión
    // Todos devuelven false (y dejan el motivo en el registro, con el prefijo "[MundoInfinito][API]") si el registro se rechaza; nunca lanzan.
    // Terreno, fenómenos, mundos y escuchas de TerrenoGenerado se registran AL CARGAR EL MOD (constructor, init o loadContent): al abrir el
    // primer mundo el registro se cierra, porque un generador tardío dejaría chunks ya generados sin él.

    /** Generador de terreno de fondo. Ver {@link GeneradorTerreno}. */
    public static boolean registrarTerreno(GeneradorTerreno g){
        return Extensiones.registrarTerreno(g);
    }

    /** Generador de fenómenos con distancia mínima. Ver {@link GeneradorFenomeno}. */
    public static boolean registrarFenomeno(GeneradorFenomeno g){
        return Extensiones.registrarFenomeno(g);
    }

    /** Generador de estructuras (plantillas colocadas una vez). Ver {@link GeneradorEstructura}. */
    public static boolean registrarEstructura(GeneradorEstructura g){
        return Extensiones.registrarEstructura(g);
    }

    /** Política que decide si las unidades de un mod duermen fuera de vista. Ver {@link PoliticaSueno}. Se puede registrar en cualquier momento. */
    public static boolean registrarPolitica(PoliticaSueno p){
        return Extensiones.registrarPolitica(p);
    }

    /**
     * Equipo (Team) propio cuyos edificios se guardan y restauran SIEMPRE. En mundos nuevos ({@code limpio}: creados sin generador de enemigos del
     * mod base) ya se conserva todo lo que pongan los mods; "limpio = false" solo existe en mundos antiguos, donde lo enemigo o abandonado
     * guardado se descartaba. Registrar el equipo garantiza que el guardado periódico lo recorre aunque el base no lo conozca.
     */
    public static boolean registrarEquipo(mindustry.game.Team equipo){
        return Extensiones.registrarEquipo(equipo);
    }

    /**
     * (v2) Añade una gamerule al botón "Gamerules" del diálogo de nuevo mundo. El jugador la activa o no al crear el mundo, el valor se guarda en
     * el mundo y {@link GameRule#aplicacion} lo aplica sobre las reglas de Mindustry cada vez que se abre. Se puede registrar en cualquier
     * momento antes de crear el mundo. Ver {@link GameRule}.
     */
    public static boolean registrarGameRule(GameRule regla){
        return Extensiones.registrarGameRule(regla);
    }

    /** (v2) Valor de una gamerule en el mundo abierto (su valor por defecto si no hay mundo abierto; false si la regla no existe). Hilo del juego. */
    public static boolean gameRule(String id){
        final GameRule g = Extensiones.gameRule(id);
        if(g == null) return false;
        return Mundos.actual == null ? g.porDefecto : Mundos.actual.gameRule(g);
    }

    /** Registra un mundo (dimensión) nuevo con su generador, reglas y nombre; aparece como destino en los portales. Ver {@link MundoRegistrado}. */
    public static boolean registrarMundo(MundoRegistrado mundo){
        return Extensiones.registrarMundo(mundo);
    }

    /** Escucha el evento TerrenoGenerado (hilo de fondo). {@code id} con formato "idmod:nombre": de él sale la semilla del contexto. */
    public static void alTerrenoGenerado(String id, Cons<TerrenoGenerado> escucha){
        Extensiones.alTerrenoGenerado(id, escucha);
    }

    /** Teletransporta al jugador (hilo del juego) al mundo registrado con ese id, igual que el portal. false si no existe o no se puede viajar ahora. */
    public static boolean viajarA(String idMundo){
        final MundoRegistrado m = Extensiones.mundoPorId(idMundo);
        if(m == null || !activo() || Mundos.viajando || Mundos.actual == null || !Mundos.actual.dimensional() || m.dimension() == Mundos.dimActual()) return false;
        Mundos.saltar(m.dimension());
        return true;
    }

    /** Vuelve a una dimensión del mundo base (0 Serpulo, 1 Erekir). */
    public static boolean viajarADimensionBase(int dimension){
        if((dimension != 0 && dimension != 1) || !activo() || Mundos.viajando || Mundos.actual == null || !Mundos.actual.dimensional() || dimension == Mundos.dimActual()) return false;
        Mundos.saltar(dimension);
        return true;
    }

    /** Capacidades que ofrece esta versión de la API (las que un mod puede pedir en su mod.json). */
    public static String[] capacidades(){
        return Extensiones.CAPACIDADES.clone();
    }

    // ------------------------------------------------------------------ consultas

    /** ¿Hay un mundo abierto jugándose ahora mismo? */
    public static boolean activo(){
        return Streamer.activo && Streamer.meta != null;
    }

    /** Dimensión actual: 0 = Serpulo, 1 = Erekir. */
    public static int dimension(){
        return Mundos.dimActual();
    }

    public static int semilla(){
        return Streamer.meta == null ? 0 : Streamer.meta.semilla;
    }

    /** Límite de terreno en tiles (ancho total). 0 = infinito. */
    public static int limiteX(){
        return Streamer.meta == null ? 0 : Streamer.meta.limX;
    }

    /** Límite de terreno en tiles (alto total). 0 = infinito. */
    public static int limiteY(){
        return Streamer.meta == null ? 0 : Streamer.meta.limY;
    }

    /** ¿Existe el chunk (virtual) en el mundo ahora mismo? */
    public static boolean chunkCargado(int cx, int cy){
        return Streamer.enMapa(cx, cy);
    }

    /** ¿Permite el límite de terreno del mundo que exista este chunk? */
    public static boolean chunkPermitido(int cx, int cy){
        return Streamer.permitido(cx, cy);
    }

    // ------------------------------------------------------------------ reglas

    private static final Seq<Cons<Rules>> reglas = new Seq<>();

    /** Registra un ajuste de reglas que se aplica cada vez que se abre un mundo (después de las reglas del mod). */
    public static void registrarReglas(Cons<Rules> ajuste){
        if(ajuste != null && !reglas.contains(ajuste)) reglas.add(ajuste);
    }

    static void aplicarReglas(Rules r){
        for(Cons<Rules> c : reglas){
            try{
                c.get(r);
            }catch(Throwable t){
                Log.err("[MundoInfinito] Un mod falló al ajustar las reglas", t);
            }
        }
    }

    // ------------------------------------------------------------------ disparadores (uso interno)

    static void mundoAbierto(Meta m, boolean nuevo){
        fire(new MundoAbierto(m, nuevo));
    }

    static void chunkCargado_(int cx, int cy){
        fire(new ChunkCargado(cx, cy));
    }

    static void chunkDescargado_(int cx, int cy){
        fire(new ChunkDescargado(cx, cy));
    }

    static void edificioRestaurado_(mindustry.gen.Building b){
        fire(new EdificioRestaurado(b));
    }

    static void entidadDespertada_(mindustry.gen.Unit u){
        fire(new EntidadDespertada(u));
    }

    private static void fire(Object e){
        try{
            Events.fire(e);
        }catch(Throwable t){
            Log.err("[MundoInfinito] Un mod falló en el evento @", e.getClass().getSimpleName(), t);
        }
    }

    // ------------------------------------------------------------------ detección de mods

    /** Un mod activo que puede cambiar el comportamiento del mundo. */
    public static final class ModDetectado{
        public final String nombre, version;
        /** true = declara a este mod en dependencies / softDependencies: usa la API a propósito. */
        public final boolean declarado;
        /** true = tiene código Java (puede alterar cualquier cosa aunque no lo declare). */
        public final boolean java;

        ModDetectado(LoadedMod m, boolean declarado){
            this.nombre = m.meta.displayName != null ? m.meta.displayName : m.name;
            this.version = m.meta.version == null ? "?" : m.meta.version;
            this.declarado = declarado;
            this.java = m.isJava();
        }

        @Override
        public String toString(){
            return nombre + " " + version + (declarado ? " (usa la API)" : " (Java)");
        }
    }

    /**
     * Mods activos, aparte de este, que declaran depender del mundo abierto o que traen código Java (y por tanto pueden alterar sus
     * comportamientos). Los mods de solo contenido (bloques, unidades) que no lo declaran no se listan: no cambian el mundo.
     */
    public static Seq<ModDetectado> modsDetectados(){
        final Seq<ModDetectado> r = new Seq<>();
        if(Vars.mods == null) return r;
        for(LoadedMod m : Vars.mods.list()){
            if(m == null || !m.enabled() || ID_MOD.equals(m.name)) continue;
            final boolean declarado = m.meta.dependencies.contains(ID_MOD) || m.meta.softDependencies.contains(ID_MOD);
            if(declarado || m.isJava()) r.add(new ModDetectado(m, declarado));
        }
        return r;
    }

    /** Nombres internos de los mods detectados, separados por coma (se guardan en el mundo). */
    public static String firmaMods(){
        final StringBuilder sb = new StringBuilder();
        for(ModDetectado d : modsDetectados()){
            if(sb.length() > 0) sb.append(',');
            sb.append(d.nombre);
        }
        return sb.toString();
    }

    /** Texto corto para la interfaz: "Mods que alteran el mundo: A, B" o "". */
    public static String resumenMods(){
        final Seq<ModDetectado> l = modsDetectados();
        if(l.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        for(ModDetectado d : l){
            if(sb.length() > 0) sb.append(", ");
            sb.append(d.nombre);
        }
        return sb.toString();
    }

    /** Escribe en el registro los mods detectados (al abrir un mundo): ayuda a depurar quién cambia qué. */
    static void registrarEnLog(){
        Extensiones.registrarEnLog();
        final Seq<ModDetectado> l = modsDetectados();
        if(l.isEmpty()){
            Log.info("[MundoInfinito] API v@: ningún mod adicional altera el mundo.", VERSION);
            return;
        }
        Log.info("[MundoInfinito] API v@: @ mod(s) pueden alterar el mundo:", VERSION, l.size);
        for(ModDetectado d : l) Log.info("[MundoInfinito]   - @", d);
    }
}
