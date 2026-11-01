package mundoinfinito;

import mindustry.Vars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * API PÚBLICA DE PLANETAS (fase de tiempo). Rotación (duración de un día, en ticks) y órbita (duración de una vuelta alrededor de la estrella, que marca
 * el año y sus cuatro temporadas). Serpulo: rotación 23 min 38 s, órbita ~11 min. Erekir: rotación estimada, órbita ~2,5 min.
 * Los valores son ajustes de generación del mundo: los mundos nuevos los guardan; los anteriores conservan los de entonces (10 min de día, 28 días de año).
 * Un addon cambia un planeta proponiendo valores con prioridad: gana la propuesta de menor prioridad (número), con política de prioridad absoluta.
 *
 * Nombres y posición (fase 1 del plan v2): cada planeta tiene un TAG técnico (su id, fijo) y un nombre visible (el que elige el jugador, guardado con
 * el mundo). La fase orbital avanza con el tiempo de juego según el periodo de cada planeta en el mundo abierto.
 */
public final class ApiPlaneta{
    public static final String SERPULO = "serpulo";
    public static final String EREKIR = "erekir";

    /** Valores de los mundos anteriores: un día de 10 minutos y un año de 28 días. */
    static final long LEGADO_ROTACION = 36000L, LEGADO_ORBITA = 1008000L;

    /** Ids de todos los planetas registrados (los del juego y los de los addons), en orden de registro. */
    private static final ArrayList<String> planetas = new ArrayList<String>();
    /** Vista de solo lectura de {@link #planetas}, para no crear una copia en cada uso. */
    private static final List<String> VISTA = Collections.unmodifiableList(planetas);

    private static boolean iniciado;

    private ApiPlaneta(){}

    /** Un planeta tal como lo ve el jugador y los addons: tag técnico (fijo) y nombre visible en el mundo abierto. */
    public static final class Ficha{
        /** Tag técnico: el id del planeta. Los addons identifican siempre por el tag. */
        public final String tag;
        /** Nombre visible: el que eligió el jugador en este mundo, o el por defecto. */
        public final String nombre;

        Ficha(String tag, String nombre){
            this.tag = tag;
            this.nombre = nombre;
        }
    }

    static String ajusteRotacion(String planeta){
        return "mundo-infinito:planeta-" + planeta + "-rotacion";
    }

    static String ajusteOrbita(String planeta){
        return "mundo-infinito:planeta-" + planeta + "-orbita";
    }

    /** Se llama al cargar el mod: registra los ajustes de Serpulo y Erekir. Idempotente. */
    static void iniciar(){
        if(iniciado) return;
        iniciado = true;
        registrarAjustes(SERPULO, 85080, 39600);
        registrarAjustes(EREKIR, 18000, 9000);
    }

    private static boolean registrarAjustes(String planeta, int rotacion, int orbita){
        final boolean a = RegistroAjustes.registrar(AjusteMundo.entero(ajusteRotacion(planeta), "Rotación de " + planeta + " (ticks)",
            "Duración de un día de " + planeta + " en ticks (60 por segundo). Serpulo: 23 min 38 s.", AjusteMundo.Ambito.GENERACION, rotacion, 600, 600000)
            .conPolitica(AjusteMundo.Politica.PRIORIDAD_ABSOLUTA));
        final boolean b = RegistroAjustes.registrar(AjusteMundo.entero(ajusteOrbita(planeta), "Órbita de " + planeta + " (ticks)",
            "Duración de una vuelta alrededor de la estrella. Marca el año: sus cuatro temporadas son cuartos de órbita.", AjusteMundo.Ambito.GENERACION, orbita, 600, 6000000)
            .conPolitica(AjusteMundo.Politica.PRIORIDAD_ABSOLUTA));
        final boolean ok = a && b;
        if(ok && !planetas.contains(planeta)) planetas.add(planeta);
        return ok;
    }

    /** Registra un planeta nuevo con su rotación y órbita por defecto. Solo antes de abrir un mundo. Devuelve false si el id no es válido o ya existe. */
    public static boolean registrar(String planeta, int rotacionTicks, int orbitaTicks){
        if(planeta == null || planeta.isEmpty() || planeta.indexOf(' ') >= 0 || planeta.indexOf(':') >= 0) return false;
        return registrarAjustes(planeta, rotacionTicks, orbitaTicks);
    }

    /**
     * Propone valores de rotación y órbita para un planeta. Solo antes de abrir un mundo. Gana la propuesta de MENOR prioridad (número): un addon
     * pone -100 para sobrescribir el valor del juego. Dos propuestas con la misma prioridad se resuelven por fuente, sin depender del orden de carga.
     */
    public static boolean proponer(String fuente, String id, String planeta, int rotacionTicks, int orbitaTicks, int prioridad){
        final boolean a = ApiAjustes.proponer(fuente, id + "-rotacion", ajusteRotacion(planeta), rotacionTicks, 1.0, prioridad);
        final boolean b = ApiAjustes.proponer(fuente, id + "-orbita", ajusteOrbita(planeta), orbitaTicks, 1.0, prioridad);
        return a && b;
    }

