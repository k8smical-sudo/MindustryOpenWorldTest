package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleBiFunction;

/**
 * API PÚBLICA DE COLISIONES ENTRE CUERPOS (versión {@link ApiVersion#COLISION_MAYOR}.{@link ApiVersion#COLISION_MENOR}). Plan v9, P10.
 *
 * Una regla de colisión dice qué pasa cuando dos cuerpos concretos chocan: en qué fases ocurre, si el choque llega a producirse y cuál de los dos
 * sobrevive. El mod da el marco; qué significa cada fase para el mundo (destruir un mundo, abrir una grieta) lo decide quien se suscribe.
 *
 *  - FASES. Cada fase es un evento de {@link ApiTiempo} con id {@code idRegla/nombreFase}. {@link #iniciar} programa todas las fases a partir de un
 *    tiempo de inicio, a su desplazamiento en ticks. Para reaccionar, un addon se suscribe con {@link ApiTiempo#suscribir} a ese id.
 *  - SI OCURRE. Por regla: siempre ({@link Disparo#SEGURO}), nunca ({@link Disparo#NUNCA}) o con una probabilidad ({@link Disparo#AZAR}) decidida
 *    con {@link ApiAzar}: mismo mundo, misma regla y mismo instante dan siempre el mismo resultado.
 *  - SUPERVIVIENTE. Por una variable de los cuerpos (por ejemplo "masa"): sobrevive el de valor mayor o el de menor, o uno fijo, o ninguno. El valor
 *    de la variable lo da quien llama ({@link #resolver}); la API no inventa valores: si falta uno (NaN), no hay superviviente.
 *
 * DESACTIVADAS POR DEFECTO. Existe un ajuste de mundo {@link #AJUSTE_ACTIVO}, apagado, que se elige al crear el mundo. Con él apagado, {@link #iniciar}
 * no programa nada. Sin addon que registre reglas, el juego no tiene colisiones.
 *
 * La resolución ({@link #resolver}) y la planificación ({@link #plan}) son lógica pura y se prueban en CI sin el juego. Solo {@link #iniciar} y
 * {@link #activa} tocan el mundo abierto. Ver docs/API.md.
 */
public final class ApiColision{
    /** Ajuste de mundo que activa las colisiones. Apagado por defecto. Lo registra {@link #iniciarAjustes}. */
    public static final String AJUSTE_ACTIVO = "mundo-infinito:colisiones";
    /** Tope de fases por regla y de reglas registradas: acota el coste de programar. */
    public static final int FASES_MAX = 16, REGLAS_MAX = 256;

    private ApiColision(){}

    /** Cuál de los dos cuerpos sobrevive. */
    public enum Criterio{
        /** Sobrevive el de mayor valor de la variable. */
        MAYOR,
        /** Sobrevive el de menor valor de la variable. */
        MENOR,
        /** Sobrevive siempre el cuerpo A de la regla. */
        A,
        /** Sobrevive siempre el cuerpo B de la regla. */
        B,
        /** No sobrevive ninguno. */
        NINGUNO
    }

    /** Si el choque llega a producirse. */
    public enum Disparo{
        /** Siempre ocurre. */
        SEGURO,
        /** Nunca ocurre (la regla queda declarada pero inerte: sirve para apagar una regla sin quitarla). */
        NUNCA,
        /** Ocurre con la probabilidad de la regla, con azar determinista. */
        AZAR
    }

    /** Una fase de la colisión: nombre y desplazamiento en ticks desde el inicio. Inmutable. */
    public static final class Fase{
        public final String nombre;
        public final long ticks;

        Fase(String nombre, long ticks){
            this.nombre = nombre;
            this.ticks = ticks;
        }
    }

    /**
     * Regla de colisión entre dos cuerpos. Se construye con {@link #de} y los métodos encadenables, y se valida al registrarla. Una vez registrada
     * no cambia: se quita y se vuelve a registrar.
     */
    public static final class Regla{
        public final String id, cuerpoA, cuerpoB;
        private final List<Fase> fases = new ArrayList<Fase>();
        private Criterio criterio = Criterio.NINGUNO;
        private String variable = "";
        private Disparo disparo = Disparo.SEGURO;
        private double probabilidad = 1.0;

