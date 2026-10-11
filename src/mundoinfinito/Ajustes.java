package mundoinfinito;

import arc.Core;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;

/**
 * AJUSTES del jugador (Ajustes -> OpenWorld). Todo se lee EN VIVO desde Core.settings: cambiar un valor surte efecto en
 * el siguiente escaneo, sin reiniciar el mundo.
 *
 *   distancia de carga       radio (en chunks, REDONDO) que se mantiene cargado alrededor del jugador
 *   distancia de simulación  radio (REDONDO) dentro del cual viven las unidades; más lejos duermen (ver Entidades)
 *   hilos de generación      cuántos hilos calculan terreno a la vez
 *   caché de chunks          MB de RAM para no recalcular terreno que ya se generó
 *   presupuesto por frame    tiempo del hilo principal que se puede gastar aplicando terreno al juego
 *   reubicación circular     al mover la ventana, solo se reubica un círculo alrededor del jugador (no toda la ventana)
 *   modo estricto            las distancias de carga y simulación son LÍMITES de verdad (lo que se ve en pantalla no las salta)
 *   reubicación anticipada   la ventana se prepara antes de llegar al borde, en trocitos, para que no haya tirones
 */
final class Ajustes{
    static final String K_CARGA = "ow-carga", K_SIM = "ow-sim", K_HILOS = "ow-hilos", K_CACHE = "ow-cache",
        K_PRESUP = "ow-presup", K_CIRCULAR = "ow-rebase-circular", K_ESTRICTO = "ow-estricto", K_ANTICIPAR = "ow-anticipar";

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

    static int maxHilos(){
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    static int hilos(){
        int def = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        return Mathf.clamp(Core.settings.getInt(K_HILOS, def), 1, maxHilos());
    }

    static int cacheMB(){
        return Mathf.clamp(Core.settings.getInt(K_CACHE, 24), 8, 256);   // mínimo 8: la reubicación de la ventana lee de la caché
    }

    /** En décimas de milisegundo (9 = 0,9 ms, el valor original del mod). */
    static long presupuestoNs(){
        return Mathf.clamp(Core.settings.getInt(K_PRESUP, 9), 2, 60) * 100_000L;
    }

    static boolean rebaseCircular(){
        return Core.settings.getBool(K_CIRCULAR, true);
    }

    /** true = la distancia de carga y la de simulación se respetan siempre (también con zoom alejado). */
    static boolean estricto(){
        return Core.settings.getBool(K_ESTRICTO, true);
    }

    /** true = la ventana se prepara antes de llegar al borde (sin congelamientos). false = comportamiento antiguo. */
    static boolean anticipar(){
        return Core.settings.getBool(K_ANTICIPAR, true);
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
            p.put("setting." + K_CARGA + ".name", "Distancia de carga (chunks)");
            p.put("setting." + K_SIM + ".name", "Distancia de simulación (chunks)");
            p.put("setting." + K_HILOS + ".name", "Hilos de generación de terreno");
            p.put("setting." + K_CACHE + ".name", "Caché de chunks (MB)");
            p.put("setting." + K_PRESUP + ".name", "Tiempo por frame para aplicar terreno");
            p.put("setting." + K_CIRCULAR + ".name", "Reubicación circular de la ventana");
            p.put("setting." + K_ESTRICTO + ".name", "Respetar distancias estrictamente (si no, lo que se ve siempre se carga)");
            p.put("setting." + K_ANTICIPAR + ".name", "Reubicación anticipada (sin tirones al cruzar el borde)");
            Vars.ui.settings.addCategory("OpenWorld", Icon.map, t -> {
                t.add("[accent]Carga de chunks[]").left().padTop(6f).row();
                t.sliderPref(K_CARGA, Math.min(8, maxCarga()), 3, maxCarga(), v -> v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles, redondo)");
                t.sliderPref(K_HILOS, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), 1, maxHilos(), v -> v + (v == 1 ? " hilo" : " hilos"));
                t.sliderPref(K_CACHE, 24, 8, 256, v -> v + " MB (40 % caliente, 60 % comprimida)");
                t.sliderPref(K_PRESUP, 9, 2, 60, v -> (v / 10f) + " ms");
                t.checkPref(K_CIRCULAR, true);
                t.checkPref(K_ANTICIPAR, true);
                t.checkPref(K_ESTRICTO, true);
                t.add("[accent]Simulación[]").left().padTop(10f).row();
                t.sliderPref(K_SIM, 6, 2, maxCarga(), v -> v + " chunks (redondo)");
                t.add("[accent]Reparación[]").left().padTop(10f).row();
                t.button("Reparar texturas del suelo", Suelo::sanear).left().padTop(6f).row();
            });
        }catch(Throwable e){
            Log.err("[MundoInfinito] No se pudo crear la sección OpenWorld en Ajustes", e);
        }
    }
}
