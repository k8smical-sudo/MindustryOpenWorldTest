package mundoinfinito;

import arc.func.Prov;

/**
 * API PÚBLICA DE DESDUPLICACIÓN ADAPTATIVA DE CÁLCULOS (versión {@link ApiVersion#DEDUP_MAYOR}.{@link ApiVersion#DEDUP_MENOR}).
 *
 * Un addon envuelve un cálculo caro con {@link #calcular}. Con las mismas entradas y la misma versión, el resultado se reutiliza, y si otro hilo lo
 * pide mientras se ejecuta, espera el resultado. El sistema MIDE si desduplicar compensa: un cálculo barato o sin repeticiones pasa directo, con
 * casi ningún coste extra. No hace falta clasificar nada a mano.
 *
 * CONTRATO: el cálculo debe ser puro (las mismas entradas dan el mismo resultado). Así, desduplicar o no desduplicar cambia solo el coste, nunca el
 * resultado. Las entradas deben contener todo lo que influye en el resultado. Si el código del cálculo cambia, sube la versión.
 *
 * Políticas: {@link #AUTO} (por defecto, mide), {@link #SIEMPRE} (desduplica siempre) y {@link #NUNCA} (nunca desduplica).
 */
public final class ApiDesduplicacion{
    /** El sistema decide por medición (por defecto). */
    public static final int AUTO = 0;
    /** Desduplica siempre. */
    public static final int SIEMPRE = 1;
    /** Nunca desduplica: cada llamada se ejecuta, sin ningún coste de medición. */
    public static final int NUNCA = 2;

    private ApiDesduplicacion(){}

    /**
     * Ejecuta el cálculo, o reutiliza el resultado guardado para las mismas entradas (si la política lo permite).
     * @param contribuyente id "fuente:nombre" del cálculo
     * @param calculo lo que se ejecuta si no hay resultado guardado
     * @param entradas todo lo que determina el resultado
     * @throws IllegalArgumentException si el contribuyente no es un id válido
     */
    public static <T> T calcular(String contribuyente, Prov<T> calculo, long... entradas){
        return Desduplicacion.calcular(contribuyente, calculo, entradas);
    }

    /**
     * Registra un cálculo con su versión, su tope de resultados guardados y su política. Si la versión cambia, su caché se vacía. Un addon lo llama
     * una vez al cargarse. Devuelve false si el id o la política no son válidos.
     */
    public static boolean registrar(String contribuyente, int version, int tope, int politica){
        return Desduplicacion.registrar(contribuyente, version, tope, politica);
    }

    /** Declara que el cálculo NO se desduplica (política {@link #NUNCA}); conserva su versión y su caché. Devuelve false si el id no es válido. */
    public static boolean noDesduplicar(String contribuyente){
        return Desduplicacion.fijarPolitica(contribuyente, NUNCA);
    }

    /** Quita un cálculo y libera su caché. Si vuelve a pedirse, se registra de nuevo con los valores por defecto. */
    public static void olvidar(String contribuyente){
        Desduplicacion.olvidar(contribuyente);
    }

    /** Modo que eligió la medición: "DEDUP" (desduplica), "DIRECTO" (pasa sin caché) o null si el cálculo no está registrado. */
    public static String modo(String contribuyente){
        return Desduplicacion.modo(contribuyente);
    }

    /** Estadísticas desde el arranque: {cálculos ejecutados, peticiones reutilizadas, cálculos directos}. Para diagnóstico. */
    public static long[] estadisticas(){
        return Desduplicacion.estadisticas();
    }
}
