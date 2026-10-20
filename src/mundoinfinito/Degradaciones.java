package mundoinfinito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REGISTRO DE DEGRADACIONES (plan, sección 5): quién fue recortado, en qué chunk, cuántas unidades pidió, cuántas se le permitieron y
 * qué regla se aplicó. Forma parte del resultado del mundo y se guarda por mundo (la persistencia llega en la fase 7).
 *
 * Fase 7: lo llena el núcleo cuando una contribución excede su presupuesto y se le aplica una {@link ReglaDegradacion}. Es seguro entre hilos y se
 * reinicia al abrir un mundo; como la degradación es determinista (unidades contadas, mismas entradas), reabrir el mundo y regenerar sus chunks lo
 * reconstruye igual. Guarda como máximo {@link #MAX} entradas; pasado el tope solo cuenta.
 */
final class Degradaciones{
    static final int MAX = 4096;

    static final class Entrada{
        final String contribuyente, regla;
        final int vcx, vcy, pedidas, permitidas;

        Entrada(String contribuyente, int vcx, int vcy, int pedidas, int permitidas, String regla){
            this.contribuyente = contribuyente;
            this.vcx = vcx;
            this.vcy = vcy;
            this.pedidas = pedidas;
            this.permitidas = permitidas;
            this.regla = regla;
        }
    }

    private static final ConcurrentLinkedQueue<Entrada> entradas = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger guardadas = new AtomicInteger();
    private static final AtomicLong total = new AtomicLong();

    private Degradaciones(){}

    static void registrar(String contribuyente, int vcx, int vcy, int pedidas, int permitidas, String regla){
        total.incrementAndGet();
        if(guardadas.incrementAndGet() <= MAX) entradas.add(new Entrada(contribuyente, vcx, vcy, pedidas, permitidas, regla));
        else guardadas.decrementAndGet();
    }

    static long total(){
        return total.get();
    }

    /** Copia de lo registrado, en el orden de llegada. */
    static List<Entrada> copia(){
        return new ArrayList<Entrada>(entradas);
    }

    /** Al abrir un mundo nuevo. */
    static void limpiar(){
        entradas.clear();
        guardadas.set(0);
        total.set(0L);
    }
}