    /** Rotación de un planeta con los valores por defecto (sin mundo abierto). */
    public static int rotacion(String planeta){
        final double v = ApiAjustes.valor(ajusteRotacion(planeta));
        return Double.isNaN(v) ? 0 : (int)v;
    }

    /** Órbita de un planeta con los valores por defecto (sin mundo abierto). */
    public static int orbita(String planeta){
        final double v = ApiAjustes.valor(ajusteOrbita(planeta));
        return Double.isNaN(v) ? 0 : (int)v;
    }

    // ================================================================== registro, nombres y fase

    /** ¿Está registrado este planeta (del juego o de un addon)? */
    public static boolean existe(String planeta){
        return planeta != null && planetas.contains(planeta);
    }

    /** Ids de todos los planetas registrados. Lista de solo lectura, sin copia. */
    static List<String> ids(){
        return VISTA;
    }

    /** Todos los planetas registrados, con su tag técnico y su nombre visible en el mundo abierto (o el por defecto sin mundo). */
    public static List<Ficha> listar(){
        final ArrayList<Ficha> out = new ArrayList<Ficha>(planetas.size());
        for(String p : planetas) out.add(new Ficha(p, nombre(p)));
        return out;
    }

    /** Nombre visible de un planeta en el mundo abierto. Sin mundo: el nombre por defecto. */
    public static String nombre(String planeta){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null || m.planetas == null ? PlanetasMundo.nombrePorDefecto(planeta) : m.planetas.nombre(planeta);
    }

    /**
     * Cambia el nombre visible de un planeta en el mundo abierto (se guarda con el mundo). Un nombre vacío vuelve al nombre por defecto.
     * El tag técnico no cambia. Devuelve false sin mundo abierto o si el planeta no está registrado.
     */
    public static boolean renombrar(String planeta, String nombre){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null || m.planetas == null) return false;
        return m.planetas.renombrar(planeta, nombre);
    }

    /** Fase orbital de un planeta en el mundo abierto, en vueltas [0, 1). Sin mundo: 0. */
    public static double fase(String planeta){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null || m.planetas == null ? 0.0 : m.planetas.fase(planeta);
    }

    /**
     * Periodo orbital de un planeta en un mundo (ticks). Si el mundo lo guardó, ese valor. Serpulo y Erekir sin valor guardado son mundos anteriores:
     * usan el valor de entonces. Un planeta de un addon sin valor guardado usa su valor por defecto. Sin perfil: el valor por defecto.
     */
    static double orbitaEn(PerfilMundo p, String planeta){
        if(p == null) return defectoOrbita(planeta);
        final boolean juego = SERPULO.equals(planeta) || EREKIR.equals(planeta);
        return p.valorPlaneta(ajusteOrbita(planeta), juego ? LEGADO_ORBITA : defectoOrbita(planeta));
    }

    /** Valor por defecto registrado de la órbita de un planeta (no el del mundo abierto). 0 si no está registrado. */
    private static double defectoOrbita(String planeta){
        final AjusteMundo a = RegistroAjustes.get(ajusteOrbita(planeta));
        return a == null ? 0.0 : a.defecto;
    }

    // ================================================================== valores del mundo abierto

    /** Planeta del mundo abierto: Serpulo o Erekir. Los mundos con terreno mezclado usan Serpulo. Null sin mundo abierto. */
    static String planetaDeMundo(MundoInfinitoMod.Meta m){
        if(m == null) return null;
        return m.dimGen() == MundoInfinitoMod.DIM_EREKIR ? EREKIR : SERPULO;
    }

    /** Rotación del mundo abierto (lo guardado en su perfil). Sin mundo: la de Serpulo por defecto. */
    public static int rotacionMundo(){
        return valorMundo(true);
    }

    /** Órbita del mundo abierto (lo guardado en su perfil). Sin mundo: la de Serpulo por defecto. */
    public static int orbitaMundo(){
        return valorMundo(false);
    }

    private static int valorMundo(boolean rotacion){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null || m.perfil == null){
            return rotacion ? rotacion(SERPULO) : orbita(SERPULO);
        }
        final String p = planetaDeMundo(m);
        final double v = rotacion
            ? m.perfil.valorPlaneta(ajusteRotacion(p), LEGADO_ROTACION)
            : m.perfil.valorPlaneta(ajusteOrbita(p), LEGADO_ORBITA);
        return (int)v;
    }
}
