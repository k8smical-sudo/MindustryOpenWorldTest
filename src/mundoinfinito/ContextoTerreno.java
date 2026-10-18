package mundoinfinito;

import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;
import mundoinfinito.MundoInfinitoMod.Paleta;

/**
 * Lo único que un generador de terreno puede tocar: las 1024 casillas del chunk que se está construyendo (piso, mena y muro) más el
 * terreno base en solo lectura. TODAS las coordenadas son VIRTUALES (tile = chunk * 32 + 0..31).
 *
 * Contrato del hilo: el contexto pertenece al hilo de fondo que lo recibe y deja de ser válido cuando el generador termina. Usarlo desde
 * otro hilo o guardarlo para después lanza IllegalStateException. No hay acceso a Vars.world, edificios ni unidades: lo que no se puede
 * tocar tampoco se puede alcanzar desde aquí.
 */
public final class ContextoTerreno{
    public static final int TAM = MundoInfinitoMod.TAM_CHUNK;

    /** Dimensión del mundo (-1 antiguo mezclado, 0 Serpulo, 1 Erekir, >= 16 mundo registrado). */
    public final int dimension;
    /** Semilla del mundo tal como la eligió el jugador. */
    public final int semilla;
    /** Semilla propia de este generador, derivada de su id: es la que usan azar() y ruido(). */
    public final int semillaMod;
    /** Chunk virtual que se está construyendo y su casilla de origen (esquina inferior izquierda). */
    public final int vcx, vcy, x0, y0;
    /** Terreno base en solo lectura (cualquier coordenada, también fuera del chunk). */
    public final ContextoFenomeno lectura;

    private final short[] piso, mena, bloque;
    private final Thread hilo = Thread.currentThread();
    private volatile boolean abierto = true;

    ContextoTerreno(short[] piso, short[] mena, short[] bloque, Paleta pal, int semilla, int dimension, int vcx, int vcy, String idGenerador){
        this.piso = piso;
        this.mena = mena;
        this.bloque = bloque;
        this.semilla = semilla;
        this.dimension = dimension;
        this.vcx = vcx;
        this.vcy = vcy;
        this.x0 = vcx * TAM;
        this.y0 = vcy * TAM;
        this.lectura = new ContextoFenomeno(pal, semilla, dimension, idGenerador);
        this.semillaMod = lectura.semillaMod;
    }

    /** Un contexto nuevo para otro generador sobre el MISMO chunk (misma memoria, otra semilla derivada). */
    ContextoTerreno para(String idGenerador, Paleta pal){
        return new ContextoTerreno(piso, mena, bloque, pal, semilla, dimension, vcx, vcy, idGenerador);
    }

    void cerrar(){
        abierto = false;
    }

    private void comprobar(){
        if(!abierto || Thread.currentThread() != hilo){
            throw new IllegalStateException("ContextoTerreno usado fuera del generador o desde otro hilo");
        }
    }

    private int indice(int vx, int vy){
        final int lx = vx - x0, ly = vy - y0;
        if(lx < 0 || ly < 0 || lx >= TAM || ly >= TAM) return -1;
        return ly * TAM + lx;
    }

    /** ¿Pertenece la casilla virtual (vx, vy) al chunk que se está construyendo? */
    public boolean contiene(int vx, int vy){
        return indice(vx, vy) >= 0;
    }

    // ------------------------------------------------------------------ lectura (null si la casilla es de otro chunk)
    public Block piso(int vx, int vy){
        comprobar();
        final int i = indice(vx, vy);
        return i < 0 ? null : mindustry.Vars.content.block(piso[i]);
    }

    /** Mena (overlay) de la casilla; {@code Blocks.air} si no hay. */
    public Block mena(int vx, int vy){
        comprobar();
        final int i = indice(vx, vy);
        return i < 0 ? null : (mena[i] == 0 ? Blocks.air : mindustry.Vars.content.block(mena[i]));
    }

    /** Muro o prop de la casilla; {@code Blocks.air} si no hay. */
    public Block bloque(int vx, int vy){
        comprobar();
        final int i = indice(vx, vy);
        return i < 0 ? null : (bloque[i] == 0 ? Blocks.air : mindustry.Vars.content.block(bloque[i]));
    }

    /** ¿Hay un bloque sólido (muro) en la casilla? Las casillas de otro chunk cuentan como false. */
    public boolean esMuro(int vx, int vy){
        final Block b = bloque(vx, vy);
        return b != null && b != Blocks.air && b.solid;
    }

    // ------------------------------------------------------------------ escritura (false si la casilla es de otro chunk)
    public boolean ponerPiso(int vx, int vy, Block suelo){
        comprobar();
        if(!(suelo instanceof Floor)) throw new IllegalArgumentException("ponerPiso necesita un Floor, no " + suelo);
        final int i = indice(vx, vy);
        if(i < 0) return false;
        piso[i] = (short)suelo.id;
        return true;
    }

    /** null o Blocks.air quita la mena. La mena debe ser un overlay (Floor). */
    public boolean ponerMena(int vx, int vy, Block overlay){
        comprobar();
        if(overlay != null && overlay != Blocks.air && !(overlay instanceof Floor)) throw new IllegalArgumentException("ponerMena necesita un overlay (Floor), no " + overlay);
        final int i = indice(vx, vy);
        if(i < 0) return false;
        mena[i] = (short)(overlay == null || overlay == Blocks.air ? 0 : overlay.id);
        return true;
    }

    /** null o Blocks.air quita el muro o prop. */
    public boolean ponerBloque(int vx, int vy, Block muro){
        comprobar();
        final int i = indice(vx, vy);
        if(i < 0) return false;
        bloque[i] = (short)(muro == null || muro == Blocks.air ? 0 : muro.id);
        return true;
    }

    // ------------------------------------------------------------------ aleatoriedad (atajos al contexto de lectura)
    public float azar(int x, int y, int capa){
        return lectura.azar(x, y, capa);
    }

    public float ruido(int capa, float escala, int octavas, double x, double y){
        return lectura.ruido(capa, escala, octavas, x, y);
    }
}
