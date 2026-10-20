package mundoinfinito;

/**
 * REGLAS DE DEGRADACIÓN de simulación (fase 7). Se aplican cuando una contribución pide más unidades de trabajo ({@code pedidas}) de las que
 * se le permiten ({@code permitidas}). Todas son deterministas: se deciden por unidades CONTADAS, nunca por tiempo, y dan siempre la misma salida
 * para la misma entrada. Ninguna interrumpe la generación: el trabajo que falta se queda como lo dejaron las contribuciones anteriores.
 *
 * Cada contribución declara cuál le corresponde ({@link Contribucion#reglaDegradacion()}); la de por defecto es el recorte por orden canónico (D-N4).
 */
enum ReglaDegradacion{
    /**
     * Se hace solo el trabajo de las primeras {@code permitidas} unidades en orden canónico (casillas fila a fila); el resto queda como estaba.
     */
    RECORTE_CANONICO("recorte-canonico"),

    /**
     * Se hace el trabajo en una retícula más gruesa: una casilla de cada k x k (k potencia de 2, la menor que cabe en el presupuesto), con la retícula
     * anclada a las coordenadas VIRTUALES para que dos chunks vecinos coincidan. Da una versión más ligera y repartida de lo mismo (menos decoración, menos
     * menas), en lugar de un corte.
     */
    REDUCCION_DE_RESOLUCION("reduccion-de-resolucion"),

    /** No se hace nada de esa contribución en ese chunk (todo o nada). */
    OMISION("omision");

    /** Nombre que sale en el registro de degradaciones. */
    final String nombre;

    ReglaDegradacion(String nombre){
        this.nombre = nombre;
    }
}
