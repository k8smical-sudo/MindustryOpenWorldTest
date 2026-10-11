package mundoinfinito;

import arc.Core;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;

/**
 * AJUSTES del jugador (Ajustes -> OpenWorld). Solo quedan dos, leídos EN VIVO desde Core.settings:
 *
 *   distancia de carga       radio (en chunks, REDONDO) que se mantiene cargado alrededor del jugador
 *   distancia de simulación  radio (REDONDO) dentro del cual viven las unidades; más lejos duermen (ver Entidades)
 *
 * El resto (hilos, caché, presupuesto por frame, reubicación circular/anticipada, modo estricto) ya no es configurable: los
 * métodos se conservan con valores fijos para que el resto del mod compile sin tocarlo, y el render por chunks no los necesita.
 */
final class Ajustes{
    static final String K_CARGA = "ow-carga", K_SIM = "ow-sim";

    private Ajustes(){}

    /** Mayor radio útil: la ventana en RAM es finita, más allá de su mitad no hay dónde cargar. */
    static int maxCarga(){
        return Math.max(4, MundoInfinitoMod.ventana() / MundoInfinitoMod.TAM_CHUNK / 2 - 2);
    }

    static int carga(){
        return Mathf.clamp(Core.settings.getInt(K_CARGA, Math.min(8, maxCarga())), 3, maxCarga());
    }

    /** Nunca mayor que la carga: no se puede simular lo que no está cargado. */
    static int sim(){
        return Mathf.clamp(Core.settings.getInt(K_SIM, 6), 2, carga());
    }

    // ---- valores fijos (antes eran ajustes) ----
    static int maxHilos(){
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    static int hilos(){
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
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
        return Mathf.clamp(carga(), 3, 12);
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
            Vars.ui.settings.addCategory("OpenWorld", Icon.map, t -> {
                t.sliderPref(K_CARGA, Math.min(8, maxCarga()), 3, maxCarga(), v -> v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles, redondo)");
                t.sliderPref(K_SIM, 6, 2, maxCarga(), v -> v + " chunks (redondo)");
            });
        }catch(Throwable e){
            Log.err("[MundoInfinito] No se pudo crear la sección OpenWorld en Ajustes", e);
        }
    }
}
