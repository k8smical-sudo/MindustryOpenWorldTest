package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;

/**
 * INICIO DE LA HORA DE UN MUNDO NUEVO (plan v8, respuesta de hora aleatoria). Por defecto, una hora aleatoria dentro del ciclo
 * día-noche, determinista por semilla: la misma semilla empieza siempre a la misma hora, y semillas distintas empiezan a horas distintas.
 *
 * API: un addon puede proponer otro inicio con {@link #proponer}. Gana el de mayor prioridad. Si falla o devuelve un valor no válido,
 * se desactiva con aviso y se usa el siguiente, o el de defecto. Sin Arc salvo el log de avisos.
 */
public final class ApiInicioMundo{
    /** Canal de azar fijo: el resultado no cambia entre versiones para la misma semilla. */
    static final String CANAL = "inicio-hora";

    /** Propuesta de hora inicial en ticks (0 a un día). */
    @FunctionalInterface
    public interface Inicio{
        float ticks(int semilla);
    }

    private static final class Registro{
        final String id;
        final int prioridad;
        final Inicio inicio;
        boolean activa = true;

        Registro(String id, int prioridad, Inicio inicio){
            this.id = id;
            this.prioridad = prioridad;
            this.inicio = inicio;
        }
    }

    private static final ArrayList<Registro> registros = new ArrayList<Registro>();

    private ApiInicioMundo(){}

    /** Hora aleatoria dentro de un día del ciclo día-noche, determinista por semilla. */
    public static float inicioPorDefecto(int semilla){
        return ApiAzar.unidad(semilla, 0, CANAL, 0, 0) * CicloDiaNoche.DIA_TICKS;
    }

    /** Propone un inicio. Devuelve false si el id es nulo, la propuesta es nula o el id ya existe. */
    public static boolean proponer(String id, int prioridad, Inicio inicio){
        if(id == null || inicio == null) return false;
        for(Registro r : registros) if(r.id.equals(id)) return false;
        registros.add(new Registro(id, prioridad, inicio));
        return true;
    }

    /** Solo para pruebas. */
    static void reiniciar(){ registros.clear(); }

    /** Hora inicial de un mundo nuevo con esa semilla: la propuesta activa de mayor prioridad, o la de defecto. */
    public static float inicio(int semilla){
        final ArrayList<Registro> orden = new ArrayList<Registro>(registros);
        orden.sort((a, b) -> a.prioridad != b.prioridad ? Integer.compare(b.prioridad, a.prioridad) : a.id.compareTo(b.id));
        for(Registro r : orden){
            if(!r.activa) continue;
            try{
                final float v = r.inicio.ticks(semilla);
                if(Float.isFinite(v) && v >= 0f) return v;
                throw new IllegalStateException("hora no válida: " + v);
            }catch(RuntimeException e){
                r.activa = false;
                Log.err("[MundoInfinito] El inicio de hora '@' falla y se desactiva: @", r.id, e.toString());
            }
        }
        return inicioPorDefecto(semilla);
    }
}
