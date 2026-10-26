package mundoinfinito;

/**
 * API PÚBLICA DE AJUSTES Y PROPUESTAS (versión {@link ApiVersion#AJUSTES_MAYOR}.{@link ApiVersion#AJUSTES_MENOR}). Un mod registra parámetros de
 * generación propios (con tipo, rango y valor por defecto), propone valores para ellos y los lee. Los valores se resuelven al CREAR un mundo con la política
 * del ajuste y quedan guardados en él: abrirlo después no depende de qué mods estén ni de su orden.
 *
 * Los ajustes de generación se registran al cargar el mod, antes de abrir el primer mundo. Un valor fuera de rango se recorta con aviso.
 * Ver docs/API.md y docs/AJUSTES.md.
 */
public final class ApiAjustes{
    private ApiAjustes(){}

    /** Cómo se resuelve un ajuste cuando varios mods proponen su valor. */
    public enum Politica{
        /** Media de los valores (recortados al rango) ponderada por el peso. */
        MEDIA_PONDERADA,
        /** Gana la propuesta de menor prioridad. */
        PRIORIDAD_ABSOLUTA,
        /** Se elige una propuesta con probabilidad proporcional al peso, con un hash determinista de la semilla. */
        ELECCION_PONDERADA
    }

    private static AjusteMundo.Politica politica(Politica p){
        return p == null ? null : AjusteMundo.Politica.valueOf(p.name());
    }

    /** Registra un ajuste de generación decimal. @return false si el id es inválido o repetido, o ya se abrió un mundo */
    public static boolean registrarDecimal(String id, String nombre, String descripcion, double defecto, double min, double max, Politica politica){
        return RegistroAjustes.registrar(AjusteMundo.decimal(id, nombre, descripcion, AjusteMundo.Ambito.GENERACION, defecto, min, max).conPolitica(politica(politica)));
    }

    /** Registra un ajuste de generación entero. */
    public static boolean registrarEntero(String id, String nombre, String descripcion, int defecto, int min, int max, Politica politica){
        return RegistroAjustes.registrar(AjusteMundo.entero(id, nombre, descripcion, AjusteMundo.Ambito.GENERACION, defecto, min, max).conPolitica(politica(politica)));
    }

    /** Registra un ajuste de generación booleano (se lee como 0 o 1). */
    public static boolean registrarBooleano(String id, String nombre, String descripcion, boolean defecto, Politica politica){
        return RegistroAjustes.registrar(AjusteMundo.bool(id, nombre, descripcion, AjusteMundo.Ambito.GENERACION, defecto, null).conPolitica(politica(politica)));
    }

    /**
     * Propone un valor para un ajuste (propio o de otro mod). Se resuelve al crear un mundo, con la política del ajuste. Con varios proponentes y un ajuste
     * sin política, no se resuelve y se avisa. @param id id único de esta propuesta ("fuente:nombre")
     */
    public static boolean proponer(String fuente, String id, String ajuste, double valor, double peso, int prioridad){
        return Nucleo.registrarParametro(PropuestaParametro.de(fuente, id, ajuste, valor, peso, prioridad));
    }

    /** Valor de un ajuste en el mundo abierto (el guardado en su perfil); el valor por defecto si no hay mundo; NaN si el ajuste no existe. */
    public static double valor(String id){
        final AjusteMundo a = RegistroAjustes.get(id);
        if(a == null) return Double.NaN;
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null ? a.defecto : m.perfil.valor(a);
    }

    /**
     * Procedencia de una macrocélula (64x64 casillas) del mundo abierto: quién es dueño del terreno y cuántos fenómenos de cada generador hay dentro.
     * Texto para diagnóstico; null si no hay mundo abierto.
     */
    public static String procedencia(int mcx, int mcy){
        final Procedencia.Macro m = Extensiones.procedencia(mcx, mcy);
        return m == null ? null : m.toString();
    }
}
