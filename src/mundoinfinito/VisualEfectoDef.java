package mundoinfinito;

import arc.func.Cons;

/**
 * Definición de un EFECTO: algo que un mod puede disparar en una posición, con su capa de dibujo y su duración. Se dibuja cada fotograma mientras vive.
 * Declara su coste de dibujo (por instancia viva) y si es esencial: los opcionales se adelgazan con la calidad y se omiten si no caben en el presupuesto;
 * los esenciales no. Un efecto cuyo dibujo lanza una excepción se desactiva para la sesión, con aviso.
 */
final class VisualEfectoDef{
    final String id, fuente;
    final VisualCapa capa;
    /** Menor = más importante (se desaloja el último, se dibuja antes en el presupuesto). */
    final int prioridad;
    /** Duración en ticks (60 por segundo). */
    final float duracion;
    /** Unidades de coste de dibujo por instancia viva. */
    final int coste;
    final boolean esencial;
    /** Dibuja una instancia (ya está en su capa). Puede leer posición, color y progreso; no debe guardar la instancia. */
    final Cons<VisualEfectoInst> dibujar;

    private VisualEfectoDef(String id, String fuente, VisualCapa capa, int prioridad, float duracion, int coste, boolean esencial, Cons<VisualEfectoInst> dibujar){
        this.id = id;
        this.fuente = fuente;
        this.capa = capa;
        this.prioridad = prioridad;
        this.duracion = duracion;
        this.coste = coste;
        this.esencial = esencial;
        this.dibujar = dibujar;
    }

    /** @param id con el formato "fuente:nombre" */
    static VisualEfectoDef de(String fuente, String id, VisualCapa capa, int prioridad, float duracion, int coste, boolean esencial, Cons<VisualEfectoInst> dibujar){
        return new VisualEfectoDef(id, fuente, capa, prioridad, duracion, coste, esencial, dibujar);
    }
}
