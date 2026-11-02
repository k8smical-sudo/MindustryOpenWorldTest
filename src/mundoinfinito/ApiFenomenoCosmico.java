package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API PÚBLICA DE FENÓMENOS CÓSMICOS (versión {@link ApiVersion#FENOMENO_COSMICO_MAYOR}.{@link ApiVersion#FENOMENO_COSMICO_MENOR}). Plan v9, P10.
 *
 * Un fenómeno cósmico es algo que vive en el mundo abierto (un agujero negro, un pulsar, una nebulosa...) con variables propias. Lo que hace
 * cada fenómeno lo decide su dueño: el mod da el marco, no los efectos. Un fenómeno declara:
 *  - su id "fuente:nombre";
 *  - sus variables, con un valor por defecto. Cada variable puede cambiarla el motor de dificultad ({@link ApiDificultad}), así que el perfil
 *    del jugador puede ajustar la intensidad de cualquier fenómeno sin que el fenómeno lo sepa;
 *  - {@link Fenomeno#evaluar}: sus efectos, llamados con un contexto determinista (semilla, tiempo, azar por canal);
 *  - {@link Fenomeno#dibujar}: su visual 2D, para que un addon pueda pintarlo en el mapa.
 *
 * ACTIVACIÓN. Cada fenómeno tiene un ajuste de mundo "fuente:nombre:activo" (apagado por defecto). Se elige al CREAR el mundo, en el diálogo
 * de creación, y no se puede cambiar después. Un fenómeno apagado no se evalúa ni se dibuja.
 *
 * FALLOS: un fenómeno que lanza una excepción se desactiva con aviso en el log hasta reiniciar el juego; el resto sigue funcionando.
 *
 * DETERMINISMO: las variables y el azar dependen solo de la semilla, la dimensión, el tiempo del mundo y los datos declarados, nunca de la hora
 * real ni del orden de carga. Ver {@link ContextoCosmico#azar}.
 */
public final class ApiFenomenoCosmico{
    private ApiFenomenoCosmico(){}

    /** Un fenómeno. Implementa solo lo que necesita: los métodos por defecto no hacen nada. */
    public interface Fenomeno{
        /** Id único "fuente:nombre". */
        String id();

        /** Variables y su valor por defecto. Vacío por defecto. Las claves son los nombres de las variables. */
        default Map<String, Double> variables(){
            return Collections.emptyMap();
        }

        /** Efectos del fenómeno, llamados mientras esté activo en el mundo abierto. Por defecto, ninguno. */
        default void evaluar(ContextoCosmico c){
        }

        /** Visual 2D del fenómeno, para la capa que lo pinte. Por defecto, ninguno. */
        default void dibujar(ContextoCosmico c){
        }
    }

    /** Contexto de un fenómeno en un instante: valores efectivos de sus variables, tiempo y azar. Inmutable. */
    public static final class ContextoCosmico{
        public final String fenomeno;
        public final int semilla, dimension;
        /** Tiempo del mundo en ticks. */
        public final float tiempo;
        private final Map<String, Double> defectos;

        ContextoCosmico(String fenomeno, int semilla, int dimension, float tiempo, Map<String, Double> defectos){
            this.fenomeno = fenomeno;
            this.semilla = semilla;
            this.dimension = dimension;
            this.tiempo = tiempo;
            this.defectos = defectos;
        }

        /**
         * Valor efectivo de una variable: su valor por defecto, cambiado por el motor de dificultad. NaN si la variable no está declarada
         * (no inventa valores).
         */
        public double variable(String nombre){
            final Double d = defectos.get(nombre);
            if(d == null) return Double.NaN;
            return ApiDificultad.valorCon(ApiDificultad.actual(), fenomeno + "#" + nombre, d, contextoDificultad());
        }

        /** Azar determinista en [0, 1): mismo mundo, mismo fenómeno, mismo canal y mismo bloque de tiempo, mismo valor. */
        public float azar(String canal, int bloque){
            return ApiAzar.unidad(semilla, dimension, fenomeno + "/" + canal, bloque, 0);
        }

        private ApiDificultad.ContextoDificultad contextoDificultad(){
            return new ApiDificultad.ContextoDificultad(semilla, dimension, tiempo);
        }
    }

    private static final class Registro{
        final Fenomeno f;
        final String ajuste;

        Registro(Fenomeno f, String ajuste){
            this.f = f;
            this.ajuste = ajuste;
        }
    }

    private static final Map<String, Registro> REGISTRO = new LinkedHashMap<String, Registro>();
    private static final Set<String> FALLIDOS = new HashSet<String>();

    /**
     * Registra un fenómeno y su ajuste de activación (apagado por defecto). Devuelve false si es nulo, su id no tiene formato "fuente:nombre", ya
     * existe, o ya se abrió un mundo (el ajuste ya no se puede registrar). Registrar al cargar el mod.
     */
    public static synchronized boolean registrar(Fenomeno f){
        if(f == null || !idValido(f.id()) || REGISTRO.containsKey(f.id())) return false;
        final String ajuste = f.id() + ":activo";
        final boolean ok = ApiAjustes.registrarBooleano(ajuste, "Fenómeno: " + f.id(),
            "Activa este fenómeno cósmico en el mundo. Se elige al crear el mundo.", false, null);
        if(!ok) return false;
        REGISTRO.put(f.id(), new Registro(f, ajuste));
        return true;
    }

    /** Quita un fenómeno de la evaluación y el dibujo. Su ajuste de activación sigue registrado (no se puede quitar un ajuste). */
    public static synchronized boolean quitar(String id){
        return REGISTRO.remove(id) != null;
    }

    /** Ids de los fenómenos registrados, en orden de registro. */
    public static synchronized List<String> ids(){
        return new ArrayList<String>(REGISTRO.keySet());
    }

    /** ¿Está activo el fenómeno en el mundo abierto? Falso si no existe, si no hay mundo, si está apagado o si ha fallado. */
    public static synchronized boolean activo(String id){
        final Registro r = REGISTRO.get(id);
        if(r == null || FALLIDOS.contains(id)) return false;
        final double v = ApiAjustes.valor(r.ajuste);
        return !Double.isNaN(v) && v != 0.0;
    }

    /** Evalúa los efectos de todos los fenómenos activos del mundo abierto. Llamar en cada paso del juego (lo conecta P12). */
    public static void evaluarTodos(){
        for(Registro r : copia()){
            if(!activo(r.f.id())) continue;
            ejecutar(r, true, contexto(r));
        }
    }

    /** Dibuja todos los fenómenos activos del mundo abierto. Llamar desde la capa visual (lo conecta P12). */
    public static void dibujarTodos(){
        for(Registro r : copia()){
            if(!activo(r.f.id())) continue;
            ejecutar(r, false, contexto(r));
        }
    }

    /** Ejecuta los efectos o el dibujo de un fenómeno. Un fallo lo desactiva con aviso. Visible para las pruebas. */
    static void ejecutar(Registro r, boolean efectos, ContextoCosmico c){
        try{
            if(efectos) r.f.evaluar(c);
            else r.f.dibujar(c);
        }catch(Throwable t){
            synchronized(ApiFenomenoCosmico.class){
                if(FALLIDOS.add(r.f.id())) Log.err("[MundoInfinito] Fenómeno cósmico '@' falló (@) y queda desactivado hasta reiniciar el juego.", r.f.id(), t.toString());
            }
        }
    }

    private static synchronized List<Registro> copia(){
        return new ArrayList<Registro>(REGISTRO.values());
    }

    private static ContextoCosmico contexto(Registro r){
        final ApiDificultad.ContextoDificultad base = ApiDificultad.contextoActual();
        final Map<String, Double> defectos = new LinkedHashMap<String, Double>();
        final Map<String, Double> declarados = r.f.variables();
        if(declarados != null) defectos.putAll(declarados);
        return new ContextoCosmico(r.f.id(), base.semilla, base.dimension, base.tiempo, defectos);
    }

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }
}
