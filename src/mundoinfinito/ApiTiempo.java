package mundoinfinito;

import arc.func.Cons;

/**
 * API PÚBLICA DE TIEMPO, CALENDARIO Y EVENTOS (versión {@link ApiVersion#TIEMPO_MAYOR}.{@link ApiVersion#TIEMPO_MENOR}).
 *
 * El tiempo del mundo avanza jugando (no en pausa ni con el juego cerrado) y se guarda con el mundo. Un evento se programa para un tiempo y se
 * dispara una vez, en el fotograma en que se alcanza. Su acción la registra el mod por id al cargarse; si no está registrada, el evento espera.
 * Ver docs/TIEMPO_CALENDARIO.md.
 */
public final class ApiTiempo{
    private ApiTiempo(){}

    /** Tiempo de juego del mundo abierto, en ticks (60 por segundo). 0 si no hay mundo abierto. */
    public static float tiempoMundo(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null || m.calendario == null ? 0f : m.calendario.tiempo();
    }

    /** Día del mundo abierto (empieza en 0). */
    public static int dia(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null || m.calendario == null ? 0 : m.calendario.dia();
    }

    /** Temporada del mundo abierto: primavera, verano, otoño o invierno. */
    public static String temporada(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m == null || m.calendario == null ? CalendarioMundo.TEMPORADAS[0] : m.calendario.temporada();
    }

    /**
     * Registra la acción de un evento (id "fuente:nombre"). Hay que registrarla al cargar el mod, antes de abrir el mundo, para que los eventos guardados
     * puedan dispararse al reabrirlo. Un id repetido devuelve false.
     */
    public static boolean registrarAccion(String id, Cons<EventoTiempo> accion){
        return CalendarioMundo.registrarAccion(id, accion);
    }

    /** Programa un evento para un tiempo del mundo abierto. Un tiempo ya pasado, o sin mundo abierto, devuelve false. */
    public static boolean programar(String id, float tiempoObjetivo){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m != null && m.calendario != null && m.calendario.programar(id, tiempoObjetivo);
    }

    /**
     * Programa un evento de SEGUNDO PLANO: se dispara cuando han pasado {@code segundos} de reloj real, aunque el juego esté cerrado; si estaba cerrado,
     * se aplica al volver, una sola vez. NO es determinista: depende de cuándo se cerró el juego. Úsalo para efectos que no cambian el terreno ni la
     * simulación (avisos, recompensas, notas al jugador). Para cambios del mundo, usa {@link #programar(String, float)}.
     */
    public static boolean programarDeFondo(String id, long segundos){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        return m != null && m.calendario != null && segundos >= 0L
            && m.calendario.programarPared(id, System.currentTimeMillis() + segundos * 1000L);
    }

    /** Suscribe a un evento: recibe una copia cada vez que se dispara. Sin suscriptores un evento no cuesta nada extra. */
    public static boolean suscribir(String id, Cons<EventoTiempo> suscriptor){
        return CalendarioMundo.suscribir(id, suscriptor);
    }
}
