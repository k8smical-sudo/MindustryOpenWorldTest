package mundoinfinito;

/**
 * CALIDAD VISUAL (fase 9): bajo, medio y alto. Reduce pasadas, resolución y efectos; NUNCA afecta a la simulación ni a la generación del mundo.
 *
 * Los presupuestos se miden en unidades visuales declaradas por cada contribución: llamadas de dibujo (la unidad que ella misma cuenta), pasadas de
 * post-procesado y bytes de textura. Si se exceden, el registro visual omite lo opcional empezando por lo último en orden canónico.
 */
enum VisualCalidad{
    BAJA("Baja", 40, 0, 0L, 0.5f),
    MEDIA("Media", 120, 2, 8L << 20, 0.75f),
    ALTA("Alta", 400, 6, 64L << 20, 1f);

    final String nombre;
    /** Máximo de llamadas de dibujo declaradas por fotograma. */
    final int llamadasDibujo;
    /** Máximo de pasadas de post-procesado por fotograma. */
    final int pasadasPost;
    /** Máximo de bytes de textura de las pasadas de post-procesado. */
    final long bytesTextura;
    /** Multiplicador de la densidad de efectos y partículas (lo usan las contribuciones de efectos). */
    final float densidadEfectos;

    VisualCalidad(String nombre, int llamadasDibujo, int pasadasPost, long bytesTextura, float densidadEfectos){
        this.nombre = nombre;
        this.llamadasDibujo = llamadasDibujo;
        this.pasadasPost = pasadasPost;
        this.bytesTextura = bytesTextura;
        this.densidadEfectos = densidadEfectos;
    }

    /** 0 = baja, 1 = media, 2 = alta. Cualquier otro valor se recorta. */
    static VisualCalidad deIndice(int i){
        final VisualCalidad[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, i))];
    }

    VisualCalidad menos(){
        return deIndice(ordinal() - 1);
    }

    VisualCalidad mas(){
        return deIndice(ordinal() + 1);
    }
}
