package mundoinfinito;

/**
 * AZAR DETERMINISTA para addons (fase de robustez): un valor que depende SOLO de la semilla del mundo, la dimensión, un canal (el id del addon) y una
 * posición del MUNDO (no del chunk). Mismo mundo, misma posición, mismo valor, sin importar el orden de generación ni el número de hilos.
 * No tiene estado compartido: cada llamada es una función pura, segura en cualquier hilo.
 */
public final class ApiAzar{
    private ApiAzar(){}

    private static long mezcla(long z){
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long hashCanal(String canal){
        long h = 0xcbf29ce484222325L;
        if(canal == null) return h;
        for(int i = 0; i < canal.length(); i++){
            h ^= canal.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static long base(int semilla, int dimension, String canal, int x, int y, long k){
        long h = mezcla((long)semilla * 0x9E3779B97F4A7C15L ^ (long)dimension * 0xC2B2AE3D27D4EB4FL);
        h = mezcla(h ^ hashCanal(canal));
        h = mezcla(h ^ ((long)x * 0x165667B19E3779F9L) ^ ((long)y * 0x27D4EB2F165667C5L) ^ (k * 0x85EBCA77C2B2AE63L));
        return h;
    }

    private static float unidadDe(long h){
        return (float)((h >>> 40) * 0x1.0p-24);
    }

    /** Valor en [0, 1) para (semilla, dimensión, canal, x, y). */
    public static float unidad(int semilla, int dimension, String canal, int x, int y){
        return unidadDe(base(semilla, dimension, canal, x, y, 0L));
    }

    /** Entero en [0, n) para (semilla, dimensión, canal, x, y). Si n <= 0 devuelve 0. */
    public static int entero(int semilla, int dimension, String canal, int x, int y, int n){
        if(n <= 0) return 0;
        final int r = (int)(unidadDe(base(semilla, dimension, canal, x, y, 0L)) * n);
        return r >= n ? n - 1 : r;
    }

    /** Valor en [0, 1) usando la semilla y dimensión de un contexto de terreno. */
    public static float unidad(ContextoTerreno ctx, String canal, int x, int y){
        return unidad(ctx.lectura.semilla, ctx.lectura.dimension, canal, x, y);
    }

    /** Valor en [0, 1) usando la semilla y dimensión de un contexto de fenómeno. */
    public static float unidad(ContextoFenomeno ctx, String canal, int x, int y){
        return unidad(ctx.semilla, ctx.dimension, canal, x, y);
    }

    /** Secuencia de valores para una posición: útil cuando hacen falta varias muestras. Cada secuencia es independiente. */
    public static final class Secuencia{
        private long estado;

        Secuencia(long estado){
            this.estado = estado;
        }

        /** Siguiente valor en [0, 1). */
        public float siguiente(){
            estado += 0x9E3779B97F4A7C15L;
            return unidadDe(mezcla(estado));
        }

        /** Siguiente entero en [0, n). Si n <= 0 devuelve 0. */
        public int siguienteEntero(int n){
            if(n <= 0) return 0;
            final int r = (int)(siguiente() * n);
            return r >= n ? n - 1 : r;
        }
    }

    /** Crea una secuencia para (semilla, dimensión, canal, x, y). */
    public static Secuencia secuencia(int semilla, int dimension, String canal, int x, int y){
        return new Secuencia(base(semilla, dimension, canal, x, y, 0L));
    }
}
