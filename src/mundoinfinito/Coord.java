package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * ÚNICA fuente de verdad de las coordenadas. El mundo del mod es infinito y usa coordenadas VIRTUALES (el spawn es (0,0));
 * Mindustry solo conoce una ventana finita con coordenadas LOCALES (0..tam). Toda conversión pasa por aquí: así el mod y el
 * juego nunca discrepan (HUD, minimapa, portales, estructuras, entidades).
 *
 *   virtual = origen de la ventana + local
 *   chunk   = floorDiv(tile, 32)
 */
final class Coord{
    static final int CHUNK = 32;

    private Coord(){}

    // ---- puras (se pueden probar sin el juego) ----
    static int chunkDe(int tile){
        return Math.floorDiv(tile, CHUNK);
    }

    static int chunkDe(double tile){
        return Math.floorDiv((int)Math.floor(tile), CHUNK);
    }

    static int aVirtual(int local, int origen){
        return origen + local;
    }

    static double aVirtual(double local, int origen){
        return origen + local;
    }

    static float aLocal(double virtual, int origen){
        return (float)(virtual - origen);
    }

    /** Clave de un chunk LOCAL dentro de una ventana de n x n chunks. */
    static int clave(int cx, int cy, int n){
        return cy * n + cx;
    }

    /** Clave única de un chunk VIRTUAL (para mapas y archivos). */
    static long claveVirtual(int vcx, int vcy){
        return ((long)vcx << 32) ^ (vcy & 0xffffffffL);
    }

    static int vcxDeClave(long k){
        return (int)(k >> 32);
    }

    static int vcyDeClave(long k){
        return (int)k;
    }

    // ---- con la ventana actual ----
    static int origenX(){
        return Streamer.ox;
    }

    static int origenY(){
        return Streamer.oy;
    }

    /** Posición virtual en tiles de una posición del mundo en unidades de juego (8 por tile). */
    static double virtualX(float mundoX){
        return Streamer.ox + mundoX / 8.0;
    }

    static double virtualY(float mundoY){
        return Streamer.oy + mundoY / 8.0;
    }

    /** Posición del mundo (unidades de juego) de una posición virtual en tiles. */
    static float mundoX(double virtualX){
        return (float)((virtualX - Streamer.ox) * 8.0);
    }

    static float mundoY(double virtualY){
        return (float)((virtualY - Streamer.oy) * 8.0);
    }

    static int localX(double virtualX){
        return (int)Math.floor(virtualX - Streamer.ox);
    }

    static int localY(double virtualY){
        return (int)Math.floor(virtualY - Streamer.oy);
    }

    /** Chunk local -> chunk virtual. */
    static int vcx(int cxLocal){
        return Streamer.ox / CHUNK + cxLocal;
    }

    static int vcy(int cyLocal){
        return Streamer.oy / CHUNK + cyLocal;
    }

    static int cxLocal(int vcx){
        return vcx - Streamer.ox / CHUNK;
    }

    static int cyLocal(int vcy){
        return vcy - Streamer.oy / CHUNK;
    }
}
