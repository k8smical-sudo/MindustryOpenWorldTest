package mundoinfinito;

/**
 * NAVEGACIÓN DE UNA VISTA CON ZOOM Y PANEO (plan v6, D14). Compartida por el árbol tecnológico y por la vista 2D de planetas.
 *
 * Es solo matemática, sin Arc: así se prueba en CI sin el juego ({@code PruebasNavegacion}).
 *
 * Modelo: pantalla = pan + contenido * escala. El pan está en píxeles de la ventana. Un zoom en un punto de pantalla mantiene fijo ese punto
 * del contenido. Los límites mantienen un margen del contenido visible; si el contenido cabe entero, se centra.
 */
final class NavegacionVista{
    final float min, max;
    float escala = 1f, panX, panY;

    /** Requiere 0 &lt; min &lt;= max. */
    NavegacionVista(float min, float max){
        this.min = min;
        this.max = max;
    }

    float aContenidoX(float px){ return (px - panX) / escala; }
    float aContenidoY(float py){ return (py - panY) / escala; }
    float aPantallaX(float cx){ return panX + cx * escala; }
    float aPantallaY(float cy){ return panY + cy * escala; }

    /** Cambia la escala (acotada a [min, max]) manteniendo fijo el punto del contenido que está bajo la pantalla (px, py). */
    void zoomEn(float px, float py, float nuevaEscala){
        final float cx = aContenidoX(px), cy = aContenidoY(py);
        escala = clamp(nuevaEscala, min, max);
        panX = px - cx * escala;
        panY = py - cy * escala;
    }

    void paneo(float dx, float dy){
        panX += dx;
        panY += dy;
    }

    /**
     * Mantiene el contenido (cw x ch, en sus unidades) con al menos {@code margen} píxeles visibles en la ventana (vw x vh). Si el contenido
     * cabe entero con su margen, lo centra.
     */
    void ajustar(float vw, float vh, float cw, float ch, float margen){
        panX = limitar(panX, vw, cw * escala, margen);
        panY = limitar(panY, vh, ch * escala, margen);
    }

    /** Escala para encajar el contenido en la ventana (sin pasar de 1, dentro de [min, max]) y lo centra. */
    void encajar(float vw, float vh, float cw, float ch, float margen){
        escala = clamp(Math.min(1f, Math.min(vw / cw, vh / ch)), min, max);
        panX = (vw - cw * escala) / 2f;
        panY = (vh - ch * escala) / 2f;
        ajustar(vw, vh, cw, ch, margen);
    }

    private static float limitar(float pan, float ventana, float tamano, float margen){
        if(tamano + 2f * margen <= ventana) return (ventana - tamano) / 2f;   // cabe: centrado
        return clamp(pan, margen - tamano, ventana - margen);
    }

    static float clamp(float v, float lo, float hi){
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
