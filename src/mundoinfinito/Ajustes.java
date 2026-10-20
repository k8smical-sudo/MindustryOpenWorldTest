package mundoinfinito;

import arc.Core;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;

/**
 * AJUSTES del jugador (Ajustes -> OpenWorld), leídos EN VIVO desde Core.settings:
 *
 *   distancia de carga       radio (en chunks, REDONDO) que se mantiene cargado alrededor del jugador
 *   distancia de simulación  radio (REDONDO) dentro del cual viven las unidades; más lejos duermen (ver Entidades)
 *   niebla de guerra         activada = lo no explorado se oculta; desactivada = se ve todo lo cargado (como "fogofwar" en Mindustry v4)
 *   visión del dron          radio (en chunks) que el dron del jugador revela a su alrededor (0 = automático: la distancia de carga)
 *
 * El resto (hilos, caché, presupuesto por frame, reubicación circular/anticipada, modo estricto) ya no es configurable: los
 * métodos se conservan con valores fijos para que el resto del mod compile sin tocarlo, y el render por chunks no los necesita.
 */
final class Ajustes{
    static final String K_CARGA = "ow-carga", K_SIM = "ow-sim", K_NIEBLA = "ow-niebla", K_VISION = "ow-vision";
    /** Con visión automática el dron nunca revela más de esto (la niebla se actualiza por cada casilla que se mueve el dron). */
    static final int VISION_AUTO_MAX = 12;
    /** Menor distancia de chunks permitida (antes 3): con 1 solo se carga el chunk del jugador y sus vecinos inmediatos. */
    static final int MIN_CARGA = 1;

    private Ajustes(){}

    /**
     * Mayor radio útil: el anillo tiene 32 chunks por lado y los chunks cargados (radio + 1 de reserva + histéresis de descarga)
     * no pueden pasar de ese lado o dos chunks se disputarían el mismo hueco: 32 / 2 - 4 = 12.
     */
    static int maxCarga(){
        return Math.max(4, MundoInfinitoMod.Streamer.ANILLO_CHUNKS / 2 - 4);
    }

    /**
     * En móvil se aplican ~120 chunks reales (radio 6) en vez de ~290 (radio 8): cada chunk son 1024 casillas escritas en el hilo
     * del juego. Lo que se VE más allá lo dibuja el render por chunks desde la caché (no necesita casillas), como el rango de
     * envío de Luanti frente a su rango de bloques activos. Quien ya tenga el ajuste guardado conserva su valor.
     */
    static int cargaPorDefecto(){
        return Vars.mobile ? 6 : 8;
    }

    static int simPorDefecto(){
        return Vars.mobile ? 4 : 6;
    }

    static int carga(){
        return Mathf.clamp(Core.settings.getInt(K_CARGA, Math.min(cargaPorDefecto(), maxCarga())), MIN_CARGA, maxCarga());
    }

    /** Nunca mayor que la carga: no se puede simular lo que no está cargado. */
    static int sim(){
        return Mathf.clamp(Core.settings.getInt(K_SIM, simPorDefecto()), MIN_CARGA, carga());
    }

    /** ¿Niebla de guerra activa? Por defecto sí (igual que el ajuste "fogofwar" de Mindustry v4). */
    static boolean niebla(){
        return Core.settings.getBool(K_NIEBLA, true);
    }

    /**
     * Radio (en chunks) que revela el dron del jugador. 0 en el ajuste = automático: sigue a la distancia de carga (con tope).
     * Nunca mayor que la carga: más allá no hay terreno aplicado que revelar (misma regla que la distancia de simulación).
     */
    static int visionChunks(){
        int v = Core.settings.getInt(K_VISION, 0);
        int max = carga();
        if(v <= 0) return Mathf.clamp(max, 1, VISION_AUTO_MAX);
        return Mathf.clamp(v, 1, max);
    }

    /** Lo mismo en tiles (lo que espera UnitType.fogRadius). */
    static float visionTiles(){
        return visionChunks() * (float)MundoInfinitoMod.TAM_CHUNK;
    }

    // ---- valores fijos (antes eran ajustes) ----
    static int maxHilos(){
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    static int hilos(){
        // "ow-hilos" (o -Dmow.hilos) gobierna todos los hilos del mod: 1 = ruta de un solo hilo, n = n hilos, 0 = automático
        return Hilos.hilosTerreno(Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 2)));
    }

    /** El render por chunks lee de esta caché todo lo que se ve fuera de la ventana: más holgura que antes (24 MB). */
    static int cacheMB(){
        return 48;
    }

    /** 0,9 ms por frame para aplicar terreno al juego (el valor original del mod). */
    static long presupuestoNs(){
        return 900_000L;
    }

    static boolean rebaseCircular(){
        return true;
    }

    static boolean estricto(){
        return true;
    }

    static boolean anticipar(){
        return true;
    }

    /** Radio (en chunks) que el minimapa va fotografiando alrededor del jugador: sigue a la distancia de carga. */
    static int rMapa(){
        return Mathf.clamp(carga(), 1, 12);
    }

    /** ¿(dx, dy) está dentro del círculo de radio r? (un poco redondeado hacia fuera para que el borde no tenga muescas) */
    static boolean dentro(int dx, int dy, int r){
        return dx * dx + dy * dy <= r * r + r;
    }

    // ------------------------------------------------------------------ interfaz
    private static boolean registrado;

    static void registrar(){
        if(registrado || Vars.headless || Vars.ui == null || Vars.ui.settings == null) return;
        registrado = true;
        try{
            var p = Core.bundle.getProperties();
            p.put("setting." + K_CARGA + ".name", "Distancia de chunks (carga)");
            p.put("setting." + K_SIM + ".name", "Distancia de simulación (chunks)");
            p.put("setting." + K_NIEBLA + ".name", "Niebla de guerra (ocultar lo no explorado)");
            p.put("setting." + K_VISION + ".name", "Visión del dron (chunks que revela)");
            Vars.ui.settings.addCategory("OpenWorld", Icon.map, t -> {
                t.sliderPref(K_CARGA, Math.min(cargaPorDefecto(), maxCarga()), MIN_CARGA, maxCarga(), v -> v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles, redondo)");
                t.sliderPref(K_SIM, simPorDefecto(), MIN_CARGA, maxCarga(), v -> v + " chunks (redondo)");
                t.checkPref(K_NIEBLA, true);
                t.sliderPref(K_VISION, 0, 0, maxCarga(), v -> v <= 0 ? "Automática (según la distancia de carga)" : v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles)");
            });
        }catch(Throwable e){
            Log.err("[MundoInfinito] No se pudo crear la sección OpenWorld en Ajustes", e);
        }
    }
}