        private Regla(String id, String cuerpoA, String cuerpoB){
            this.id = id;
            this.cuerpoA = cuerpoA;
            this.cuerpoB = cuerpoB;
        }

        /** Regla con id "fuente:nombre" entre dos cuerpos (ids de {@link EstadoSistemas}). Por defecto: siempre ocurre y no sobrevive ninguno. */
        public static Regla de(String id, String cuerpoA, String cuerpoB){
            return new Regla(id, cuerpoA, cuerpoB);
        }

        /** Añade una fase a {@code ticks} del inicio. Los desplazamientos no pueden decrecer entre fases. */
        public Regla fase(String nombre, long ticks){
            fases.add(new Fase(nombre, ticks));
            return this;
        }

        /** Sobrevive el cuerpo con el valor mayor o menor de la variable. */
        public Regla superviviente(Criterio criterio, String variable){
            this.criterio = criterio == null ? Criterio.NINGUNO : criterio;
            this.variable = variable == null ? "" : variable;
            return this;
        }

        /** Sobrevive uno fijo, o ninguno. Para MAYOR y MENOR usa {@link #superviviente(Criterio, String)}. */
        public Regla superviviente(Criterio criterio){
            return superviviente(criterio, "");
        }

        /** La colisión ocurre siempre (por defecto). */
        public Regla siempre(){
            disparo = Disparo.SEGURO;
            probabilidad = 1.0;
            return this;
        }

        /** La colisión no ocurre nunca. */
        public Regla nunca(){
            disparo = Disparo.NUNCA;
            probabilidad = 0.0;
            return this;
        }

        /** La colisión ocurre con esta probabilidad (0 a 1), decidida con azar determinista. */
        public Regla conProbabilidad(double p){
            disparo = Disparo.AZAR;
            probabilidad = p;
            return this;
        }

        public List<Fase> fases(){ return Collections.unmodifiableList(fases); }
        public Criterio criterio(){ return criterio; }
        public String variable(){ return variable; }
        public Disparo disparo(){ return disparo; }
        public double probabilidad(){ return probabilidad; }

        /** Id del evento de {@link ApiTiempo} de una fase de esta regla. */
        public String eventoDe(Fase f){
            return id + "/" + f.nombre;
        }
    }

    /** Resultado de resolver una regla en un instante: si ocurre, quién sobrevive y quién se destruye. Inmutable. */
    public static final class Desenlace{
        public final boolean ocurre;
        /** Id del cuerpo superviviente, o null si no sobrevive ninguno (o la colisión no ocurre). */
        public final String superviviente;
        /** Ids de los cuerpos destruidos: ninguno, uno o los dos. Vacío si la colisión no ocurre. */
        public final List<String> destruidos;
        /** Tirada de azar usada, en [0, 1); NaN si la regla no usa azar. */
        public final double tirada;

        Desenlace(boolean ocurre, String superviviente, List<String> destruidos, double tirada){
            this.ocurre = ocurre;
            this.superviviente = superviviente;
            this.destruidos = Collections.unmodifiableList(destruidos);
            this.tirada = tirada;
        }
    }

    /** Un evento que {@link #plan} calcula: id del evento y tiempo absoluto en el que se dispara. */
    public static final class Programacion{
        public final String evento;
        public final float tiempo;

        Programacion(String evento, float tiempo){
            this.evento = evento;
            this.tiempo = tiempo;
        }
    }

    // ================================================================== validación y registro

    private static final Map<String, Regla> REGLAS = new LinkedHashMap<String, Regla>();

