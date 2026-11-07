package mundoinfinito;

/**
 * RESPIRADEROS SEGÚN LA FICHA FÍSICA DEL LUGAR (plan v9, P9, generación 6). En los mundos de la generación {@link MundoInfinitoMod#GEN_FICHA} o posterior, el
 * tamaño del planeta del lugar (categoría de su ficha física, {@link FichaFisica#tamano}) multiplica la probabilidad de un respiradero: un planeta pequeño
 * tiene menos y uno grande tiene más. Los valores están en {@link Calibracion}. Los mundos de generaciones anteriores no lo aplican y conservan su terreno.
 *
 * Lógica pura: el factor depende solo de la categoría, así que el terreno sigue siendo función de la semilla y las coordenadas (determinista).
 */
final class RespiraderosFicha{
    private RespiraderosFicha(){}

    /** Factor de la categoría de tamaño ("pequeno", "medio", "grande"). Cualquier otro valor (vacío, desconocido): 1, sin efecto. */
    static float factor(String tamano){
        if("pequeno".equals(tamano)) return (float)Calibracion.RESPIRADERO_FICHA_PEQUENO;
        if("medio".equals(tamano)) return (float)Calibracion.RESPIRADERO_FICHA_MEDIO;
        if("grande".equals(tamano)) return (float)Calibracion.RESPIRADERO_FICHA_GRANDE;
        return 1f;
    }
}
