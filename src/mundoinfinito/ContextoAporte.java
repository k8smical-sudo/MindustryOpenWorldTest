package mundoinfinito;

/**
 * Lo que una contribución de terreno sabe del chunk que se le pide: identidad y parámetros de generación del mundo, y nada más. Inmutable y de solo lectura.
 * Las coordenadas son VIRTUALES (tile = chunk * 32 + 0..31).
 */
final class ContextoAporte{
    final int semilla, dimension;
    /** Casilla de origen del chunk (esquina inferior izquierda), en coordenadas virtuales. */
    final int x0, y0;
    /** Chunk virtual. */
    final int vcx, vcy;
    /** Parámetros de generación del mundo (fase 4). Forman parte de la entrada: mismo mundo, mismos parámetros, mismo resultado. */
    final ParametrosGeneracion params;

    ContextoAporte(int semilla, int dimension, int x0, int y0, int vcx, int vcy, ParametrosGeneracion params){
        this.semilla = semilla;
        this.dimension = dimension;
        this.x0 = x0;
        this.y0 = y0;
        this.vcx = vcx;
        this.vcy = vcy;
        this.params = params;
    }
}
