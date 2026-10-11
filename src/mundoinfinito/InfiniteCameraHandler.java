package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.input.GestureDetector;
import arc.math.Mathf;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * MANEJADOR DE CÁMARA INFINITA.
 *
 * 1. BYPASS DEL CLAMPING. En Android, MobileInput.pan() recorta Core.camera.position a [-w/4, unitWidth + w/4] (las dimensiones
 *    de la ventana de tiles de Mindustry): esa es la "pared invisible". No se puede quitar ese recorte (está dentro del método
 *    del juego), así que se DESHACE: un GestureDetector propio recibe los mismos deltas de pan que el juego; en Trigger.preDraw
 *    (antes de camera.update()) se calcula dónde habría quedado la cámara sin recorte y, SOLO en el eje donde el juego la
 *    recortó, se le devuelve esa posición. La posición previa se guarda en coordenadas VIRTUALES, así una reubicación de la
 *    ventana (que desplaza la cámara) no falsea el cálculo. En escritorio no hay recorte: esto no hace nada.
 *
 * 2. ALCANCE PREDICTIVO. Con la velocidad del jugador (Motor.vx/vy) o la de la propia cámara (la mayor), se calcula hacia dónde
 *    va la vista y cuánto adelantar (hasta ADELANTE_MAX chunks, ~HORIZONTE_S segundos de viaje):
 *      - predX0..predY1: rectángulo de mundo ampliado de forma ASIMÉTRICA hacia la marcha; ChunkMeshRenderer precarga ahí el
 *        terreno (a la caché) antes de que la cámara llegue.
 *      - centroX/centroY/radio: lo que Streamer.escanear() entrega al Motor (staging de la grilla): el disco de la cámara se
 *        desplaza media distancia hacia delante y crece lo mismo, así cubre la vista actual y se alarga solo hacia delante.
 */
final class InfiniteCameraHandler{
    static final float HORIZONTE_S = 4f;        // segundos de viaje que se intenta tener ya cargados
    static final int ADELANTE_MAX = 6;          // chunks máximos de adelanto
    static final float UMBRAL = 0.15f;          // chunks/s por debajo de los cuales se considera quieto
    /**
     * false (por defecto): el adelanto solo alimenta la PRECARGA de terreno del renderizador. true: además amplía el centro y el
     * radio que usa Streamer.escanear(). Se dejó apagado porque Streamer.rVista también lo usan la reubicación de la ventana, las
     * entidades y Ventana.avanzar: inflarlo hace más caro justo el momento en que más importa que no haya tirones.
     */
    static final boolean AMPLIAR_STREAMER = false;

    // rectángulo predictivo en unidades de mundo (ventana local); ancho 0 = sin datos
    static float predX0, predY0, predX1, predY1;
    static float dirX, dirY;                    // dirección de marcha (unitaria)
    static int adelante;                        // chunks de adelanto actuales

    private static boolean iniciado, tienePrev;
    private static double prevVX, prevVY;       // posición de la cámara en el fotograma anterior, en tiles VIRTUALES
    private static float panX, panY;            // pan acumulado (píxeles) recibido desde el último fotograma
    private static float camVx, camVy;          // velocidad suavizada de la cámara (tiles/tick)

    private InfiniteCameraHandler(){}

    /** HILO PRINCIPAL. Llamar una vez (ClientLoadEvent). */
    static void iniciar(){
        if(iniciado || Vars.headless) return;
        iniciado = true;
        try{
            // ARC: segundo GestureDetector sobre el mismo input; mismos parámetros que InputHandler. No consume eventos (devuelve false).
            Core.input.addProcessor(new GestureDetector(20, 0.5f, 0.3f, 0.15f, new GestureDetector.GestureListener(){
                @Override
                public boolean pan(float x, float y, float deltaX, float deltaY){
                    panX += deltaX;
                    panY += deltaY;
                    return false;
                }
            }));
            Events.run(Trigger.preDraw, InfiniteCameraHandler::antesDeDibujar);
            Events.run(Trigger.postDraw, InfiniteCameraHandler::despuesDeDibujar);
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo iniciar el manejador de cámara infinita", t);
        }
    }

    // ---------------------------------------------------------------- API para Streamer.escanear()
    static int centroX(int cenX){
        if(!AMPLIAR_STREAMER) return cenX;
        return cenX + Math.round(dirX * adelante * 0.5f);
    }

    static int centroY(int cenY){
        if(!AMPLIAR_STREAMER) return cenY;
        return cenY + Math.round(dirY * adelante * 0.5f);
    }

