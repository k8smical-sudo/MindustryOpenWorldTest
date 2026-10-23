package mundoinfinito;

/**
 * Una instancia de un efecto, del POOL: se reutiliza, nunca se crea una nueva al emitir. Solo es válida mientras el efecto la está dibujando; no hay que
 * guardarla.
 */
final class VisualEfectoInst{
    VisualEfectoDef def;
    float x, y, rot;
    /** Color del efecto (0 a 1). */
    float r, g, b, a;
    /** Ticks que lleva vivo. */
    float tiempo;
    /** Lo que el emisor quiera pasar al dibujo. */
    Object datos;
    /** Orden de emisión (desempata el orden de dibujo y la antigüedad). */
    long seq;
    /** ¿Se dibuja en este fotograma? (si no cabe en el presupuesto, no) */
    boolean dibujar;

    /** Progreso del efecto: 0 al emitirse, 1 al terminar su duración. */
    float fin(){
        final float d = def == null ? 1f : def.duracion;
        return d <= 0f ? 1f : Math.max(0f, Math.min(1f, tiempo / d));
    }

    /** 1 - fin(): lo que queda. */
    float fout(){
        return 1f - fin();
    }

    void limpiar(){
        def = null;
        datos = null;
        dibujar = false;
    }
}
