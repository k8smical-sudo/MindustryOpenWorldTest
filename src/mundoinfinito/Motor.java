package mundoinfinito;

import arc.struct.IntFloatMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.util.Log;

/**
 * MOTOR DE CHUNKS (política de carga), INDEPENDIENTE del juego. Solo sabe de chunks, no de Tiles ni de unidades.
 *
 * Corre en SU PROPIO HILO ("MundoInfinito-Motor"): el hilo del juego solo le entrega una foto (posición, velocidad, cámara,
 * ajustes) con solicitar() y recoge el último plan terminado con recoger(). Nunca espera: si el motor aún calcula, el juego
 * sigue con el plan anterior. Así planificar (ordenar cientos de chunks) ya no cuesta nada en el fotograma.
 *
 *   nivel 0  lo que el jugador pisa y (si no es estricto) lo que ve en pantalla       (urgente)
 *   nivel 1  un cono hacia donde se mueve; cuanto más rápido, más lejos llega         (anticipación)
 *   nivel 2  un DISCO alrededor del jugador, de radio = "distancia de carga" de Ajustes (reserva)
 *   nivel 3  chunks con edificios guardados, que deben existir bajo ellos             (último)
 *
 * La unidad que CONTROLA el jugador y la CÁMARA no tienen tope: lo que pisa y lo que se ve se carga siempre, en orden de
 * cercanía, de forma progresiva. La distancia de carga de Ajustes solo gobierna la reserva (el disco de alrededor).
 *
 * Además del orden de chunks, el plan trae las REGIONES de disco que va a necesitar, para que el juego las lea antes en
 * segundo plano y nunca tenga que leer un archivo en el hilo principal.
 */
final class Motor{
    static final int R_BASE = 2;                 // siempre cargado alrededor del jugador
    static final int AD_MIN = 3, AD_MAX = 9;     // alcance del cono hacia delante, en chunks
    static final float SEGUNDOS_ADELANTE = 4f;   // cuánto tiempo de viaje se intenta tener ya cargado
    static final float UMBRAL_MOVIMIENTO = 0.12f;// chunks/s por debajo de los cuales se considera quieto

    // ---------------------------------------------------------------- estado del jugador (solo hilo del juego)
    private float px, py;                        // última posición (tiles)
    private boolean tienePos;
    float vx, vy;                                // velocidad suavizada (tiles/tick)

    // ---------------------------------------------------------------- salida (solo hilo del juego)
    IntSeq claves = new IntSeq();                // chunks deseados, del más urgente al menos
    IntSeq regX = new IntSeq(), regY = new IntSeq();   // regiones (coordenadas virtuales) que el plan necesita
    private Plan enUso = new Plan();

    // ---------------------------------------------------------------- intercambio con el hilo del motor
    private static final class Plan{
        final IntSeq claves = new IntSeq();
        final IntSeq regX = new IntSeq(), regY = new IntSeq();
        final IntFloatMap mejor = new IntFloatMap();
        long[] orden = new long[512];
        int epoca;
    }

    private static final class Entrada{
        int n, pcx, pcy, camX, camY, rVista, carga, vcx0, vcy0, epoca;
        boolean estricto;
        float px, py, vx, vy;
        int[] req = new int[64];
        int nReq;
    }

    private final Object lock = new Object();
    private Entrada pendiente, reciclada;        // pendiente: la última foto sin atender (se pisa: solo importa la más reciente)
    private Plan listo, trabajo = new Plan();    // listo: plan terminado sin recoger; trabajo: en el que calcula el hilo
    private boolean nuevo;
    private Thread hilo;

    // ================================================================ hilo del juego
    /** Llamar con la posición del jugador cada vez que se escanea; dt = ticks transcurridos desde la última llamada. */
    void actualizar(float x, float y, float dt){
        if(!tienePos || dt <= 0f){
            px = x; py = y; tienePos = true;
            return;
        }
        float dx = x - px, dy = y - py;
        px = x; py = y;
        if(Math.hypot(dx, dy) > 96f){ vx = vy = 0f; return; }   // teletransporte: no es velocidad
        float a = 1f - (float)Math.exp(-dt / 30f);               // constante de tiempo ~0,5 s
        vx += (dx / dt - vx) * a;
        vy += (dy / dt - vy) * a;
    }

    void olvidar(){
        tienePos = false;
        vx = vy = 0f;
    }

