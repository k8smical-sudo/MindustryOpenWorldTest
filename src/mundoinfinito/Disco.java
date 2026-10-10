package mundoinfinito;

import arc.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.zip.GZIPOutputStream;

/**
 * E/S DEDICADA del mod: UN hilo propio que hace todo el acceso a disco, para que el hilo del juego nunca lea, comprima ni
 * escriba archivos.
 *
 *  - Escritura diferida y COALESCENTE: si un archivo se pide guardar varias veces antes de que le toque, solo se escribe la
 *    última versión.
 *  - Escritura ATÓMICA: se escribe en "archivo.tmp", se sincroniza y se renombra. Si el juego se cierra a mitad, queda el
 *    archivo anterior intacto (antes se truncaba el archivo y se podía perder la región entera).
 *  - Compresión (gzip) en este hilo, no en el principal.
 *  - Lecturas anticipadas (prefetch) con prioridad sobre las escrituras: el motor de chunks pide las regiones que el jugador
 *    va a necesitar antes de que las necesite.
 *  - vaciar(): espera a que todo lo pendiente llegue a disco (al guardar y salir).
 */
final class Disco{
    private static final class Tarea{
        File destino;
        byte[] datos;     // null = borrar
        boolean gzip;
    }

    private static final Object lock = new Object();
    private static final LinkedHashMap<String, Tarea> escrituras = new LinkedHashMap<>();
    private static final ArrayDeque<Runnable> despues = new ArrayDeque<>();   // tras las escrituras, en orden (p. ej. el meta)
    private static final ArrayDeque<Runnable> lecturas = new ArrayDeque<>();
    private static Thread hilo;
    private static boolean trabajando;
    static volatile long escritos, leidos, coalescidos;   // estadísticas (pruebas y depuración)

    private Disco(){}

    private static void asegurarHilo(){
        if(hilo != null && hilo.isAlive()) return;
        hilo = new Thread(Disco::bucle, "MundoInfinito-IO");
        hilo.setDaemon(true);
        hilo.start();
    }

    /** Guarda 'datos' en 'destino'. Si gzip, los datos llegan SIN comprimir y se comprimen aquí. Gana la última petición. */
    static void escribir(File destino, byte[] datos, boolean gzip){
        Tarea t = new Tarea();
        t.destino = destino;
        t.datos = datos;
        t.gzip = gzip;
        synchronized(lock){
            if(escrituras.put(destino.getPath(), t) != null) coalescidos++;
            asegurarHilo();
            lock.notifyAll();
        }
    }

    static void borrar(File destino){
        escribir(destino, null, false);
    }

    /** Ejecuta algo en el hilo de E/S DESPUÉS de las escrituras que haya pendientes. */
    static void despues(Runnable r){
        synchronized(lock){
            despues.addLast(r);
            asegurarHilo();
            lock.notifyAll();
        }
    }

    /** Encola una lectura (prefetch): el resultado lo deja el propio Runnable en una cola que el hilo del juego consulta. */
    static void leer(Runnable lectura){
        synchronized(lock){
            lecturas.addLast(lectura);
            asegurarHilo();
            lock.notifyAll();
        }
    }

    /** Espera a que lo pendiente llegue a disco. @return true si terminó antes del tiempo límite. */
    static boolean vaciar(long ms){
        long fin = System.currentTimeMillis() + ms;
        synchronized(lock){
            while(!escrituras.isEmpty() || !despues.isEmpty() || !lecturas.isEmpty() || trabajando){
                long resto = fin - System.currentTimeMillis();
                if(resto <= 0) return false;
                try{
                    lock.wait(Math.min(resto, 50));
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    static boolean ocupado(){
        synchronized(lock){
            return !escrituras.isEmpty() || !despues.isEmpty() || !lecturas.isEmpty() || trabajando;
        }
    }

    private static void bucle(){
        while(true){
            Runnable lectura = null;
            Tarea[] lote = null;
            Runnable[] posteriores = null;
            synchronized(lock){
                while(lecturas.isEmpty() && escrituras.isEmpty() && despues.isEmpty()){
                    try{
                        lock.wait();
                    }catch(InterruptedException e){
                        return;
                    }
                }
                trabajando = true;
                if(!lecturas.isEmpty()){
                    lectura = lecturas.pollFirst();             // las lecturas van primero: desbloquean el juego
                }else{
                    lote = escrituras.values().toArray(new Tarea[0]);
                    escrituras.clear();
                    posteriores = despues.toArray(new Runnable[0]);
                    despues.clear();
                }
            }
            try{
                if(lectura != null){
                    lectura.run();
                    leidos++;
                }else{
                    for(Tarea t : lote){
                        atenderLecturas();     // una lectura pedida mientras se escribe no espera a todo el lote
                        ejecutar(t);
                    }
                    for(Runnable r : posteriores) r.run();
                }
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error de E/S", t);
            }finally{
                synchronized(lock){
                    trabajando = false;
                    lock.notifyAll();
                }
            }
        }
    }

    private static void atenderLecturas(){
        while(true){
            Runnable r;
            synchronized(lock){
                r = lecturas.pollFirst();
            }
            if(r == null) return;
            try{
                r.run();
                leidos++;
            }catch(Throwable t){
                Log.err("[MundoInfinito] Error de lectura", t);
            }
        }
    }

    private static void ejecutar(Tarea t){
        try{
            if(t.datos == null){
                if(t.destino.exists()) t.destino.delete();
            }else{
                escribirAtomico(t.destino, t.datos, t.gzip);
            }
            escritos++;
        }catch(Throwable e){
            Log.err("[MundoInfinito] No se pudo escribir " + t.destino.getName(), e);
        }
    }

    /** Escribe en un archivo temporal, lo sincroniza y lo renombra: nunca queda un archivo a medias. */
    static void escribirAtomico(File destino, byte[] datos, boolean gzip) throws IOException{
        File padre = destino.getParentFile();
        if(padre != null) padre.mkdirs();
        File tmp = new File(destino.getPath() + ".tmp");
        try(FileOutputStream fos = new FileOutputStream(tmp)){
            if(gzip){
                GZIPOutputStream gz = new GZIPOutputStream(fos);
                gz.write(datos);
                gz.finish();
            }else{
                fos.write(datos);
            }
            fos.flush();
            fos.getFD().sync();
        }
        mover(tmp, destino);
    }

    private static void mover(File tmp, File destino) throws IOException{
        try{
            java.nio.file.Files.move(tmp.toPath(), destino.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException | UnsupportedOperationException | LinkageError e){
            // sistemas sin movimiento atómico (o sin java.nio.file): renombrar, que en Unix/Android reemplaza
            if(!tmp.renameTo(destino)){
                if(destino.exists()) destino.delete();
                if(!tmp.renameTo(destino)) throw new IOException("No se pudo renombrar " + tmp.getName());
            }
        }
    }

    /** Escribe de forma síncrona y atómica (para pruebas y rutas que deban bloquear). */
    static void escribirYa(File destino, byte[] datos, boolean gzip){
        try{
            escribirAtomico(destino, datos, gzip);
        }catch(IOException e){
            Log.err("[MundoInfinito] No se pudo escribir " + destino.getName(), e);
        }
    }
}
