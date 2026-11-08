package mundoinfinito;

/**
 * Embudo de diagnóstico del reparto de menas de pared: cuenta, por cada filtro, cuántas casillas de depósitos de pared
 * sobreviven, y cuánto mineral de suelo se pierde porque el depósito es de pared. Solo cuenta cuando {@link #activo} está
 * encendido (lo enciende el modo "embudo" del Corpus, en un único hilo). Fuera de ese modo no hace nada.
 */
public final class ApiDiagnosticoPared {
    public static final int TOTAL = 0, EN_MURO = 1, EN_ZONA = 2, EXPUESTO = 3, ACCESIBLE = 4, COLOCADA = 5,
        SUELO_PERDIDO = 6, SUELO_MENA = 7;
    static final String[] NOMBRES = {
        "casillas de depósito de pared (berilio/tungsteno/torio)", "  en muro natural", "  en muro dentro de la zona (radN<100)",
        "  con taladro posible (expuesto)", "  y espacio abierto suficiente (accesible)", "  => mena de pared colocada",
        "suelo perdido por depósito de pared (casillas que habrían tenido mena de suelo)", "mena de suelo colocada (todas)"};

    public static volatile boolean activo = false;
    static final long[] C = new long[NOMBRES.length];

    private ApiDiagnosticoPared(){}

    static void contar(int i){ if(activo) C[i]++; }

    /** Modo "embudo": por cada semilla y tipo de mundo imprime el embudo. Solo lee: no guarda nada. */
    public static int embudo(String[] args){
        final int radio = args.length > 1 ? Math.max(0, Integer.parseInt(args[1])) : 8;
        final long[] semillas = {12345L, 777L, 4242L};
        final int T = AporteTerreno.T;
        final AporteTerreno ap = new AporteTerreno();
        final EtapaBase[] etapas = PipelineBase.etapasBase();
        for(int dim : new int[]{1, 0}){
            for(long sem : semillas){
                java.util.Arrays.fill(C, 0L);
                activo = true;
                final ParametrosGeneracion par = Nucleo.parametros();
                for(int cy = -radio; cy <= radio; cy++)
                    for(int cx = -radio; cx <= radio; cx++)
                        PipelineBase.ejecutar(new ContextoAporte((int)sem, dim, cx * T, cy * T, cx, cy, par), ap, etapas);
                activo = false;
                System.out.println("[Embudo] dimension=" + dim + " semilla=" + sem + " radio=" + radio);
                for(int i = 0; i < NOMBRES.length; i++) System.out.println("[Embudo]   " + NOMBRES[i] + ": " + C[i]);
            }
        }
        return 0;
    }
}
