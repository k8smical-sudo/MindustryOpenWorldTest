package mundoinfinito;

import java.util.Comparator;

/**
 * ORDEN CANÓNICO (plan, sección 5): (prioridad, id de fuente, id de contribución) para las contribuciones y (cy, cx) para las coordenadas.
 * Ningún resultado depende del orden de llegada, de carga ni del hilo que termine antes. String.compareTo no depende del idioma del dispositivo.
 */
final class Orden{
    static final Comparator<Contribucion> CANONICO = (a, b) -> {
        int c = Integer.compare(a.prioridad(), b.prioridad());
        if(c != 0) return c;
        c = a.fuente().compareTo(b.fuente());
        if(c != 0) return c;
        return a.id().compareTo(b.id());
    };

    private Orden(){}

    /** Orden de dos chunks: primero por fila, luego por columna. */
    static int coordenadas(int ax, int ay, int bx, int by){
        final int c = Integer.compare(ay, by);
        return c != 0 ? c : Integer.compare(ax, bx);
    }
}