    /**
     * Motivo por el que una regla no es válida, o null si lo es. Comprueba: id "fuente:nombre"; dos cuerpos distintos y no vacíos; 0 a
     * {@link #FASES_MAX} fases con nombre sin espacios ni '/' ni repetido y desplazamientos no negativos y no decrecientes; una variable si el
     * criterio es MAYOR o MENOR; y una probabilidad en [0, 1] si el disparo es AZAR.
     */
    public static String motivoInvalida(Regla r){
        if(r == null) return "regla nula";
        if(!idValido(r.id)) return "id sin formato fuente:nombre";
        if(r.id.indexOf('/') >= 0) return "el id no puede contener '/'";
        if(vacio(r.cuerpoA) || vacio(r.cuerpoB)) return "falta un cuerpo";
        if(r.cuerpoA.equals(r.cuerpoB)) return "un cuerpo no choca consigo mismo";
        if(r.fases.size() > FASES_MAX) return "demasiadas fases";
        long previo = 0L;
        for(int i = 0; i < r.fases.size(); i++){
            final Fase f = r.fases.get(i);
            if(vacio(f.nombre) || f.nombre.indexOf(' ') >= 0 || f.nombre.indexOf('/') >= 0) return "nombre de fase no válido: " + f.nombre;
            if(f.ticks < 0L) return "desplazamiento negativo en la fase " + f.nombre;
            if(f.ticks < previo) return "las fases no pueden ir hacia atrás: " + f.nombre;
            for(int j = 0; j < i; j++) if(r.fases.get(j).nombre.equals(f.nombre)) return "fase repetida: " + f.nombre;
            previo = f.ticks;
        }
        if((r.criterio == Criterio.MAYOR || r.criterio == Criterio.MENOR) && vacio(r.variable)) return "falta la variable que decide el superviviente";
        if(r.disparo == Disparo.AZAR && !(r.probabilidad >= 0.0 && r.probabilidad <= 1.0)) return "probabilidad fuera de 0 a 1";
        return null;
    }

    /**
     * Registra una regla. Devuelve false si no es válida (con el motivo en el log), si ya existe una con ese id, o si se alcanzó el tope de reglas.
     * Registra además la acción de cada fase en {@link ApiTiempo} (necesaria para que el evento pueda dispararse y llegar a sus suscriptores).
     * Registrar al cargar el mod, antes de abrir el primer mundo.
     */
    public static synchronized boolean registrar(Regla r){
        final String motivo = motivoInvalida(r);
        if(motivo != null){
            Log.warn("[MundoInfinito] Colisión '@' rechazada: @", r == null ? "null" : r.id, motivo);
            return false;
        }
        if(REGLAS.containsKey(r.id) || REGLAS.size() >= REGLAS_MAX) return false;
        REGLAS.put(r.id, r);
        for(Fase f : r.fases) ApiTiempo.registrarAccion(r.eventoDe(f), e -> { });   // la acción no hace nada: reaccionan los suscriptores
        return true;
    }

    /** Quita una regla. Sus eventos ya programados siguen su curso: se disparan sin efecto propio. */
    public static synchronized boolean quitar(String id){
        return REGLAS.remove(id) != null;
    }

    /** Reglas registradas, en orden de registro. */
    public static synchronized List<Regla> reglas(){
        return new ArrayList<Regla>(REGLAS.values());
    }

    /** Regla por id, o null. */
    public static synchronized Regla regla(String id){
        return REGLAS.get(id);
    }

    /** Regla entre dos cuerpos en cualquier orden, o null. */
    public static synchronized Regla entre(String a, String b){
        for(Regla r : REGLAS.values()){
            if((r.cuerpoA.equals(a) && r.cuerpoB.equals(b)) || (r.cuerpoA.equals(b) && r.cuerpoB.equals(a))) return r;
        }
        return null;
    }

    /** Solo para pruebas. */
    static synchronized void reiniciar(){
        REGLAS.clear();
    }

    // ================================================================== lógica pura

    /** Plan de eventos de una regla iniciada en el tiempo {@code inicio}: una programación por fase, en orden. Inicio no válido: vacío. */
    public static List<Programacion> plan(Regla r, float inicio){
        final List<Programacion> out = new ArrayList<Programacion>();
        if(r == null || !Float.isFinite(inicio) || inicio < 0f) return out;
        for(Fase f : r.fases) out.add(new Programacion(r.eventoDe(f), inicio + f.ticks));
        return out;
    }

