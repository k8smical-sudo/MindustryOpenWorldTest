package mundoinfinito;

import arc.func.Prov;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DESDUPLICACIÓN DE CÁLCULOS (plan v2, M7). Un cálculo se identifica por su CONTRIBUYENTE (un id "fuente:nombre") y por sus ENTRADAS (una lista de
 * enteros largos). Con las mismas entradas, el cálculo se ejecuta una vez y su resultado se reutiliza.
 *
 *  - CLAVE EXACTA: la clave guarda las entradas completas, no un hash. Dos cálculos distintos nunca comparten resultado por una colisión.
 *  - SIN REPETIR EN PARALELO: si otro hilo pide el mismo cálculo mientras se ejecuta, espera el resultado en vez de repetirlo.
 *  - FALLOS: un cálculo que lanza una excepción no se guarda; la siguiente petición lo vuelve a intentar. Quien esperaba recibe la misma excepción.
 *  - SIN DESDUPLICAR: un contribuyente puede declarar que su cálculo NO se desduplica (para contenidos que deben ejecutarse cada vez). Se respeta.
 *  - ACOTADA: como mucho MAX_ENTRADAS resultados guardados; se expulsan los más antiguos ya terminados. Un cálculo en vuelo nunca se expulsa.
 *  - Obligatoria por defecto: no hay forma de apagarla globalmente.
 *
 * Lo que este archivo NO hace: no decide qué cálculos del mod se desduplican. Eso se conecta en cada sitio de llamada cuando se mida que se repiten.
 */
final class Desduplicacion{
    /** Máximo de resultados guardados a la vez. */
    static final int MAX_ENTRADAS = 4096;

    /** Clave exacta: contribuyente y entradas completas. */
    private static final class Clave{
        final String contribuyente;
        final long[] entradas;
        private final int hash;

        Clave(String contribuyente, long[] entradas){
            this.contribuyente = contribuyente;
            this.entradas = entradas.clone();
            this.hash = 31 * contribuyente.hashCode() + Arrays.hashCode(this.entradas);
        }

        @Override public int hashCode(){
            return hash;
        }

        @Override public boolean equals(Object o){
            if(!(o instanceof Clave)) return false;
            final Clave k = (Clave)o;
            return hash == k.hash && contribuyente.equals(k.contribuyente) && Arrays.equals(entradas, k.entradas);
        }
    }

    /** Todo lo que cambia se guarda bajo este cerrojo. Los cálculos se ejecutan FUERA del cerrojo. */
    private static final Object CERROJO = new Object();
    private static final Set<String> SIN_DESDUPLICAR = new HashSet<String>();

    /** LRU por acceso. Solo se expulsan entradas terminadas. */
    private static final LinkedHashMap<Clave, FutureTask<Object>> CACHE = new LinkedHashMap<Clave, FutureTask<Object>>(256, 0.75f, true){
        @Override protected boolean removeEldestEntry(Map.Entry<Clave, FutureTask<Object>> eldest){
            return size() > MAX_ENTRADAS && eldest.getValue().isDone();
        }
    };

    static final AtomicLong EJECUTADOS = new AtomicLong(), REUTILIZADOS = new AtomicLong(), DIRECTOS = new AtomicLong();

    private Desduplicacion(){}

    /** Declara que el contribuyente NO se desduplica. Devuelve false si el id no es válido. Es irreversible en la sesión. */
    static boolean sinDesduplicar(String contribuyente){
        if(!CalendarioMundo.idValido(contribuyente)) return false;
        synchronized(CERROJO){
            SIN_DESDUPLICAR.add(contribuyente);
        }
        return true;
    }

    /** ¿Está declarado sin desduplicar? */
    static boolean esSinDesduplicar(String contribuyente){
        synchronized(CERROJO){
            return SIN_DESDUPLICAR.contains(contribuyente);
        }
    }

    /**
     * Ejecuta el cálculo, o reutiliza el resultado si ya se calculó (o se está calculando) con las mismas entradas.
     * @throws IllegalArgumentException si el id del contribuyente no es válido
     */
    static <T> T calcular(String contribuyente, Prov<T> calculo, long[] entradas){
        if(!CalendarioMundo.idValido(contribuyente)) throw new IllegalArgumentException("contribuyente no válido: " + contribuyente);
        if(esSinDesduplicar(contribuyente)){
            DIRECTOS.incrementAndGet();
            return calculo.get();
        }
        final Clave clave = new Clave(contribuyente, entradas);
        final FutureTask<Object> futuro;
        final boolean propio;
        synchronized(CERROJO){
            final FutureTask<Object> existente = CACHE.get(clave);
            if(existente != null){
                futuro = existente;
                propio = false;
            }else{
                futuro = new FutureTask<Object>(new Callable<Object>(){
                    @Override public Object call(){
                        return calculo.get();
                    }
                });
                CACHE.put(clave, futuro);
                propio = true;
            }
        }
        if(propio){
            EJECUTADOS.incrementAndGet();
            futuro.run();          // ejecuta aquí; los demás hilos esperan en futuro.get()
        }else{
            REUTILIZADOS.incrementAndGet();
        }
        return resultado(futuro, clave, propio);
    }

    @SuppressWarnings("unchecked")
    private static <T> T resultado(FutureTask<Object> futuro, Clave clave, boolean propio){
        try{
            return (T)futuro.get();
        }catch(ExecutionException e){
            if(propio) olvidar(clave, futuro);
            final Throwable causa = e.getCause();
            if(causa instanceof RuntimeException) throw (RuntimeException)causa;
            if(causa instanceof Error) throw (Error)causa;
            throw new IllegalStateException("el cálculo de " + clave.contribuyente + " falló", causa);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera del cálculo de " + clave.contribuyente + " interrumpida", e);
        }
    }

    /** Quita un cálculo fallido para que la siguiente petición lo repita. */
    private static void olvidar(Clave clave, FutureTask<Object> futuro){
        synchronized(CERROJO){
            if(CACHE.get(clave) == futuro) CACHE.remove(clave);
        }
    }

    /** Resultados guardados ahora mismo (terminados o en vuelo). Para comprobaciones y diagnóstico. */
    static int tamanio(){
        synchronized(CERROJO){
            return CACHE.size();
        }
    }

    /** {ejecutados, reutilizados, directos} desde el arranque: ejecutados = cálculos reales; reutilizados = peticiones servidas desde la caché. */
    static long[] estadisticas(){
        return new long[]{EJECUTADOS.get(), REUTILIZADOS.get(), DIRECTOS.get()};
    }
}
