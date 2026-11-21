package mundoinfinito;

/**
 * Barridos de Erekir, solo lectura (cada combinación se instala como parámetros del mundo durante la medición):
 *  1) Pesos y probabilidades de pared por tipo (32 combinaciones).
 *  2) Reglas de acceso de la mena de pared con la mejor combinación de (1) fijada (54 combinaciones):
 *     radio, camino mínimo, espacio local y tamaño del taladro.
 * Cada línea muestra el reparto medido y el error frente a los objetivos de Erekir. 10 semillas por combinación.
 */
public final class ApiBarridoPared {
    private ApiBarridoPared(){}

    static final int SEMILLAS = 10;
    static final int LADO_EREKIR = 512;
    static final String[] OBJ_NOMBRES = {"berilio", "tungsteno", "torio-cristal", "berilio-pared", "tungsteno-pared", "torio-pared"};
    static final float[] OBJETIVOS = {16.7f, 52.3f, 0f, 26f, 3f, 2f};

    // (1) pesos y probabilidades
    static final float[] PROB_BERILIO = {0.3f, 0.6f, 0.86f, 1.0f};
    static final float[] PROB_TUNGSTENO = {0.12f, 0.4f};
    static final int[] PESO_BERILIO = {13, 18};
    static final int[] PESO_TUNGSTENO = {35, 44};

    // (2) reglas de acceso, sobre la mejor combinación de (1)
    static final float FIJO_PROB_BERILIO = 0.86f, FIJO_PROB_TUNGSTENO = 0.40f;
    static final int FIJO_PESO_BERILIO = 18, FIJO_PESO_TUNGSTENO = 35;
    static final int[] RADIOS = {4};
    static final int[] CAMINOS = {8};
    static final int[] ESPACIOS_LOCALES = {4};
    static final int[] TALADROS = {1, 2};
    static final int[] ZONAS_INTERIOR = {16, 64, 128, 256};

    static boolean esPared(int codigo){
        return codigo == MundoInfinitoMod.Muestreo.O_BERILIO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TORIO_MURO;
    }

    static int indice(int codigo){
        if(codigo == MundoInfinitoMod.Muestreo.O_BERILIO) return 0;
        if(codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO) return 1;
        if(codigo == MundoInfinitoMod.Muestreo.O_TORIO_CRISTAL) return 2;
        if(codigo == MundoInfinitoMod.Muestreo.O_BERILIO_MURO) return 3;
        if(codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO) return 4;
        if(codigo == MundoInfinitoMod.Muestreo.O_TORIO_MURO) return 5;
        return -1;
    }

    /** Ejecuta un conjunto de parámetros y devuelve el reparto medido y el error. */
    private static String medir(ParametrosGeneracion par, String etiqueta, AporteTerreno ap, EtapaBase[] etapas){
        final int T = AporteTerreno.T;
        final int n = (LADO_EREKIR + T - 1) / T;
        final int desde = -(n / 2), hasta = n - n / 2 - 1;
        Nucleo.alReiniciarMundo(par);
        final long[] cuenta = new long[OBJETIVOS.length];
        long total = 0, pared = 0;
        for(int i = 0; i < SEMILLAS; i++){
            final int sem = 12345 + 1000 * i;
            for(int cy = desde; cy <= hasta; cy++)
            for(int cx = desde; cx <= hasta; cx++){
                PipelineBase.ejecutar(new ContextoAporte(sem, 1, cx * T, cy * T, cx, cy, par), ap, etapas);
                for(int j = 0; j < AporteTerreno.N; j++){
                    final int code = ap.mena[j];
                    if(code == 0) continue;
                    total++;
                    final int k = indice(code);
                    if(k >= 0) cuenta[k]++;
                    if(esPared(code)) pared++;
                }
            }
        }
        double error = 0;
        final double[] pct = new double[OBJETIVOS.length];
        for(int k = 0; k < OBJETIVOS.length; k++){
            pct[k] = total == 0 ? 0 : 100.0 * cuenta[k] / total;
            error += Math.abs(pct[k] - OBJETIVOS[k]);
        }
        return String.format("[Barrido] %s | suelo: berilio %5.1f tungsteno %5.1f torio-c %4.1f | pared: berilio %5.1f tungsteno %5.1f torio %4.1f | pared total %5.1f | error=%6.1f",
            etiqueta, pct[0], pct[1], pct[2], pct[3], pct[4], pct[5], total == 0 ? 0 : 100.0 * pared / total, error);
    }

    /** Barrido completo: (1) pesos y probabilidades, luego (2) reglas de acceso sobre la mejor de (1). */
    public static int barrido(String[] args){
        final AporteTerreno ap = new AporteTerreno();
        final EtapaBase[] etapas = PipelineBase.etapasBase();
        System.out.println("[Barrido] erekir lado=" + LADO_EREKIR + " semillas=" + SEMILLAS);
        try{
            final float mB = FIJO_PROB_BERILIO, mT = FIJO_PROB_TUNGSTENO; final int mWB = FIJO_PESO_BERILIO, mWT = FIJO_PESO_TUNGSTENO;
            System.out.println("[Barrido] --- (2) espacio local corto: 12 combinaciones con la mejor de (1) fijada");
            for(int zona : ZONAS_INTERIOR) for(int radio : RADIOS) for(int camino : CAMINOS) for(int espacio : ESPACIOS_LOCALES) for(int tal : TALADROS){
                final ParametrosGeneracion par = new PerfilMundo().parametros();
                par.paredProbBerilio = mB; par.paredProbTungsteno = mT; par.pesoBerilioE = mWB; par.pesoTungstenoE = mWT;
                par.menasUmbralZona = zona;
                par.menasAccesoRadio = radio; par.menasAccesoMinimo = camino; par.menasEspacioLocal = espacio; par.menasTaladroTamano = tal;
                final String etiqueta = String.format("umbral=%d radio=%d camino=%d espacio=%d taladro=%d", zona, radio, camino, espacio, tal);
                System.out.println(medir(par, etiqueta, ap, etapas));
            }
        }finally{
            Nucleo.alReiniciarMundo(null);
        }
        return 0;
    }
}