    /**
     * Resuelve una regla en un instante.
     * @param semilla semilla del mundo
     * @param dimension dimensión del mundo
     * @param bloqueTiempo instante (por ejemplo, el tiempo de inicio en ticks): mismo instante, misma tirada
     * @param variable valor de una variable de un cuerpo, como (cuerpo, nombre); NaN si se desconoce. Solo se llama con criterio MAYOR o MENOR
     */
    public static Desenlace resolver(Regla r, int semilla, int dimension, int bloqueTiempo, ToDoubleBiFunction<String, String> variable){
        if(r == null) return new Desenlace(false, null, new ArrayList<String>(), Double.NaN);
        double tirada = Double.NaN;
        boolean ocurre;
        switch(r.disparo){
            case NUNCA: ocurre = false; break;
            case AZAR:
                tirada = ApiAzar.unidad(semilla, dimension, "colision/" + r.id, bloqueTiempo, 0);
                ocurre = tirada < r.probabilidad;
                break;
            default: ocurre = true;
        }
        if(!ocurre) return new Desenlace(false, null, new ArrayList<String>(), tirada);
        final String vivo = superviviente(r, variable);
        final List<String> destruidos = new ArrayList<String>(2);
        if(!r.cuerpoA.equals(vivo)) destruidos.add(r.cuerpoA);
        if(!r.cuerpoB.equals(vivo)) destruidos.add(r.cuerpoB);
        return new Desenlace(true, vivo, destruidos, tirada);
    }

    private static String superviviente(Regla r, ToDoubleBiFunction<String, String> variable){
        switch(r.criterio){
            case A: return r.cuerpoA;
            case B: return r.cuerpoB;
            case MAYOR:
            case MENOR: {
                if(variable == null) return null;
                final double va = variable.applyAsDouble(r.cuerpoA, r.variable), vb = variable.applyAsDouble(r.cuerpoB, r.variable);
                if(Double.isNaN(va) || Double.isNaN(vb)) return null;   // no se inventan valores: sin dato, no hay superviviente
                if(va == vb) return r.cuerpoA.compareTo(r.cuerpoB) <= 0 ? r.cuerpoA : r.cuerpoB;   // empate: el id menor, siempre
                final boolean gana = r.criterio == Criterio.MAYOR ? va > vb : va < vb;
                return gana ? r.cuerpoA : r.cuerpoB;
            }
            default: return null;
        }
    }

    // ================================================================== mundo abierto

    /** Registra el ajuste de activación. Lo llama el mod al cargar, antes de abrir el primer mundo. */
    static void iniciarAjustes(){
        ApiAjustes.registrarBooleano(AJUSTE_ACTIVO, "Colisiones entre cuerpos",
            "Permite las colisiones entre cuerpos que definan los addons. Se elige al crear el mundo.", false, null);
    }

    /** ¿Están activas las colisiones en el mundo abierto? Falso sin mundo o si el ajuste no existe. */
    public static boolean activa(){
        final double v = ApiAjustes.valor(AJUSTE_ACTIVO);
        return !Double.isNaN(v) && v != 0.0;
    }

    /**
     * Inicia una colisión en el mundo abierto: programa las fases de la regla desde el tiempo actual y avisa a la capa visual (pulso rojo del visor 2D).
     * Devuelve las fases programadas (vacío si las colisiones están apagadas, la regla no existe o no hay mundo abierto). Es responsabilidad de quien
     * llama llamar a {@link #resolver} para saber el desenlace; el resultado depende del instante de inicio, así que repetirlo da lo mismo.
     */
    public static List<String> iniciar(String idRegla){
        final List<String> programadas = new ArrayList<String>();
        if(!activa()) return programadas;
        final Regla r = regla(idRegla);
        if(r == null) return programadas;
        final float inicio = ApiTiempo.tiempoMundo();
        for(Programacion p : plan(r, inicio)){
            if(ApiTiempo.programar(p.evento, p.tiempo)) programadas.add(p.evento);
        }
        if(!programadas.isEmpty()) ColisionVisual.pulso(r.cuerpoA, r.cuerpoB);
        return programadas;
    }

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    private static boolean vacio(String t){
        return t == null || t.trim().isEmpty();
    }
}
