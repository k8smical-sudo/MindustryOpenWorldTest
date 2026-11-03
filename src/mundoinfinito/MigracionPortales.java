package mundoinfinito;

/**
 * MIGRACIÓN DE PORTALES ANTIGUOS (plan v9, P7, decisión 4.4 = opción C). El silo interdimensional y su diálogo de portal pasan a ser el tier 1 del
 * sistema de portales por tiers. Un mundo guardado con el bloque antiguo se migra POR LECTURA: al restaurar un edificio, el nombre guardado se
 * traduce al nombre actual. El bloque nuevo tiene la misma clase y la misma huella (4x4), así que los datos guardados (ítems, líquidos, energía) se
 * leen tal cual. El archivo del mundo no se reescribe aparte: la próxima vez que se guarde, el edificio ya sale con el nombre nuevo.
 *
 * Lógica pura (sin Arc): se prueba en CI sin el juego. Clase de paquete (R4).
 */
final class MigracionPortales{
    /** Nombre del bloque antiguo (silo interdimensional con diálogo de portal). */
    static final String ANTIGUO = "silo-interdimensional";
    /** Nombres de los bloques de portal por tier: el 1 sustituye al antiguo. */
    static final String TIER_1 = "portal-viaje-1", TIER_2 = "portal-viaje-2", TIER_3 = "portal-viaje-3";

    private MigracionPortales(){}

    /** Nombre actual de un bloque guardado. Lo que no es un portal antiguo se devuelve igual (también null). */
    static String nombreActual(String guardado){
        return ANTIGUO.equals(guardado) ? TIER_1 : guardado;
    }

    /** ¿Es el nombre de un bloque de portal antiguo, que hay que migrar? */
    static boolean esAntiguo(String guardado){
        return ANTIGUO.equals(guardado);
    }

    /** Nombre del bloque de portal del tier dado (acotado de 1 a 3). */
    static String nombreDeTier(int tier){
        switch(Calibracion.indice(tier)){
            case 0: return TIER_1;
            case 1: return TIER_2;
            default: return TIER_3;
        }
    }
}
