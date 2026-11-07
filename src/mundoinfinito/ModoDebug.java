package mundoinfinito;

import arc.Core;
import arc.files.Fi;
import arc.util.Log;
import arc.util.OS;
import mindustry.core.Version;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Modo debug (ajuste "OpenWorld > Modo debug: guardar logs en Descargas"). Copia el log del juego a un .txt nuevo en cada sesión,
 * línea a línea y sin esperar al cierre, para que un crash deje rastro en el archivo.
 *
 * Carpeta: en Android, Download del almacenamiento compartido; en PC, Downloads o Descargas del usuario. Si no se puede escribir en
 * ninguna (Android 11+ puede denegarlo), usa la carpeta de datos de Mindustry, subcarpeta debug. La ruta queda en la cabecera del .txt.
 *
 * Se mantiene engancharlo aunque otro sistema reemplace el logger o el manejador de errores: al cargar el cliente se vuelve a
 * encadenar y queda escrito en el archivo. Cada 30 s escribe un latido con la memoria usada, para ver la evolución antes de un crash.
 * Se activa al arrancar: cambiar el ajuste requiere reiniciar el juego. Nunca lanza excepciones hacia el juego.
 */
final class ModoDebug{
    private static final Object CERROJO = new Object();
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final SimpleDateFormat NOMBRE = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final long LATIDO_MS = 30000L;

    private static boolean activo = false;
    private static Writer salida;
    private static String ruta = "(inactivo)";
    private static Log.LogHandler miLogger;
    private static Thread.UncaughtExceptionHandler miManejador;

    private ModoDebug(){}

    /** Llamar una vez al cargar el mod (al inicio del constructor). Sin el ajuste activo no hace nada. */
    static void iniciarSiActivo(){
        if(activo) return;
        try{
            if(Core.settings == null || !Ajustes.debugLogs()) return;

            final Fi carpeta = carpetaDestino();
            carpeta.file().mkdirs();
            final Fi archivo = carpeta.child("mundo-infinito-debug-" + NOMBRE.format(new Date()) + ".txt");
            synchronized(CERROJO){ salida = archivo.writer(false); }   // archivo nuevo por sesión
            ruta = archivo.absolutePath();
            cabecera();

            instalarLogger();
            instalarManejadorDeErrores();
            Log.level = Log.LogLevel.debug;   // el modo debug necesita también los mensajes de depuración
            iniciarLatido();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> escribir("INFO", "Sesión cerrada (cierre normal o del sistema).")));

            activo = true;
            Log.info("[ModoDebug] Guardando todos los logs en: @", ruta);
        }catch(Throwable e){
            Log.err("[ModoDebug] No se pudo activar: el juego sigue sin guardar logs", e);
        }
    }

    /**
     * Llamar al cargar el cliente (ClientLoadEvent, cuando ya están cargados los sistemas de log de Mindustry). Si otro sistema
     * reemplazó el logger o el manejador de errores después de nosotros, lo vuelve a encadenar y lo deja escrito en el archivo.
     */
    static void reengancharSiHaceFalta(){
        if(!activo) return;
        try{
            if(Log.logger != miLogger){
                escribir("WARN", "Log.logger fue reemplazado por otro sistema: se vuelve a encadenar el registro en el archivo.");
                instalarLogger();
            }
            if(Thread.getDefaultUncaughtExceptionHandler() != miManejador){
                escribir("WARN", "El manejador de errores no capturados fue reemplazado: se vuelve a encadenar.");
                instalarManejadorDeErrores();
            }
            escribir("INFO", "Cliente cargado. Plataforma: " + plataforma() + ".");
        }catch(Throwable e){
            escribir("WARN", "No se pudo re-enganchar el modo debug: " + trazas(e));
        }
    }

    /** Carpeta de Descargas del dispositivo; si no hay ninguna escribible, la carpeta de datos de Mindustry. */
    static Fi carpetaDestino(){
        final List<String> candidatas = new ArrayList<String>();
        if(esAndroid()){
            candidatas.add("/storage/emulated/0/Download");
            candidatas.add("/sdcard/Download");
        }else{
            final String home = System.getProperty("user.home", "");
            candidatas.add(home + "/Downloads");
            candidatas.add(home + "/Descargas");
        }
        for(String c : candidatas){
            try{
                final Fi f = Core.files.absolute(c);
                if(f.exists() && f.isDirectory() && f.file().canWrite()) return f;
            }catch(Throwable ignored){}
        }
        return Core.settings.getDataDirectory().child("debug");
    }

    /** Android se detecta de dos formas (propiedades de la JVM y la aplicación de Arc); basta una. */
    static boolean esAndroid(){
        return OS.isAndroid || (Core.app != null && Core.app.isAndroid());
    }

    static String plataforma(){
        return esAndroid() ? "Android" : "PC (" + OS.osName + ")";
    }

    private static void cabecera(){
        escribir("INFO", "=== Modo debug de MindustryOpenWorld ===");
        escribir("INFO", "Archivo: " + ruta);
        escribir("INFO", "Plataforma: " + plataforma() + " | Versión: " + Version.combined());
        escribir("INFO", "SO: " + OS.osName + " | Java: " + OS.javaVersion);
        escribir("INFO", "Memoria máxima: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB | Núcleos: " + OS.cores);
        if(esAndroid() && Core.app != null) escribir("INFO", "Android API: " + Core.app.getVersion());
    }

    /** Encadena el logger actual: primero lo que ya había, luego este archivo. */
    private static void instalarLogger(){
        final Log.LogHandler previo = Log.logger;
        miLogger = (nivel, texto) -> {
            try{ previo.log(nivel, texto); }catch(Throwable ignored){}
            escribir(nivel.name().toUpperCase(), Log.removeColors(texto));
        };
        Log.logger = miLogger;
    }

    /** Errores no capturados: se escriben antes de dejar que el juego haga su propio informe. */
    private static void instalarManejadorDeErrores(){
        final Thread.UncaughtExceptionHandler previo = Thread.getDefaultUncaughtExceptionHandler();
        miManejador = (hilo, ex) -> {
            escribir("FATAL", "Excepción no capturada en el hilo \"" + hilo.getName() + "\":\n" + trazas(ex));
            if(previo != null) previo.uncaughtException(hilo, ex);
        };
        Thread.setDefaultUncaughtExceptionHandler(miManejador);
    }

    private static void iniciarLatido(){
        final Thread t = new Thread(() -> {
            while(true){
                try{ Thread.sleep(LATIDO_MS); }catch(InterruptedException e){ return; }
                final Runtime rt = Runtime.getRuntime();
                escribir("INFO", "Latido: memoria usada " + ((rt.totalMemory() - rt.freeMemory()) / 1024 / 1024)
                    + " MB de " + (rt.maxMemory() / 1024 / 1024) + " MB");
            }
        }, "ModoDebug-latido");
        t.setDaemon(true);
        t.start();
    }

    /** Escribe una línea y la vuelca al disco de inmediato (así sobrevive a un crash). */
    private static void escribir(String nivel, String texto){
        synchronized(CERROJO){
            if(salida == null) return;
            try{
                salida.write("[" + HORA.format(new Date()) + "][" + nivel + "] " + texto + "\n");
                salida.flush();
            }catch(IOException | RuntimeException ignored){
                // sin escritura no hay a dónde avisar; el juego sigue
            }
        }
    }

    private static String trazas(Throwable ex){
        final StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
