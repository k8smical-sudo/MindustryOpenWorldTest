package mundoinfinito;

import mindustry.Vars;

/**
 * ARRANQUE DEL RENDER POR CHUNKS (sustituye al antiguo "reparador de suelo").
 *
 * El antiguo Suelo.sanear() llamaba por reflexión a FloorRenderer.clearTiles(), que NO existe en esta versión del juego
 * (el log lo confirma: NoSuchMethodException). Además ya no hace falta: el suelo vanilla queda apagado y el terreno lo dibuja
 * ChunkMeshRenderer.
 *
 * MundoInfinitoMod ya llama a vigilarTirones() en cada tick del mundo del mod (hilo principal, con contexto GL), así que se usa
 * como punto de arranque: iniciar() de ambas clases es idempotente. No hace falta tocar MundoInfinitoMod.java para engancharlas.
 */
final class Suelo{
    private Suelo(){}

    /** Ya no hay nada que reparar: el suelo vanilla está apagado. Se conserva la firma para no tocar a los llamadores. */
    static void sanear(){}

    /** Cada tick: garantiza que el renderizador por chunks y el manejador de cámara estén iniciados (solo actúa la primera vez). */
    static void vigilarTirones(){
        if(Vars.headless) return;
        ChunkMeshRenderer.iniciar();
        InfiniteCameraHandler.iniciar();
    }
}
