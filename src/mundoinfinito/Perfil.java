package mundoinfinito;

import arc.Core;
import arc.util.Log;

/**
 * PERFILADOR DE TIRONES. El log de logcat de Android no dice NADA de lo que pasa dentro del mod (Choreographer solo avisa de
 * tirones de más de ~0,5 s), así que aquí se mide el hilo del juego por secciones y se escribe una línea cuando algo se pasa:
 *
 *   [Perfil] tick 41.2 ms (fotograma 58.0 ms) | bases=33.1 aplicar=4.0 ausencia=2.5 | heap 71 MB
 *
 * Uso (sin asignaciones en régimen):   long t = Perfil.t();  ...trabajo...;  Perfil.fin(Perfil.S_BASES, t);
 * Los eventos de una sola vez (reubicar, guardar, hibernar) se miden con Perfil.evento("nombre", t).
 * Busca "[Perfil]" en el log: el orden de las secciones (de mayor a menor) indica qué causa cada congelamiento.
 */
final class Perfil{
    static final int S_ESCANEO = 0, S_ESTRUCTURAS = 1, S_BASES = 2, S_AUSENCIA = 3, S_MAPA = 4, S_VENTANA = 5, S_APLICAR = 6,
        S_PEDIR = 7, S_GUARDAR = 8, S_REBASE = 9, S_RENDER_ACTUALIZAR = 10, S_RENDER_DIBUJO = 11, S_RESTAURAR = 12;
    private static final String[] NOMBRES = {
        "escaneo", "estructuras", "bases", "ausencia", "minimapa", "ventana", "aplicar", "pedir", "guardar", "rebase",
        "render-act", "render-dibujo", "restaurar"
    };
    private static final int N = NOMBRES.length;

    /** Un tick del mod que tarde más que esto se escribe en el log. */
    static final long UMBRAL_TICK_NS = 8_000_000L;
    /** Un fotograma real más lento que esto (s) también se escribe, aunque el mod no sea el culpable. */
    static final float UMBRAL_FOTOGRAMA_S = 0.045f;
    /** Un evento puntual más lento que esto se escribe siempre. */
    static final long UMBRAL_EVENTO_NS = 4_000_000L;

    static boolean activo = true;

    private static final long[] acum = new long[N];
    private static long ultimoLog;
    private static int cuadros;
    // peor tick de la ventana de ~15 s, para el resumen
    private static long peorNs;
    private static String peorTexto = "";
    private static int tiradas;

    private Perfil(){}

    static long t(){
        return System.nanoTime();
    }

    static void fin(int seccion, long t0){
        acum[seccion] += System.nanoTime() - t0;
    }

    /** Cierra el fotograma del mod: escribe una línea si hubo tirón y reinicia los contadores. Llamar una vez por tick. */
    static void cierre(){
        if(!activo){
            java.util.Arrays.fill(acum, 0L);
            return;
        }
        cuadros++;
        long total = 0L;
        for(int i = 0; i < N; i++) total += acum[i];
        float dt = Core.graphics == null ? 0f : Core.graphics.getDeltaTime();
        boolean lento = total >= UMBRAL_TICK_NS || dt >= UMBRAL_FOTOGRAMA_S;

        if(lento || total > peorNs){
            String texto = descripcion(total, dt);
            if(total > peorNs){
                peorNs = total;
                peorTexto = texto;
            }
            long ahora = System.nanoTime();
            // máximo ~2 líneas por segundo, salvo tirones grandes (>25 ms), que se escriben siempre
            if(lento && (total >= 25_000_000L || ahora - ultimoLog > 500_000_000L)){
                ultimoLog = ahora;
                tiradas++;
                Log.info("[Perfil] @", texto);
            }
        }
        if(cuadros >= 900){
            Log.info("[Perfil] resumen 15 s: peor tick del mod = @ | tirones escritos = @", peorTexto.isEmpty() ? "ninguno" : peorTexto, tiradas);
            cuadros = 0;
            peorNs = 0L;
            peorTexto = "";
            tiradas = 0;
        }
        java.util.Arrays.fill(acum, 0L);
    }

    private static String descripcion(long total, float dt){
        StringBuilder sb = new StringBuilder(160);
        sb.append("tick ").append(ms(total)).append(" ms (fotograma ").append(ms((long)(dt * 1_000_000_000f))).append(" ms) |");
        // secciones de mayor a menor, solo las que pesan algo
        boolean[] usada = new boolean[N];
        for(int k = 0; k < 4; k++){
            int mejor = -1;
            for(int i = 0; i < N; i++) if(!usada[i] && acum[i] >= 500_000L && (mejor < 0 || acum[i] > acum[mejor])) mejor = i;
            if(mejor < 0) break;
            usada[mejor] = true;
            sb.append(' ').append(NOMBRES[mejor]).append('=').append(ms(acum[mejor]));
        }
        Runtime rt = Runtime.getRuntime();
        sb.append(" | heap ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" MB");
        sb.append(" | listos=").append(MundoInfinitoMod.Streamer.listos.size());
        sb.append(" solicitados=").append(MundoInfinitoMod.Streamer.solicitados.size);
        return sb.toString();
    }

    private static String ms(long ns){
        long d = ns / 100_000L;   // décimas de ms
        return (d / 10) + "." + (d % 10);
    }

    /** Evento puntual (hibernar una base, reubicar la ventana, guardar...). Se escribe si tardó más que el umbral. */
    static void evento(String nombre, long t0){
        if(!activo) return;
        long dt = System.nanoTime() - t0;
        if(dt >= UMBRAL_EVENTO_NS) Log.info("[Perfil] evento @: @ ms", nombre, ms(dt));
    }
}
