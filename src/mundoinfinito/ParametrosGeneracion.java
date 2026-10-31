package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

/**
 * PARÁMETROS DE GENERACIÓN de un mundo. Inmutable; el resultado del terreno depende de ellos.
 * Valores heredados: un mundo CREADO ANTES de un ajuste no tiene su clave en el perfil y se abre con el valor de entonces (LEGADO_*), así que no cambia.
 * Los mundos nuevos guardan los valores de DEFECTO al crearse.
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
    static final String ID_POZOS_SPAWN = "mundo-infinito:pozos-spawn";
    /** Probabilidad de respiraderos por bioma (multiplicador de la densidad base): riolita, arkyic, amarillo, rojo. */
    static final String[] ID_RESP_BIOMA = {"mundo-infinito:respiraderos-bioma-riolita", "mundo-infinito:respiraderos-bioma-arkyic",
        "mundo-infinito:respiraderos-bioma-amarillo", "mundo-infinito:respiraderos-bioma-rojo"};
    /** Multiplicador del umbral de terreno abierto (menor = más respiraderos). */
    static final String ID_RESP_UMBRAL = "mundo-infinito:respiraderos-umbral";

    static final int LEGADO_SEPARACION_RESPIRADEROS = 48;
    static final float LEGADO_MEZCLA_RESPIRADEROS = 1f;
    static final int LEGADO_RADIO_MIN = 6, LEGADO_RADIO_MAX = 14;
    static final boolean LEGADO_POZOS_SPAWN = true;
    static final float[] LEGADO_MULT_BIOMA = {1f, 1f, 1f, 1f};
    static final float LEGADO_UMBRAL_RESPIRADEROS = 1f;

    /** Valores de los mundos NUEVOS. */
    static final float[] DEFECTO_MULT_BIOMA = {1.6f, 1.6f, 1.6f, 1.6f};
    static final float DEFECTO_UMBRAL_RESPIRADEROS = 0.85f;

    static final ParametrosGeneracion DEFECTO = new ParametrosGeneracion(Muestreo.K_ALTURA, Muestreo.K_TEMP_S, Muestreo.K_HUM_S, 0, 100, 120, 0.5f, 4, 12, LEGADO_POZOS_SPAWN,
        DEFECTO_MULT_BIOMA, DEFECTO_UMBRAL_RESPIRADEROS);

    final float estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo;
    final int distanciaMinimaFenomenos;
    final int presupuestoTerrenoPct;
    final int separacionRespiraderos;
    final float mezclaRespiraderos;
    final int radioMezclaMin, radioMezclaMax;
    final boolean pozosSpawn;
    /** Multiplicador de la probabilidad de respiradero por grupo de bioma (riolita, arkyic, amarillo, rojo). */
    final float[] multBioma;
    /** Multiplicador del umbral de terreno abierto de un respiradero. */
    final float umbralRespiraderos;

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
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, distanciaMinimaFenomenos, presupuestoTerrenoPct,
            separacionRespiraderos, mezclaRespiraderos, radioMezclaMin, radioMezclaMax, LEGADO_POZOS_SPAWN);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo,
                         int distanciaMinimaFenomenos, int presupuestoTerrenoPct,
                         int separacionRespiraderos, float mezclaRespiraderos, int radioMezclaMin, int radioMezclaMax, boolean pozosSpawn){
        this(estirarAlturaSerpulo, estirarTemperaturaSerpulo, estirarHumedadSerpulo, distanciaMinimaFenomenos, presupuestoTerrenoPct,
            separacionRespiraderos, mezclaRespiraderos, radioMezclaMin, radioMezclaMax, pozosSpawn, LEGADO_MULT_BIOMA, LEGADO_UMBRAL_RESPIRADEROS);
    }

    ParametrosGeneracion(float estirarAlturaSerpulo, float estirarTemperaturaSerpulo, float estirarHumedadSerpulo,
                         int distanciaMinimaFenomenos, int presupuestoTerrenoPct,
                         int separacionRespiraderos, float mezclaRespiraderos, int radioMezclaMin, int radioMezclaMax,
                         boolean pozosSpawn, float[] multBioma, float umbralRespiraderos){
        this.estirarAlturaSerpulo = estirarAlturaSerpulo;
        this.estirarTemperaturaSerpulo = estirarTemperaturaSerpulo;
        this.estirarHumedadSerpulo = estirarHumedadSerpulo;
        this.distanciaMinimaFenomenos = distanciaMinimaFenomenos;
        this.presupuestoTerrenoPct = presupuestoTerrenoPct;
        this.separacionRespiraderos = separacionRespiraderos;
        this.mezclaRespiraderos = mezclaRespiraderos;
        this.radioMezclaMin = radioMezclaMin;
        this.radioMezclaMax = radioMezclaMax;
        this.pozosSpawn = pozosSpawn;
        this.multBioma = new float[4];
        for(int i = 0; i < 4; i++) this.multBioma[i] = multBioma != null && i < multBioma.length && multBioma[i] >= 0f ? multBioma[i] : 1f;
        this.umbralRespiraderos = umbralRespiraderos > 0f ? umbralRespiraderos : 1f;
    }
}