    /** Velocidad en chunks por segundo. */
    float velocidad(){
        return velocidad(vx, vy);
    }

    int alcance(){
        return alcance(vx, vy);
    }

    private static float velocidad(float vx, float vy){
        return (float)Math.hypot(vx, vy) * 60f / Coord.CHUNK;
    }

    private static int alcance(float vx, float vy){
        return Math.max(AD_MIN, Math.min(AD_MAX, Math.round(velocidad(vx, vy) * SEGUNDOS_ADELANTE) + AD_MIN));
    }

    /**
     * Entrega una foto al motor (no espera). n = chunks por lado de la ventana; (pcx, pcy) chunk del jugador; (camX, camY) chunk de
     * la cámara y rVista su radio; (vcx0, vcy0) chunk virtual de la esquina de la ventana; epoca = la del Streamer (un plan de
     * otra época se descarta); requeridos = chunks con edificios guardados (claves locales).
     */
    void solicitar(int n, int pcx, int pcy, int camX, int camY, int rVista, int carga, boolean estricto,
                   int vcx0, int vcy0, int epoca, IntSet requeridos){
        Entrada e;
        synchronized(lock){
            e = reciclada;
            reciclada = null;
        }
        if(e == null) e = new Entrada();
        e.n = n; e.pcx = pcx; e.pcy = pcy; e.camX = camX; e.camY = camY; e.rVista = rVista; e.carga = carga;
        e.estricto = estricto; e.vcx0 = vcx0; e.vcy0 = vcy0; e.epoca = epoca;
        e.px = px; e.py = py; e.vx = vx; e.vy = vy;
        int sz = requeridos == null ? 0 : requeridos.size;
        if(e.req.length < sz) e.req = new int[Math.max(sz, e.req.length * 2)];
        int i = 0;
        if(requeridos != null){
            IntSet.IntSetIterator it = requeridos.iterator();
            while(it.hasNext) e.req[i++] = it.next();
        }
        e.nReq = i;
        synchronized(lock){
            if(pendiente != null) reciclada = pendiente;
            pendiente = e;
            lock.notifyAll();
        }
        asegurarHilo();
    }

    /**
     * Cada fotograma: si el motor terminó un plan nuevo lo pone en uso. Devuelve true si cambió (y es de la época actual).
     * Un plan de otra época (la ventana se movió mientras se calculaba) se descarta: sus claves ya no significan nada.
     */
    boolean recoger(int epoca){
        boolean hay;
        synchronized(lock){
            hay = nuevo;
            if(hay){
                Plan t = enUso;
                enUso = listo;
                listo = t;
                nuevo = false;
            }
        }
        if(!hay) return false;
        boolean valido = enUso.epoca == epoca;
        if(!valido){
            enUso.claves.clear();
            enUso.regX.clear();
            enUso.regY.clear();
        }
        claves = enUso.claves;
        regX = enUso.regX;
        regY = enUso.regY;
        return valido;
    }

    float prioridad(int clave){
        return enUso.mejor.get(clave, Float.MAX_VALUE);
    }

    // ================================================================ hilo del motor
    private void asegurarHilo(){
        if(hilo != null && hilo.isAlive()) return;
        hilo = new Thread(this::bucle, "MundoInfinito-Motor");
        hilo.setDaemon(true);
        try{ hilo.setPriority(Thread.NORM_PRIORITY - 1); }catch(Throwable ignored){}
        hilo.start();
    }

    private void bucle(){
        while(true){
            Entrada e;
            Plan p;
            synchronized(lock){
                while(pendiente == null){
                    try{ lock.wait(); }catch(InterruptedException x){ return; }
                }
                e = pendiente;
                pendiente = null;
                p = trabajo;
            }
            try{
                calcular(e, p);
                synchronized(lock){
                    Plan viejo = listo;
                    listo = trabajo;
                    trabajo = viejo != null ? viejo : new Plan();
                    nuevo = true;
                }
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error en el motor de chunks", t);
            }
            synchronized(lock){
                if(reciclada == null) reciclada = e;
            }
        }
    }

    private static void poner(Plan p, int n, int cx, int cy, float prio){
        if(cx < 0 || cy < 0 || cx >= n || cy >= n) return;
        int k = cy * n + cx;
        if(!p.mejor.containsKey(k) || p.mejor.get(k, Float.MAX_VALUE) > prio) p.mejor.put(k, prio);
    }

