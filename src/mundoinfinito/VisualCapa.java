package mundoinfinito;

import mindustry.graphics.Layer;

/**
 * CAPAS DE DIBUJO de la capa visual (fase 9). Cada contribución visual declara en cuál dibuja; el orden entre capas es el del motor (z creciente) y
 * dentro de una capa el de (prioridad, id de fuente, id): nunca el orden de carga.
 *
 * Las capas están en el orden real en que el motor las pinta, que no coincide con el orden en que el plan las enumera: las balas (z 100) quedan por debajo
 * de las unidades voladoras (z 115). Se usan las constantes de {@code Layer}, que son valores fijos y no cargan la clase en ejecución.
 */
enum VisualCapa{
    /** Marcas sobre el suelo, bajo los escombros y los bloques. */
    SUELO(Layer.scorch),
    /** Con los bloques normales. */
    BLOQUES(Layer.block),
    /** Sobre los bloques (guías, brillos). */
    SOBRE_BLOQUES(Layer.blockOver),
    /** Con las unidades terrestres. */
    UNIDADES(Layer.groundUnit),
    /** Con las balas. */
    BALAS(Layer.bullet),
    /** Sobre las unidades voladoras. */
    SOBRE_UNIDADES(Layer.flyingUnit + 2f),
    /** Superposiciones del mundo, como los nombres de jugador. No sustituye a la interfaz de Scene2D del juego. */
    INTERFAZ(Layer.playerName);

    /** Valor z base de la capa. */
    final float z;

    VisualCapa(float z){
        this.z = z;
    }
}
