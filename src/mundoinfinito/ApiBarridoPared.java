package mundoinfinito;

/**
 * Barrido de Erekir: prueba combinaciones de probabilidad de pared por tipo (berilio, tungsteno) y de peso de suelo
 * (berilio, tungsteno). Cada combinación se instala como parámetros del mundo solo durante la medición y se mide con
 * 10 semillas en la región de tamaño de Erekir. Muestra el reparto medido y un error total frente a los objetivos.
 * Solo lee: no cambia los valores por defecto.
 */
public final class ApiBarridoPared {
    private ApiBarridoPared(){}

    static final int SEMILLAS = 10;
    static final int LADO_EREKIR = 512;
    static final float[] PROB_BERILIO = {0.3f, 0.6f, 0.86f, 1.0f};
    static final float[] PROB_TUNGSTENO = {0.12f, 0.4f};
    static final int[] PESO_BERILIO = {13, 18};
    static final int[] PESO_TUNGSTENO = {35, 44};
    /** Objetivos de Erekir (% del total de menas), en el orden de OBJ_NOMBRES. */
    static final String[] OBJ_NOMBRES = {"berilio", "tungsteno", "torio-cristal", "berilio-pared", "tungsteno-pared", "torio-pared"};
    static final float[] OBJETIVOS = {16.7f, 52.3f, 0f, 26f, 3f, 2f};

    static boolean esPared(int codigo){
        return codigo == MundoInfinitoMod.Muestreo.O_BERILIO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TORIO_MURO;
    }

    /** Índice en OBJ_NOMBRES de un código de mena de Erekir, o -1 si no es uno de los seis. */
    static int indice(int codigo){
        final int M = MundoInfinitoMod.Muestreo.O_BERILIO, T = MundoInfinitoMod.Muestreo.O_TUNGSTENO;
        if(codigo == M) return 0;
        if(codigo == T) return 1;
        if(codigo == MundoInfinitoMod.Muestreo.O_TORIO_CRISTAL) return 2;
        if(codigo == MundoInfinitoMod.Muestreo.O_BERILIO_MURO) return 3;
        if(codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO) return 4;
        if(codigo == MundoInfinitoMod.Muestreo.O_TORIO_MURO) return 5;
        return -1;
    }

    /** Barrido completo de Erekir: una línea por combinación, con el reparto medido y el error frente a objetivos. */
    public static int barrido(String[] args){
        final int T = AporteTerreno.T;
        final int n = (LADO_EREKIR + T - 1) / T;
        final int desde = -(n / 2), hasta = n - n / 2 - 1;
        final AporteTerreno ap = new AporteTerreno();
        final EtapaBase[] etapas = PipelineBase.etapasBase();
        System.out.println("[Barrido] erekir lado=" + LADO_EREKIR + " chunks=" + (n * n) + " semillas=" + SEMILLAS
            + " combinaciones=" + (PROB_BERILIO.length * PROB_TUNGSTENO.length * PESO_BERILIO.length * PESO_TUNGSTENO.length));
        try{
            for(float pB : PROB_BERILIO)
            for(float pT : PROB_TUNGSTENO)
            for(int wB : PESO_BERILIO)
            for(int wT : PESO_TUNGSTENO){
                final ParametrosGeneracion par = new PerfilMundo().parametros();
                par.paredProbBerilio = pB;
                par.paredProbTungsteno = pT;
                par.pesoBerilioE = wB;
                par.pesoTungstenoE = wT;
                Nucleo.alReiniciarMundo(par);
                final double[] pct = new double[OBJETIVOS.length];
                long total = 0;
                final long[] cuenta = new long[OBJETIVOS.length];
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
                        }
                    }
                }
                double error = 0;
                for(int k = 0; k < OBJETIVOS.length; k++){
                    pct[k] = total == 0 ? 0 : 100.0 * cuenta[k] / total;
                    error += Math.abs(pct[k] - OBJETIVOS[k]);
                }
                System.out.println(String.format(
                    "[Barrido] pB=%.2f pT=%.2f wB=%d wT=%d | suelo: berilio %5.1f tungsteno %5.1f torio-c %4.1f | pared: berilio %5.1f tungsteno %5.1f torio %4.1f | error=%6.1f",
                    pB, pT, wB, wT, pct[0], pct[1], pct[2], pct[3], pct[4], pct[5], error));
            }
        }finally{
            Nucleo.alReiniciarMundo(null);
        }
        return 0;
    }
}
