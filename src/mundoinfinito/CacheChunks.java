package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.ChunkData;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * CACHÉ DE TERRENO ya generado, por chunk VIRTUAL. El terreno es una función pura de (semilla, dimensión, coordenada), así
 * que un chunk que ya se calculó nunca hace falta calcularlo otra vez: al volver a una zona, o al reubicar la ventana,
 * se copia de aquí (6 KB) en vez de volver a muestrear el ruido (~1000 tiles x varias octavas).
 *
 * Es una LRU con tope en MB (Ajustes). Se vacía solo cuando cambia el mundo o la dimensión. Todo es seguro entre hilos.
 * NO cachea edificios ni terreno modificado por el jugador: eso vive en Regiones.
 */
final class CacheChunks{
    static final int CELDAS = MundoInfinitoMod.TAM_CHUNK * MundoInfinitoMod.TAM_CHUNK;
    static final int BYTES = CELDAS * 3 * 2;                  // piso + mena + bloque, short cada uno

    private static final LinkedHashMap<Long, short[]> mapa = new LinkedHashMap<>(512, 0.75f, true);
    private static volatile int mundo;                        // identifica (semilla, dimensión) vigentes
    private static int semilla, dim;
    private static boolean hayMundo;
    static volatile long aciertos, fallos;                    // estadísticas

    private CacheChunks(){}

    /** Llamar al (re)iniciar el streaming. Solo vacía la caché si el mundo o la dimensión cambiaron. */
    static synchronized void configurar(int sem, int d){
        if(hayMundo && sem == semilla && d == dim) return;
        hayMundo = true;
        semilla = sem;
        dim = d;
        mapa.clear();
        mundo++;
    }

    static int mundo(){
        return mundo;
    }

    static int maxChunks(){
        return Ajustes.cacheMB() * 1024 * 1024 / BYTES;
    }

    static synchronized boolean tiene(int mid, long k){
        return mid == mundo && mapa.containsKey(k);
    }

    /** Un ChunkData (del pool) ya relleno con el chunk virtual k, o null si no está. El llamador lo libera. */
    static synchronized ChunkData copiar(int mid, long k, int cx, int cy, int epoca){
        if(mid != mundo){ return null; }
        short[] a = mapa.get(k);
        if(a == null){ fallos++; return null; }
        aciertos++;
        ChunkData d = ChunkData.obtener(cx, cy, epoca);
        System.arraycopy(a, 0, d.piso, 0, CELDAS);
        System.arraycopy(a, CELDAS, d.mena, 0, CELDAS);
        System.arraycopy(a, CELDAS * 2, d.bloque, 0, CELDAS);
        return d;
    }

    static synchronized void poner(int mid, long k, ChunkData d){
        int max = maxChunks();
        if(max <= 0 || mid != mundo) return;
        short[] a = mapa.get(k);
        if(a == null){
            a = new short[CELDAS * 3];
            mapa.put(k, a);
        }
        System.arraycopy(d.piso, 0, a, 0, CELDAS);
        System.arraycopy(d.mena, 0, a, CELDAS, CELDAS);
        System.arraycopy(d.bloque, 0, a, CELDAS * 2, CELDAS);
        if(mapa.size() > max){
            Iterator<Long> it = mapa.keySet().iterator();   // el menos usado recientemente va primero
            while(mapa.size() > max && it.hasNext()){ it.next(); it.remove(); }
        }
    }

    static synchronized int tamano(){
        return mapa.size();
    }
}
