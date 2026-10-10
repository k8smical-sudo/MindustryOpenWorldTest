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
 *   nivel 2  un anillo corto alrededor, para cuando se detiene o gira             (reserva)
 *   nivel 3  chunks con edificios guardados, que deben existir bajo ellos         (último)
 *
 * Las unidades aliadas y enemigas NO piden terreno: lejos del jugador duermen (ver Entidades).
 */
final class Motor{
    static final int R_BASE = 2;                 // siempre cargado alrededor del jugador
    static final int R_QUIETO = 4;               // anillo de reserva
    static final int AD_MIN = 3, AD_MAX = 9;     // alcance del cono hacia delante, en chunks
    static final float SEGUNDOS_ADELANTE = 4f;   // cuánto tiempo de viaje se intenta tener ya cargado
    static final float UMBRAL_MOVIMIENTO = 0.12f;// chunks/s por debajo de los cuales se considera quieto

    private float px, py;                        // última posición (tiles)
    private boolean tienePos;
    float vx, vy;                                // velocidad suavizada (tiles/tick)

    final IntSeq claves = new IntSeq();          // salida: chunks deseados, del más urgente al menos
    final IntFloatMap mejor = new IntFloatMap();
    private final IntSeq tmp = new IntSeq();

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

    /**
     * Calcula los chunks deseados. n = chunks por lado de la ventana; (pcx, pcy) chunk del jugador; (camX, camY) chunk de la
     * cámara y rVista su radio; requeridos = chunks con edificios guardados (claves locales).
     */
    void plan(int n, int pcx, int pcy, int camX, int camY, int rVista, IntSet requeridos){
        mejor.clear();
        claves.clear();
        // nivel 0: bajo el jugador y lo que se ve
        for(int dy = -R_BASE; dy <= R_BASE; dy++){
            for(int dx = -R_BASE; dx <= R_BASE; dx++) poner(n, pcx + dx, pcy + dy, (float)Math.hypot(dx, dy));
        }
        for(int dy = -rVista; dy <= rVista; dy++){
            for(int dx = -rVista; dx <= rVista; dx++){
                int cx = camX + dx, cy = camY + dy;
                poner(n, cx, cy, 0.5f + (float)Math.hypot(cx - pcx, cy - pcy) * 0.05f);
            }
        }
        // nivel 1: cono hacia donde se mueve
        float vel = velocidad();
        if(vel > UMBRAL_MOVIMIENTO){
            float len = (float)Math.hypot(vx, vy), dirx = vx / len, diry = vy / len;
            float cxf = px / Coord.CHUNK, cyf = py / Coord.CHUNK;
            int ad = alcance();
            for(int d = 1; d <= ad; d++){
                int w = (int)Math.ceil(1 + d * 0.45f);
                for(int l = -w; l <= w; l++){
                    int cx = (int)Math.floor(cxf + dirx * d - diry * l + 0.5f - 0.5f), cy = (int)Math.floor(cyf + diry * d + dirx * l + 0.5f - 0.5f);
                    poner(n, cx, cy, 1000f + d * 10f + Math.abs(l));
                }
            }
        }
        // nivel 2: reserva alrededor
        for(int dy = -R_QUIETO; dy <= R_QUIETO; dy++){
            for(int dx = -R_QUIETO; dx <= R_QUIETO; dx++) poner(n, pcx + dx, pcy + dy, 2000f + (float)Math.hypot(dx, dy));
        }
        // nivel 3: bajo los edificios guardados
        if(requeridos != null){
            IntSet.IntSetIterator it = requeridos.iterator();
            while(it.hasNext){
                int k = it.next();
                int cx = k % n, cy = k / n;
                poner(n, cx, cy, 3000f + (float)Math.hypot(cx - pcx, cy - pcy));
            }
        }
        // orden por prioridad (menor primero)
        tmp.clear();
        for(IntFloatMap.Entry e : mejor) tmp.add(e.key);
        int sz = tmp.size;
        Integer[] arr = new Integer[sz];
        for(int i = 0; i < sz; i++) arr[i] = tmp.items[i];
        java.util.Arrays.sort(arr, (a, b) -> Float.compare(mejor.get(a, 0f), mejor.get(b, 0f)));
        for(Integer k : arr) claves.add(k);
    }

    float prioridad(int clave){
        return mejor.get(clave, Float.MAX_VALUE);
    }
}
