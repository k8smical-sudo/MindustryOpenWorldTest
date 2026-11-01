package mundoinfinito;

import arc.func.Prov;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DESDUPLICACIÓN ADAPTATIVA DE CÁLCULOS (plan v2, M7, P8). Un cálculo se identifica por su CONTRIBUYENTE ("fuente:nombre"), su VERSIÓN y sus
 * ENTRADAS (números largos). Con las mismas entradas y la misma versión, el resultado se reutiliza.
 *
 * Contrato: el cálculo debe ser PURO (las mismas entradas dan el mismo resultado). Entonces desduplicar o no desduplicar NUNCA cambia el resultado:
 * solo cambia el coste. Por eso la política puede decidir sola.
 *
 * POLÍTICA (por contribuyente):
 *  - AUTO (por defecto): mide. Por cada ventana de {@link #VENTANA} llamadas calcula la tasa de aciertos y el coste medio de calcular. Desduplica
 *    solo si la tasa de aciertos por el coste medio supera el sobrecoste de la caché con un margen. Si no compensa, pasa DIRECTO, con muestreo
 *    mínimo (un cronometraje cada {@link #MUESTREO_DIRECTO} llamadas), y vuelve a probar cada {@link #PERIODO_DIRECTO} llamadas. Un cálculo barato
 *    casi no paga nada; uno caro con repeticiones se desduplica; uno caro sin repeticiones no guarda nada.
 *  - SIEMPRE: desduplica siempre. Para pruebas y para cálculos cuya medición no se quiere.
 *  - NUNCA: nunca desduplica. Ningún coste de medición.
 *
 * CADA CONTRIBUYENTE TIENE SU CACHÉ, con tope propio (LRU). Un cálculo en vuelo lo comparten los hilos que lo piden (no se repite en paralelo).
 * Un cálculo que falla no se guarda. Cambiar la VERSIÓN de un contribuyente vacía su caché (el código cambió, los resultados viejos ya no valen).
 * Olvidar un contribuyente (quitado del mod) libera su caché.
 *
 * Lo que este archivo NO hace: no decide qué cálculos del mod se desduplican. Eso lo declara cada cálculo en {@link Calculos}.
 */
final class Desduplicacion{
    static final int POLITICA_AUTO = 0, POLITICA_SIEMPRE = 1, POLITICA_NUNCA = 2;
    static final int TOPE_POR_DEFECTO = 256;
    /** Llamadas por ventana de medición (en modo desduplicado). */
    static final int VENTANA = 128;
    /** En modo directo, cada cuántas llamadas se vuelve a probar la desduplicación. */
    static final int PERIODO_DIRECTO = 4096;
    /** En modo directo, un cronometraje cada cuántas llamadas (las demás no miden nada). */
    static final int MUESTREO_DIRECTO = 64;
    /** Margen: desduplicar solo si compensa al menos este factor (evita oscilar). */
    static final double MARGEN = 1.5;
    /** Sobrecoste inicial estimado de la caché, en ns. Se corrige con las mediciones. */
    static final double SOBRECOSTE_INICIAL_NS = 100.0;

    /** Sobrecoste medio de consultar la caché, en ns (media móvil; compartida por todos los contribuyentes). */
    private static volatile double sobrecosteNs = SOBRECOSTE_INICIAL_NS;

    /** Clave exacta: contribuyente, versión y entradas completas (sin hash: sin colisiones). */
    private static final class Clave{
        final String contribuyente;
        final int version;
        final long[] entradas;
        private final int hash;

        Clave(String contribuyente, int version, long[] entradas){
            this.contribuyente = contribuyente;
            this.version = version;
            this.entradas = entradas.clone();
            this.hash = (31 * contribuyente.hashCode() + version) * 31 + Arrays.hashCode(this.entradas);
        }

        @Override public int hashCode(){
            return hash;
        }

        @Override public boolean equals(Object o){
            if(!(o instanceof Clave)) return false;
            final Clave k = (Clave)o;
            return hash == k.hash && version == k.version && contribuyente.equals(k.contribuyente) && Arrays.equals(entradas, k.entradas);
        }
    }

    /** Estado de un contribuyente. Los campos de política se tocan solo bajo el cerrojo de la cuenta. */
    static final class Cuenta{
        final String id;
        int version;
        int tope;
        int politica;
        /** true = desduplica ahora (en AUTO, la decisión vigente). */
        volatile boolean desduplica = true;
        /** Llamadas directas desde la última prueba (sin cerrojo: es un contador). */
        final AtomicInteger directas = new AtomicInteger();
        /** Coste medio de calcular, en ns (media móvil). 0 = aún sin medir. */
        double costeNs;
        /** Llamadas y aciertos de la ventana en curso (modo desduplicado). */
        int llamadas, aciertos;
        final LinkedHashMap<Clave, FutureTask<Object>> cache;

        Cuenta(String id, int version, int tope, int politica){
            this.id = id;
            this.version = version;
            this.tope = tope;
            this.politica = politica;
            this.cache = new LinkedHashMap<Clave, FutureTask<Object>>(64, 0.75f, true){
                @Override protected boolean removeEldestEntry(Map.Entry<Clave, FutureTask<Object>> eldest){
                    return size() > Cuenta.this.tope && eldest.getValue().isDone();
                }
            };
            this.desduplica = politica != POLITICA_NUNCA;
        }

        /** Registra un coste medido de calcular (media móvil). */
        synchronized void registrarCoste(long ns){
            costeNs = costeNs == 0.0 ? ns : 0.9 * costeNs + 0.1 * ns;
        }

        /** Cierra la ventana si toca: decide si desduplicar, por la tasa de aciertos y el coste medio. Bajo el cerrojo. */
        synchronized void cerrarVentanaSiToca(){
            if(politica != POLITICA_AUTO || llamadas < VENTANA) return;
            final double tasa = (double)aciertos / (double)llamadas;
            final boolean rentable = costeNs > 0.0 && tasa * costeNs > sobrecosteNs * MARGEN;
            if(rentable != desduplica){
                desduplica = rentable;
                if(!rentable){
                    cache.clear();   // ya no se desduplica: no se retiene memoria
                    directas.set(0);
                }
            }
            llamadas = 0;
            aciertos = 0;
        }

        /** Cambia la versión: el código cambió, así que la caché vieja ya no vale. */
        synchronized void cambiarVersion(int nueva){
            if(nueva == version) return;
            version = nueva;
            cache.clear();
        }

        synchronized void cambiarTope(int nuevo){
            tope = Math.max(1, nuevo);
            while(cache.size() > tope){
                final java.util.Iterator<FutureTask<Object>> it = cache.values().iterator();
                if(!it.hasNext()) break;
                final FutureTask<Object> primero = it.next();
                if(!primero.isDone()) break;
                it.remove();
            }
        }

        synchronized void vaciar(){
            cache.clear();
        }
    }

    /** Todos los contribuyentes registrados, por id. */
    private static final Map<String, Cuenta> CUENTAS = new HashMap<String, Cuenta>();
    private static final Object CERROJO_REGISTRO = new Object();

    static final AtomicLong EJECUTADOS = new AtomicLong(), REUTILIZADOS = new AtomicLong(), DIRECTOS = new AtomicLong();

    private Desduplicacion(){}

    /** Registra o actualiza un contribuyente. Si la versión cambia, su caché se vacía. Devuelve false si el id no es válido. */
    static boolean registrar(String id, int version, int tope, int politica){
        if(!CalendarioMundo.idValido(id) || politica < POLITICA_AUTO || politica > POLITICA_NUNCA) return false;
        synchronized(CERROJO_REGISTRO){
            final Cuenta c = CUENTAS.get(id);
            if(c == null){
                CUENTAS.put(id, new Cuenta(id, version, Math.max(1, tope), politica));
            }else{
                c.cambiarVersion(version);
                c.cambiarTope(Math.max(1, tope));
                synchronized(c){
                    c.politica = politica;
                    c.desduplica = politica != POLITICA_NUNCA;
                    c.llamadas = 0;
                    c.aciertos = 0;
                }
            }
        }
        return true;
    }

    /** Quita un contribuyente y libera su caché. Un cálculo que vuelva a pedirse se registra de nuevo con los valores por defecto. */
    static void olvidar(String id){
        synchronized(CERROJO_REGISTRO){
            final Cuenta c = CUENTAS.remove(id);
            if(c != null) c.vaciar();
        }
    }

    /**
     * Cuenta de un contribuyente. Si no existe, se crea con los valores de {@link Calculos} si el cálculo está en el catálogo, o con los valores
     * por defecto (AUTO) si no. Lanza si el id no es válido.
     */
    private static Cuenta cuenta(String id){
        if(!CalendarioMundo.idValido(id)) throw new IllegalArgumentException("contribuyente no válido: " + id);
        synchronized(CERROJO_REGISTRO){
            Cuenta c = CUENTAS.get(id);
            if(c == null){
                final Calculos.Entrada e = Calculos.buscar(id);
                c = e == null ? new Cuenta(id, 1, TOPE_POR_DEFECTO, POLITICA_AUTO) : new Cuenta(id, e.version, e.tope, e.politica);
                CUENTAS.put(id, c);
            }
            return c;
        }
    }

    /** Versión actual de un contribuyente, o 0 si no está registrado. */
    static int versionDe(String id){
        synchronized(CERROJO_REGISTRO){
            final Cuenta c = CUENTAS.get(id);
            return c == null ? 0 : c.version;
        }
    }

    /** Cambia solo la política de un contribuyente (la versión y la caché se conservan). Devuelve false si el id no es válido. */
    static boolean fijarPolitica(String id, int politica){
        if(!CalendarioMundo.idValido(id) || politica < POLITICA_AUTO || politica > POLITICA_NUNCA) return false;
        final Cuenta c = cuenta(id);
        synchronized(c){
            c.politica = politica;
            c.desduplica = politica != POLITICA_NUNCA;
            c.llamadas = 0;
            c.aciertos = 0;
            c.directas.set(0);
            if(politica == POLITICA_NUNCA) c.cache.clear();
        }
        return true;
    }

    /** Modo actual: "DEDUP", "DIRECTO" o null si no está registrado. Para diagnóstico y pruebas. */
    static String modo(String id){
        final Cuenta c;
        synchronized(CERROJO_REGISTRO){
            c = CUENTAS.get(id);
        }
        if(c == null) return null;
        return c.desduplica ? "DEDUP" : "DIRECTO";
    }

    /** Entradas guardadas de un contribuyente (terminadas o en vuelo). 0 si no está registrado. */
    static int tamanio(String id){
        final Cuenta c;
        synchronized(CERROJO_REGISTRO){
            c = CUENTAS.get(id);
        }
        if(c == null) return 0;
        synchronized(c){
            return c.cache.size();
        }
    }

    /**
     * Ejecuta el cálculo, o reutiliza el resultado si la política lo permite y ya está guardado para las mismas entradas y versión.
     * @throws IllegalArgumentException si el id no es válido
     */
    static <T> T calcular(String id, Prov<T> calculo, long[] entradas){
        final Cuenta c = cuenta(id);
        if(c.politica == POLITICA_NUNCA || !c.desduplica){
            if(c.politica == POLITICA_NUNCA) return directo(c, calculo, 0);
            // AUTO en modo directo: cuenta la llamada; cada PERIODO_DIRECTO vuelve a probar la desduplicación
            final int n = c.directas.incrementAndGet();
            if(n < PERIODO_DIRECTO || !cambiarADesduplicar(c)) return directo(c, calculo, n);
        }
        return desduplicado(c, calculo, entradas);
    }

    /** Pasa de directo a desduplicado para probar, si sigue en directo. Bajo el cerrojo de la cuenta. */
    private static boolean cambiarADesduplicar(Cuenta c){
        synchronized(c){
            if(c.desduplica) return false;
            c.desduplica = true;
            c.llamadas = 0;
            c.aciertos = 0;
            c.directas.set(0);
            return true;
        }
    }

    /** Camino directo: sin caché. Con muestreo, mide el coste cada MUESTREO_DIRECTO llamadas (n = número de llamada directa, o 0 si NUNCA). */
    private static <T> T directo(Cuenta c, Prov<T> calculo, int n){
        DIRECTOS.incrementAndGet();
        if(c.politica == POLITICA_NUNCA || n % MUESTREO_DIRECTO != 0) return calculo.get();
        final long t0 = System.nanoTime();
        final T r = calculo.get();
        c.registrarCoste(System.nanoTime() - t0);
        return r;
    }

    /** Camino desduplicado: caché del contribuyente, cálculo en vuelo compartido, medición de aciertos y de costes. */
    private static <T> T desduplicado(Cuenta c, Prov<T> calculo, long[] entradas){
        final long t0 = System.nanoTime();
        final Clave clave;
        final FutureTask<Object> futuro;
        final boolean propio;
        synchronized(c){
            clave = new Clave(c.id, c.version, entradas);
            final FutureTask<Object> existente = c.cache.get(clave);
            if(existente != null){
                futuro = existente;
                propio = false;
                c.aciertos++;
            }else{
                futuro = new FutureTask<Object>(new Callable<Object>(){
                    @Override public Object call(){
                        return calculo.get();
                    }
                });
                c.cache.put(clave, futuro);
                propio = true;
            }
            c.llamadas++;
        }
        final long t1 = System.nanoTime();
        sobrecosteNs = 0.95 * sobrecosteNs + 0.05 * (t1 - t0);
        if(propio){
            EJECUTADOS.incrementAndGet();
            futuro.run();
            c.registrarCoste(System.nanoTime() - t1);
        }else{
            REUTILIZADOS.incrementAndGet();
        }
        final T r = resultado(futuro, clave, propio, c);
        c.cerrarVentanaSiToca();
        return r;
    }

    @SuppressWarnings("unchecked")
    private static <T> T resultado(FutureTask<Object> futuro, Clave clave, boolean propio, Cuenta c){
        try{
            return (T)futuro.get();
        }catch(ExecutionException e){
            if(propio){
                synchronized(c){
                    if(c.cache.get(clave) == futuro) c.cache.remove(clave);
                }
            }
            final Throwable causa = e.getCause();
            if(causa instanceof RuntimeException) throw (RuntimeException)causa;
            if(causa instanceof Error) throw (Error)causa;
            throw new IllegalStateException("el cálculo de " + clave.contribuyente + " falló", causa);
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera del cálculo de " + clave.contribuyente + " interrumpida", e);
        }
    }

    /** {ejecutados, reutilizados, directos} desde el arranque. Para diagnóstico. */
    static long[] estadisticas(){
        return new long[]{EJECUTADOS.get(), REUTILIZADOS.get(), DIRECTOS.get()};
    }

    /** Sobrecoste medido de la caché, en ns (para diagnóstico y para el banco). */
    static double sobrecosteMedidoNs(){
        return sobrecosteNs;
    }
}
