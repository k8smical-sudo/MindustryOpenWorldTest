package mundoinfinito;

/** Por dónde sale el dibujo de un fotograma: la implementación real usa {@code Draw}; las pruebas anotan lo que pasa. */
interface VisualSalida{
    /** Fija la capa (z) en la que se dibuja a partir de ahora, con el dibujo ordenado del motor. */
    void z(float z);

    /** Deja el estado de dibujo como estaba tras una contribución. */
    void reiniciar();

    /** Ejecuta {@code r} en el lugar que le corresponde a la capa {@code z} (cuando el motor ordena y vuelca el lote). Necesario para usar shaders. */
    void enCapa(float z, Runnable r);
}
