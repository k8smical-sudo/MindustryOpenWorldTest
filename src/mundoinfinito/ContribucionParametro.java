package mundoinfinito;

/**
 * Contribución que PROPONE un valor para un ajuste de generación (fase 5): "quiero que el relieve sea 3.0, con peso 1".
 * Varias fuentes pueden proponer sobre el mismo ajuste; {@link Resolucion} las resuelve con la política que declaró el ajuste.
 * Es un dato constante: no depende de la semilla ni de nada que cambie, así que el resultado no depende del orden de carga.
 */
interface ContribucionParametro extends Contribucion{
    @Override
    default Tipo tipo(){
        return Tipo.PARAMETRO;
    }

    /** Id del ajuste al que se refiere (por ejemplo "mundo-infinito:serpulo-estiramiento-altura"). */
    String parametro();

    /** Valor propuesto. Si está fuera del rango del ajuste se recorta al rango (y se anota); nunca se impone tal cual. */
    double valor();
}
