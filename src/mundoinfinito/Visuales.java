package mundoinfinito;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * SPRITES DE LOS EDIFICIOS "CACHEADOS" DEL RENDERIZADOR VANILLA.
 *
 * Los bloques con {@code drawCached} (puentes de conductos, muros, almacenes...) no se dibujan cada fotograma: su sprite se hornea en una
 * caché por chunk de Mindustry ({@code BlockRenderer.cacheChunk}). La caché solo se actualiza cuando el juego recibe un evento de cambio
 * del tile. Un edificio que el mod pone o restaura sin pasar por ese camino queda sin sprite en la caché (se ve su conexión o su cinta,
 * que no están cacheadas, pero no el cuerpo). Por eso el mod avisa al renderizador después de cada colocación o restauración.
 *
 * Solo hilo del juego. No hace nada en servidores ni sin renderizador.
 */
final class Visuales{
    private Visuales(){}

    private static boolean registrado;

    /** Engancha la colocación del jugador (y de cualquier otro origen que use el flujo normal de construcción). */
    static void registrar(){
        if(registrado) return;
        registrado = true;
        Events.on(BlockBuildEndEvent.class, e -> {
            if(!e.breaking) refrescar(e.tile);
        });
    }

    /** Pide al renderizador que rehornee el sprite de este edificio en la próxima vez que se dibuje su chunk. */
    static void refrescar(Tile t){
        if(t == null || t.build == null || Vars.headless || Vars.renderer == null || Vars.renderer.blocks == null) return;
        final Block b = t.block();
        if(!b.drawCached) return;   // los bloques que se dibujan cada fotograma no tienen caché que corregir
        try{
            Vars.renderer.blocks.recacheBuilding(b.buildingCacheLayer, t);
            // fuerza también la rama de "vuelve a ser visible" del renderizador (recache de la propia construcción)
            t.build.wasVisible = false;
            t.build.recache();
        }catch(Throwable ignored){
            // el renderizador vanilla puede estar en un estado transitorio (cambio de mundo): la caché se corrige en el siguiente cambio
        }
    }
}
