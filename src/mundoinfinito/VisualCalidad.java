package mundoinfinito;

/**
 * CALIDAD VISUAL (fase 9): bajo, medio y alto. Reduce pasadas, resolución y efectos; NUNCA afecta a la simulación ni a la generación del mundo.
 *
 * Los presupuestos se miden en unidades visuales declaradas por cada contribución: llamadas de dibujo (la unidad que ella misma cuenta), pasadas de
 * post-procesado y bytes de textura. Si se exceden, el registro visual omite lo opcional empezando por lo último en orden canónico.
 */
enum VisualCalidad{
    BAJA("Baja", 40, 0, 0L, 0.5f, 32, 64, 1),
    MEDIA("Media", 120, 2, 8L << 20, 0.75f, 128, 256, 2),
    ALTA("Alta", 400, 6, 64L << 20, 1f, 512, 1024, 4);

    final String nombre;
    /** Máximo de llamadas de dibujo declaradas por fotograma. */
    final int llamadasDibujo;
    /** Máximo de pasadas de post-procesado por fotograma. */
    final int pasadasPost;
    /** Máximo de bytes de textura de las pasadas de post-procesado. */
    final long bytesTextura;
    /** Fracción de las emisiones de efectos opcionales que se aceptan (0.5 = una de cada dos). Los efectos esenciales no se adelgazan. */
    final float densidadEfectos;
    /** Máximo de efectos vivos a la vez. */
    final int efectosMax;
    /** Presupuesto de coste de dibujo de efectos por fotograma (suma de los costes declarados de los efectos vivos que se dibujan). */
    final int costeEfectos;
    /** Máximo de mallas o texturas preparadas fuera del hilo de render que se suben por fotograma. */
    final int subidasMalla;

    VisualCalidad(String nombre, int llamadasDibujo, int pasadasPost, long bytesTextura, float densidadEfectos, int efectosMax, int costeEfectos, int subidasMalla){
        this.nombre = nombre;
        this.llamadasDibujo = llamadasDibujo;
        this.pasadasPost = pasadasPost;
        this.bytesTextura = bytesTextura;
        this.densidadEfectos = densidadEfectos;
        this.efectosMax = efectosMax;
        this.costeEfectos = costeEfectos;
        this.subidasMalla = subidasMalla;
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
