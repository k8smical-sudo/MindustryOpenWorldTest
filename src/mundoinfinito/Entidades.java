package mundoinfinito;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Regiones;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * Unidades aliadas "por chunk", como las entidades de Minecraft: las que quedan lejos del jugador, sobre terreno que aÃºn no
 * existe o fuera de la ventana, DUERMEN: se guardan con su posiciÃ³n virtual, vida, escudo y municiÃ³n, y vuelven al acercarse
 * el jugador. AsÃ­ nunca mueren por salir del mundo cargado, por caer sobre terreno viejo ni por el lÃ­mite de unidades.
 */
final class Entidades{
    /**
     * Radio (en chunks, REDONDO) dentro del cual viven las unidades aliadas: "distancia de simulaciÃ³n" de Ajustes.
     * Se respeta el ajuste del jugador: la vista en pantalla nunca amplÃ­a el radio mÃ¡s allÃ¡ de la distancia de carga
     * (antes rVista+1 podÃ­a forzar un radio mayor que el configurado y el ajuste parecÃ­a ignorado).
     */
    static int radioActivo(){
        return Math.max(Ajustes.sim(), Math.min(Streamer.rVista + 1, Ajustes.carga()));
    }

    static boolean dormible(Unit u){
        return u != null && u.isValid() && u.team == Team.sharded && u.type != null
            && !u.isPlayer() && !u.spawnedByCore && !(u.controller() instanceof Estructuras.Guardia);
    }

    /** Guarda la unidad en el almacÃ©n (en el chunk virtual donde estÃ¡) y la quita del mundo. */
    static void hibernar(Unit u){
        try{
            Estructuras.RegU r = Estructuras.RegU.aliada(u, Coord.origenX(), Coord.origenY());
            int vcx = Math.floorDiv((int)Math.floor(r.vx), MundoInfinitoMod.TAM_CHUNK), vcy = Math.floorDiv((int)Math.floor(r.vy), MundoInfinitoMod.TAM_CHUNK);
            Regiones.chunk(vcx, vcy, true).unidades.add(r);
            u.remove();
        }catch(Throwable t){
            Log.warn("[MundoInfinito] No se pudo dormir una unidad: @", t.toString());
        }
    }

    /** Antes de mover la ventana: todas las aliadas (menos la que controla el jugador) pasan al almacÃ©n. */
    static void hibernarTodas(){
        Seq<Unit> lista = new Seq<>();
        for(Unit u : Groups.unit) if(dormible(u)) lista.add(u);
        for(Unit u : lista) hibernar(u);
    }

    /** Despierta una unidad dormida en su posiciÃ³n virtual exacta, con la vida que tenÃ­a. */
    static boolean despertar(Estructuras.RegU r){
        try{
            UnitType t = Vars.content.unit(r.tipo);
            if(t == null) return true;   // tipo que ya no existe: se descarta
            double lx = r.vx - Coord.origenX(), ly = r.vy - Coord.origenY();
            if(lx < 1 || ly < 1 || lx > Streamer.meta.tam - 1 || ly > Streamer.meta.tam - 1) return false;
            float wx = (float)(lx * 8.0), wy = (float)(ly * 8.0);
            if(!t.flying && !t.allowLegStep){
                // una unidad terrestre que cae sobre una casilla sÃ³lida muere al instante (aunque tenga la vida completa):
                // se busca la casilla libre mÃ¡s cercana; si el terreno aÃºn no estÃ¡ listo, sigue durmiendo
                mindustry.world.Tile ti = Vars.world.tileWorld(wx, wy);
                if(ti == null || ti.solid()){
                    mindustry.world.Tile libre = null;
                    for(int rad = 1; rad <= 8 && libre == null; rad++){
                        for(int dy = -rad; dy <= rad && libre == null; dy++){
                            for(int dx = -rad; dx <= rad; dx++){
                                if(Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                                mindustry.world.Tile c = Vars.world.tile((int)(wx / 8f) + dx, (int)(wy / 8f) + dy);
                                if(c != null && !c.solid()){ libre = c; break; }
                            }
                        }
                    }
                    if(libre == null) return false;
                    wx = libre.worldx();
                    wy = libre.worldy();
                }
            }
            Unit u = t.spawn(Team.get(r.equipo), wx, wy);
            u.health = Math.max(1f, r.vida);
            u.shield = r.escudo;
            u.rotation = r.rot;
            return true;
        }catch(Throwable e){
            Log.warn("[MundoInfinito] No se pudo despertar una unidad @", r.tipo);
            return true;
        }
    }

    /** Despierta las dormidas del chunk virtual (vcx, vcy) si estÃ¡n dentro del radio activo del jugador. */
    static void despertarEnChunk(int vcx, int vcy){
        Regiones.ChunkGuardado c = Regiones.chunk(vcx, vcy, false);
        if(c == null || c.unidades.isEmpty()) return;
        int lcx = Coord.cxLocal(vcx), lcy = Coord.cyLocal(vcy);
        if(!Streamer.enMapa(lcx, lcy) || !Streamer.generados.contains(Streamer.clave(lcx, lcy))) return;
        int pcx = (int)Streamer.jugadorX / MundoInfinitoMod.TAM_CHUNK, pcy = (int)Streamer.jugadorY / MundoInfinitoMod.TAM_CHUNK;
        if(!Ajustes.dentro(lcx - pcx, lcy - pcy, radioActivo())) return;
        boolean cambio = false;
        for(int i = c.unidades.size - 1; i >= 0; i--){
            Estructuras.RegU r = c.unidades.get(i);
            if(r.clase != 1) continue;
            if(despertar(r)){ c.unidades.remove(i); cambio = true; }
        }
        if(cambio) Regiones.chunk(vcx, vcy, true);   // marca la regiÃ³n como modificada
    }

    /** Cada ~0,5 s: duerme las que se alejaron y despierta las que el jugador volviÃ³ a acercar. */
    static void tick(int pcx, int pcy){
        if(Streamer.restaurando || Mundos.rebase != null || Mundos.viajando) return;
        int radio = radioActivo();
        Seq<Unit> dormir = new Seq<>();
        for(Unit u : Groups.unit){
            if(!dormible(u)) continue;
            int cx = Math.floorDiv(u.tileX(), MundoInfinitoMod.TAM_CHUNK), cy = Math.floorDiv(u.tileY(), MundoInfinitoMod.TAM_CHUNK);
            boolean fuera = !Streamer.enMapa(cx, cy) || !Streamer.generados.contains(Streamer.clave(cx, cy));
            if(fuera || !Ajustes.dentro(cx - pcx, cy - pcy, radio + 1)) dormir.add(u);   // +1: histÃ©resis, no parpadea en el borde
        }
        for(Unit u : dormir) hibernar(u);
        int vcx0 = Coord.vcx(0), vcy0 = Coord.vcy(0);
        for(int dy = -radio; dy <= radio; dy++){
            for(int dx = -radio; dx <= radio; dx++){
                int cx = pcx + dx, cy = pcy + dy;
                if(!Ajustes.dentro(dx, dy, radio) || !Streamer.enMapa(cx, cy)) continue;
                despertarEnChunk(vcx0 + cx, vcy0 + cy);
            }
        }
    }
}