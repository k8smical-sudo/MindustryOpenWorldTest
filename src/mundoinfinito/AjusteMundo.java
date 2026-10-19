package mundoinfinito;

/**
 * AJUSTE DE MUNDO (fase 4): el único concepto para todo lo que se elige o se guarda por mundo. Une las reglas de juego (gamerules) y
 * los parámetros de generación: un id "fuente:nombre", un tipo, un ámbito, un valor por defecto y un rango.
 * Los valores se guardan en el {@link PerfilMundo} del mundo.
 *
 * Validación: un valor fuera de rango se RECORTA al rango (nunca se rechaza ni se aborta) y queda registrado en {@link RegistroAjustes};
 * un valor no numérico se sustituye por el valor por defecto.
 */
final class AjusteMundo{
    enum Tipo{ BOOL, ENTERO, DECIMAL }

    /**
     * Cómo se resuelve un ajuste cuando VARIAS contribuciones proponen su valor (fase 5). La política la declara el dueño del ajuste.
     * Con un solo contribuyente no hace falta política; con varios y sin política el ajuste NO se resuelve (se reporta y queda su valor por defecto).
     */
    enum Politica{
        /** Media de los valores, cada uno recortado al rango, ponderada por el peso de su contribución. */
        MEDIA_PONDERADA,
        /** Gana la primera contribución en orden canónico (menor prioridad; empates por id de fuente y de contribución). */
        PRIORIDAD_ABSOLUTA,
        /** Se elige UNA contribución con probabilidad proporcional a su peso, con un hash determinista de (semilla, id del ajuste). */
        ELECCION_PONDERADA
    }

    /** JUEGO: se aplica sobre las reglas de Mindustry y puede registrarse en cualquier momento. GENERACION: lo lee la generación del terreno;
     *  se registra antes de abrir el primer mundo y queda guardado, y fijo, en cada mundo. */
    enum Ambito{ JUEGO, GENERACION }

    final String id, nombre, descripcion;
    final Tipo tipo;
    final Ambito ambito;
    final double defecto, min, max;
    /** Solo en reglas de juego (BOOL, ámbito JUEGO): qué cambia en las reglas de Mindustry. Null en el resto. */
    final GameRule.Aplicacion aplicacion;
    /** Política para varias contribuciones sobre este ajuste, o null si el dueño no la declaró. */
    final Politica politica;

    private AjusteMundo(String id, String nombre, String descripcion, Tipo tipo, Ambito ambito, double defecto, double min, double max, GameRule.Aplicacion aplicacion, Politica politica){
        this.id = id;
        this.nombre = nombre == null ? id : nombre;
        this.descripcion = descripcion == null ? "" : descripcion;
        this.tipo = tipo;
        this.ambito = ambito;
        final double lo = Math.min(min, max), hi = Math.max(min, max);
        this.min = lo;
        this.max = hi;
        double d = Double.isNaN(defecto) ? lo : defecto;
        d = d < lo ? lo : (d > hi ? hi : d);
        this.defecto = tipo == Tipo.ENTERO ? Math.rint(d) : d;
        this.aplicacion = aplicacion;
        this.politica = politica;
    }

    static AjusteMundo bool(String id, String nombre, String descripcion, Ambito ambito, boolean defecto, GameRule.Aplicacion aplicacion){
        return new AjusteMundo(id, nombre, descripcion, Tipo.BOOL, ambito, defecto ? 1.0 : 0.0, 0.0, 1.0, aplicacion, null);
    }

    static AjusteMundo entero(String id, String nombre, String descripcion, Ambito ambito, int defecto, int min, int max){
        return new AjusteMundo(id, nombre, descripcion, Tipo.ENTERO, ambito, defecto, min, max, null, null);
    }

    static AjusteMundo decimal(String id, String nombre, String descripcion, Ambito ambito, double defecto, double min, double max){
        return new AjusteMundo(id, nombre, descripcion, Tipo.DECIMAL, ambito, defecto, min, max, null, null);
    }

    /** Copia de este ajuste con la política de resolución indicada (los ajustes son inmutables). */
    AjusteMundo conPolitica(Politica p){
        return new AjusteMundo(id, nombre, descripcion, tipo, ambito, defecto, min, max, aplicacion, p);
    }

    /** Una gamerule registrada con la API de siempre es un ajuste booleano de ámbito JUEGO. */
    static AjusteMundo deGameRule(GameRule g){
        return bool(g.id, g.nombre, g.descripcion, Ambito.JUEGO, g.porDefecto, g.aplicacion);
    }

    /** El valor válido más cercano: no numérico -> defecto; fuera de rango -> recortado; ENTERO redondeado; BOOL -> 0 ó 1. */
    double validar(double v){
        if(Double.isNaN(v) || Double.isInfinite(v)) return defecto;
        if(tipo == Tipo.BOOL) return v != 0.0 ? 1.0 : 0.0;
        final double r = v < min ? min : (v > max ? max : v);
        return tipo == Tipo.ENTERO ? Math.rint(r) : r;
    }

    boolean fueraDeRango(double v){
        return tipo != Tipo.BOOL && (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max);
    }
}
