package mundoinfinito;

/**
 * Una PASADA de post-procesado: una operación sobre la pantalla ya dibujada. Declara su coste en pasadas y en bytes de textura; el registro las ejecuta en
 * orden canónico (prioridad, id de fuente, id) mientras quepan en el presupuesto de la calidad. Una pasada que lanza una excepción se desactiva para la
 * sesión, con aviso.
 */
final class VisualPasada{
    final String id, fuente;
    final int prioridad;
    /** Pasadas de pantalla que gasta (normalmente 1). */
    final int pasadas;
    /** Bytes de textura que mantiene. */
    final long bytesTextura;
    final Runnable ejecutar;

    private VisualPasada(String id, String fuente, int prioridad, int pasadas, long bytesTextura, Runnable ejecutar){
        this.id = id;
        this.fuente = fuente;
        this.prioridad = prioridad;
        this.pasadas = pasadas;
        this.bytesTextura = bytesTextura;
        this.ejecutar = ejecutar;
    }

    static VisualPasada de(String fuente, String id, int prioridad, int pasadas, long bytesTextura, Runnable ejecutar){
        return new VisualPasada(id, fuente, prioridad, pasadas, bytesTextura, ejecutar);
    }
}
