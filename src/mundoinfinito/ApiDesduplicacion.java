package mundoinfinito;

import arc.func.Prov;

/**
 * API PÚBLICA DE DESDUPLICACIÓN DE CÁLCULOS (versión {@link ApiVersion#DEDUP_MAYOR}.{@link ApiVersion#DEDUP_MENOR}).
 *
 * Un addon envuelve un cálculo caro con {@link #calcular}. Con las mismas entradas, el cálculo se ejecuta una vez y su resultado se reutiliza;
 * si otro hilo lo pide mientras se ejecuta, espera el resultado. Un cálculo que falla no se guarda.
 *
 * El contribuyente es un id "fuente:nombre" (p. ej. "minmod:ruta"). Las entradas son los números que determinan el resultado: si cambia
 * cualquiera, el cálculo se vuelve a ejecutar. Si el cálculo depende de algo que no está en las entradas, el resultado será incorrecto: las
 * entradas deben contener todo lo que influye.
 *
 * Un addon que necesita ejecutar su cálculo cada vez (por ejemplo, con efectos secundarios) declara {@link #noDesduplicar}.
 */
public final class ApiDesduplicacion{
    private ApiDesduplicacion(){}

    /**
     * Ejecuta el cálculo, o reutiliza el resultado guardado para las mismas entradas.
     * @param contribuyente id "fuente:nombre" del cálculo
     * @param calculo lo que se ejecuta si no hay resultado guardado
     * @param entradas todo lo que determina el resultado
     * @throws IllegalArgumentException si el contribuyente no es un id válido
     */
    public static <T> T calcular(String contribuyente, Prov<T> calculo, long... entradas){
        return Desduplicacion.calcular(contribuyente, calculo, entradas);
    }

    /**
     * Declara que el cálculo de este contribuyente NO se desduplica: cada llamada se ejecuta. Devuelve false si el id no es válido.
     */
    public static boolean noDesduplicar(String contribuyente){
        return Desduplicacion.sinDesduplicar(contribuyente);
    }

    /** Estadísticas desde el arranque: {cálculos ejecutados, peticiones reutilizadas, cálculos sin desduplicar}. Para diagnóstico. */
    public static long[] estadisticas(){
        return Desduplicacion.estadisticas();
    }
}
