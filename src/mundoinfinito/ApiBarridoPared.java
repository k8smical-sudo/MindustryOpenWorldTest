package mundoinfinito;

/**
 * Barrido de parámetros de las menas de pared en Erekir: separa el efecto de la probabilidad de depósitos de pared
 * del efecto de la regla de acceso (taladro y camino mínimo). Cada combinación se instala como parámetros del mundo
 * (sin tocar los valores por defecto) y se mide con 10 semillas en la región de tamaño del planeta.
 * Solo lee: no guarda nada.
 */
public final class ApiBarridoPared {
    private ApiBarridoPared(){}

    static final float[] PROBS = {0.5f, 0.75f, 1.0f};
    static final int[] TALADROS = {1, 2};
    static final int[] CAMINOS = {8, 16};
    static final int SEMILLAS = 10;
    static final int LADO_EREKIR = 512;

    static boolean esPared(int codigo){
        return codigo == MundoInfinitoMod.Muestreo.O_BERILIO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO
            || codigo == MundoInfinitoMod.Muestreo.O_TORIO_MURO;
    }

    /** Mide la proporción de pared (% del total de menas) para cada combinación de probabilidad, taladro y camino. */
    public static int barrido(String[] args){
        final int T = AporteTerreno.T;
        final int n = (LADO_EREKIR + T - 1) / T;
        final int desde = -(n / 2), hasta = n - n / 2 - 1;
        final AporteTerreno ap = new AporteTerreno();
        System.out.println("[Barrido] erekir lado=" + LADO_EREKIR + " chunks=" + (n * n) + " semillas=" + SEMILLAS
            + " (pared = % del total de menas)");
        try{
            for(float prob : PROBS){
                for(int tal : TALADROS){
                    for(int camino : CAMINOS){
                        final ParametrosGeneracion par = new PerfilMundo().parametros();
                        par.menasParedProb = prob;
                        par.menasTaladroTamano = tal;
                        par.menasAccesoMinimo = camino;
                        Nucleo.alReiniciarMundo(par);
                        final EtapaBase[] etapas = PipelineBase.etapasBase();
                        double min = 100, max = 0, suma = 0;
                        for(int i = 0; i < SEMILLAS; i++){
                            final int sem = 12345 + 1000 * i;
                            long menas = 0, pared = 0;
                            for(int cy = desde; cy <= hasta; cy++){
                                for(int cx = desde; cx <= hasta; cx++){
                                    PipelineBase.ejecutar(new ContextoAporte(sem, 1, cx * T, cy * T, cx, cy, par), ap, etapas);
                                    for(int j = 0; j < AporteTerreno.N; j++){
                                        final int code = ap.mena[j];
                                        if(code == 0) continue;
                                        menas++;
                                        if(esPared(code)) pared++;
                                    }
                                }
                            }
                            final double pct = menas == 0 ? 0 : 100.0 * pared / menas;
                            min = Math.min(min, pct);
                            max = Math.max(max, pct);
                            suma += pct;
                        }
                        System.out.println(String.format("[Barrido] prob=%.2f taladro=%d camino=%d  pared media=%5.1f%%  min=%5.1f%%  max=%5.1f%%  rango=%5.1f",
                            prob, tal, camino, suma / SEMILLAS, min, max, max - min));
                    }
                }
            }
        }finally{
            Nucleo.alReiniciarMundo(null);   // vuelve a los valores por defecto
        }
        return 0;
    }
}
