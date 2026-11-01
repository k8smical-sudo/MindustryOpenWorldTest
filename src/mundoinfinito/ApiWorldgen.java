package mundoinfinito;

import arc.util.Log;
import mindustry.Vars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * API PÚBLICA DE WORLDGEN (plan v5, D12 y D13). Versión {@link ApiVersion#WORLDGEN_MAYOR}.{@link ApiVersion#WORLDGEN_MENOR}.
 *
 * Una FUENTE aporta información sobre una coordenada del mundo: el bioma, multiplicadores de probabilidad por fenómeno y bioma, y valores fijos
 * que sustituyen a la probabilidad. Las fuentes se consultan en orden canónico (prioridad, id): el resultado no depende del orden de carga.
 *
 * Reglas de combinación:
 *  - Bioma: gana la fuente de MENOR prioridad (número) que devuelva un bioma distinto de {@link #BIOMA_NINGUNO}.
 *  - Valor fijo: gana la fuente de menor prioridad que devuelva un valor (no NaN). Sustituye a la probabilidad base y a los multiplicadores.
 *  - Multiplicadores: se multiplican todos los que devuelvan un valor (no NaN). Un multiplicador negativo o infinito desactiva su fuente.
 *
 * Una fuente que lanza una excepción queda desactivada con un aviso en el log, sin afectar a las demás.
 * Todas las decisiones deben depender solo de la semilla, la dimensión, la coordenada y los datos declarados: usa {@link #azar} para el azar.
 * Ver docs/WORLDGEN_API.md.
 */
public final class ApiWorldgen{
    /** Sin bioma: la fuente no tiene opinión. */
    public static final int BIOMA_NINGUNO = -1;
    /** Grupos de bioma de Erekir (los del juego). Un addon puede usar otros números. */
    public static final int BIOMA_RIOLITA = 0, BIOMA_ARKYIC = 1, BIOMA_AMARILLO = 2, BIOMA_ROJO = 3, BIOMA_CARBONO = 4;
    /** Id del fenómeno de respiraderos de Erekir, el que consultan los multiplicadores del juego. */
    public static final String FENOMENO_RESPIRADEROS = "mundo-infinito:respiraderos-erekir";

    private static final Object CERROJO = new Object();
    private static final List<Entrada> REGISTRO = new ArrayList<Entrada>();
    /** Copia de solo lectura, ya ordenada: las lecturas (que pueden venir de hilos de generación) no toman el cerrojo. */
    private static volatile Entrada[] orden = new Entrada[0];
    private static volatile boolean iniciado;
    private static final Comparator<Entrada> ORDEN = new Comparator<Entrada>(){
        @Override
        public int compare(Entrada a, Entrada b){
            final int c = Integer.compare(a.prio, b.prio);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private ApiWorldgen(){}

    /** Lo que se sabe de una coordenada del mundo. Solo datos: no expone tipos internos del generador. */
    public static final class Lugar{
        public final int semilla, dimension, x, y;
        /** Código interno del piso: solo lo usan las fuentes del propio juego (mismo paquete). */
        final int codigoPiso;
        private final IntSupplier menas;
        private final Supplier<String> nombre;
        private int menasMemo = -1;
        private String nombreMemo;

        Lugar(int semilla, int dimension, int x, int y, int codigoPiso, IntSupplier menas, Supplier<String> nombre){
            this.semilla = semilla;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.codigoPiso = codigoPiso;
            this.menas = menas;
            this.nombre = nombre;
        }

        /** Casillas con mena (mineral) en la vecindad de 7 x 7 alrededor de la coordenada: de 0 a 49. Se calcula al primer uso. */
        public int menas(){
            if(menasMemo < 0) menasMemo = Math.max(0, menas.getAsInt());
            return menasMemo;
        }

        /** Nombre del piso del terreno base en la coordenada (por ejemplo "rhyolite"). Se calcula al primer uso. */
        public String pisoNombre(){
            if(nombreMemo == null) nombreMemo = nombre.get();
            return nombreMemo;
        }
    }

    /**
     * Una fuente de información de worldgen. Implementa solo lo que aporta: los métodos por defecto no tienen opinión.
     * El id es único y tiene la forma "fuente:nombre". La prioridad se lee una vez, al registrar la fuente.
     */
    public interface Fuente{
        String id();

        default int prioridad(){
            return 0;
        }

        /** Bioma de la coordenada, o {@link #BIOMA_NINGUNO} si no tiene opinión. */
        default int bioma(Lugar l){
            return BIOMA_NINGUNO;
        }

        /** Multiplicador de la probabilidad del fenómeno en ese bioma, o NaN si no tiene opinión. Los valores son finitos y no negativos. */
        default float multiplicador(String fenomeno, int bioma, Lugar l){
            return Float.NaN;
        }

        /** Probabilidad fija (entre 0 y 1) del fenómeno en ese bioma, o NaN si no tiene opinión. Sustituye a la probabilidad base. */
        default float valorFijo(String fenomeno, int bioma, Lugar l){
            return Float.NaN;
        }
    }

    private static final class Entrada{
        final Fuente f;
        final String id;
        final int prio;
        volatile boolean activa = true;

        Entrada(Fuente f){
            this.f = f;
            this.id = f.id();
            this.prio = f.prioridad();
        }
    }

    /** Registra una fuente. Devuelve false si el id no es válido o ya existe. Se puede llamar en cualquier momento. */
    public static boolean registrar(Fuente f){
        if(f == null || !idValido(f.id())) return false;
        synchronized(CERROJO){
            for(Entrada e : REGISTRO) if(e.id.equals(f.id())) return false;
            REGISTRO.add(new Entrada(f));
            publicar();
            return true;
        }
    }

    /** Quita una fuente registrada. */
    public static boolean quitar(String id){
        synchronized(CERROJO){
            for(int i = 0; i < REGISTRO.size(); i++){
                if(REGISTRO.get(i).id.equals(id)){
                    REGISTRO.remove(i);
                    publicar();
                    return true;
                }
            }
            return false;
        }
    }

    /** Bioma de la coordenada: el de la fuente de menor prioridad que tenga opinión, o {@link #BIOMA_NINGUNO}. */
    public static int bioma(Lugar l){
        asegurarIniciado();
        for(Entrada e : orden){
            if(!e.activa) continue;
            try{
                final int b = e.f.bioma(l);
                if(b != BIOMA_NINGUNO) return b;
            }catch(Throwable t){
                desactivar(e, t);
            }
        }
        return BIOMA_NINGUNO;
    }

    /**
     * Probabilidad de un fenómeno en una coordenada. Si alguna fuente da un valor fijo, gana el de menor prioridad (entre 0 y 1).
     * Si no, la probabilidad base se multiplica por todos los multiplicadores que haya. El resultado nunca es negativo.
     */
    public static float probabilidad(String fenomeno, float base, Lugar l){
        asegurarIniciado();
        final Entrada[] ord = orden;
        final int b = bioma(l);
        for(Entrada e : ord){
            if(!e.activa) continue;
            try{
                final float v = e.f.valorFijo(fenomeno, b, l);
                if(!Float.isNaN(v)) return v < 0f ? 0f : (v > 1f ? 1f : v);
            }catch(Throwable t){
                desactivar(e, t);
            }
        }
        float p = base;
        for(Entrada e : ord){
            if(!e.activa) continue;
            try{
                final float m = e.f.multiplicador(fenomeno, b, l);
                if(Float.isNaN(m)) continue;
                if(Float.isInfinite(m) || m < 0f) throw new IllegalStateException("multiplicador no válido: " + m);
                p *= m;
            }catch(Throwable t){
                desactivar(e, t);
            }
        }
        return p < 0f ? 0f : p;
    }

    /** Azar determinista para una coordenada: mismo mundo, misma coordenada y canal, mismo valor en [0, 1). El canal es el id del que lo pide. */
    public static float azar(Lugar l, String canal){
        return ApiAzar.unidad(l.semilla, l.dimension, canal, l.x, l.y);
    }

    /** Registra las fuentes del propio juego. Idempotente. Se llama sola la primera vez que se consulta la API. */
    static synchronized void iniciar(){
        if(iniciado) return;
        iniciado = true;
        FuentesWorldgen.registrar();
    }

    private static void asegurarIniciado(){
        if(!iniciado) iniciar();
    }

    /** Lugar de un generador de fenómenos: la coordenada del terreno base y las consultas del contexto. */
    static Lugar lugar(ContextoFenomeno c, int x, int y){
        final int codigo = c.codigoPiso(x, y);
        return new Lugar(c.semilla, c.dimension, x, y, codigo, () -> contarMenas(c, x, y), () -> Vars.content.block(c.pal.idPiso[codigo]).name);
    }

    private static int contarMenas(ContextoFenomeno c, int x, int y){
        int n = 0;
        for(int dy = -3; dy <= 3; dy++){
            for(int dx = -3; dx <= 3; dx++){
                if(c.muestra(x + dx, y + dy)[1] != 0) n++;   // 0 = sin mena; cualquier otro valor es una mena en la casilla
            }
        }
        return n;
    }

    private static void publicar(){
        final Entrada[] nuevo = REGISTRO.toArray(new Entrada[0]);
        Arrays.sort(nuevo, ORDEN);
        orden = nuevo;
    }

    private static void desactivar(Entrada e, Throwable t){
        if(!e.activa) return;
        e.activa = false;
        Log.err("[MundoInfinito] Worldgen: la fuente '@' falló (@) y queda desactivada hasta quitarla o reiniciar el juego.", e.id, t.toString());
    }

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }
}
