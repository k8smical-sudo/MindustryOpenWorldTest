package mundoinfinito;

import mindustry.Vars;

/**
 * API PÚBLICA DE PLANETAS (fase de tiempo). Rotación (duración de un día, en ticks) y órbita (duración de una vuelta alrededor de la estrella, que marca
 * el año y sus cuatro temporadas). Serpulo: rotación 23 min 38 s, órbita ~11 min. Erekir: rotación estimada, órbita ~2,5 min.
 * Los valores son ajustes de generación del mundo: los mundos nuevos los guardan; los anteriores conservan los de entonces (10 min de día, 28 días de año).
 * Un addon cambia un planeta proponiendo valores con prioridad: gana la propuesta de menor prioridad (número), con política de prioridad absoluta.
 */
public final class ApiPlaneta{
    public static final String SERPULO = "serpulo";
    public static final String EREKIR = "erekir";

    /** Valores de los mundos anteriores: un día de 10 minutos y un año de 28 días. */
    static final long LEGADO_ROTACION = 36000L, LEGADO_ORBITA = 1008000L;

    private static boolean iniciado;

    private ApiPlaneta(){}

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
        return a && b;
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
