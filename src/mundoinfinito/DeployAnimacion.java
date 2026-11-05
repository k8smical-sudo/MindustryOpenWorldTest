package mundoinfinito;

import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;

/**
 * ANIMACIÓN DE DESPLIEGUE AL CREAR UN MUNDO (plan v9). La primera vez que se crea un mundo —nunca al continuarlo, al cambiar de dimensión o al
 * viajar por un portal— se reproduce la misma animación de aterrizaje del núcleo que usa el modo campaña: desciende con propulsores y humo, la
 * cámara se centra en él y suena su música de aterrizaje.
 *
 * Es la propia secuencia del juego base: se dispara el mismo evento ({@code Trigger.newGame}) que usa el modo campaña al entrar en un sector, así
 * que respeta sola el ajuste del jugador para saltársela ("skipcoreanimation") y usa el núcleo y la música que tenga definidos el mod ({@code
 * SiloInterdimensional} no toca esto: es el núcleo del jugador, no un portal). El mod no reimplementa la animación: la pide prestada.
 *
 * Solo se llama una vez por mundo, justo después de {@code Vars.logic.play()} en la creación (ver {@code Mundos.crearConSemilla} y
 * {@code Mundos.creandoMundoNuevo}).
 */
final class DeployAnimacion{
    private DeployAnimacion(){}

    /** Dispara la animación de despliegue del juego base. Sin interfaz (servidor headless) no hace nada: no hay nada que animar. */
    static void reproducir(){
        if(Vars.headless) return;
        try{
            Events.fire(Trigger.newGame);
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo reproducir la animación de despliegue del núcleo.", t);
        }
    }
}
