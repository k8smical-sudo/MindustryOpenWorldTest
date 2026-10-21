package mundoinfinito;

/**
 * Un programa de shader listo para usar: lo único que el registro visual necesita saber de él. La implementación real envuelve un shader de Arc
 * ({@code VisualArc}); las pruebas usan programas falsos, así que la lógica del registro se comprueba sin GPU.
 */
interface VisualPrograma{
    /** Ejecuta {@code dibujo} con este programa activo y deja el estado de dibujo como estaba. Puede lanzar una excepción: el registro desactiva el shader. */
    void aplicar(Runnable dibujo);

    /** Libera los recursos (GPU). Solo se llama desde el hilo de render. */
    default void liberar(){}
}
