package mundoinfinito;

/**
 * ETAPA DEL TERRENO BASE: una contribución de terreno que trabaja casilla a casilla sobre el estado común {@link CeldaBase}.
 * El núcleo recorre las casillas UNA vez y por cada una aplica las etapas activas en orden canónico (no se recalcula nada por etapa).
 * Evaluada sola ({@link #aportar}) es una tubería de una sola etapa.
 */
abstract class EtapaBase implements ContribucionTerreno{
    /** Una unidad = una casilla del chunk. */
    static final PresupuestoTrabajo PRESUPUESTO_CASILLAS = new PresupuestoTrabajo(AporteTerreno.N, 0, 8 * 1024, "casilla");

    private final String nombre;
    private final int prioridad;

    EtapaBase(String nombre, int prioridad){
        this.nombre = nombre;
        this.prioridad = prioridad;
    }

    @Override
    public final String id(){
        return "mundo-infinito:" + nombre;
    }

    @Override
    public final String fuente(){
        return "mundo-infinito";
    }

    @Override
    public int version(){
        return 1;
    }

    @Override
    public final int prioridad(){
        return prioridad;
    }

    @Override
    public PresupuestoTrabajo presupuesto(){
        return PRESUPUESTO_CASILLAS;
    }

    /** Aplica la etapa a la casilla: lee el servicio de solo lectura y el estado que dejaron las etapas anteriores, y escribe el suyo. */
    abstract void celda(CeldaBase ce);

    @Override
    public final int aportar(ContextoAporte ctx, AporteTerreno out){
        return PipelineBase.ejecutar(ctx, out, new EtapaBase[]{this});
    }
}
