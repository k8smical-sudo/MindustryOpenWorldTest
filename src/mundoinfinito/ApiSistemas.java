package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * API DE SISTEMAS (plan v7, F4 y F5; D16 a D18 y D20). Órbitas compartidas por tipo de mundo, slots, capacidad, planetas solitarios,
 * movimiento orbital simulado y deriva opcional de los sistemas. Sin Arc salvo el log de avisos: se prueba en CI sin el juego.
 *
 * MOVIMIENTO (respuesta 3 de la sección 10): el giro de los planetas y su órbita son simulados y están activos por defecto
 * ({@link #anguloEn}, {@link #rotacionEn}). La deriva de un sistema hacia un lugar del espacio está DESACTIVADA por defecto, como en
 * el juego vanilla; un addon la activa con {@link #activarDeriva}.
 *
 * Una órbita es la trayectoria de un tipo de planeta base (D19). Cada mundo de ese tipo ocupa un slot. Capacidad nominal
 * floor(2πr / (diámetro + margen)) (D16). El ángulo lo decide una política de slot ({@link PoliticaSlot}): la política por defecto
 * coloca el nuevo cuerpo en el centro del hueco mayor; los addons pueden proponer otra por prioridad. El resultado de cualquier política
 * se comprueba: si no respeta la separación mínima con todos los cuerpos de la órbita, la creación se rechaza. Una política que lanza
 * excepción se desactiva con aviso y se usa la siguiente, o la de defecto.
 *
 * LÍMITE CONOCIDO: con la política por defecto, la capacidad nominal es un máximo que puede no alcanzarse, porque el hueco mayor se
 * parte por la mitad y puede quedar menor que dos separaciones mínimas. Entonces la creación da SIN_HUECO aunque la capacidad no esté
 * agotada. Ver docs/ENTREGA_A_SISTEMAS.md.
 */
public final class ApiSistemas{
    /** Separación extra entre cuerpos de una misma órbita, en unidades del sistema solar (default declarado). */
    public static final float MARGEN_ORBITA = 2f;
    static final String MENSAJE_CAPACIDAD = "no hay espacio en esta trayectoria; crea otro sistema solar";
    static final String MENSAJE_HUECO = "no queda hueco libre con separación mínima en esta trayectoria; crea otro sistema solar";
    private static final double DOS_PI = 2.0 * Math.PI, TOLERANCIA = 1e-9;

    /** Motivo de un resultado. */
    public enum Motivo{ OK, CAPACIDAD_AGOTADA, SIN_HUECO, ID_REPETIDO, DATOS_INVALIDOS }

    /** Resultado de una creación. Si no es OK, {@link #sistema} es null y {@link #mensaje} explica el motivo. */
    public static final class Resultado{
        public final boolean ok;
        public final Motivo motivo;
        public final String mensaje;
        public final EstadoSistemas.SistemaSolar sistema;

        Resultado(boolean ok, Motivo motivo, String mensaje, EstadoSistemas.SistemaSolar sistema){
            this.ok = ok;
            this.motivo = motivo;
            this.mensaje = mensaje;
            this.sistema = sistema;
        }
    }

    /**
     * Política de slot. Recibe los ángulos ocupados (ordenados, en [0, 2π)), el radio de la órbita, el diámetro del cuerpo nuevo y el margen.
     * Devuelve el ángulo del nuevo slot en radianes, o NaN si no cabe. Debe ser determinista: solo depende de sus argumentos.
     */
    @FunctionalInterface
    public interface PoliticaSlot{
        double angulo(double[] ocupados, double radio, double diametro, double margen);
    }

    private static final class Politica{
        final String id;
        final int prioridad;
        final PoliticaSlot politica;
        boolean activa = true;

        Politica(String id, int prioridad, PoliticaSlot politica){
            this.id = id;
            this.prioridad = prioridad;
            this.politica = politica;
        }
    }

    private static final ArrayList<Politica> politicas = new ArrayList<Politica>();

    private ApiSistemas(){}

    // ================================================================== capacidad y política

    /** Capacidad nominal de una órbita: floor(2πr / (diámetro + margen)). 0 si algún dato no es válido. */
    public static int capacidad(double radio, double diametro, double margen){
        final double arco = diametro + margen;
        if(!(radio > 0.0) || !(arco > 0.0) || Double.isInfinite(radio) || Double.isInfinite(arco)) return 0;
        final double c = Math.floor(DOS_PI * radio / arco);
        return c >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)c;
    }

    /** Política por defecto: centro del hueco mayor (en empate, el primero en orden de ángulo). NaN si ningún hueco da dos separaciones. */
    public static double politicaPorDefecto(double[] ocupados, double radio, double diametro, double margen){
        if(!(radio > 0.0) || !(diametro + margen > 0.0)) return Double.NaN;
        if(ocupados.length == 0) return 0.0;
        final double minSep = (diametro + margen) / radio;
        final double[] o = ocupados.clone();
        Arrays.sort(o);
        double mejorHueco = -1.0, mejorInicio = 0.0;
        for(int i = 0; i < o.length; i++){
            final double a = o[i];
            final double b = i + 1 < o.length ? o[i + 1] : o[0] + DOS_PI;
            final double hueco = b - a;
            if(hueco > mejorHueco){
                mejorHueco = hueco;
                mejorInicio = a;
            }
        }
        if(mejorHueco < 2.0 * minSep) return Double.NaN;
        return normalizar(mejorInicio + mejorHueco / 2.0);
    }

    /**
     * Propone una política de slot. Gana la de mayor prioridad (en empate, el id en orden alfabético). Devuelve false si el id es nulo,
     * la política es nula o el id ya existe.
     */
    public static boolean proponerPolitica(String id, int prioridad, PoliticaSlot politica){
        if(id == null || politica == null) return false;
        for(Politica p : politicas) if(p.id.equals(id)) return false;
        politicas.add(new Politica(id, prioridad, politica));
        return true;
    }

    /** Quita todas las políticas propuestas. Solo para pruebas. */
    static void reiniciarPoliticas(){ politicas.clear(); }

    /** Ángulo del nuevo slot según la política activa de mayor prioridad. Un fallo la desactiva con aviso y se prueba la siguiente. */
    static double anguloSlot(double[] ocupados, double radio, double diametro, double margen){
        final ArrayList<Politica> orden = new ArrayList<Politica>(politicas);
        orden.sort((a, b) -> a.prioridad != b.prioridad ? Integer.compare(b.prioridad, a.prioridad) : a.id.compareTo(b.id));
        for(Politica p : orden){
            if(!p.activa) continue;
            try{
                return p.politica.angulo(ocupados.clone(), radio, diametro, margen);
            }catch(RuntimeException e){
                p.activa = false;
                Log.err("[MundoInfinito] La política de slot '@' falla y se desactiva: @", p.id, e.toString());
            }
        }
        return politicaPorDefecto(ocupados, radio, diametro, margen);
    }

    /** Distancia angular mínima (radianes, circular) entre un ángulo y los ocupados. Sin ocupados: 2π. */
    static double separacionMinima(double angulo, double[] ocupados){
        double min = DOS_PI;
        for(double o : ocupados){
            final double d = normalizar(angulo - o);
            min = Math.min(min, Math.min(d, DOS_PI - d));
        }
        return min;
    }

    static double normalizar(double angulo){
        final double r = angulo % DOS_PI;
        return r < 0.0 ? r + DOS_PI : r;
    }

    // ================================================================== órbitas compartidas (F4)

    /**
     * Crea un mundo de un tipo de planeta en su órbita compartida (D16, D17). Si el sistema ya existe, el cuerpo se añade a él; si no, se
     * crea con posición (0, 0) y sin velocidad. El radio de la órbita lo fija el primer mundo de ese tipo.
     */
    public static Resultado anadirMundoEnOrbita(EstadoSistemas estado, String idSistema, String idMundo, String tipoPlaneta,
                                                double radio, double diametro){
        if(estado == null || !identificadorValido(idSistema) || !identificadorValido(idMundo) || !identificadorValido(tipoPlaneta)){
            return fallo(Motivo.DATOS_INVALIDOS, "identificadores no válidos");
        }
        if(!valorValido(diametro)) return fallo(Motivo.DATOS_INVALIDOS, "diámetro no válido");
        if(estado.buscarCuerpo(idMundo) != null) return fallo(Motivo.ID_REPETIDO, "ya existe un cuerpo con el id " + idMundo);

        EstadoSistemas.SistemaSolar sistema = estado.buscar(idSistema);
        if(sistema != null && !sistema.cuerpos().isEmpty() && sistema.cuerpos().get(0).orbita == null){
            return fallo(Motivo.DATOS_INVALIDOS, "el sistema " + idSistema + " es un planeta solitario y no admite mundos en órbita");
        }

        final double radioFijado = estado.radioOrbita(tipoPlaneta);
        final double r = Double.isNaN(radioFijado) ? radio : radioFijado;
        if(!valorValido(r)) return fallo(Motivo.DATOS_INVALIDOS, "radio de órbita no válido");

        final double[] ocupados = estado.angulosOrbita(tipoPlaneta);
        if(ocupados.length >= capacidad(r, diametro, MARGEN_ORBITA)) return fallo(Motivo.CAPACIDAD_AGOTADA, MENSAJE_CAPACIDAD);

        final double angulo = anguloSlot(ocupados, r, diametro, MARGEN_ORBITA);
        final double minSep = (diametro + MARGEN_ORBITA) / r;
        if(Double.isNaN(angulo) || separacionMinima(angulo, ocupados) < minSep - TOLERANCIA){
            return fallo(Motivo.SIN_HUECO, MENSAJE_HUECO);
        }

        final FichaFisica ficha = ApiFisica.estimadaPlaneta(tipoPlaneta, (float)diametro);
        final EstadoSistemas.Cuerpo cuerpo = new EstadoSistemas.Cuerpo(idMundo, ApiFisica.TIPO_PLANETA, tipoPlaneta, idMundo,
            tipoPlaneta, ocupados.length, r, angulo, ficha);
        if(sistema == null){
            sistema = new EstadoSistemas.SistemaSolar(idSistema, null, 0.0, 0.0, 0.0, 0.0, 0.0);
            estado.anadir(sistema);
        }
        sistema.anadirCuerpo(cuerpo);
        return new Resultado(true, Motivo.OK, "", sistema);
    }

    // ================================================================== planetas solitarios (F4, D18)

    /**
     * Crea un planeta solitario: un sistema de un solo cuerpo, con posición y velocidad propias y sin órbita compartida. No ocupa slot
     * de ninguna órbita (D18).
     */
    public static Resultado crearSolitario(EstadoSistemas estado, String idSistema, String idMundo, String tipoPlaneta,
                                           double x, double y, double vx, double vy, double tiempoRef, double diametro){
        if(estado == null || !identificadorValido(idSistema) || !identificadorValido(idMundo) || !identificadorValido(tipoPlaneta)){
            return fallo(Motivo.DATOS_INVALIDOS, "identificadores no válidos");
        }
        if(!valorValido(diametro)) return fallo(Motivo.DATOS_INVALIDOS, "diámetro no válido");
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(tiempoRef)){
            return fallo(Motivo.DATOS_INVALIDOS, "posición, velocidad o tiempo no válidos");
        }
        if(estado.buscar(idSistema) != null) return fallo(Motivo.ID_REPETIDO, "ya existe un sistema con el id " + idSistema);
        if(estado.buscarCuerpo(idMundo) != null) return fallo(Motivo.ID_REPETIDO, "ya existe un cuerpo con el id " + idMundo);

        final EstadoSistemas.SistemaSolar s = new EstadoSistemas.SistemaSolar(idSistema, null, x, y, vx, vy, tiempoRef);
        final FichaFisica ficha = ApiFisica.estimadaPlaneta(tipoPlaneta, (float)diametro);
        s.anadirCuerpo(new EstadoSistemas.Cuerpo(idMundo, ApiFisica.TIPO_PLANETA, tipoPlaneta, idMundo, null, -1, 0.0, 0.0, ficha));
        estado.anadir(s);
        return new Resultado(true, Motivo.OK, "", s);
    }

    // ================================================================== movimiento (F5, D20)

    /**
     * Posición de un sistema en el tiempo t: p0 + v·(t − tiempoRef). Lineal y exacta, sin integración. Con t no finito devuelve la
     * posición de referencia p0. Un sistema sin velocidad no se mueve.
     */
    public static double[] posicion(EstadoSistemas.SistemaSolar s, double t){
        if(!derivaActiva() || !Double.isFinite(t)) return new double[]{s.p0x, s.p0y};
        final double dt = t - s.tiempoRef;
        return new double[]{s.p0x + s.vx * dt, s.p0y + s.vy * dt};
    }

    /**
     * Ángulo de un cuerpo de órbita en el tiempo t: su ángulo inicial más 2π·t/periodo (simulado, sin integración). Un periodo
     * no válido (cero, negativo o no finito) deja el ángulo fijo en el de creación.
     */
    public static double anguloEn(EstadoSistemas.Cuerpo c, double t, double periodoTicks){
        if(!(periodoTicks > 0.0) || !Double.isFinite(periodoTicks) || !Double.isFinite(t)) return c.angulo;
        return normalizar(c.angulo + DOS_PI * t / periodoTicks);
    }

    /** Giro de un planeta sobre sí mismo en el tiempo t, según su periodo de rotación en ticks. Periodo no válido: 0. */
    public static double rotacionEn(double t, double periodoRotacionTicks){
        if(!(periodoRotacionTicks > 0.0) || !Double.isFinite(periodoRotacionTicks) || !Double.isFinite(t)) return 0.0;
        return normalizar(DOS_PI * t / periodoRotacionTicks);
    }

    /** Fuentes que han activado la deriva de los sistemas. Por defecto vacío: sin deriva. */
    private static final java.util.TreeSet<String> derivas = new java.util.TreeSet<String>();

    /** Activa la deriva de los sistemas desde una fuente (un addon, un id). Devuelve false si el id es vacío o ya estaba activo. */
    public static boolean activarDeriva(String fuente){
        if(fuente == null || fuente.isEmpty()) return false;
        return derivas.add(fuente);
    }

    /** Quita la activación de una fuente. Si ninguna queda, la deriva se apaga. */
    public static boolean desactivarDeriva(String fuente){ return derivas.remove(fuente); }

    /** ¿Hay alguna fuente que tenga activa la deriva? Por defecto, no. */
    public static boolean derivaActiva(){ return !derivas.isEmpty(); }

    /** Solo para pruebas. */
    static void reiniciarDeriva(){ derivas.clear(); }

    // ================================================================== auxiliares

    private static Resultado fallo(Motivo motivo, String mensaje){
        return new Resultado(false, motivo, mensaje, null);
    }

    private static boolean identificadorValido(String s){
        return s != null && !s.isEmpty() && s.length() <= 120;
    }

    private static boolean valorValido(double v){
        return Double.isFinite(v) && v > 0.0;
    }
}
