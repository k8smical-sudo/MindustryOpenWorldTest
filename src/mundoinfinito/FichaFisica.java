package mundoinfinito;

/**
 * FICHA FÍSICA DE UN CUERPO (plan v6, D24). Medidas en unidades del sistema solar. Son estimaciones salvo que el origen sea
 * {@link #ORIGEN_DECLARADA}. Inmutable. Las fichas inválidas se sustituyen con {@link ApiFisica#validar} (F3).
 */
public final class FichaFisica{
    public static final String ORIGEN_ESTIMADA = "estimada", ORIGEN_DECLARADA = "declarada";
    /** Índices de las distancias: a la estrella, entre cuerpos de la misma órbita y entre sistemas. */
    public static final int DIST_ESTRELLA = 0, DIST_CUERPOS = 1, DIST_SISTEMAS = 2, NUM_DISTANCIAS = 3;

    public final float diametro, anchura, altura;
    /** Categoría derivada del diámetro (ver {@link ApiFisica#categoria}); cadena vacía si el diámetro no es válido. */
    public final String tamano;
    public final String origen;
    private final double[] distancias;

    public FichaFisica(float diametro, float anchura, float altura, String origen, double[] distancias){
        this.diametro = diametro;
        this.anchura = anchura;
        this.altura = altura;
        this.tamano = ApiFisica.categoria(diametro);
        this.origen = origen == null ? ORIGEN_ESTIMADA : origen;
        this.distancias = new double[NUM_DISTANCIAS];
        if(distancias != null) System.arraycopy(distancias, 0, this.distancias, 0, Math.min(distancias.length, NUM_DISTANCIAS));
    }

    /** Distancia del índice dado ({@link #DIST_ESTRELLA} etc.). Índice fuera de rango: IndexOutOfBoundsException. */
    public double distancia(int indice){ return distancias[indice]; }

    public double[] distancias(){ return distancias.clone(); }
}
