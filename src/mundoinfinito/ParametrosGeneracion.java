package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

/**
 * PARÁMETROS DE GENERACIÓN de un mundo: la parte del perfil que lee la generación. Inmutable; el resultado del terreno depende de ellos.
 *
 * Valores heredados: un mundo CREADO ANTES de que existieran los ajustes de respiraderos no tiene sus claves guardadas en el perfil, y se abre con los
 * valores de entonces (LEGADO_*), así que no cambia su terreno. Los mundos nuevos guardan los valores de DEFECTO al crearse.
 * Los constructores de 3, 4 y 5 argumentos (pruebas y llamadas antiguas) usan también los valores heredados.
 */
final class ParametrosGeneracion{
    static final String ID_ALTURA_SERPULO = "mundo-infinito:serpulo-estiramiento-altura";
    static final String ID_TEMPERATURA_SERPULO = "mundo-infinito:serpulo-estiramiento-temperatura";
    static final String ID_HUMEDAD_SERPULO = "mundo-infinito:serpulo-estiramiento-humedad";
    static final String ID_FENOMENOS_DISTANCIA = "mundo-infinito:fenomenos-distancia-minima";
    static final String ID_PRESUPUESTO_TERRENO = "mundo-infinito:terreno-presupuesto-pct";
    static final String ID_RESPIRADEROS_SEPARACION = "mundo-infinito:respiraderos-separacion";
    static final String ID_RESPIRADEROS_MEZCLA = "mundo-infinito:respiraderos-mezcla";
    static final String ID_RESPIRADEROS_RADIO_MIN = "mundo-infinito:respiraderos-radio-min";
    static final String ID_RESPIRADEROS_RADIO_MAX = "mundo-infinito:respiraderos-radio-max";

    /** Valores de los mundos anteriores a los ajustes de respiraderos (los que tenían fijos en el código). */
    static final int LEGADO_SEPARACION_RESPIRADEROS = 48;
    static final float LEGADO_MEZCLA_RESPIRADEROS = 1f;
    static final int LEGADO_RADIO_MIN = 6, LEGADO_RADIO_MAX = 14;

    /** El perfil por defecto de los mundos NUEVOS. */
    static final ParametrosGeneracion DEFECTO = new ParametrosGeneracion(Muestreo.K_ALTURA, Muestreo.K_TEMP_S, Muestreo.K_HUM_S, 0, 100, 120, 0.5f, 4, 12);

    /** Cuánto se estira el ruido de relieve, temperatura y humedad de Serpulo hacia [0,1]: más alto = biomas más contrastados. */
    final float estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo;
    /** Distancia mínima, en casillas, entre dos fenómenos de CUALQUIER generador. 0 = sin distancia global. */
    final int distanciaMinimaFenomenos;
    /** Porcentaje (1 a 100) del presupuesto declarado de cada contribución de terreno que se permite gastar por chunk. */
    final int presupuestoTerrenoPct;
    /** Separación mínima, en casillas, entre dos respiraderos de Erekir (la retícula de búsqueda se ajusta a ella). */
    final int separacionRespiraderos;
    /** Fracción (0 a 1) de los respiraderos que alteran el suelo de su alrededor. El resto aparece sobre el terreno natural. */
    final float mezclaRespiraderos;
    /** Radio mínimo y máximo, en casillas, de la zona alterada alrededor de un respiradero. */
    final int radioMezclaMin, radioMezclaMax;

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, 0, 100);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo, int distanciaMinimaFenomenos){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, distanciaMinimaFenomenos, 100);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo, int distanciaMinimaFenomenos, int presupuestoTerrenoPct){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, distanciaMinimaFenomenos, presupuestoTerrenoPct,
            LEGADO_SEPARACION_RESPIRADEROS, LEGADO_MEZCLA_RESPIRADEROS, LEGADO_RADIO_MIN, LEGADO_RADIO_MAX);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo,
                         int distanciaMinimaFenomenos, int presupuestoTerrenoPct,
                         int separacionRespiraderos, float mezclaRespiraderos, int radioMezclaMin, int radioMezclaMax){
        this.estirarAlturaSerpulo = estirarAlturaSerpulo;
        this.estirarTemperaturaSerpulo = estirarTemperaturaSerpulo;
        this.estirarHumedadSerpulo = estirarHumedadSerpulo;
        this.distanciaMinimaFenomenos = distanciaMinimaFenomenos;
        this.presupuestoTerrenoPct = presupuestoTerrenoPct;
        this.separacionRespiraderos = separacionRespiraderos;
        this.mezclaRespiraderos = mezclaRespiraderos;
        this.radioMezclaMin = radioMezclaMin;
        this.radioMezclaMax = radioMezclaMax;
    }
}
