package mundoinfinito;

import mindustry.Vars;
import mindustry.world.Block;
import mundoinfinito.MundoInfinitoMod.Muestreo;
import mundoinfinito.MundoInfinitoMod.Paleta;

/**
 * Vista de SOLO LECTURA del terreno base del mundo y fuente de aleatoriedad determinista para un generador. No pertenece a un chunk:
 * se puede consultar cualquier coordenada VIRTUAL (el terreno base es una función pura de la semilla), también fuera del chunk que se
 * está generando. Seguro en hilos de fondo; no toca Vars.world.
 *
 * Solo disponible en las dimensiones del mundo base (-1, 0 y 1). Los mundos registrados no tienen terreno base muestreable: sus
 * generadores trabajan sobre el chunk con {@link ContextoTerreno}.
 */
public final class ContextoFenomeno{
    public final int dimension, semilla, semillaMod;
    final int semDim;
    final Paleta pal;
    private Muestreo.Spawn sp;

    private static final ThreadLocal<float[]> AUX = ThreadLocal.withInitial(() -> new float[14]);
    private static final ThreadLocal<int[]> OUT = ThreadLocal.withInitial(() -> new int[4]);

    ContextoFenomeno(Paleta pal, int semilla, int dimension, String idGenerador){
        this.pal = pal;
        this.semilla = semilla;
        this.dimension = dimension;
        this.semDim = Muestreo.semDim(semilla, dimension);
        this.semillaMod = Muestreo.sem(semDim, idGenerador.hashCode());
    }

    // ------------------------------------------------------------------ aleatoriedad (semilla propia del generador)
    /** Número en [0, 1] que solo depende de (x, y, capa) y de la semilla del generador. */
    public float azar(int x, int y, int capa){
        return Muestreo.rnd(x, y, Muestreo.sem(semillaMod, capa));
    }

    /** Hash entero de (x, y, capa) con la semilla del generador (para rangos o desempates). */
    public int hash(int x, int y, int capa){
        return Muestreo.hash(x, y, Muestreo.sem(semillaMod, capa));
    }

    /** Ruido fBm en [0, 1]. {@code escala} = tamaño de la mancha principal en casillas. Cada capa es independiente. */
    public float ruido(int capa, float escala, int octavas, double x, double y){
        return Muestreo.fbm(Muestreo.sem(semillaMod, capa), escala, octavas, x, y);
    }

    // ------------------------------------------------------------------ terreno base
    private void comprobarBase(){
        if(dimension > 1) throw new IllegalStateException("El terreno base solo se puede muestrear en las dimensiones 0 y 1 (y -1, mundos antiguos)");
    }

    private Muestreo.Spawn spawn(){
        Muestreo.Spawn s = sp;
        if(s == null) sp = s = Muestreo.spawn(semDim, dimension);
        return s;
    }

    /** Muestrea el terreno base. Devuelve un búfer del hilo: copiar los valores antes de volver a llamar. [0]=piso [1]=mena [2]=muro [3]=prop */
    int[] muestra(int vx, int vy){
        comprobarBase();
        final float[] aux = AUX.get();
        final int[] out = OUT.get();
        aux[11] = 0f;
        Muestreo.muestrear(semDim, vx, vy, spawn(), aux, out);
        return out;
    }

    /** Código interno del piso base (constantes Muestreo.P_*). */
    int codigoPiso(int vx, int vy){
        return muestra(vx, vy)[0];
    }

    /** Piso del terreno base en (vx, vy), tal como saldría sin generadores añadidos. */
    public Block pisoBase(int vx, int vy){
        return Vars.content.block(pal.idPiso[muestra(vx, vy)[0]]);
    }

    /** ¿Hay un muro sólido en el terreno base? */
    public boolean muroBase(int vx, int vy){
        return muestra(vx, vy)[2] != 0;
    }

    /** ¿Es líquido el piso del terreno base? */
    public boolean liquidoBase(int vx, int vy){
        return Muestreo.liquido(muestra(vx, vy)[0]);
    }

    /** ¿Pertenece (vx, vy) a una zona de tipo Erekir? (siempre true en la dimensión 1 y false en la 0; varía solo en mundos antiguos). */
    public boolean esTipoErekir(int vx, int vy){
        comprobarBase();
        return Muestreo.esE(semDim, vx, vy, spawn());
    }

    /** Distancia en casillas al punto de aparición (0, 0). */
    public double distanciaSpawn(int vx, int vy){
        return Math.sqrt((double)vx * vx + (double)vy * vy);
    }

    /** Radio, en casillas, del claro inicial alrededor del spawn en este mundo. */
    public float radioClaroSpawn(){
        comprobarBase();
        return spawn().radioLibre;
    }

    /** ¿Cae (vx, vy) a menos de {@code margen} casillas de algún pozo del spawn (agua, alquitrán, geotérmico, respiraderos...)? */
    public boolean cercaDePozoSpawn(int vx, int vy, float margen){
        comprobarBase();
        final float[] poz = spawn().poz;
        for(int k = 0; k < poz.length; k += Muestreo.STRIDE_POZ){
            final float dx = vx - poz[k + 1], dy = vy - poz[k + 2], r = poz[k + 3] * 1.9f + margen;
            if(dx * dx + dy * dy <= r * r) return true;
        }
        return false;
    }
}