    private static void disco(Plan p, int n, int cx0, int cy0, int r, float base, float peso){
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                if(!Ajustes.dentro(dx, dy, r)) continue;
                poner(p, n, cx0 + dx, cy0 + dy, base + (float)Math.hypot(dx, dy) * peso);
            }
        }
    }

    /** Como disco(), pero solo los chunks que además caen dentro del disco de carga alrededor del jugador (modo estricto). */
    private static void discoLimitado(Plan p, int n, int cx0, int cy0, int r, float base, float peso, int pcx, int pcy, int carga){
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                if(!Ajustes.dentro(dx, dy, r)) continue;
                int cx = cx0 + dx, cy = cy0 + dy;
                if(!Ajustes.dentro(cx - pcx, cy - pcy, carga)) continue;
                poner(p, n, cx, cy, base + (float)Math.hypot(dx, dy) * peso);
            }
        }
    }

    private static void calcular(Entrada e, Plan p){
        p.mejor.clear();
        p.claves.clear();
        p.regX.clear();
        p.regY.clear();
        p.epoca = e.epoca;
        final int n = e.n, carga = e.carga;
        // nivel 0: bajo el jugador y lo que se ve (discos)
        disco(p, n, e.pcx, e.pcy, R_BASE, 0f, 1f);
        // la CÁMARA siempre carga lo que ve (sin tope, como Minecraft): lo más cercano a la unidad primero, hacia fuera
        disco(p, n, e.camX, e.camY, e.rVista, 0.5f, 0.05f);
        // nivel 1: cono hacia donde se mueve, recortado por el disco de carga
        if(velocidad(e.vx, e.vy) > UMBRAL_MOVIMIENTO){
            float len = (float)Math.hypot(e.vx, e.vy), dirx = e.vx / len, diry = e.vy / len;
            float cxf = e.px / Coord.CHUNK, cyf = e.py / Coord.CHUNK;
            int ad = Math.min(alcance(e.vx, e.vy), carga);
            for(int d = 1; d <= ad; d++){
                int w = (int)Math.ceil(1 + d * 0.45f);
                for(int l = -w; l <= w; l++){
                    int cx = (int)Math.floor(cxf + dirx * d - diry * l), cy = (int)Math.floor(cyf + diry * d + dirx * l);
                    if(!Ajustes.dentro(cx - e.pcx, cy - e.pcy, carga)) continue;
                    poner(p, n, cx, cy, 1000f + d * 10f + Math.abs(l));
                }
            }
        }
        // nivel 2: reserva = disco de "distancia de carga" alrededor del jugador
        disco(p, n, e.pcx, e.pcy, carga, 2000f, 1f);
        // nivel 3: bajo los edificios guardados
        for(int i = 0; i < e.nReq; i++){
            int k = e.req[i];
            int cx = k % n, cy = k / n;
            poner(p, n, cx, cy, 3000f + (float)Math.hypot(cx - e.pcx, cy - e.pcy));
        }
        // orden por prioridad (menor primero), sin objetos: la prioridad (>= 0, sus bits conservan el orden) va en la mitad alta del long
        int sz = p.mejor.size;
        if(p.orden.length < sz) p.orden = new long[Math.max(sz, p.orden.length * 2)];
        int i = 0;
        for(IntFloatMap.Entry en : p.mejor) p.orden[i++] = ((long)Float.floatToIntBits(en.value) << 32) | (en.key & 0xffffffffL);
        java.util.Arrays.sort(p.orden, 0, sz);
        for(int j = 0; j < sz; j++) p.claves.add((int)p.orden[j]);
        // regiones de disco que el plan va a necesitar (para leerlas antes, en segundo plano)
        final int reg = MundoInfinitoMod.REGION_CHUNKS;
        for(int j = 0; j < p.claves.size; j++){
            int k = p.claves.items[j];
            int rx = Math.floorDiv(e.vcx0 + k % n, reg), ry = Math.floorDiv(e.vcy0 + k / n, reg);
            boolean ya = false;
            for(int q = 0; q < p.regX.size; q++){
                if(p.regX.items[q] == rx && p.regY.items[q] == ry){ ya = true; break; }
            }
            if(!ya){ p.regX.add(rx); p.regY.add(ry); }
        }
    }
}
