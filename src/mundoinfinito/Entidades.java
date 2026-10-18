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
 * Unidades aliadas "por chunk", como las entidades de Minecraft: las que quedan lejos del jugador, sobre terreno que aún no
 * existe o fuera de la ventana, DUERMEN: se guardan con su posición virtual, vida, escudo y munición, y vuelven al acercarse
 * el jugador. Así nunca mueren por salir del mundo cargado, por caer sobre terreno viejo ni por el límite de unidades.
 */
final class Entidades{
    /** Radio (en chunks, REDONDO) dentro del cual viven las unidades aliadas: "distancia de simulación" de Ajustes. Lo que se ve en pantalla nunca duerme. */
    static int radioActivo(){
        // modo estricto: la distancia de simulación manda. Si no, lo que se ve en pantalla nunca duerme.
        return Ajustes.estricto() ? Ajustes.sim() : Math.max(Ajustes.sim(), Streamer.rVista + 1);
    }

    /** 1 = aliada del mundo base, 2 = unidad de un mod cuya PoliticaSueno manda dormir, 0 = no duerme. */
    static int claseSueno(Unit u){
        if(u == null || !u.isValid() || u.type == null || u.isPlayer() || u.spawnedByCore) return 0;
        if(u.team == Team.sharded) return 1;
        return Extensiones.claseSueno(u);
    }

    static boolean dormible(Unit u){
        return claseSueno(u) != 0;
    }

    /** Guarda la unidad en el almacén (en el chunk virtual donde está) y la quita del mundo. */
    static void hibernar(Unit u){
        try{
            Estructuras.RegU r = Estructuras.RegU.dormida(u, Coord.origenX(), Coord.origenY(), claseSueno(u) == 0 ? 1 : claseSueno(u));
            int vcx = Math.floorDiv((int)Math.floor(r.vx), MundoInfinitoMod.TAM_CHUNK), vcy = Math.floorDiv((int)Math.floor(r.vy), MundoInfinitoMod.TAM_CHUNK);
            Regiones.chunk(vcx, vcy, true).unidades.add(r);
            u.remove();
        }catch(Throwable t){
            Log.warn("[MundoInfinito] No se pudo dormir una unidad: @", t.toString());
        }
    }

    /**
     * Duerme las aliadas que están en el chunk LOCAL (cx, cy): se va a reescribir su terreno (reubicación anticipada).
     * @return false si no se pudo (la región de disco de ese chunk aún no está en memoria): se reintenta luego.
     */
    static boolean despejar(int cx, int cy){
        Seq<Unit> lista = null;
        for(Unit u : Groups.unit){
            if(!dormible(u)) continue;
            if(Math.floorDiv(u.tileX(), MundoInfinitoMod.TAM_CHUNK) != cx || Math.floorDiv(u.tileY(), MundoInfinitoMod.TAM_CHUNK) != cy) continue;
            if(lista == null) lista = new Seq<>();
            lista.add(u);
        }
        if(lista == null) return true;
        if(!Regiones.lista(Coord.vcx(cx), Coord.vcy(cy))) return false;
        for(Unit u : lista) hibernar(u);
        return true;
    }

    /**
     * Tras (re)escribir terreno en el chunk LOCAL (cx, cy): toda unidad que no vuela y quedó sobre una casilla que no puede pisar
     * (muro, roca, edificio sólido) se aparta a la casilla libre más cercana. Mindustry mata al instante a la unidad de tierra que
     * está sobre una casilla sólida (UnitComp.update): es lo que hacía "explotar" a las entidades cerca de paredes e instalaciones
     * cuando el terreno nuevo les caía encima. Las que pueden despegar (canBoost) las deja: despegan solas.
     */
    static void rescatar(int cx, int cy){
        final int tam = MundoInfinitoMod.TAM_CHUNK;
        for(Unit u : Groups.unit){
            if(u == null || !u.isValid() || u.type == null || u.isFlying() || u.type.canBoost) continue;
            final int tx = u.tileX(), ty = u.tileY();
            if(Math.floorDiv(tx, tam) != cx || Math.floorDiv(ty, tam) != cy) continue;
            if(u.canPass(tx, ty)) continue;
            mindustry.world.Tile libre = null;
            for(int rad = 1; rad <= 12 && libre == null; rad++){
                for(int dy = -rad; dy <= rad && libre == null; dy++){
                    for(int dx = -rad; dx <= rad; dx++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                        // solo casillas ya escritas (generadas) o de este mismo chunk, y que la unidad pueda pisar
                        final int x = tx + dx, y = ty + dy;
                        if(!Streamer.enMapa(Math.floorDiv(x, tam), Math.floorDiv(y, tam))) continue;
                        mindustry.world.Tile c = Vars.world.tile(x, y);
                        if(c != null && u.canPass(x, y)){ libre = c; break; }
                    }
                }
            }
            if(libre != null){
                u.set(libre.worldx(), libre.worldy());
                u.vel.setZero();
            }
        }
    }

    /** Antes de mover la ventana: todas las aliadas (menos la que controla el jugador) pasan al almacén. */
    static void hibernarTodas(){
        Seq<Unit> lista = new Seq<>();
        for(Unit u : Groups.unit) if(dormible(u)) lista.add(u);
        for(Unit u : lista) hibernar(u);
    }

