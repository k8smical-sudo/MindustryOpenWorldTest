package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.ChunkData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * CACHÉ DE TERRENO por NIVELES, por chunk VIRTUAL. El terreno es una función pura de (semilla, dimensión, coordenada), así
 * que un chunk ya calculado no hace falta calcularlo otra vez.
 *
 *   N1 "caliente"  short[] sin comprimir en RAM. Lo que se usó hace poco (lo que rodea al jugador). Acceso = arraycopy.
 *   N2 "tibio"     el mismo chunk comprimido (Deflate rápido) en RAM. El terreno comprime ~6-10 veces, así que con el mismo
 *                  presupuesto caben muchísimos más chunks. Costo de subirlo a N1: ~0,1 ms.
 *   (N3 disco)     lo que NO se puede recalcular barato ya vive en disco: edificios y unidades (Regiones) y miniaturas (Mapa).
 *                  El terreno se descarta al caer de N2: regenerarlo es una función pura, y un archivo por chunk no compensa.
 *
 * Presupuesto total = Ajustes.cacheMB() (mínimo 8 MB: la reubicación de la ventana lee de aquí). 40 % para N1, 60 % para N2.
 * Seguro entre hilos. NO cachea edificios ni nada que el jugador cambie.
 */
final class CacheChunks{
    static final int CELDAS = MundoInfinitoMod.TAM_CHUNK * MundoInfinitoMod.TAM_CHUNK;
    static final int BYTES = CELDAS * 3 * 2;                  // piso + mena + bloque, short cada uno

    private static final LinkedHashMap<Long, short[]> n1 = new LinkedHashMap<>(512, 0.75f, true);
    private static final LinkedHashMap<Long, byte[]> n2 = new LinkedHashMap<>(512, 0.75f, true);
    private static long bytesN2;
    private static volatile int mundo;                        // identifica (semilla, dimensión) vigentes
    private static int semilla, dim;
    private static boolean hayMundo;
    static volatile long aciertos, aciertosN2, fallos;        // estadísticas

    private CacheChunks(){}

    /** Llamar al (re)iniciar el streaming. Solo vacía la caché si el mundo o la dimensión cambiaron. */
    static synchronized void configurar(int sem, int d){
        if(hayMundo && sem == semilla && d == dim) return;
        hayMundo = true;
        semilla = sem;
        dim = d;
        n1.clear();
        n2.clear();
        bytesN2 = 0;
        mundo++;
    }

    static int mundo(){
        return mundo;
    }

    private static long presupuesto(){
        return Math.max(8, Ajustes.cacheMB()) * 1024L * 1024L;
    }

    /** Capacidad aproximada en chunks sin comprimir (N1); siempre > 0. */
    static int maxChunks(){
        return (int)(presupuesto() * 4 / 10 / BYTES);
    }

    static synchronized boolean tiene(int mid, long k){
        return mid == mundo && (n1.containsKey(k) || n2.containsKey(k));
    }

    /** Un ChunkData (del pool) ya relleno con el chunk virtual k, o null si no está. El llamador lo libera. */
    static synchronized ChunkData copiar(int mid, long k, int cx, int cy, int epoca){
        if(mid != mundo) return null;
        short[] a = n1.get(k);
        if(a == null){
            byte[] z = n2.remove(k);
            if(z != null){
                bytesN2 -= z.length;
                a = descomprimir(z);
                if(a != null){
                    aciertosN2++;
                    n1.put(k, a);          // sube a caliente
                    recortar();
                }
            }
        }
        if(a == null){ fallos++; return null; }
        aciertos++;
        ChunkData d = ChunkData.obtener(cx, cy, epoca);
        System.arraycopy(a, 0, d.piso, 0, CELDAS);
        System.arraycopy(a, CELDAS, d.mena, 0, CELDAS);
        System.arraycopy(a, CELDAS * 2, d.bloque, 0, CELDAS);
        return d;
    }

    static synchronized void poner(int mid, long k, ChunkData d){
        if(mid != mundo) return;
        short[] a = n1.get(k);
        if(a == null){
            a = new short[CELDAS * 3];
            n1.put(k, a);
            byte[] z = n2.remove(k);
            if(z != null) bytesN2 -= z.length;
        }
        System.arraycopy(d.piso, 0, a, 0, CELDAS);
        System.arraycopy(d.mena, 0, a, CELDAS, CELDAS);
        System.arraycopy(d.bloque, 0, a, CELDAS * 2, CELDAS);
        recortar();
    }

    /** N1 -> N2 (comprimiendo) y fuera de N2 (descartando) hasta caber en el presupuesto. */
    private static void recortar(){
        long p = presupuesto();
        int max1 = Math.max(64, (int)(p * 4 / 10 / BYTES));
        if(n1.size() > max1){
            Iterator<Map.Entry<Long, short[]>> it = n1.entrySet().iterator();   // el menos usado recientemente va primero
            while(n1.size() > max1 && it.hasNext()){
                Map.Entry<Long, short[]> e = it.next();
                byte[] z = comprimir(e.getValue());
                it.remove();
                if(z != null){
                    byte[] viejo = n2.put(e.getKey(), z);
                    if(viejo != null) bytesN2 -= viejo.length;
                    bytesN2 += z.length;
                }
            }
        }
        long max2 = p * 6 / 10;
        if(bytesN2 > max2){
            Iterator<Map.Entry<Long, byte[]>> it = n2.entrySet().iterator();
            while(bytesN2 > max2 && it.hasNext()){
                bytesN2 -= it.next().getValue().length;
                it.remove();
            }
        }
    }

    private static byte[] comprimir(short[] a){
        try{
            byte[] crudo = new byte[BYTES];
            for(int i = 0, j = 0; i < a.length; i++){
                crudo[j++] = (byte)a[i];
                crudo[j++] = (byte)(a[i] >> 8);
            }
            Deflater df = new Deflater(Deflater.BEST_SPEED, true);
            df.setInput(crudo);
            df.finish();
            byte[] buf = new byte[BYTES + 64];
            int n = 0;
            while(!df.finished() && n < buf.length) n += df.deflate(buf, n, buf.length - n);
            boolean completo = df.finished();
            df.end();
            if(!completo) return null;      // no comprimió (no pasa con terreno): se descarta
            byte[] z = new byte[n];
            System.arraycopy(buf, 0, z, 0, n);
            return z;
        }catch(Throwable t){
            return null;
        }
    }

    private static short[] descomprimir(byte[] z){
        try{
            Inflater in = new Inflater(true);
            in.setInput(z);
            byte[] crudo = new byte[BYTES];
            int n = 0;
            while(n < BYTES && !in.finished()){
                int r = in.inflate(crudo, n, BYTES - n);
                if(r == 0 && (in.needsInput() || in.needsDictionary())) break;
                n += r;
            }
            in.end();
            if(n != BYTES) return null;
            short[] a = new short[CELDAS * 3];
            for(int i = 0, j = 0; i < a.length; i++, j += 2) a[i] = (short)((crudo[j] & 0xff) | (crudo[j + 1] << 8));
            return a;
        }catch(DataFormatException e){
            return null;
        }
    }

    /** Chunks en la caché (N1 + N2). */
    static synchronized int tamano(){
        return n1.size() + n2.size();
    }

    static synchronized int tamanoN1(){
        return n1.size();
    }

    static synchronized long bytesN2(){
        return bytesN2;
    }
}
