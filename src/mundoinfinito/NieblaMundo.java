package mundoinfinito;

import mindustry.Vars;
import mindustry.game.Team;

/**
 * GLUE entre la niebla del motor ({@code Vars.fogControl}, formato opaco de 16 longs = 1024 bits por chunk de equipo) y {@link ApiNiebla}. Clase
 * de paquete: los addons pasan siempre por {@link ApiNiebla}, nunca por aquí. Centraliza las llamadas al motor para que haya un solo sitio que las
 * haga (en vez de repetirlas en {@link Anillo} y en la API), y respeta el mismo ajuste de niebla del juego ({@code Vars.state.rules.fog}) que ya
 * usaba el mod.
 */
final class NieblaMundo{
    private NieblaMundo(){}

    private static boolean disponible(){
        return Vars.fogControl != null && Vars.state != null && Vars.state.rules != null && Vars.state.rules.fog;
    }

    /** Bits vivos de un chunk, sin modificar la niebla (se toman y se devuelven tal cual). null si no hay niebla activa en el motor ahora mismo. */
    static long[] leer(int cx, int cy){
        if(!disponible()) return null;
        final long[] b = new long[16];
        Vars.fogControl.takeStaticChunk(Team.sharded, cx, cy, b);
        Vars.fogControl.putStaticChunk(Team.sharded, cx, cy, b);
        return b;
    }

    /** Toma los bits vivos de un chunk (destructivo: el motor los quita de donde estaban) para guardarlos o inspeccionarlos. */
    static long[] tomar(int cx, int cy){
        if(!disponible()) return null;
        final long[] b = new long[16];
        Vars.fogControl.takeStaticChunk(Team.sharded, cx, cy, b);
        return b;
    }

    /** Aplica estos bits a la niebla viva de un chunk. No hace nada sin niebla activa en el motor, o con bits nulos o del tamaño equivocado. */
    static void escribir(int cx, int cy, long[] bits){
        if(!disponible() || bits == null || bits.length != 16) return;
        Vars.fogControl.putStaticChunk(Team.sharded, cx, cy, bits);
    }

    /** Bits guardados (32 ints, formato en disco) a bits del motor (16 longs). */
    static long[] deFormatoGuardado(int[] niebla){
        final long[] b = new long[16];
        for(int i = 0; i < 16; i++) b[i] = (niebla[2 * i] & 0xFFFFFFFFL) | ((long)niebla[2 * i + 1] << 32);
        return b;
    }

    /** Bits del motor (16 longs) a formato guardado (32 ints). null si ningún bit está puesto (nada que guardar). */
    static int[] aFormatoGuardado(long[] bits){
        boolean alguno = false;
        final int[] filas = new int[32];
        for(int i = 0; i < 16; i++){
            filas[2 * i] = (int)bits[i];
            filas[2 * i + 1] = (int)(bits[i] >>> 32);
            if(bits[i] != 0L) alguno = true;
        }
        return alguno ? filas : null;
    }
}
