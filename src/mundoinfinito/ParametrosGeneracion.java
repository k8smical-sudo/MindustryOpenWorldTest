package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

/**
 * PARÁMETROS DE GENERACIÓN de un mundo: la parte del perfil que lee la generación (fase 4). Inmutable, y es parte de la entrada de las
 * contribuciones: el resultado del terreno depende de (semilla, dimensión, coordenadas, versión de generación, estos parámetros).
 * Los valores por defecto son EXACTAMENTE las constantes de siempre, así que un mundo con el perfil por defecto no cambia.
 *
 * Cada campo nuevo exige: un id aquí, un {@link AjusteMundo} registrado en {@link RegistroAjustes} con su rango, y la lectura en
 * {@link PerfilMundo#parametros()}.
 */
final class ParametrosGeneracion{
    static final String ID_ALTURA_SERPULO = "mundo-infinito:serpulo-estiramiento-altura";
    static final String ID_TEMPERATURA_SERPULO = "mundo-infinito:serpulo-estiramiento-temperatura";
    static final String ID_HUMEDAD_SERPULO = "mundo-infinito:serpulo-estiramiento-humedad";
    static final String ID_FENOMENOS_DISTANCIA = "mundo-infinito:fenomenos-distancia-minima";
    static final String ID_PRESUPUESTO_TERRENO = "mundo-infinito:terreno-presupuesto-pct";

    /** El perfil por defecto de todos los mundos. */
    static final ParametrosGeneracion DEFECTO = new ParametrosGeneracion(Muestreo.K_ALTURA, Muestreo.K_TEMP_S, Muestreo.K_HUM_S);

    /** Cuánto se estira el ruido de relieve, temperatura y humedad de Serpulo hacia [0,1]: más alto = biomas más contrastados. */
    final float estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo;
    /** Distancia mínima, en casillas, entre dos fenómenos de CUALQUIER generador. 0 = sin distancia global (cada generador solo respeta la suya). */
    final int distanciaMinimaFenomenos;
    /**
     * Qué porcentaje (1 a 100) del presupuesto declarado de cada contribución de terreno se le permite gastar por chunk. 100 = todo el declarado (por defecto:
     * nada se degrada). Menos de 100 da una generación más barata y más simple, y la MISMA siempre: se guarda con el mundo y la degradación es determinista.
     */
    final int presupuestoTerrenoPct;

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, 0, 100);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo, int distanciaMinimaFenomenos){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, distanciaMinimaFenomenos, 100);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo, int distanciaMinimaFenomenos, int presupuestoTerrenoPct){
        this.estirarAlturaSerpulo = estirarAlturaSerpulo;
        this.estirarTemperaturaSerpulo = estirarTemperaturaSerpulo;
        this.estirarHumedadSerpulo = estirarHumedadSerpulo;
        this.distanciaMinimaFenomenos = distanciaMinimaFenomenos;
        this.presupuestoTerrenoPct = presupuestoTerrenoPct;
    }
}