    /** Despierta una unidad dormida en su posición virtual exacta, con la vida que tenía. */
    static boolean despertar(Estructuras.RegU r){
        try{
            UnitType t = Vars.content.unit(r.tipo);
            if(t == null) return true;   // tipo que ya no existe: se descarta
            double lx = r.vx - Coord.origenX(), ly = r.vy - Coord.origenY();
            // ERROR ANTIGUO: aquí se exigía 1 <= lx, ly <= tam - 1 (la ventana de 1024 casillas de cuando el mundo era una ventana). Con el mundo en
            // anillo las coordenadas son virtuales y negativas o mayores que 1024 son normales: toda unidad dormida fuera de ese cuadrado
            // NUNCA volvía a despertar y se perdía ("entidades que desaparecen"). Ahora basta con que su casilla exista (chunk cargado).
            if(Vars.world.tileWorld((float)(lx * 8.0), (float)(ly * 8.0)) == null) return false;
            float wx = (float)(lx * 8.0), wy = (float)(ly * 8.0);
            if(!t.flying && !t.allowLegStep){
                // una unidad terrestre que cae sobre una casilla sólida muere al instante (aunque tenga la vida completa):
                // se busca la casilla libre más cercana; si el terreno aún no está listo, sigue durmiendo
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
            if(r.clase == 2) OpenWorldAPI.entidadDespertada_(u);
            return true;
        }catch(Throwable e){
            Log.warn("[MundoInfinito] No se pudo despertar una unidad @", r.tipo);
            return true;
        }
    }

    /** Despierta las dormidas del chunk virtual (vcx, vcy) si están dentro del radio activo del jugador. */
    static void despertarEnChunk(int vcx, int vcy){
        // la región de disco debe estar ya en memoria: leerla aquí (hilo del juego) congelaba el juego al cruzar su límite
        if(!Regiones.lista(vcx, vcy)) return;
        Regiones.ChunkGuardado c = Regiones.chunk(vcx, vcy, false);
        if(c == null || c.unidades.isEmpty()) return;
        int lcx = Coord.cxLocal(vcx), lcy = Coord.cyLocal(vcy);
        if(!Streamer.enMapa(lcx, lcy) || !Streamer.generados.contains(Streamer.clave(lcx, lcy))) return;
        int pcx = Streamer.pcx(), pcy = Streamer.pcy();
        if(!Ajustes.dentro(lcx - pcx, lcy - pcy, radioActivo())) return;
        boolean cambio = false;
        for(int i = c.unidades.size - 1; i >= 0; i--){
            Estructuras.RegU r = c.unidades.get(i);
            if(r.clase != 1 && r.clase != 2) continue;
            if(despertar(r)){ c.unidades.remove(i); cambio = true; }
        }
        if(cambio) Regiones.chunk(vcx, vcy, true);   // marca la región como modificada
    }

    /** Cada ~0,5 s: duerme las que se alejaron y despierta las que el jugador volvió a acercar. */
    static void tick(int pcx, int pcy){
        if(Streamer.restaurando || Mundos.viajando) return;
        int radio = radioActivo();
        Seq<Unit> dormir = new Seq<>();
        for(Unit u : Groups.unit){
            if(!dormible(u)) continue;
            int cx = Math.floorDiv(u.tileX(), MundoInfinitoMod.TAM_CHUNK), cy = Math.floorDiv(u.tileY(), MundoInfinitoMod.TAM_CHUNK);
            boolean fuera = !Streamer.enMapa(cx, cy) || !Streamer.generados.contains(Streamer.clave(cx, cy));
            if(fuera || !Ajustes.dentro(cx - pcx, cy - pcy, radio + 1)){   // +1: histéresis, no parpadea en el borde
                if(!Regiones.lista(Coord.vcx(cx), Coord.vcy(cy))) continue; // su región aún se lee del disco: se duerme en el siguiente ciclo
                dormir.add(u);
            }
        }
        for(Unit u : dormir) hibernar(u);
        // lectura anticipada de las regiones que cubre el disco de simulación (así despertar nunca lee disco en este hilo)
        int vcx0 = Coord.vcx(0), vcy0 = Coord.vcy(0);
        int reg = MundoInfinitoMod.REGION_CHUNKS;
        for(int ry = Math.floorDiv(vcy0 + pcy - radio, reg); ry <= Math.floorDiv(vcy0 + pcy + radio, reg); ry++){
            for(int rx = Math.floorDiv(vcx0 + pcx - radio, reg); rx <= Math.floorDiv(vcx0 + pcx + radio, reg); rx++) Regiones.precargar(rx, ry);
        }
        for(int dy = -radio; dy <= radio; dy++){
            for(int dx = -radio; dx <= radio; dx++){
                int cx = pcx + dx, cy = pcy + dy;
                if(!Ajustes.dentro(dx, dy, radio) || !Streamer.enMapa(cx, cy)) continue;
                despertarEnChunk(vcx0 + cx, vcy0 + cy);
            }
        }
    }
}
