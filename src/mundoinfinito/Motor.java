package mundoinfinito;

import arc.struct.IntFloatMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;

/**
 * MOTOR DE CHUNKS (política de carga), separado del juego. Solo sabe de chunks, no de Tiles ni de unidades:
 * decide QUÉ chunks hacen falta y en qué ORDEN según el jugador y hacia dónde se dirige, como Minecraft.
 *
 *   nivel 0  lo que el jugador pisa y lo que ve en pantalla                       (urgente)
 *   nivel 1  un cono hacia donde se mueve; cuanto más rápido, más lejos llega     (anticipación)
 *   nivel 2  un DISCO alrededor del jugador, de radio = "distancia de carga" de Ajustes  (reserva)
 *   nivel 3  chunks con edificios guardados, que deben existir bajo ellos         (último)
 *
 * Las unidades aliadas y enemigas NO piden terreno: lejos del jugador duermen (ver Entidades).
 */
final class Motor{
    // Todas las zonas son CÍRCULOS (no cuadrados): a igual radio, un disco tiene ~21 % menos chunks que su cuadrado.
    static final int R_BASE = 2;                 // siempre cargado alrededor del jugador
    static final int AD_MIN = 3, AD_MAX = 9;     // alcance del cono hacia delante, en chunks
    static final float SEGUNDOS_ADELANTE = 4f;   // cuánto tiempo de viaje se intenta tener ya cargado
    static final float UMBRAL_MOVIMIENTO = 0.12f;// chunks/s por debajo de los cuales se considera quieto

    private float px, py;                        // última posición (tiles)
    private boolean tienePos;
    float vx, vy;                                // velocidad suavizada (tiles/tick)

    final IntSeq claves = new IntSeq();          // salida: chunks deseados, del más urgente al menos
    final IntFloatMap mejor = new IntFloatMap();

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
        return (float)Math.hypot(vx, vy) * 60f / Coord.CHUNK;
    }

    int alcance(){
        return Math.max(AD_MIN, Math.min(AD_MAX, Math.round(velocidad() * SEGUNDOS_ADELANTE) + AD_MIN));
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

    private long[] orden = new long[512];

    /**
     * Calcula los chunks deseados. n = chunks por lado de la ventana; (pcx, pcy) chunk del jugador; (camX, camY) chunk de la
     * cámara y rVista su radio; requeridos = chunks con edificios guardados (claves locales).
     */
    void plan(int n, int pcx, int pcy, int camX, int camY, int rVista, IntSet requeridos){
        mejor.clear();
        claves.clear();
        final int carga = Ajustes.carga();
        // nivel 0: bajo el jugador y lo que se ve (discos). Lo que se ve se carga aunque la distancia de carga sea menor.
        disco(n, pcx, pcy, R_BASE, 0f, 1f);
        disco(n, camX, camY, rVista, 0.5f, 0.05f);
        // nivel 1: cono hacia donde se mueve, recortado por el disco de carga
        float vel = velocidad();
        if(vel > UMBRAL_MOVIMIENTO){
            float len = (float)Math.hypot(vx, vy), dirx = vx / len, diry = vy / len;
            float cxf = px / Coord.CHUNK, cyf = py / Coord.CHUNK;
            int ad = Math.min(alcance(), carga);
            for(int d = 1; d <= ad; d++){
                int w = (int)Math.ceil(1 + d * 0.45f);
                for(int l = -w; l <= w; l++){
                    int cx = (int)Math.floor(cxf + dirx * d - diry * l), cy = (int)Math.floor(cyf + diry * d + dirx * l);
                    if(!Ajustes.dentro(cx - pcx, cy - pcy, carga)) continue;
                    poner(n, cx, cy, 1000f + d * 10f + Math.abs(l));
                }
            }
        }
        // nivel 2: reserva = disco de "distancia de carga" alrededor del jugador
        disco(n, pcx, pcy, carga, 2000f, 1f);
        // nivel 3: bajo los edificios guardados
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

    float prioridad(int clave){
        return mejor.get(clave, Float.MAX_VALUE);
    }
}
