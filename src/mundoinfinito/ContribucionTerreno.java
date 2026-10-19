package mundoinfinito;

/**
 * Contribución que aporta TERRENO de un chunk: por casilla, un código de piso, de mena, de muro y de decoración (los códigos de
 * {@code Muestreo}, independientes de los ids de bloque del juego). Se evalúa en hilos de fondo, varios a la vez.
 */
interface ContribucionTerreno extends Contribucion{
    @Override
    default Tipo tipo(){
        return Tipo.TERRENO;
    }

    /**
     * Escribe el aporte en {@code out} (TODAS las casillas) y devuelve las unidades de trabajo que gastó, contadas por la propia
     * contribución según lo que declaró en {@link #presupuesto()}. Nunca mide tiempo: la degradación se decide por unidades contadas.
     */
    int aportar(ContextoAporte ctx, AporteTerreno out);
}
