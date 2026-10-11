package mundoinfinito;

import arc.struct.IntFloatMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;

/**
 * MOTOR DE CHUNKS (politica de carga), separado del juego. Solo sabe de chunks, no de Tiles ni de
 * unidades: decide QUE chunks hacen falta y en QUE orden segun el jugador, COMO MINECRAFT:
 * sin prediccion y sin valores fijos. El unico radio es el que el usuario configura en Ajustes
 * ("distancia de carga"), y el terreno se genera a medida que el usuario se mueve: continuo y
 * procedural, sin barreras que esperar.
 *
 *   nivel 0  lo que el jugador pisa y lo que ve en pantalla                       (urgente)
 *   nivel 1  un DISCO alrededor del jugador de radio = "distancia de carga" de Ajustes,
 *            que se llena del mas cercano al mas lejano: al moverse, lo nuevo que entra al
 *            disco se pide en el escaneo siguiente (medio segundo como mucho)          (continuo)
 *   nivel 2  chunks con edificios guardados, que deben existir bajo ellos         (ultimo)
 *
 * Las unidades aliadas y enemigas NO piden terreno: lejos del jugador duermen (ver Entidades).
 * La "distancia de simulacion" de Ajustes tampoco pide terreno: solo decide donde viven las
 * unidades. No hay cono de anticipacion ni radios internos: TODO lo pone la config del usuario.
 */
final class Motor{
    // Todas las zonas son CIRCULOS (no cuadrados): a igual radio, un disco tiene ~21 % menos chunks que su cuadrado.
    static final int R_BASE = 2;                 // siempre cargado alrededor del jugador

    final IntSeq claves = new IntSeq();          // salida: chunks deseados, del mas urgente al menos
    final IntFloatMap mejor = new IntFloatMap();

    private long[] orden = new long[512];

    /** Llamar con la posicion del jugador cada vez que se escanea (no se predice nada: solo se llena el disco configurado). */
    void actualizar(float x, float y, float dt){
    }

    void olvidar(){
    }

    private void poner(int n, int cx, int cy, float prio){
        if(cx < 0 || cy < 0 || cx >= n || cy >= n) return;
        int k = cy * n + cx;
        if(!mejor.containsKey(k) || mejor.get(k, Float.MAX_VALUE) > prio) mejor.put(k, prio);
    }

    private void disco(int n, int cx0, int cy0, int r, float base, float peso){
        for(int dy = -r; dy <= r; dy++){
            for(int dx = -r; dx <= r; dx++){
                if(!Ajustes.dentro(dx, dy, r)) continue;
                poner(n, cx0 + dx, cy0 + dy, base + (float)Math.hypot(dx, dy) * peso);
            }
        }
    }

    /**
     * Calcula los chunks deseados. n = chunks por lado de la ventana; (pcx, pcy) chunk del jugador;
     * (camX, camY) chunk de la camara y rVista su radio; requeridos = chunks con edificios guardados
     * (claves locales). Todo el radio lo pone la config del usuario: nunca hay un valor fijo.
     */
    void plan(int n, int pcx, int pcy, int camX, int camY, int rVista, IntSet requeridos){
        mejor.clear();
        claves.clear();
        final int carga = Ajustes.carga();
        // nivel 0: bajo el jugador y lo que se ve en pantalla. El radio de vision se recorta por la
        // distancia de carga: el ajuste del usuario SIEMPRE se respeta (nunca se carga de mas).
        disco(n, pcx, pcy, R_BASE, 0f, 1f);
        disco(n, camX, camY, Math.min(rVista, carga), 0.5f, 0.05f);
        // nivel 1: el DISCO de "distancia de carga" alrededor del jugador, SIN cono de anticipacion:
        // continuo y procedural, del mas cercano al mas lejano. Lo nuevo que entra al disco al moverse
        // esta a "carga" chunks del jugador (3 como minimo = 96 tiles de colchon), asi que nunca se
        // alcanza antes de generarse: no hay barrera que esperar.
        disco(n, pcx, pcy, carga, 2000f, 1f);
        // nivel 2: bajo los edificios guardados
        if(requeridos != null){
            IntSet.IntSetIterator it = requeridos.iterator();
            while(it.hasNext){
                int k = it.next();
                int cx = k % n, cy = k / n;
                poner(n, cx, cy, 3000f + (float)Math.hypot(cx - pcx, cy - pcy));
            }
        }
        // orden por prioridad (menor primero), sin objetos: prioridad (>= 0, sus bits conservan el orden) en la mitad alta del long
        int sz = mejor.size;
        if(orden.length < sz) orden = new long[Math.max(sz, orden.length * 2)];
        int i = 0;
        for(IntFloatMap.Entry e : mejor) orden[i++] = ((long)Float.floatToIntBits(e.value) << 32) | (e.key & 0xffffffffL);
        java.util.Arrays.sort(orden, 0, sz);
        for(int j = 0; j < sz; j++) claves.add((int)orden[j]);
    }
}