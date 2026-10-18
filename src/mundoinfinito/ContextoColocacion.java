package mundoinfinito;

import mindustry.Vars;
import mindustry.game.Team;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mundoinfinito.MundoInfinitoMod.Muestreo;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * Contexto de {@link GeneradorEstructura#colocar}. Corre en el HILO DEL JUEGO: aquí sí se pueden tocar casillas, edificios y unidades.
 * Las coordenadas son VIRTUALES (el mundo del mod usa origen 0: virtual == local).
 */
public final class ContextoColocacion{
    public final String generador;
    public final int dimension, semilla, semillaMod, anclaX, anclaY;

    ContextoColocacion(String generador, int dimension, int semilla, int anclaX, int anclaY){
        this.generador = generador;
        this.dimension = dimension;
        this.semilla = semilla;
        this.anclaX = anclaX;
        this.anclaY = anclaY;
        this.semillaMod = Muestreo.sem(Muestreo.semDim(semilla, dimension), generador.hashCode());
    }

    /** Número en [0, 1] determinista por (x, y, capa) con la semilla del generador. */
    public float azar(int x, int y, int capa){
        return Muestreo.rnd(x, y, Muestreo.sem(semillaMod, capa));
    }

    /** La casilla, o null si su chunk no está cargado. */
    public Tile tile(int vx, int vy){
        return Vars.world.tile(vx - Streamer.ox, vy - Streamer.oy);
    }

    /** ¿Se puede construir encima? (casilla cargada, sin edificio y sin muro). */
    public boolean libre(int vx, int vy){
        final Tile t = tile(vx, vy);
        return t != null && t.build == null && !t.solid() && !t.floor().isLiquid;
    }

    /** Pone un bloque (de cualquier equipo, también nuevo): se guarda y restaura con el chunk. @return false si la casilla no existe. */
    public boolean poner(int vx, int vy, Block bloque, Team equipo, int rotacion){
        final Tile t = tile(vx, vy);
        if(t == null) return false;
        t.setBlock(bloque, equipo, rotacion);
        Visuales.refrescar(t);   // el sprite cacheado del bloque se rehornea (ver Visuales)
        return true;
    }

    /** Crea una unidad en el centro de la casilla. Si su equipo tiene {@link PoliticaSueno}, dormirá fuera de vista. */
    public mindustry.gen.Unit unidad(UnitType tipo, Team equipo, int vx, int vy){
        final Tile t = tile(vx, vy);
        return t == null ? null : tipo.spawn(equipo, t.worldx(), t.worldy());
    }
}
