package mundoinfinito;

/**
 * API DE FÍSICA (plan v7, F3, D24). Fichas físicas por defecto, categorías y validación. Sin Arc: se prueba en CI sin el juego.
 *
 * Unidades: las mismas que usa Mindustry (Planet.radius y Planet.orbitRadius). Las estimaciones salen del código fuente de Mindustry:
 *  - sol: radio 4, diámetro 8 (Planets.java).
 *  - Serpulo y Erekir: radio 1, diámetro 2 (Planets.java).
 *  - órbita a la estrella, con la fórmula de Planet.java y el espaciado por defecto (12): Erekir ~17, Serpulo ~31.
 * Lunas, estaciones y agujeros no existen en el juego: sus valores son estimaciones propias y se revisan en F17.
 * Por defecto cada cuerpo es una esfera: anchura = altura = diámetro. Un addon puede declarar su ficha; si es inválida se sustituye por
 * la estimada del tipo.
 */
public final class ApiFisica{
    public static final String TIPO_PLANETA = "planeta", TIPO_ESTRELLA = "estrella", TIPO_LUNA = "luna",
        TIPO_ESTACION = "estacion", TIPO_AGUJERO = "agujero";
    public static final String PEQUENO = "pequeno", MEDIO = "medio", GRANDE = "grande";

    /** Diámetros de referencia (unidades de Mindustry). Planeta y estrella vienen de Planets.java. */
    public static final float DIAMETRO_PLANETA = 2f, DIAMETRO_ESTRELLA = 8f;
    /** Estimaciones propias para cuerpos que el juego no tiene. */
    public static final float DIAMETRO_LUNA = 0.5f, DIAMETRO_ESTACION = 0.25f, DIAMETRO_AGUJERO = 1f, DIAMETRO_DEFECTO = 2f;
    /** Umbrales de categoría: menor que UMBRAL_MEDIO es pequeño; menor que UMBRAL_GRANDE es medio; el resto, grande. */
    public static final float UMBRAL_MEDIO = 1.5f, UMBRAL_GRANDE = 4f;
    /** Órbita a la estrella estimada de los dos planetas base (Planet.java con espaciado 12). */
    // Órbitas clonadas del campaign de Mindustry (content/Planets.java + type/Planet.java): orbitRadius = parent.totalRadius
    // + parent.orbitSpacing + radius, con el orden de carga del juego (sol 4, orbitSpacing 12 por defecto).
    // Erekir: 4 + 12 + 1 = 17. Serpulo: el Sol crece a 44,24 al añadir Tantros (43,24 = 30,24 + 12 + 1) => 44,24 + 12 + 1 = 57,24.
    public static final float ORBITA_SERPULO = 57.24f, ORBITA_EREKIR = 17f;

    private ApiFisica(){}

    /** Diámetro de referencia de un tipo de cuerpo. Tipo desconocido o nulo: DIAMETRO_DEFECTO. */
    public static float diametroEstimado(String tipo){
        if(tipo == null) return DIAMETRO_DEFECTO;
        switch(tipo){
            case TIPO_PLANETA: return DIAMETRO_PLANETA;
            case TIPO_ESTRELLA: return DIAMETRO_ESTRELLA;
            case TIPO_LUNA: return DIAMETRO_LUNA;
            case TIPO_ESTACION: return DIAMETRO_ESTACION;
            case TIPO_AGUJERO: return DIAMETRO_AGUJERO;
            default: return DIAMETRO_DEFECTO;
        }
    }

    /** Órbita a la estrella estimada de un planeta base (serpulo, erekir). Otro planeta: 0, sin dato. */
    public static float distanciaOrbitaEstimada(String planeta){
        if("serpulo".equals(planeta)) return ORBITA_SERPULO;
        if("erekir".equals(planeta)) return ORBITA_EREKIR;
        return 0f;
    }

    /** Categoría de un diámetro según los umbrales. Diámetro no válido: cadena vacía. */
    public static String categoria(float diametro){
        if(!valido(diametro)) return "";
        if(diametro < UMBRAL_MEDIO) return PEQUENO;
        if(diametro < UMBRAL_GRANDE) return MEDIO;
        return GRANDE;
    }

    /** Ficha estimada: esfera del diámetro dado (o la de referencia del tipo si el dado no es válido). */
    public static FichaFisica estimada(String tipo, float diametro){
        final float d = valido(diametro) ? diametro : diametroEstimado(tipo);
        return new FichaFisica(d, d, d, FichaFisica.ORIGEN_ESTIMADA, null);
    }

    /** Ficha estimada de un planeta base, con su órbita a la estrella como distancia. Otro planeta: distancia 0. */
    public static FichaFisica estimadaPlaneta(String planeta, float diametro){
        final float d = valido(diametro) ? diametro : DIAMETRO_PLANETA;
        return new FichaFisica(d, d, d, FichaFisica.ORIGEN_ESTIMADA,
            new double[]{distanciaOrbitaEstimada(planeta), 0.0, 0.0});
    }

    /** ¿La ficha es utilizable? Medidas finitas y positivas; distancias finitas y no negativas. */
    public static boolean valida(FichaFisica f){
        if(f == null) return false;
        if(!valido(f.diametro) || !valido(f.anchura) || !valido(f.altura)) return false;
        for(int i = 0; i < FichaFisica.NUM_DISTANCIAS; i++){
            final double d = f.distancia(i);
            if(!Double.isFinite(d) || d < 0.0) return false;
        }
        return true;
    }

    /** Devuelve la ficha si es válida; si no, una estimada del tipo (con el diámetro de la ficha si ese es válido). */
    public static FichaFisica validar(FichaFisica f, String tipo){
        if(valida(f)) return f;
        return estimada(tipo, f == null ? Float.NaN : f.diametro);
    }

    /** Distancia euclídea en el plano del sistema solar. */
    public static double distancia(double ax, double ay, double bx, double by){
        return Math.hypot(ax - bx, ay - by);
    }

    private static boolean valido(float v){ return Float.isFinite(v) && v > 0f; }
}
