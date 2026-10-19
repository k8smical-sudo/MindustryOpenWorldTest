package mundoinfinito;

/**
 * Aporte de terreno de un chunk: 1024 casillas (fila a fila) con los códigos de {@code Muestreo}.
 * Un buffer por hilo, reutilizado (cero asignaciones por chunk): solo es válido hasta la siguiente llamada a {@link Nucleo#terreno} en el mismo hilo.
 * Las contribuciones DEBEN escribir todas las casillas.
 */
final class AporteTerreno{
    static final int T = MundoInfinitoMod.TAM_CHUNK, N = T * T;
    /** Código de piso de una casilla más allá del borde del mundo: el consumidor pone el muro del borde. */
    static final int FUERA = -1;

    /** Código de piso (Muestreo.P_*), o {@link #FUERA}. */
    final int[] piso = new int[N];
    /** Código de mena (Muestreo.O_*), 0 = ninguna. */
    final int[] mena = new int[N];
    /** Muro: 0 = ninguno, 1 = muro del piso, 2 = grafito. */
    final int[] muro = new int[N];
    /** Decoración: 0 = ninguna, 1 = decoración del piso, 2 = cristal, 3 = cristal vivo. */
    final int[] prop = new int[N];

    /** Trabajo interno de las etapas del terreno base (lo que antes llevaba cada ChunkData). No forma parte del aporte. */
    final float[] aux = new float[14];
    /** Estado de la casilla que atraviesan las etapas del terreno base (uno por hilo, reutilizado). */
    final CeldaBase celda = new CeldaBase();
}
