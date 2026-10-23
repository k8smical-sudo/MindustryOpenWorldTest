package mundoinfinito;

import arc.func.Cons;
import arc.func.Prov;
import arc.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PREPARACIÓN DE MALLAS Y TEXTURAS fuera del hilo de render, con la SUBIDA en el hilo de render (fase 10).
 *
 * Un mod entrega dos funciones: una que PREPARA los datos (vértices, píxeles...) y corre en un hilo del pool, y otra que los SUBE a la GPU y corre solo en
 * el hilo de render. La primera no puede tocar la GPU ni el mundo vivo; la segunda es la única que puede. El registro garantiza el reparto:
 *  - {@link #preparar} encola el trabajo en el carril visual del pool de hilos y solo guarda el resultado.
 *  - {@link #subirListos} (hilo de render, una vez por fotograma) sube, en el orden en que se pidieron, los que ya están listos, hasta un máximo por
 *    fotograma. Si se llama desde otro hilo no hace nada y avisa: ninguna llamada de GPU se ejecuta fuera del hilo de render.
 *  - Un trabajo o una subida que lanzan una excepción se descartan con aviso y los demás siguen.
 * La malla de terreno del propio mod ({@code ChunkGeometryCooker}) usa el mismo reparto con su propia cola.
 */
final class VisualMallas{
    private static final VisualMallas GLOBAL = new VisualMallas(256);

    static VisualMallas global(){
        return GLOBAL;
    }

    private abstract static class Item{
        final String etiqueta;

        Item(String etiqueta){
            this.etiqueta = etiqueta;
        }

        abstract boolean listo();

        /** Sube el resultado. Solo desde el hilo de render. */
        abstract void subir();
    }

    private static final class ItemT<T> extends Item{
        final Hilos.Futuro<T> futuro;
        final Cons<T> subida;

        ItemT(String etiqueta, Hilos.Futuro<T> futuro, Cons<T> subida){
            super(etiqueta);
            this.futuro = futuro;
            this.subida = subida;
        }

        @Override
        boolean listo(){
            return futuro.listo();
        }

        @Override
        void subir(){
            final T v = futuro.valor();
            if(v == null){
                throw new IllegalStateException("el trabajo de preparación no produjo datos" + (futuro.error() == null ? "" : ": " + futuro.error()));
            }
            subida.get(v);
        }
    }

    private final int capacidad;
    private final ConcurrentLinkedQueue<Item> pendientes = new ConcurrentLinkedQueue<Item>();
    private final AtomicInteger cuenta = new AtomicInteger();
    private final AtomicLong subidas = new AtomicLong(), fallos = new AtomicLong();
    private final ConcurrentHashMap<String, Boolean> avisos = new ConcurrentHashMap<String, Boolean>();

    /** @param capacidad cuántos trabajos puede tener pendientes a la vez (los demás se rechazan hasta que se suban) */
    VisualMallas(int capacidad){
        this.capacidad = capacidad;
    }

    /**
     * Pide preparar unos datos fuera del hilo de render y subirlos después en él.
     * @param trabajo prepara los datos: corre en un hilo de fondo; puro (ni GPU ni mundo vivo). Debe devolver algo distinto de null.
     * @param subida recibe los datos y los sube a la GPU: corre en el hilo de render
     * @return false si hay demasiados pendientes (se puede volver a pedir más tarde)
     */
    <T> boolean preparar(String etiqueta, Prov<T> trabajo, Cons<T> subida){
        if(trabajo == null || subida == null) return false;
        if(cuenta.incrementAndGet() > capacidad){
            cuenta.decrementAndGet();
            return false;
        }
        final Hilos.Futuro<T> f = Hilos.enviarSiempre(Hilos.Carril.VISUAL, etiqueta, trabajo);
        pendientes.add(new ItemT<T>(etiqueta, f, subida));
        return true;
    }

    /**
     * HILO DE RENDER, una vez por fotograma: sube, en orden de petición, los trabajos ya terminados, hasta {@code max}. El primero que aún no está listo
     * detiene la cola (el orden se conserva). Desde cualquier otro hilo no sube nada.
     * @return cuántos se subieron (o descartaron por fallo) en esta llamada
     */
    int subirListos(int max){
        if(!Hilos.exigirPrincipal("VisualMallas.subirListos")) return 0;
        int n = 0;
        while(n < max){
            final Item it = pendientes.peek();
            if(it == null || !it.listo()) break;
            pendientes.poll();
            cuenta.decrementAndGet();
            n++;
            try{
                it.subir();
                subidas.incrementAndGet();
            }catch(Throwable t){
                fallos.incrementAndGet();
                if(avisos.putIfAbsent(it.etiqueta, Boolean.TRUE) == null){
                    Log.warn("[MundoInfinito] Visual: la malla '" + it.etiqueta + "' no se pudo preparar o subir (" + t + "); se descarta y el juego continúa.");
                }
            }
        }
        return n;
    }

    /** Trabajos pedidos que aún no se han subido. */
    int pendientes(){
        return cuenta.get();
    }

    /** ¿Están terminados todos los trabajos pendientes? (para pruebas y diagnóstico) */
    boolean todoListo(){
        for(Item it : pendientes) if(!it.listo()) return false;
        return true;
    }

    long subidas(){
        return subidas.get();
    }

    long fallos(){
        return fallos.get();
    }
}
