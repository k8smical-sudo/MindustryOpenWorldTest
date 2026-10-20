package mundoinfinito;

import arc.Core;
import arc.func.Prov;
import arc.struct.Seq;
import arc.util.Log;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SERVICIO DE HILOS DEL NÚCLEO (plan definitivo: D-N2, principio 7, fase 8).
 *
 * Es el único sitio donde el núcleo reparte CÁLCULO PURO entre hilos. Reglas:
 *
 *  1. Una tarea es una función pura: lee datos que nadie más escribe y devuelve un valor. NO toca el mundo vivo, los edificios, las
 *     unidades ni la GPU. Eso se hace en el hilo principal (en Mindustry el hilo del juego y el de dibujo son el mismo).
 *  2. El resultado NO depende de qué hilo termine antes: {@link #ordenado} devuelve siempre los valores en el orden en que se
 *     entregaron las tareas (el llamador las ordena en el orden canónico: prioridad, id de fuente, coordenadas).
 *  3. Ruta de un solo hilo: con un núcleo, o con el ajuste "ow-hilos" = 1 (o -Dmow.hilos=1), {@link #ordenado} ejecuta las tareas
 *     una tras otra en el hilo que llama y las asíncronas van a un único hilo de fondo. El resultado es idéntico al paralelo.
 *  4. Degradar, nunca rechazar: una tarea que falla devuelve null y se avisa UNA vez; nada se aborta.
 *  5. Quien espera ayuda: si nadie ha tomado una tarea, la ejecuta quien la espera. Así no hay interbloqueos aunque una tarea
 *     del pool lance otro lote, ni aunque el pool esté ocupado.
 *
 * Tres carriles por prioridad (el pool atiende siempre primero al de menor número): SIMULACION, VISUAL, PRECARGA.
 * Solo Java 8 y Arc (nada de java.util.function ni LongAdder: Android).
 */
final class Hilos{
    enum Carril{
        /** Cálculo de simulación que el juego pide ahora (terreno, fenómenos). */
        SIMULACION("sim", 0),
        /** Preparar mallas y texturas como datos (sin GPU). */
        VISUAL("visual", 0),
        /** Trabajo adelantado que puede esperar (precarga a la caché). Cede 1 ms tras cada tarea, como los hilos de terreno. */
        PRECARGA("precarga", 1);

        final String nombre;
        final int pausaMs;

        Carril(String nombre, int pausaMs){
            this.nombre = nombre;
            this.pausaMs = pausaMs;
        }
    }

    /** Ajuste del jugador (Core.settings): 0 = automático, 1 = un solo hilo, n = n hilos. */
    static final String K_HILOS = "ow-hilos";
    /** Lo mismo como propiedad de la JVM, para pruebas y CI: -Dmow.hilos=1 (tiene prioridad sobre el ajuste). */
    static final String PROP_HILOS = "mow.hilos";
    static final int MAX_HILOS = 8, MAX_AUTO = 4;

    private static final int NC = Carril.values().length;

    // ---- configuración
    private static volatile boolean configurado, unHilo;
    private static volatile int tam = 1;
    /** Lo que pidió el jugador (0 = automático). Se relee con {@link #configurar}; no se consulta el ajuste en cada llamada. */
    private static volatile int pedidos;
    private static int cuadros;

    // ---- pool
    private static final Object CERROJO = new Object();
    private static int arrancados;
    private static final AtomicLong seq = new AtomicLong();
    private static final Comparator<Futuro<?>> ORDEN = (a, b) -> {
        int c = Integer.compare(a.carril.ordinal(), b.carril.ordinal());
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    };
    private static final PriorityBlockingQueue<Futuro<?>> cola = new PriorityBlockingQueue<Futuro<?>>(64, ORDEN);

    // ---- estadísticas (por carril)
    private static final AtomicLong[] enviados = contadores(), ejecutados = contadores(), nsEjecutando = contadores(), nsEnCola = contadores(),
        fallos = contadores(), descartados = contadores();
    private static final AtomicInteger[] enCola = new AtomicInteger[NC], maxCola = new AtomicInteger[NC];
    private static final ConcurrentHashMap<String, Boolean> avisados = new ConcurrentHashMap<>();

    // ---- confinamiento
    private static volatile Thread principal;

    static{
        for(int i = 0; i < NC; i++){
            enCola[i] = new AtomicInteger();
            maxCola[i] = new AtomicInteger();
        }
    }

    private Hilos(){}

    private static AtomicLong[] contadores(){
        AtomicLong[] a = new AtomicLong[NC];
        for(int i = 0; i < NC; i++) a[i] = new AtomicLong();
        return a;
    }

    // ================================================================== configuración

    static int nucleos(){
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static int pedido(){
        int v = 0;
        try{ v = Integer.parseInt(System.getProperty(PROP_HILOS, "0").trim()); }catch(Throwable ignored){}
        if(v == 0){
            try{ if(Core.settings != null) v = Core.settings.getInt(K_HILOS, 0); }catch(Throwable ignored){}
        }
        return Math.max(0, v);
    }

    /** Relee el ajuste. Seguro de llamar cuando se quiera; los hilos del pool solo crecen (los que sobran quedan dormidos). */
    static void configurar(){
        final int v = pedido(), nuc = nucleos();
        final boolean uno = v == 1 || nuc <= 1;
        tam = uno ? 1 : (v > 1 ? Math.min(v, MAX_HILOS) : Math.max(1, Math.min(MAX_AUTO, nuc - 1)));
        unHilo = uno;
        pedidos = v;
        configurado = true;
    }

    /** ¿Ruta de un solo hilo activa? (por ajuste o porque solo hay un núcleo) */
    static boolean unHilo(){
        if(!configurado) configurar();
        return unHilo;
    }

    /**
     * Hilos de GENERACIÓN DE TERRENO (los de {@code Streamer}). {@code auto} es lo que corresponde sin ajuste. Con la ruta de un solo hilo es 1; con un
     * número pedido mayor que 1, ese número (hasta {@link #MAX_HILOS}); si no, {@code auto}. Así "ow-hilos" gobierna TODOS los hilos del mod y se puede
     * comparar uno contra varios en el mismo dispositivo.
     */
    static int hilosTerreno(int auto){
        if(!configurado) configurar();
        if(unHilo) return 1;
        final int p = pedidos;
        return p > 1 ? Math.min(p, MAX_HILOS) : Math.max(1, auto);
    }

    /** Hilos de COCINADO de mallas (datos para la GPU, sin tocar la GPU). Con la ruta de un solo hilo es 1; si no, {@code auto}. */
    static int hilosCocina(int auto){
        if(!configurado) configurar();
        return unHilo ? 1 : Math.max(1, auto);
    }

    /** Hilos de fondo que usa el pool con la configuración vigente. */
    static int hilos(){
        if(!configurado) configurar();
        return tam;
    }

    /** Arranca (perezosamente) los hilos del pool hasta igualar la configuración. No cuesta nada mientras nadie envíe tareas. */
    static void iniciar(){
        if(!configurado) configurar();
        synchronized(CERROJO){
            final int obj = tam;
            while(arrancados < obj){
                Trabajador t = new Trabajador("MundoInfinito-Pool-" + arrancados);
                t.setDaemon(true);
                try{ t.setPriority(Thread.NORM_PRIORITY); }catch(Throwable ignored){}   // en Android, prioridad < 5 = grupo de segundo plano
                t.start();
                arrancados++;
            }
        }
    }

    // ================================================================== API

    /**
     * Envía una tarea pura al pool. No bloquea. Si el mundo cambia antes de que empiece, se descarta (valor null, descartado()).
     * @param etiqueta nombre legible para los avisos y las estadísticas (p. ej. el id del contribuyente)
     */
    static <T> Futuro<T> enviar(Carril carril, String etiqueta, Prov<T> tarea){
        return poner(carril, etiqueta, tarea, CacheChunks.mundo());
    }

    /**
     * Ejecuta un lote de tareas puras y devuelve sus valores EN EL MISMO ORDEN en que se entregaron, sea cual sea el hilo que acabe
     * antes. Una tarea fallida deja null en su posición. Bloquea al que llama hasta que todas terminen (ayudando a ejecutarlas):
     * NO llamar desde el hilo principal con trabajo pesado; sí desde hilos de generación, de cocinado o del propio pool.
     * Con la ruta de un solo hilo, o con un solo elemento, todo corre aquí mismo y en orden.
     */
    static <T> Seq<T> ordenado(Carril carril, String etiqueta, Seq<Prov<T>> tareas){
        final int n = tareas.size;
        final Seq<T> out = new Seq<>(n);
        if(n == 0) return out;
        if(!configurado) configurar();
        if(unHilo || n == 1){
            for(int i = 0; i < n; i++) out.add(ejecutarAqui(carril, etiqueta, tareas.get(i)));
            return out;
        }
        final Seq<Futuro<T>> fs = new Seq<>(n);
        for(int i = 0; i < n; i++) fs.add(poner(carril, etiqueta, tareas.get(i), -1));   // -1: un lote pedido ahora no caduca
        for(int i = 0; i < n; i++){               // quien espera ayuda: lo que nadie ha tomado, lo hace él
            Futuro<T> f = fs.get(i);
            if(f.reclamar()) f.correr();
        }
        for(int i = 0; i < n; i++) out.add(fs.get(i).esperar());   // y se recoge en el orden de entrega
        return out;
    }

    /** Tareas enviadas a un carril que aún no han empezado (para que el llamador regule cuánto pide). */
    static int pendientes(Carril carril){
        return enCola[carril.ordinal()].get();
    }

    private static <T> Futuro<T> poner(Carril carril, String etiqueta, Prov<T> tarea, int mundo){
        iniciar();
        final Futuro<T> f = new Futuro<T>(carril, etiqueta, tarea, seq.incrementAndGet(), mundo);
        final int c = carril.ordinal();
        final int n = enCola[c].incrementAndGet();
        enviados[c].incrementAndGet();
        if(n > maxCola[c].get()) maxCola[c].set(n);
        cola.add(f);
        return f;
    }

    private static <T> T ejecutarAqui(Carril carril, String etiqueta, Prov<T> tarea){
        final int c = carril.ordinal();
        final long t0 = System.nanoTime();
        T r = null;
        try{
            r = tarea.get();
        }catch(Throwable e){
            fallos[c].incrementAndGet();
            avisarFallo(etiqueta, e);
        }
        nsEjecutando[c].addAndGet(System.nanoTime() - t0);
        enviados[c].incrementAndGet();
        ejecutados[c].incrementAndGet();
        return r;
    }

    private static void avisarFallo(String etiqueta, Throwable e){
        final String k = etiqueta == null ? "?" : etiqueta;
        if(avisados.putIfAbsent("fallo:" + k, Boolean.TRUE) == null){
            Log.err("[MundoInfinito] Hilos: la tarea '" + k + "' falló; se devuelve null y se sigue (aviso una vez)", e);
        }
    }

    // ================================================================== confinamiento al hilo principal

    /**
     * HILO PRINCIPAL, una vez por fotograma (la llama Perfil.cierre). Marca cuál es el hilo del juego y del dibujo (en Mindustry
     * son el mismo; se reasigna cada fotograma porque en Android el hilo de dibujo puede cambiar al pausar y reanudar) y relee el
     * ajuste de hilos cada ~10 s.
     */
    static void cuadro(){
        principal = Thread.currentThread();
        if(++cuadros >= 600){
            cuadros = 0;
            configurar();
        }
    }

    static boolean esPrincipal(){
        final Thread p = principal;
        return p != null && Thread.currentThread() == p;
    }

    /** ¿Estamos en un hilo del pool? */
    static boolean enPool(){
        return Thread.currentThread() instanceof Trabajador;
    }

    /**
     * Comprobación para código que modifica el mundo vivo o llama a la GPU. Devuelve true si se está en el hilo principal (o si
     * aún no se sabe cuál es). Si no, avisa UNA vez por sitio y devuelve false: el llamador debe omitir la operación, no abortar.
     */
    static boolean exigirPrincipal(String sitio){
        final Thread p = principal;
        if(p == null || Thread.currentThread() == p) return true;
        if(avisados.putIfAbsent("hilo:" + sitio, Boolean.TRUE) == null){
            Log.warn("[MundoInfinito] Hilos: '@' exige el hilo principal (mundo vivo / GPU) y se llamó desde '@'; se omite.", sitio, Thread.currentThread().getName());
        }
        return false;
    }

    /**
     * SOLO AVISO (nunca bloquea): anota una vez por sitio, en el log, si este punto, que debe ejecutarse en el hilo principal (estado del juego o GPU),
     * se ejecuta en otro. Sirve para comprobar en un dispositivo real la lista de puntos de enganche sin arriesgar nada; cuando no salga ningún aviso se
     * puede cambiar por {@link #exigirPrincipal}.
     */
    static void vigilarPrincipal(String sitio){
        final Thread p = principal;
        if(p == null || Thread.currentThread() == p) return;
        if(avisados.putIfAbsent("vigila:" + sitio, Boolean.TRUE) == null){
            Log.warn("[MundoInfinito] Hilos: '@' se ejecutó en el hilo '@' y no en el principal (solo aviso: no se bloquea nada).", sitio, Thread.currentThread().getName());
        }
    }

    // ================================================================== diagnóstico

    /** ¿Se ha usado el servicio alguna vez? (para que Perfil no escriba nada mientras nadie lo use) */
    static boolean activo(){
        for(int i = 0; i < NC; i++) if(enviados[i].get() > 0) return true;
        return false;
    }

    /** Una línea para el log: hilos, y por carril ejecutadas/enviadas, cola actual y máxima, tiempo medio de cálculo y de espera. */
    static String resumen(){
        final StringBuilder sb = new StringBuilder(128);
        sb.append("hilos=").append(unHilo() ? "1 (un hilo)" : String.valueOf(tam));
        for(Carril c : Carril.values()){
            final int i = c.ordinal();
            final long env = enviados[i].get();
            if(env == 0L) continue;
            final long ej = ejecutados[i].get(), base = Math.max(1L, ej);
            sb.append(" | ").append(c.nombre).append(' ').append(ej).append('/').append(env)
                .append(" cola=").append(enCola[i].get()).append("(máx ").append(maxCola[i].get()).append(')')
                .append(" calc=").append(ms(nsEjecutando[i].get() / base)).append("ms")
                .append(" espera=").append(ms(nsEnCola[i].get() / base)).append("ms");
            if(fallos[i].get() > 0) sb.append(" fallos=").append(fallos[i].get());
            if(descartados[i].get() > 0) sb.append(" descartadas=").append(descartados[i].get());
        }
        return sb.toString();
    }

    private static String ms(long ns){
        final long d = ns / 100_000L;   // décimas de ms
        return (d / 10) + "." + (d % 10);
    }

    // ================================================================== hilos y tareas

    private static final class Trabajador extends Thread{
        Trabajador(String nombre){
            super(nombre);
        }

        @Override
        public void run(){
            while(true){
                final Futuro<?> t;
                try{
                    t = cola.take();
                }catch(InterruptedException e){
                    return;
                }
                try{
                    if(!t.reclamar()) continue;   // ya la ejecutó quien la esperaba
                    t.correr();
                    final int p = t.carril.pausaMs;
                    if(p > 0) Thread.sleep(p);
                }catch(InterruptedException e){
                    return;
                }catch(Throwable e){
                    Log.err("[MundoInfinito] Hilos: error inesperado en un hilo del pool", e);   // el hilo nunca muere por una tarea
                }
            }
        }
    }

    /** Resultado futuro de una tarea. Solo lo ve quien la envió. */
    static final class Futuro<T>{
        private static final int PENDIENTE = 0, TOMADA = 1;

        final Carril carril;
        private final Prov<T> f;
        private final String etiqueta;
        private final long seq, tEnvio;
        private final int mundo;   // -1 = no caduca
        private final AtomicInteger estado = new AtomicInteger(PENDIENTE);
        private volatile boolean hecho, descartado;
        private volatile T valor;
        private volatile Throwable error;

        Futuro(Carril carril, String etiqueta, Prov<T> f, long seq, int mundo){
            this.carril = carril;
            this.etiqueta = etiqueta;
            this.f = f;
            this.seq = seq;
            this.mundo = mundo;
            this.tEnvio = System.nanoTime();
        }

        /** Se quedó con la tarea (solo uno lo consigue: el hilo del pool o quien la espera). */
        boolean reclamar(){
            if(!estado.compareAndSet(PENDIENTE, TOMADA)) return false;
            enCola[carril.ordinal()].decrementAndGet();
            return true;
        }

        /** El llamador YA la reclamó. */
        void correr(){
            final int c = carril.ordinal();
            final long t0 = System.nanoTime();
            if(mundo != -1 && mundo != CacheChunks.mundo()){   // el mundo cambió mientras esperaba: ya no hace falta
                descartados[c].incrementAndGet();
                descartado = true;
                terminar();
                return;
            }
            nsEnCola[c].addAndGet(t0 - tEnvio);
            try{
                valor = f.get();
            }catch(Throwable e){
                error = e;
                fallos[c].incrementAndGet();
                avisarFallo(etiqueta, e);
            }
            nsEjecutando[c].addAndGet(System.nanoTime() - t0);
            ejecutados[c].incrementAndGet();
            terminar();
        }

        private void terminar(){
            synchronized(this){
                hecho = true;
                notifyAll();
            }
        }

        boolean listo(){
            return hecho;
        }

        boolean descartado(){
            return descartado;
        }

        /** Error de la tarea, o null. */
        Throwable error(){
            return error;
        }

        /** Valor si ya terminó con éxito; null si no ha terminado, falló o se descartó. No bloquea. */
        T valor(){
            return hecho ? valor : null;
        }

        /** Espera el valor (null si falló o se descartó). Si nadie había empezado la tarea, la ejecuta quien espera. */
        T esperar(){
            if(!hecho && reclamar()) correr();
            if(!hecho){
                boolean interrumpido = false;
                synchronized(this){
                    while(!hecho){
                        try{ wait(); }catch(InterruptedException e){ interrumpido = true; }
                    }
                }
                if(interrumpido) Thread.currentThread().interrupt();
            }
            return valor;
        }

        /** Cancela si aún no empezó. true = cancelada. */
        boolean cancelar(){
            if(!estado.compareAndSet(PENDIENTE, TOMADA)) return false;
            enCola[carril.ordinal()].decrementAndGet();
            descartados[carril.ordinal()].incrementAndGet();
            descartado = true;
            terminar();
            return true;
        }
    }
}
