package mundoinfinito;

import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * VISTA DEL MUNDO = lo que "ve" el JUGADOR y sus UNIDADES ALIADAS, nunca la cámara.
 *
 * La región viva es la UNIÓN de un DISCO REDONDO de radio Ajustes.carga() chunks alrededor del jugador y un disco pequeño
 * (R_UNIDAD chunks) alrededor de cada unidad aliada. Motor (qué cargar), ChunkMeshRenderer (qué dibujar) y Entidades la consultan.
 *
 * Anti-parpadeo:
 *  - El chunk del jugador se lee EN VIVO de su unidad (no de Streamer.jugadorX, que solo se refresca cada ~0,5 s): al reubicar
 *    la ventana las coordenadas locales cambian de golpe y con la posición vieja todo el disco quedaba desplazado = chunks que
 *    desaparecían y se recargaban.
 *  - Las unidades se guardan en chunks VIRTUALES (no cambian al reubicar la ventana).
 *  - Si hay más unidades que MAX se conservan las MÁS CERCANAS al jugador (antes: las primeras de Groups.unit, un conjunto
 *    que cambiaba al azar de un escaneo a otro).
 */
final class Vista{
    static final int MAX = 48;
    /** Radio (en chunks, redondo) que mantiene cargado cada unidad aliada alrededor de sí misma. */
    static final int R_UNIDAD = 2;

    /** Chunks VIRTUALES de las unidades aliadas lejos del jugador. Solo hilo del juego. */
    static final int[] uvx = new int[MAX], uvy = new int[MAX];
    private static final long[] dist = new long[MAX];
    static int n;

    private Vista(){}

    static void limpiar(){
        n = 0;
    }

    /** Chunk LOCAL del jugador, en vivo. */
    static int pcx(){
        final Unit u = Vars.player == null ? null : Vars.player.unit();
        if(u != null && !Vars.player.dead()) return Math.floorDiv(u.tileX(), MundoInfinitoMod.TAM_CHUNK);
        return Math.floorDiv((int)Streamer.jugadorX, MundoInfinitoMod.TAM_CHUNK);
    }

    static int pcy(){
        final Unit u = Vars.player == null ? null : Vars.player.unit();
        if(u != null && !Vars.player.dead()) return Math.floorDiv(u.tileY(), MundoInfinitoMod.TAM_CHUNK);
        return Math.floorDiv((int)Streamer.jugadorY, MundoInfinitoMod.TAM_CHUNK);
    }

    /** Hilo del juego (cada escaneo, ~0,5 s): recoge dónde están las unidades aliadas. */
    static void actualizar(int pcx, int pcy){
        n = 0;
        if(Vars.state == null || Streamer.meta == null) return;
        final int tam = MundoInfinitoMod.TAM_CHUNK, carga = Ajustes.carga();
        for(Unit u : Groups.unit){
            if(u == null || !u.isValid() || u.team != Team.sharded || u.isPlayer()) continue;
            final int cx = Math.floorDiv(u.tileX(), tam), cy = Math.floorDiv(u.tileY(), tam);
            if(!Streamer.enMapa(cx, cy)) continue;
            if(Ajustes.dentro(cx - pcx, cy - pcy, carga - R_UNIDAD)) continue;   // ya cubierta con holgura por el disco del jugador
            final int vx = Coord.vcx(cx), vy = Coord.vcy(cy);
            boolean repetida = false;
            for(int i = 0; i < n && !repetida; i++) repetida = uvx[i] == vx && uvy[i] == vy;
            if(repetida) continue;
            final long d = (long)(cx - pcx) * (cx - pcx) + (long)(cy - pcy) * (cy - pcy);
            int idx;
            if(n < MAX){
                idx = n++;
            }else{
                // lleno: sustituye a la más lejana solo si esta es más cercana
                idx = 0;
                for(int i = 1; i < n; i++) if(dist[i] > dist[idx]) idx = i;
                if(dist[idx] <= d) continue;
            }
            uvx[idx] = vx;
            uvy[idx] = vy;
            dist[idx] = d;
        }
    }

    /** ¿El chunk LOCAL (lx, ly) está dentro de la vista? 'margen' ensancha los discos (anillo de cocinado/precarga). */
    static boolean dentro(int lx, int ly, int pcx, int pcy, int radioJugador, int margen){
        if(Ajustes.dentro(lx - pcx, ly - pcy, radioJugador + margen)) return true;
        if(n == 0) return false;
        final int vx = Coord.vcx(lx), vy = Coord.vcy(ly), ru = R_UNIDAD + margen;
        for(int i = 0; i < n; i++){
            if(Ajustes.dentro(vx - uvx[i], vy - uvy[i], ru)) return true;
        }
        return false;
    }
}
