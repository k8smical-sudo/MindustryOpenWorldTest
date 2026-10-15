package mundoinfinito;

import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * VISTA DEL MUNDO = lo que "ve" el JUGADOR y sus UNIDADES ALIADAS, nunca la cámara.
 *
 * Antes la cámara también pedía terreno (disco de radio = lo que cabía en pantalla, con zoom-out hasta media ventana) y el
 * renderizador conservaba todo lo "explorado": el resultado era un rectángulo (unión de cuadrados fotografiados) en vez del
 * disco que el jugador fija en Ajustes, con la memoria creciendo con el zoom.
 *
 * Ahora la región viva es la UNIÓN de:
 *   - un DISCO REDONDO de radio Ajustes.carga() chunks alrededor del jugador, y
 *   - un disco pequeño (R_UNIDAD chunks) alrededor de cada unidad aliada activa, para que nunca pisen terreno sin cargar.
 * Motor (qué cargar), ChunkMeshRenderer (qué dibujar) y Entidades consultan esta misma definición.
 */
final class Vista{
    static final int MAX = 48;
    /** Radio (en chunks, redondo) que mantiene cargado cada unidad aliada alrededor de sí misma. */
    static final int R_UNIDAD = 2;

    /** Chunks LOCALES de las unidades aliadas (sin repetidos, sin los que ya cubre el disco del jugador). Solo hilo del juego. */
    static final int[] ucx = new int[MAX], ucy = new int[MAX];
    static int n;

    private Vista(){}

    static void limpiar(){
        n = 0;
    }

    /** Hilo del juego (cada escaneo, ~0,5 s): recoge dónde están las unidades aliadas. */
    static void actualizar(int pcx, int pcy){
        n = 0;
        if(Vars.state == null || Streamer.meta == null) return;
        final int tam = MundoInfinitoMod.TAM_CHUNK, carga = Ajustes.carga();
        for(Unit u : Groups.unit){
            if(n >= MAX) break;
            if(u == null || !u.isValid() || u.team != Team.sharded || u.isPlayer()) continue;
            final int cx = Math.floorDiv(u.tileX(), tam), cy = Math.floorDiv(u.tileY(), tam);
            if(!Streamer.enMapa(cx, cy)) continue;
            // si el disco del jugador ya la cubre con holgura, no aporta nada
            if(Ajustes.dentro(cx - pcx, cy - pcy, carga - R_UNIDAD)) continue;
            boolean repetida = false;
            for(int i = 0; i < n && !repetida; i++) repetida = ucx[i] == cx && ucy[i] == cy;
            if(repetida) continue;
            ucx[n] = cx;
            ucy[n] = cy;
            n++;
        }
    }

    /** ¿El chunk LOCAL (lx, ly) está dentro de la vista? 'margen' ensancha los discos (anillo de cocinado/precarga). */
    static boolean dentro(int lx, int ly, int pcx, int pcy, int radioJugador, int margen){
        if(Ajustes.dentro(lx - pcx, ly - pcy, radioJugador + margen)) return true;
        final int ru = R_UNIDAD + margen;
        for(int i = 0; i < n; i++){
            if(Ajustes.dentro(lx - ucx[i], ly - ucy[i], ru)) return true;
        }
        return false;
    }
}
