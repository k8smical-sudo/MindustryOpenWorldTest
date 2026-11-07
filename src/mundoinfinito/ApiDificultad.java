package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * API PÚBLICA DE DIFICULTAD (versión {@link ApiVersion#DIFICULTAD_MAYOR}.{@link ApiVersion#DIFICULTAD_MENOR}). Plan v9, P10.
 *
 * MOTOR, NO REGLAS. El mod no decide qué significa una dificultad: lo decide cada addon. Un addon registra perfiles de dificultad con un
 * código entero propio (1 a 9999; 0 es el perfil neutro, reservado). El jugador elige uno al CREAR un mundo, en el ajuste
 * {@link #AJUSTE}, que aparece en el diálogo de creación. Ese código se guarda en el mundo, así que un mundo conserva su dificultad.
 *
 * SIN CONEXIÓN POR DEFECTO. El perfil neutro no cambia ningún valor: {@link #valor} devuelve el valor base que pasa quien consulta. Hasta que
 * un addon registre un perfil y el jugador lo elija, nada del juego cambia.
 *
 * Quien consulta un valor (un fenómeno, un addon, el propio juego) lo pide con {@link #valor(String, double)}, dando una clave y su valor base.
 * El perfil decide el valor final, con el contexto del mundo (semilla, dimensión, tiempo y azar determinista).
 *
 * FALLOS: un perfil que lanza una excepción o devuelve un valor no finito queda desactivado con un aviso en el log; las consultas devuelven el
 * valor base hasta reiniciar el juego.
 */
public final class ApiDificultad{
    /** Id del ajuste de mundo que guarda el perfil elegido (un entero: el código del perfil). Lo registra {@link #iniciar}. */
    public static final String AJUSTE = "mundo-infinito:dificultad";
    /** Código del perfil neutro (el que no cambia nada). */
    public static final int CODIGO_NEUTRO = 0;
    /** Código más alto que puede tener un perfil. */
    public static final int CODIGO_MAX = 9999;

    /** Un perfil de dificultad. Lo implementa el addon; {@link #valor} tiene un valor por defecto que no cambia nada. */
    public interface Perfil{
        /** Código entero único, de 1 a {@link #CODIGO_MAX}. Se guarda en los mundos: no cambiarlo nunca. */
        int codigo();

        /** Id "fuente:nombre", único. */
        String id();

        /** Nombre que ve el jugador en el diálogo de creación. */
        String nombre();

        /** Descripción que ve el jugador. */
        String descripcion();

        /**
         * Valor final de una clave, a partir de su valor base. Por defecto, el valor base. Debe ser finito; si no, el perfil se desactiva.
         * La clave la elige quien consulta (por ejemplo "fenomeno#variable"): el perfil decide qué claves le importan.
         */
        default double valor(String clave, double base, ContextoDificultad c){
            return base;
        }
    }

    /** Contexto de una consulta: datos del mundo y azar determinista. Inmutable. */
    public static final class ContextoDificultad{
        public final int semilla, dimension;
        /** Tiempo del mundo en ticks. */
        public final float tiempo;

        ContextoDificultad(int semilla, int dimension, float tiempo){
            this.semilla = semilla;
            this.dimension = dimension;
            this.tiempo = tiempo;
        }

        /** Azar determinista en [0, 1): mismo mundo, mismo canal y mismo bloque de tiempo, mismo valor. */
        public float azar(String canal, int bloque){
            return ApiAzar.unidad(semilla, dimension, canal, bloque, 0);
        }
    }

    private static final Perfil NEUTRO = new Perfil(){
        @Override public int codigo(){ return CODIGO_NEUTRO; }
        @Override public String id(){ return "mundo-infinito:neutro"; }
        @Override public String nombre(){ return "Neutro"; }
        @Override public String descripcion(){ return "Sin cambios: el juego funciona como siempre."; }
    };

    private static final TreeMap<Integer, Perfil> PERFILES = new TreeMap<Integer, Perfil>();
    private static final Set<Integer> FALLIDOS = new HashSet<Integer>();
    private static final Set<Integer> AVISADOS = new HashSet<Integer>();
    private static boolean iniciado;

    private ApiDificultad(){}

    /** Registra el ajuste de mundo del perfil. Lo llama el mod al cargar, antes de abrir el primer mundo. Idempotente. */
    static synchronized void iniciar(){
        if(iniciado) return;
        iniciado = true;
        ApiAjustes.registrarEntero(AJUSTE, "Dificultad", "Perfil de dificultad del mundo (lo define cada addon). Neutro = sin cambios.",
            CODIGO_NEUTRO, CODIGO_NEUTRO, CODIGO_MAX, null);
    }

    /**
     * Registra un perfil. Devuelve false si el perfil es nulo, su código está fuera de rango o ya lo usa otro, su id no tiene formato
     * "fuente:nombre" o ya existe. Se puede llamar en cualquier momento: un mundo guarda solo el código, no el perfil.
     */
    public static synchronized boolean registrarPerfil(Perfil p){
        if(p == null) return false;
        final int c = p.codigo();
        if(c <= CODIGO_NEUTRO || c > CODIGO_MAX || PERFILES.containsKey(c)) return false;
        final String id = p.id();
        if(id == null || id.indexOf(':') <= 0 || id.indexOf(':') >= id.length() - 1 || id.indexOf(' ') >= 0) return false;
        for(Perfil q : PERFILES.values()) if(q.id().equals(id)) return false;
        PERFILES.put(c, p);
        return true;
    }

    /** Quita un perfil por su código. Los mundos que lo eligieron pasan a verse como neutros, con aviso. */
    public static synchronized boolean quitarPerfil(int codigo){
        return PERFILES.remove(codigo) != null;
    }

    /** Perfiles disponibles, neutro primero y después por código. */
    public static synchronized List<Perfil> disponibles(){
        final List<Perfil> lista = new ArrayList<Perfil>();
        lista.add(NEUTRO);
        lista.addAll(PERFILES.values());
        return lista;
    }

    /** Perfil elegido en el mundo abierto. Neutro si no hay mundo, si el código es 0 o si el perfil ya no existe (con aviso una vez). */
    public static synchronized Perfil actual(){
        final double v = ApiAjustes.valor(AJUSTE);
        final int codigo = Double.isNaN(v) ? CODIGO_NEUTRO : (int)v;
        if(codigo == CODIGO_NEUTRO) return NEUTRO;
        final Perfil p = PERFILES.get(codigo);
        if(p == null){
            if(AVISADOS.add(codigo)) Log.warn("[MundoInfinito] Dificultad: el mundo usa el perfil @, que no está registrado. Se usa el neutro.", codigo);
            return NEUTRO;
        }
        return p;
    }

    /**
     * Valor final de una clave en el mundo abierto. Con el perfil neutro, o sin perfil, devuelve {@code base}. Con un perfil activo, devuelve lo que
     * decida el perfil; si el perfil falla, se desactiva y devuelve {@code base}.
     */
    public static double valor(String clave, double base){
        return valorCon(actual(), clave, base, contextoActual());
    }

    /** Lo mismo que {@link #valor} con un perfil y un contexto dados. Es el camino que usan las pruebas y las consultas. */
    static double valorCon(Perfil p, String clave, double base, ContextoDificultad c){
        if(p == null || p.codigo() == CODIGO_NEUTRO) return base;
        synchronized(ApiDificultad.class){
            if(FALLIDOS.contains(p.codigo())) return base;
        }
        try{
            final double v = p.valor(clave, base, c);
            if(Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalStateException("valor no finito: " + v);
            return v;
        }catch(Throwable t){
            synchronized(ApiDificultad.class){
                if(FALLIDOS.add(p.codigo())) Log.err("[MundoInfinito] Dificultad: el perfil @ (@) falló (@) y queda desactivado hasta reiniciar el juego.", p.codigo(), p.id(), t.toString());
            }
            return base;
        }
    }

    /** Contexto del mundo abierto: semilla, dimensión y tiempo. Sin mundo, semilla 0 y tiempo 0. */
    static ContextoDificultad contextoActual(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        final int semilla = m == null ? 0 : m.semilla;
        return new ContextoDificultad(semilla, MundoInfinitoMod.Mundos.terrenoActual(), ApiTiempo.tiempoMundo());
    }
}
