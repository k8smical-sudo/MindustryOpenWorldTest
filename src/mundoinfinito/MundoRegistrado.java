package mundoinfinito;

import arc.func.Cons;
import arc.struct.Seq;
import mindustry.game.Rules;
import mindustry.type.ItemStack;

/**
 * Un mundo (dimensión) añadido por un mod. Se crea con {@link #nuevo}, se configura y se registra con {@link OpenWorldAPI#registrarMundo}
 * ANTES de abrir el primer mundo. Aparece como destino en los portales interdimensionales (salvo que {@link #visible} diga lo contrario).
 *
 * Cada mundo registrado tiene sus propios chunks, edificios, checkpoints y niebla (carpeta propia dentro del mundo guardado), como Serpulo y
 * Erekir. El número de dimensión se deriva del id y es estable entre sesiones y entre órdenes de carga de los mods.
 */
public final class MundoRegistrado{
    /** Primera dimensión posible de un mundo registrado (0 y 1 son Serpulo y Erekir; el resto queda reservado). */
    public static final int DIM_MINIMA = 16;

    public final String id;
    /** Nombre visible. */
    public String nombre;
    /** Nombre interno del bloque de núcleo con el que se aparece (por defecto el Shard). */
    public String nucleo = "core-shard";
    /** Planeta cuyas reglas de entorno se usan: "serpulo" o "erekir". */
    public String planeta = "serpulo";
    /** Ítems con los que se empieza al abrir el mundo por primera vez. */
    public Seq<ItemStack> inicio = new Seq<>();
    /** Posición VIRTUAL a la que se llega al entrar por un portal (por defecto el origen, donde está el núcleo). */
    public int llegadaX = 0, llegadaY = 0;
    /** Generador del terreno completo. Obligatorio. */
    public GeneradorMundo generador;
    /** Ajuste de reglas propio de este mundo, aplicado antes que los de {@link OpenWorldAPI#registrarReglas}. */
    public Cons<Rules> reglas;
    /** ¿Se ofrece este mundo como destino desde la dimensión indicada? Por defecto, desde cualquiera. */
    public Visibilidad visible = dim -> true;

    /** Dimensión asignada al registrar (>= {@link #DIM_MINIMA}). */
    int dimension = -1;

    public interface Visibilidad{
        boolean desde(int dimensionActual);
    }

    private MundoRegistrado(String id, String nombre){
        this.id = id;
        this.nombre = nombre;
    }

    public static MundoRegistrado nuevo(String id, String nombre, GeneradorMundo generador){
        final MundoRegistrado m = new MundoRegistrado(id, nombre);
        m.generador = generador;
        return m;
    }

    public int dimension(){
        return dimension;
    }

    public MundoRegistrado nucleo(String bloque){
        this.nucleo = bloque;
        return this;
    }

    public MundoRegistrado planeta(String p){
        this.planeta = p;
        return this;
    }

    public MundoRegistrado llegada(int x, int y){
        this.llegadaX = x;
        this.llegadaY = y;
        return this;
    }

    public MundoRegistrado inicio(ItemStack... items){
        this.inicio = Seq.with(items);
        return this;
    }

    public MundoRegistrado reglas(Cons<Rules> r){
        this.reglas = r;
        return this;
    }

    public MundoRegistrado visible(Visibilidad v){
        this.visible = v;
        return this;
    }
}
