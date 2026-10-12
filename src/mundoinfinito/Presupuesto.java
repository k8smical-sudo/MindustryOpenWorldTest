package mundoinfinito;

import arc.Core;

/**
 * PRESUPUESTO DE TIEMPO COMPARTIDO.
 *
 * Antes cada sistema (estructuras 1,5 ms, bases 1,5 ms, ausencia 2,5 ms, ventana hasta 2,5 ms, terreno 0,9 ms...) tenía su
 * propio tope, y cuando coincidían (p. ej. al VOLVER a una base grande: despertar + poner al día + terreno) se SUMABAN, hasta
 * ~9 ms por fotograma en el hilo del juego: justo el congelamiento al ir/volver a ciertos chunks.
 *
 * Ahora hay UN reparto por fotograma (como el límite de tiempo por paso del servidor de Luanti): cada sistema pide su porción
 * y recibe como máximo lo que quede, con un mínimo para que NADIE se muera de hambre (el trabajo siempre avanza un poco).
 * El reparto se encoge solo si el juego va justo de FPS y crece un poco si va sobrado.
 */
final class Presupuesto{
    /** Lo mínimo que siempre se concede a un sistema aunque el reparto ya se haya gastado. */
    static final long MINIMO_NS = 250_000L;

    private static long fin;

    private Presupuesto(){}

    /** Total del fotograma según lo bien que vaya el juego. */
    static long total(){
        float dt = Core.graphics == null ? 0.016f : Core.graphics.getDeltaTime();
        if(dt > 0.050f) return 2_000_000L;   // < 20 FPS: lo imprescindible
        if(dt > 0.033f) return 3_000_000L;   // < 30 FPS
        if(dt > 0.022f) return 4_000_000L;   // < 45 FPS
        return 5_000_000L;                   // va sobrado
    }

    /** Llamar una vez al principio del tick, antes de repartir. */
    static void abrir(){
        fin = System.nanoTime() + total();
    }

    /** Instante límite para un sistema que quiere como máximo 'maxNs': lo que quede del reparto, nunca menos que el mínimo. */
    static long hasta(long maxNs){
        long ahora = System.nanoTime();
        long resta = fin - ahora;
        long dame = Math.min(maxNs, Math.max(resta, MINIMO_NS));
        return ahora + dame;
    }
}
