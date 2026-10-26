package mundoinfinito;

import arc.util.Log;

/**
 * VERSIONES DE LA API PÚBLICA. Cada parte de la API tiene su versión (mayor.menor): un cambio incompatible sube el mayor; añadir algo sin romper nada
 * sube el menor. Un mod que depende de una parte compara su versión con la que pide: es compatible si el mayor es el mismo y el menor es al menos el pedido.
 * Los métodos marcados {@code @Deprecated} se mantienen al menos una versión mayor antes de retirarse, con aviso en la documentación.
 */
public final class ApiVersion{
    /** Conjunto de la API pública (sube cuando cambia cualquiera de las partes). */
    public static final int PUBLICA_MAYOR = 1, PUBLICA_MENOR = 0;
    /** La parte visual: {@code ApiVisual}. */
    public static final int VISUAL_MAYOR = 1, VISUAL_MENOR = 0;
    /** La parte de ajustes y propuestas de valor: {@code ApiAjustes}. */
    public static final int AJUSTES_MAYOR = 1, AJUSTES_MENOR = 0;

    private ApiVersion(){}

    /** Texto para el log y los avisos: "api 1.0 (visual 1.0, ajustes 1.0)". */
    public static String texto(){
        return "api " + PUBLICA_MAYOR + "." + PUBLICA_MENOR + " (visual " + VISUAL_MAYOR + "." + VISUAL_MENOR + ", ajustes " + AJUSTES_MAYOR + "." + AJUSTES_MENOR + ")";
    }

    /** ¿Atiende una versión (mayor, menor) lo que pide un mod? Mismo mayor y menor suficiente. */
    public static boolean compatible(int mayor, int menor, int mayorPedido, int menorMinimo){
        return mayor == mayorPedido && menor >= menorMinimo;
    }

    /** Un mod pregunta si la API visual le sirve. Si no, escribe un aviso claro en el log y devuelve false. */
    public static boolean requiereVisual(String mod, int mayorPedido, int menorMinimo){
        return comprobar(mod, "visual", VISUAL_MAYOR, VISUAL_MENOR, mayorPedido, menorMinimo);
    }

    /** Un mod pregunta si la API de ajustes le sirve. */
    public static boolean requiereAjustes(String mod, int mayorPedido, int menorMinimo){
        return comprobar(mod, "de ajustes", AJUSTES_MAYOR, AJUSTES_MENOR, mayorPedido, menorMinimo);
    }

    private static boolean comprobar(String mod, String parte, int mayor, int menor, int mayorPedido, int menorMinimo){
        if(compatible(mayor, menor, mayorPedido, menorMinimo)) return true;
        Log.err("[MundoInfinito] El mod '@' pide la API @ @.@ o superior (mismo mayor) y esta es la @.@: no se puede usar. Actualiza Mundo Infinito o el mod.", mod, parte, mayorPedido, menorMinimo, mayor, menor);
        return false;
    }
}
