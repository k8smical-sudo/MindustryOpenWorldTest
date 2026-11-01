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
 *   calidad visual           baja, media o alta (solo dibujo: nunca afecta a la simulación ni al mundo) y si la medición de fotogramas puede bajarla sola
 *   prueba visual            dos casillas, apagadas por defecto, para comprobar el shader de prueba y el aislamiento de un shader que falla
 *
 * El resto (hilos, caché, presupuesto por frame, reubicación circular/anticipada, modo estricto) ya no es configurable: los
 * métodos se conservan con valores fijos para que el resto del mod compile sin tocarlo, y el render por chunks no los necesita.
 */
final class Ajustes{
    static final String K_CARGA = "ow-carga", K_SIM = "ow-sim", K_NIEBLA = "ow-niebla", K_VISION = "ow-vision";
    static final String K_CALIDAD = "ow-calidad", K_CALIDAD_AUTO = "ow-calidad-auto", K_PRUEBA = "ow-visual-prueba", K_PRUEBA_FALLA = "ow-visual-prueba-falla", K_PRUEBA_EFECTO = "ow-visual-prueba-efecto",
        K_PRUEBA_AGUA = "ow-visual-prueba-agua", K_PRUEBA_ZOOM = "ow-visual-prueba-zoom";
    static final String K_MAPA_ESCALA = "ow-mapa-escala", K_MAPA_DX = "ow-mapa-dx", K_MAPA_DY = "ow-mapa-dy", K_MAPA_VISIBLE = "ow-mapa-visible";
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

    /** Calidad visual elegida (0 baja, 1 media, 2 alta; por defecto alta). Solo la lee la capa visual. */
    static VisualCalidad calidadVisual(){
        return VisualCalidad.deIndice(Core.settings.getInt(K_CALIDAD, 2));
    }

    /** ¿Puede la medición de fotogramas bajar la calidad visual por sí sola? (por defecto sí) */
    static boolean calidadAuto(){
        return Core.settings.getBool(K_CALIDAD_AUTO, true);
    }

    static boolean pruebaVisual(){
        return Core.settings.getBool(K_PRUEBA, false);
    }

    static boolean pruebaVisualFalla(){
        return Core.settings.getBool(K_PRUEBA_FALLA, false);
    }

    static boolean pruebaEfecto(){
        return Core.settings.getBool(K_PRUEBA_EFECTO, false);
    }

    static boolean pruebaAgua(){
        return Core.settings.getBool(K_PRUEBA_AGUA, false);
    }

    static boolean pruebaZoom(){
        return Core.settings.getBool(K_PRUEBA_ZOOM, false);
    }

    /** Escala del minimapa (1 = 100 %). */
    static float mapaEscala(){
        return Core.settings.getInt(K_MAPA_ESCALA, 100) / 100f;
    }

    static boolean mapaVisible(){
        return Core.settings.getBool(K_MAPA_VISIBLE, true);
    }

    /** Separación del minimapa respecto al borde derecho, en unidades de interfaz. */
    static float mapaDx(){
        return Core.settings.getInt(K_MAPA_DX, 0);
    }

    /** Separación del minimapa respecto al borde superior, en unidades de interfaz. */
    static float mapaDy(){
        return Core.settings.getInt(K_MAPA_DY, 0);
    }

    /** Mueve el minimapa (en unidades de interfaz) y guarda la posición, limitada a un rango razonable. */
    static void moverMapa(float dx, float dy){
        Core.settings.put(K_MAPA_DX, Math.max(0, Math.min(2000, Math.round(mapaDx() + dx))));
        Core.settings.put(K_MAPA_DY, Math.max(0, Math.min(2000, Math.round(mapaDy() + dy))));
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
            p.put("setting." + K_CALIDAD + ".name", "Calidad visual (de momento solo afecta a los efectos y a las pruebas visuales)");
            p.put("setting." + K_CALIDAD_AUTO + ".name", "Bajar la calidad visual sola si hay pocos FPS");
            p.put("setting." + K_PRUEBA + ".name", "Prueba visual: shader de prueba (solo para comprobar)");
            p.put("setting." + K_PRUEBA_FALLA + ".name", "Prueba visual: shader que falla (solo para comprobar)");
            p.put("setting." + K_PRUEBA_EFECTO + ".name", "Prueba visual: efecto y luz ambiental (solo para comprobar)");
            p.put("setting." + K_PRUEBA_AGUA + ".name", "Prueba visual: sustituir el shader del agua (solo para comprobar)");
            p.put("setting." + K_PRUEBA_ZOOM + ".name", "Prueba visual: sin shaders de superficie al alejar el zoom (solo para comprobar)");
            p.put("setting." + K_MAPA_VISIBLE + ".name", "Mostrar el minimapa");
            p.put("setting." + K_MAPA_ESCALA + ".name", "Tamaño del minimapa");
            p.put("setting." + K_MAPA_DX + ".name", "Posición del minimapa (horizontal)");
            p.put("setting." + K_MAPA_DY + ".name", "Posición del minimapa (vertical)");
            Vars.ui.settings.addCategory("OpenWorld", Icon.map, t -> {
                t.sliderPref(K_CARGA, Math.min(cargaPorDefecto(), maxCarga()), MIN_CARGA, maxCarga(), v -> v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles, redondo)");
                t.sliderPref(K_SIM, simPorDefecto(), MIN_CARGA, maxCarga(), v -> v + " chunks (redondo)");
                t.checkPref(K_NIEBLA, true);
                t.sliderPref(K_VISION, 0, 0, maxCarga(), v -> v <= 0 ? "Automática (según la distancia de carga)" : v + " chunks (" + (v * MundoInfinitoMod.TAM_CHUNK) + " tiles)");
                t.sliderPref(K_CALIDAD, 2, 0, 2, v -> VisualCalidad.deIndice(v).nombre);
                t.checkPref(K_CALIDAD_AUTO, true);
                t.checkPref(K_PRUEBA, false);
                t.checkPref(K_PRUEBA_FALLA, false);
                t.checkPref(K_PRUEBA_EFECTO, false);
                t.checkPref(K_PRUEBA_AGUA, false);
                t.checkPref(K_PRUEBA_ZOOM, false);
                t.checkPref(K_MAPA_VISIBLE, true);
                t.sliderPref(K_MAPA_ESCALA, 100, 50, 200, v -> v + " %");
                t.sliderPref(K_MAPA_DX, 0, 0, 2000, v -> v + " u (aleja del borde derecho)");
                t.sliderPref(K_MAPA_DY, 0, 0, 2000, v -> v + " u (aleja del borde superior)");
            });
        }catch(Throwable e){
            Log.err("[MundoInfinito] No se pudo crear la sección OpenWorld en Ajustes", e);
        }
    }
}
