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
 * Modo debug (ajuste "OpenWorld > Modo debug: guardar logs en Descargas"). Copia TODO el log del juego a un .txt nuevo en cada sesión,
 * línea a línea y sin esperar al cierre, para que un crash deje rastro en el archivo.
 *
 * Carpeta: en Android, Download del almacenamiento compartido; en PC, Downloads o Descargas del usuario. Si no se puede escribir en
 * ninguna (Android 11+ puede denegarlo), usa la carpeta de datos de Mindustry. La ruta final queda escrita en la cabecera del .txt.
 *
 * Es un encadenado: conserva el logger que había (el last_log.txt de Mindustry sigue igual) y los errores no capturados.
 * Se activa al arrancar: cambiar el ajuste requiere reiniciar el juego. Nunca lanza excepciones hacia el juego.
 */
final class ModoDebug{   // NO pública: el corpus exige que toda clase pública sea Api* o de legado
    private static final Object CERROJO = new Object();
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final SimpleDateFormat NOMBRE = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private static boolean activo = false;
    private static Writer salida;
    private static String ruta = "(inactivo)";

    private ModoDebug(){}

    public static boolean activo(){ return activo; }
    public static String ruta(){ return ruta; }

    /** Llamar una vez al cargar el mod (al inicio del constructor). Sin el ajuste activo no hace nada. */
    public static void iniciarSiActivo(){
        if(activo) return;
        try{
            if(Core.settings == null || !Ajustes.debugLogs()) return;

            final Fi carpeta = carpetaDestino();
            carpeta.file().mkdirs();
            final Fi archivo = carpeta.child("mundo-infinito-debug-" + NOMBRE.format(new Date()) + ".txt");
            final Writer w = archivo.writer(false);   // archivo nuevo por sesión: no se sobrescribe ninguno anterior
            synchronized(CERROJO){ salida = w; }
            ruta = archivo.absolutePath();
            cabecera();

            // Encadenado: primero lo que ya había (last_log.txt), luego el archivo de debug.
            final Log.LogHandler previo = Log.logger;
            Log.logger = (nivel, texto) -> {
                try{ previo.log(nivel, texto); }catch(Throwable ignored){}
                escribir(nivel.name().toUpperCase(), Log.removeColors(texto));
            };
            Log.level = Log.LogLevel.debug;   // el modo debug necesita también los mensajes de depuración

            // Errores no capturados (crash): se escriben antes de dejar que el juego haga su propio informe.
            final Thread.UncaughtExceptionHandler previoUE = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((hilo, ex) -> {
                escribir("FATAL", "Excepción no capturada en el hilo \"" + hilo.getName() + "\":\n" + trazas(ex));
                if(previoUE != null) previoUE.uncaughtException(hilo, ex);
            });

            Runtime.getRuntime().addShutdownHook(new Thread(() -> escribir("INFO", "Sesión cerrada (cierre normal o del sistema).")));
            activo = true;
            Log.info("[ModoDebug] Guardando todos los logs en: @", ruta);
        }catch(Throwable e){
            Log.err("[ModoDebug] No se pudo activar: el juego sigue sin guardar logs", e);
        }
    }

    /** Carpeta de Descargas del dispositivo; si no hay ninguna escribible, la carpeta de datos de Mindustry. */
    static Fi carpetaDestino(){
        final List<String> candidatas = new ArrayList<String>();
        if(OS.isAndroid){
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

    private static void cabecera(){
        escribir("INFO", "=== Modo debug de MindustryOpenWorld ===");
        escribir("INFO", "Archivo: " + ruta);
        escribir("INFO", "Versión: " + Version.combined());
        escribir("INFO", "SO: " + OS.osName + (OS.isAndroid ? " (Android)" : "") + " | Java: " + OS.javaVersion);
        escribir("INFO", "Memoria máxima: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB | Núcleos: " + OS.cores);
        if(OS.isAndroid && Core.app != null) escribir("INFO", "Android API: " + Core.app.getVersion());
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
