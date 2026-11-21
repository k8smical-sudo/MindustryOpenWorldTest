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

    /** Zonas observadas (celda de depósito de 40 casillas): clave = celda, valor = {candidatas, accesibles}. Solo en modo zonas. */
    static final java.util.Map<Long, int[]> ZONAS = new java.util.HashMap<Long, int[]>();

    static void zona(int x, int y, boolean accesible){
        if(!activo) return;
        final int lado = MundoInfinitoMod.Muestreo.CELDA_MENA;
        final long clave = ((long)Math.floorDiv(x, lado) << 32) ^ (Math.floorDiv(y, lado) & 0xffffffffL);
        int[] v = ZONAS.get(clave);
        if(v == null){ v = new int[2]; ZONAS.put(clave, v); }
        v[0]++;
        if(accesible) v[1]++;
    }

    /**
     * Modo "zonas": por semilla, cuántas celdas de depósito de pared hay y cuántas casillas accesibles tiene cada una.
     * Para cada umbral k muestra qué fracción de zonas lo alcanza y qué fracción de las accesibles retiene, es decir,
     * el efecto de decidir pared o suelo por zona. Solo lee.
     */
    public static int zonas(String[] args){
        final int radio = args.length > 1 ? Math.max(0, Integer.parseInt(args[1])) : 8;
        final int T = AporteTerreno.T;
        final AporteTerreno ap = new AporteTerreno();
        final EtapaBase[] etapas = PipelineBase.etapasBase();
        final int[] umbrales = {1, 2, 4, 8, 16, 24};
        System.out.println("[Zonas] erekir radio=" + radio + " (zona = celda de " + MundoInfinitoMod.Muestreo.CELDA_MENA + " casillas)");
        for(int i = 0; i < 10; i++){
            final int sem = 12345 + 1000 * i;
            ZONAS.clear();
            activo = true;
            final ParametrosGeneracion par = Nucleo.parametros();
            for(int cy = -radio; cy <= radio; cy++)
                for(int cx = -radio; cx <= radio; cx++)
                    PipelineBase.ejecutar(new ContextoAporte(sem, 1, cx * T, cy * T, cx, cy, par), ap, etapas);
            activo = false;
            long zonas = ZONAS.size(), cand = 0, acc = 0;
            for(int[] v : ZONAS.values()){ cand += v[0]; acc += v[1]; }
            System.out.println(String.format("[Zonas] semilla=%d zonas=%d candidatas=%d accesibles=%d", sem, zonas, cand, acc));
            for(int k : umbrales){
                long zonasOk = 0, accRetenidas = 0;
                for(int[] v : ZONAS.values()) if(v[1] >= k){ zonasOk++; accRetenidas += v[1]; }
                System.out.println(String.format("[Zonas]   k>=%2d: zonas %5.1f%%  accesibles retenidas %5.1f%%", k,
                    zonas == 0 ? 0 : 100.0 * zonasOk / zonas, acc == 0 ? 0 : 100.0 * accRetenidas / acc));
            }
        }
        return 0;
    }
}
