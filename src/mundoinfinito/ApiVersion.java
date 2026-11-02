package mundoinfinito;

import arc.util.Log;

/**
 * VERSIONES DE LA API PÚBLICA. Cada parte de la API tiene su versión (mayor.menor): un cambio incompatible sube el mayor; añadir algo sin romper nada
 * sube el menor. Un mod que depende de una parte compara su versión con la que pide: es compatible si el mayor es el mismo y el menor es al menos el pedido.
 * Los métodos marcados {@code @Deprecated} se mantienen al menos una versión mayor antes de retirarse, con aviso en la documentación.
 */
public final class ApiVersion{
    /** Conjunto de la API pública (sube cuando cambia cualquiera de las partes). */
    public static final int PUBLICA_MAYOR = 1, PUBLICA_MENOR = 6;   // 1.6: ... y LUZ 1.2 (estrellas, plan v9 P8)
    /** La parte visual: {@code ApiVisual}. */
    public static final int VISUAL_MAYOR = 1, VISUAL_MENOR = 1;
    /** La parte de ajustes y propuestas de valor: {@code ApiAjustes}. */
    public static final int AJUSTES_MAYOR = 1, AJUSTES_MENOR = 0;
    /** La parte de datos del mundo: {@code ApiMundo}. */
    public static final int MUNDO_MAYOR = 1, MUNDO_MENOR = 0;
    /** La parte de configuración de modpack: {@code ApiModpack}. */
    public static final int MODPACK_MAYOR = 1, MODPACK_MENOR = 0;
    /** La parte de azar determinista: {@code ApiAzar}. */
    public static final int AZAR_MAYOR = 1, AZAR_MENOR = 0;
    /** La parte de planetas (rotación y órbita): {@code ApiPlaneta}. */
    public static final int PLANETA_MAYOR = 1, PLANETA_MENOR = 1;
    /** La parte de luz solar (eclipses, modificadores y estrellas): {@code ApiLuz}. 1.1: un modificador que falla se desactiva con aviso (P3). 1.2: estrellas con intensidad por cuerpo (P8). */
    public static final int LUZ_MAYOR = 1, LUZ_MENOR = 2;
    /** La parte de worldgen (fuentes de bioma, multiplicadores y valores fijos): {@code ApiWorldgen}. Plan v5, P4. */
    public static final int WORLDGEN_MAYOR = 1, WORLDGEN_MENOR = 0;
    /** La parte del árbol tecnológico por planeta: {@code ApiArbol}. */
    public static final int ARBOL_MAYOR = 1, ARBOL_MENOR = 0;
    /** La parte de desduplicación de cálculos: {@code ApiDesduplicacion}. */
    public static final int DEDUP_MAYOR = 1, DEDUP_MENOR = 1;   // 1.1: política adaptativa, registro por cálculo, olvidar y modo
    /** La parte de tiempo, calendario y eventos: {@code ApiTiempo}. */
    public static final int TIEMPO_MAYOR = 1, TIEMPO_MENOR = 1;
    /** La parte de sistemas (órbitas compartidas, slots, capacidad, planetas solitarios, movimiento): {@code ApiSistemas}. Plan v6, F4 y F5. */
    public static final int SISTEMAS_MAYOR = 1, SISTEMAS_MENOR = 0;
    /** La parte de física (fichas, categorías, validación): {@code ApiFisica}. Plan v6, F3. */
    public static final int FISICA_MAYOR = 1, FISICA_MENOR = 0;
    /** La parte de radares (barrido por tiers, orden por sistema y distancia, tiempo de detección): {@code ApiRadar}. Plan v7, A2. */
    public static final int RADAR_MAYOR = 1, RADAR_MENOR = 0;
    /** La parte de viajes (portales por tiers, enlaces, coste de viaje): {@code ApiViaje}. Plan v7, B1. */
    public static final int VIAJE_MAYOR = 1, VIAJE_MENOR = 0;
    /** La parte de hora inicial de un mundo nuevo: {@code ApiInicioMundo}. Plan v8. */
    public static final int INICIO_MAYOR = 1, INICIO_MENOR = 0;
    /** Formato del estado de sistemas guardado con el mundo: {@code EstadoSistemas}. Plan v6, F2. No es una API de mods. */
    public static final int ESTADO_MAYOR = 1, ESTADO_MENOR = 0;

    private ApiVersion(){}

    /** Texto para el log y los avisos: "api 1.1 (visual 1.1, ajustes 1.0)". */
    public static String texto(){
        return "api " + PUBLICA_MAYOR + "." + PUBLICA_MENOR + " (visual " + VISUAL_MAYOR + "." + VISUAL_MENOR + ", ajustes " + AJUSTES_MAYOR + "." + AJUSTES_MENOR + ", tiempo " + TIEMPO_MAYOR + "." + TIEMPO_MENOR + ")";
    }

    /** ¿Atiende una versión (mayor, menor) lo que pide un mod? Mismo mayor y menor suficiente. */
    public static boolean compatible(int mayor, int menor, int mayorPedido, int menorMinimo){
        return mayor == mayorPedido && menor >= menorMinimo;
    }

    /** Un mod pregunta si la API visual le sirve. Si no, escribe un aviso claro en el log y devuelve false. */
    public static boolean requiereVisual(String mod, int mayorPedido, int menorMinimo){
        return comprobar(mod, "visual", VISUAL_MAYOR, VISUAL_MENOR, mayorPedido, menorMinimo);
    }

    /** Un mod pregunta si la API de tiempo le sirve. */
    public static boolean requiereTiempo(String mod, int mayorPedido, int menorMinimo){
        return comprobar(mod, "de tiempo", TIEMPO_MAYOR, TIEMPO_MENOR, mayorPedido, menorMinimo);
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
