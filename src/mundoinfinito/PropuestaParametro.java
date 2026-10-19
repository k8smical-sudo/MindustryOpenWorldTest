package mundoinfinito;

/** Una propuesta de valor para un ajuste: la implementación inmutable y sencilla de {@link ContribucionParametro}. */
final class PropuestaParametro implements ContribucionParametro{
    private final String id, fuente, parametro;
    private final double valor;
    private final float peso;
    private final int prioridad;

    private PropuestaParametro(String id, String fuente, String parametro, double valor, float peso, int prioridad){
        this.id = id;
        this.fuente = fuente;
        this.parametro = parametro;
        this.valor = valor;
        this.peso = peso;
        this.prioridad = prioridad;
    }

    /**
     * @param fuente id del mod que propone (entra en el orden canónico)
     * @param id id único de esta propuesta, con el formato "fuente:nombre"
     * @param peso peso para la media y la elección ponderadas; debe ser mayor que 0 (si no, la propuesta se ignora con un aviso)
     * @param prioridad para la prioridad absoluta y el orden canónico: menor = antes
     */
    static PropuestaParametro de(String fuente, String id, String parametro, double valor, double peso, int prioridad){
        return new PropuestaParametro(id, fuente, parametro, valor, (float)peso, prioridad);
    }

    @Override
    public String id(){
        return id;
    }

    @Override
    public String fuente(){
        return fuente;
    }

    @Override
    public int version(){
        return 1;
    }

    @Override
    public float peso(){
        return peso;
    }

    @Override
    public int prioridad(){
        return prioridad;
    }

    @Override
    public String parametro(){
        return parametro;
    }

    @Override
    public double valor(){
        return valor;
    }
}
