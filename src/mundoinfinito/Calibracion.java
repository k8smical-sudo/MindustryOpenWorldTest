package mundoinfinito;

/**
 * CALIBRACIÓN CENTRAL (plan v9, P12). Todos los números de equilibrio del mod en un solo sitio, para ajustarlos sin buscar por el código.
 * Lógica pura, sin Arc: se puede leer desde cualquier prueba.
 *
 * ESTADO DE LOS VALORES: son los valores propuestos del plan, sin cambios. Se confirman o se retocan con partidas reales; esta clase solo
 * los reúne. Cuando se cambie uno, hay que revisar la prueba que lo cita (PruebasRadar, PruebasViaje, PruebasFenomenos).
 */
final class Calibracion{
    private Calibracion(){}

    // ================================================================== radares (tiers 1 a 3)
    /** Alcance en unidades del sistema solar. */
    static final double[] RADAR_ALCANCE = {40.0, 120.0, 400.0};
    /** Ticks de barrido por cuerpo detectado. */
    static final long[] RADAR_TICKS_POR_CUERPO = {600L, 240L, 60L};
    /** Consumo de energía por tick (el consumo es la decisión de diseño: los radares gastan energía, no materiales por uso). */
    static final float[] RADAR_ENERGIA = {1f, 3f, 8f};
    /** Tamaño del bloque en casillas. */
    static final int[] RADAR_TAMANO = {2, 3, 4};

    // ================================================================== portales (tiers 1 a 3)
    static final double[] PORTAL_ALCANCE = {60.0, 300.0, 1000.0};
    static final long[] PORTAL_TICKS_ESTABLECER = {300L, 900L, 2400L};
    /** Materiales que el portal pide del inventario del núcleo para cada viaje (formato "item:cantidad"). Propuesta de la sección 5.5 del plan. */
    static final String[][] PORTAL_REQUISITOS = {
        {"copper:50", "lead:30"},
        {"silicon:40", "titanium:20", "lead:40"},
        {"surge-alloy:20", "phase-fabric:20", "silicon:60"}
    };
    /** Tamaño del bloque de portal en casillas. El tier 1 mide 4, igual que el silo que sustituye (la migración conserva la huella). */
    static final int[] PORTAL_TAMANO = {4, 5, 6};

    /** Coste de CONSTRUIR el bloque de portal de cada tier. Solo ítems que existen en Serpulo y en Erekir (silicio, grafito, torio). Propuesto. */
    static final String[][] PORTAL_CONSTRUCCION = {
        {"silicon:400", "graphite:300", "thorium:200"},
        {"silicon:900", "graphite:700", "thorium:500"},
        {"silicon:1800", "graphite:1400", "thorium:1000"}
    };
    /** Un enlace nuevo solo se puede establecer con un cuerpo que un radar ya detectó. */
    static final boolean PORTAL_REQUIERE_DETECCION = true;

    // ================================================================== viaje: tiempo y coste
    static final double VIAJE_TICKS_POR_UNIDAD = 2.0;
    static final double VIAJE_COSTE_POR_UNIDAD = 1.0;

    // ================================================================== unidades que viajan
    /** Vida máxima de una unidad por debajo de la cual es de tier 1 y de tier 2. A partir de ahí, tier 3. */
    static final double UNIDAD_VIDA_TIER2 = 1000.0, UNIDAD_VIDA_TIER3 = 10000.0;
    /** Las unidades del jugador que estén a menos de esta distancia (en casillas) viajan con él. */
    static final float UNIDADES_RADIO_VIAJE = 60f;
    /** Máximo de unidades que viajan a la vez con el jugador. */
    static final int UNIDADES_MAX_VIAJE = 40;

    // ================================================================== boosters de viaje
    static final double BOOSTER_ALCANCE = 1.25, BOOSTER_VELOCIDAD = 0.80, BOOSTER_ESTABILIDAD = 0.5;

    // ================================================================== caos de viajes (apagado por defecto)
    static final double CAOS_BASE = 0.02, CAOS_TOPE = 0.25, CAOS_COSTE_EXTRA = 0.10, CAOS_RETRASO = 1.5;

    // ================================================================== agujero negro (variables por defecto)
    static final double AGUJERO_HORIZONTE = 0.4, AGUJERO_ATRACCION = 1.0, AGUJERO_INTENSIDAD = 1.0,
        AGUJERO_PERIODO_CAOS = 3600.0, AGUJERO_PROBABILIDAD_INCIDENTE = 0.02;

    // ================================================================== colisiones de cuerpos (apagadas por defecto)
    /** Duración del pulso rojo del visor 2D, en ticks. */
    static final float COLISION_PULSO_TICKS = 180f;

    /** Valor del tier (1 a 3), acotado: un tier fuera de rango usa el más cercano. */
    static int indice(int tier){
        return Math.max(1, Math.min(3, tier)) - 1;
    }
}