    /** Radio de vista (chunks) ampliado: junto con el centro adelantado, la vista actual queda cubierta y se alarga hacia delante. */
    static int radio(int rBase){
        if(!AMPLIAR_STREAMER) return rBase;
        return rBase + (adelante + 1) / 2;
    }

    // ---------------------------------------------------------------- por fotograma
    /** Trigger.preDraw: antes de Renderer.camera.update(), así la corrección llega a la matriz de este mismo fotograma. */
    private static void antesDeDibujar(){
        final arc.graphics.Camera cam = Core.camera;
        if(cam == null || !Streamer.activo || Vars.world == null || Vars.world.tiles == null){
            panX = panY = 0f;
            return;
        }
        if(Float.isNaN(cam.position.x) || Float.isNaN(cam.position.y)){
            panX = panY = 0f;
            return;
        }

        // ---- 1. deshacer el recorte del juego ----
        if((panX != 0f || panY != 0f) && tienePrev){
            final float escala = cam.width / Core.graphics.getWidth();      // igual que MobileInput.pan()
            final float ex = Coord.mundoX(prevVX) - panX * escala;           // dónde habría quedado sin recorte
            final float ey = Coord.mundoY(prevVY) - panY * escala;
            final float minX = -cam.width / 4f, maxX = Vars.world.unitWidth() + cam.width / 4f;
            final float minY = -cam.height / 4f, maxY = Vars.world.unitHeight() + cam.height / 4f;
            // solo si el juego recortó ese eje (la cámara quedó exactamente en el límite y lo esperado cae fuera)
            if((ex < minX || ex > maxX) && Math.abs(cam.position.x - Mathf.clamp(ex, minX, maxX)) < 0.05f) cam.position.x = ex;
            if((ey < minY || ey > maxY) && Math.abs(cam.position.y - Mathf.clamp(ey, minY, maxY)) < 0.05f) cam.position.y = ey;
        }
        panX = panY = 0f;

        // ---- 2. predicción: la mayor entre la velocidad del jugador y la de la cámara (tiles/tick) ----
        float vx = camVx, vy = camVy;
        if(Streamer.motor != null){
            float mx = Streamer.motor.vx, my = Streamer.motor.vy;
            if(mx * mx + my * my > vx * vx + vy * vy){ vx = mx; vy = my; }
        }
        final float len = (float)Math.hypot(vx, vy);
        final float chunksPorSeg = len * 60f / Coord.CHUNK;
        if(chunksPorSeg > UMBRAL){
            dirX = vx / len;
            dirY = vy / len;
            adelante = Math.min(ADELANTE_MAX, Math.round(chunksPorSeg * HORIZONTE_S));
        }else{
            dirX = dirY = 0f;
            adelante = 0;
        }
        final float hw = cam.width * 0.5f, hh = cam.height * 0.5f, ext = adelante * Coord.CHUNK * 8f, margen = Coord.CHUNK * 8f;
        predX0 = cam.position.x - hw - margen;
        predX1 = cam.position.x + hw + margen;
        predY0 = cam.position.y - hh - margen;
        predY1 = cam.position.y + hh + margen;
        if(dirX > 0f) predX1 += dirX * ext; else predX0 += dirX * ext;      // asimétrico: solo crece hacia donde va
        if(dirY > 0f) predY1 += dirY * ext; else predY0 += dirY * ext;
    }

    /** Trigger.postDraw: recuerda dónde terminó la cámara (en coordenadas virtuales) y mide su velocidad. */
    private static void despuesDeDibujar(){
        final arc.graphics.Camera cam = Core.camera;
        if(cam == null || !Streamer.activo){
            tienePrev = false;
            camVx = camVy = 0f;
            return;
        }
        final double vx = Coord.virtualX(cam.position.x), vy = Coord.virtualY(cam.position.y);
        if(tienePrev){
            final float dt = Math.max(0.01f, Time.delta);
            final float dx = (float)(vx - prevVX), dy = (float)(vy - prevVY);
            if(Math.hypot(dx, dy) > 96f){
                camVx = camVy = 0f;                     // salto (teletransporte, viaje): no es velocidad
            }else{
                final float a = 1f - (float)Math.exp(-dt / 20f);
                camVx += (dx / dt - camVx) * a;
                camVy += (dy / dt - camVy) * a;
            }
        }
        prevVX = vx;
        prevVY = vy;
        tienePrev = true;
    }
}
