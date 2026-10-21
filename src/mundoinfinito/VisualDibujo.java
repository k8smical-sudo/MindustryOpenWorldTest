package mundoinfinito;

import arc.func.Boolp;

/**
 * Una contribución de DIBUJO: algo que se pinta en una capa. Declara su capa, su prioridad, su coste en unidades visuales y, si quiere, un shader.
 * Orden de dibujo: capa, prioridad, id de fuente, id (nunca el orden de carga). Un dibujo que lanza una excepción se desactiva para la sesión, con aviso.
 */
final class VisualDibujo{
    final String id, fuente;
    final VisualCapa capa;
    final int prioridad;
    /** Llamadas de dibujo que declara gastar por fotograma. */
    final int coste;
    /** Un dibujo esencial no se omite nunca por exceso de presupuesto; uno opcional sí, empezando por el último en orden canónico. */
    final boolean esencial;
    /** Id del shader con el que se dibuja, o null. Si el shader está desactivado se dibuja sin él. */
    final String shader;
    /** Condición para dibujar este fotograma (null = siempre). Un dibujo cuya condición es falsa no se cuenta en el coste. */
    final Boolp activo;
    final Runnable dibujar;

    private VisualDibujo(String id, String fuente, VisualCapa capa, int prioridad, int coste, boolean esencial, String shader, Boolp activo, Runnable dibujar){
        this.id = id;
        this.fuente = fuente;
        this.capa = capa;
        this.prioridad = prioridad;
        this.coste = coste;
        this.esencial = esencial;
        this.shader = shader;
        this.activo = activo;
        this.dibujar = dibujar;
    }

    /** @param id con el formato "fuente:nombre" */
    static VisualDibujo de(String fuente, String id, VisualCapa capa, int prioridad, int coste, boolean esencial, String shader, Boolp activo, Runnable dibujar){
        return new VisualDibujo(id, fuente, capa, prioridad, coste, esencial, shader, activo, dibujar);
    }
}
