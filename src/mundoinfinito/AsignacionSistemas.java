package mundoinfinito;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * ASIGNACIÓN DE SISTEMAS A LOS MUNDOS (plan v8, P1). Cada mundo guarda en su meta UN cuerpo: el que ocupa en la órbita compartida de su
 * planeta base (o, si esa órbita no tiene hueco, un planeta solitario). El estado global (todas las órbitas, con todos los mundos) NO se guarda:
 * se une a partir de los cuerpos de cada mundo con {@link #unir}.
 *
 * Reglas (declaradas, R8):
 * - Un mundo nuevo ocupa el mejor hueco de la órbita de su planeta base ({@link ApiSistemas}); el slot que obtiene queda guardado y no se mueve nunca.
 * - Si la órbita no tiene hueco, el mundo es un planeta solitario con posición determinista por semilla (D18), fuera de la órbita mayor.
 * - Un mundo antiguo sin cuerpo se migra al leerlo: en orden de creación (y de id en empate), después de los que ya tienen cuerpo. Migrar dos
 *   veces da el mismo resultado (la segunda vez no hay nada que migrar).
 *
 * Sin Arc: se prueba en CI sin el juego. Clase de paquete (R4): la API pública de sistemas es {@link ApiSistemas}.
 */
final class AsignacionSistemas{
    static final String PREFIJO_SISTEMA = "sistema-", PREFIJO_SOLITARIO = "solitario-";
    /** Un solitario aparece entre 1,5 y 3 veces el radio de la órbita de su planeta base. */
    static final double SOLITARIO_MIN = 1.5, SOLITARIO_RANGO = 1.5;

    private AsignacionSistemas(){}

    /** Un mundo guardado, visto por la asignación. {@code estado} es lo que tiene guardado (puede estar vacío). */
    static final class Entrada{
        final String idMundo, planeta;
        final int semilla;
        final long creado;
        EstadoSistemas estado;

        Entrada(String idMundo, String planeta, int semilla, long creado, EstadoSistemas estado){
            this.idMundo = idMundo;
            this.planeta = planeta;
            this.semilla = semilla;
            this.creado = creado;
            this.estado = estado == null ? new EstadoSistemas() : estado;
        }
    }

    /** Id del sistema compartido de un planeta base. */
    static String idSistema(String planeta){
        return PREFIJO_SISTEMA + planeta;
    }

    /** Sistema que contiene el cuerpo propio del mundo, o null. */
    static EstadoSistemas.SistemaSolar sistemaDe(EstadoSistemas e, String idMundo){
        if(e == null) return null;
        for(EstadoSistemas.SistemaSolar s : e.sistemas()){
            for(EstadoSistemas.Cuerpo c : s.cuerpos()) if(c.id.equals(idMundo)) return s;
        }
        return null;
    }

    /** Cuerpo propio del mundo (el de id igual al del mundo), o null si el mundo no tiene ninguno. */
    static EstadoSistemas.Cuerpo cuerpoPropio(EstadoSistemas e, String idMundo){
        return e == null ? null : e.buscarCuerpo(idMundo);
    }

    /** Estado de un solo cuerpo: lo que se guarda en la meta de un mundo. */
    private static EstadoSistemas soloPropio(EstadoSistemas.SistemaSolar s, EstadoSistemas.Cuerpo c){
        final EstadoSistemas out = new EstadoSistemas();
        final EstadoSistemas.SistemaSolar copia = new EstadoSistemas.SistemaSolar(s.id, s.estrella, s.p0x, s.p0y, s.vx, s.vy, s.tiempoRef);
        copia.anadirCuerpo(c);
        out.anadir(copia);
        return out;
    }

    /**
     * Estado global: une el cuerpo propio de cada entrada (los ajenos y los repetidos se ignoran). Los cuerpos de un mismo sistema quedan
     * en un solo sistema. No llama a ninguna consecuencia de órbita: solo reconstruye lo ya guardado.
     */
    static EstadoSistemas unir(List<Entrada> entradas){
        final EstadoSistemas global = new EstadoSistemas();
        for(Entrada en : entradas){
            final EstadoSistemas.Cuerpo c = cuerpoPropio(en.estado, en.idMundo);
            if(c == null || global.buscarCuerpo(c.id) != null) continue;
            final EstadoSistemas.SistemaSolar s = sistemaDe(en.estado, en.idMundo);
            EstadoSistemas.SistemaSolar destino = global.buscar(s.id);
            if(destino == null){
                destino = new EstadoSistemas.SistemaSolar(s.id, s.estrella, s.p0x, s.p0y, s.vx, s.vy, s.tiempoRef);
                global.anadir(destino);
            }
            destino.anadirCuerpo(c);
        }
        return global;
    }

    /**
     * Asigna un cuerpo a un mundo y lo añade al estado global. Devuelve el estado de un cuerpo que se guarda en el mundo, o null si no se pudo
     * (datos no válidos): entonces el mundo se queda sin cuerpo y el visor no lo muestra, pero el mundo funciona igual.
     */
    static EstadoSistemas asignar(EstadoSistemas global, String idMundo, String planeta, int semilla, double radio, double diametro){
        final ApiSistemas.Resultado r = ApiSistemas.anadirMundoEnOrbita(global, idSistema(planeta), idMundo, planeta, radio, diametro);
        if(r.ok) return soloPropio(r.sistema, global.buscarCuerpo(idMundo));

        // Sin hueco (o sin capacidad): planeta solitario, con posición determinista por semilla.
        final double radioOrbita = Double.isNaN(global.radioOrbita(planeta)) ? radio : global.radioOrbita(planeta);
        final double angulo = 2.0 * Math.PI * ApiAzar.unidad(semilla, 0, "sistemas-solitario-angulo", 0, 0);
        final double dist = radioOrbita * (SOLITARIO_MIN + SOLITARIO_RANGO * ApiAzar.unidad(semilla, 0, "sistemas-solitario-distancia", 0, 0));
        final ApiSistemas.Resultado s = ApiSistemas.crearSolitario(global, PREFIJO_SOLITARIO + idMundo, idMundo, planeta,
            Math.cos(angulo) * dist, Math.sin(angulo) * dist, 0.0, 0.0, 0.0, diametro);
        return s.ok ? soloPropio(s.sistema, global.buscarCuerpo(idMundo)) : null;
    }

    /**
     * Migra los mundos sin cuerpo: les asigna uno, en orden de creación. Devuelve las entradas cambiadas (su {@code estado} ya es el nuevo),
     * que quien llama debe guardar. Con todos los mundos ya asignados no cambia nada y devuelve una lista vacía.
     */
    static List<Entrada> migrar(List<Entrada> entradas, ToDoubleFunction<String> radioDe){
        final EstadoSistemas global = unir(entradas);
        final ArrayList<Entrada> pendientes = new ArrayList<Entrada>();
        for(Entrada en : entradas) if(cuerpoPropio(en.estado, en.idMundo) == null && global.buscarCuerpo(en.idMundo) == null) pendientes.add(en);
        pendientes.sort(Comparator.<Entrada>comparingLong(a -> a.creado).thenComparing(a -> a.idMundo));
        final ArrayList<Entrada> cambiadas = new ArrayList<Entrada>();
        for(Entrada en : pendientes){
            final EstadoSistemas nuevo = asignar(global, en.idMundo, en.planeta, en.semilla, radioDe.applyAsDouble(en.planeta), ApiFisica.DIAMETRO_PLANETA);
            if(nuevo == null) continue;
            en.estado = nuevo;
            cambiadas.add(en);
        }
        return cambiadas;
    }
}
