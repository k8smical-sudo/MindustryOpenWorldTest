package mundoinfinito;

/** Un evento de tiempo entregado a sus suscriptores. Es inmutable. */
public final class EventoTiempo{
    private final String id;
    private final float tiempo;
    private final int dia;
    private final String temporada;

    EventoTiempo(String id, float tiempo){
        this(id, tiempo, CalendarioMundo.diaEn(tiempo), CalendarioMundo.temporadaEn(tiempo));
    }

    EventoTiempo(String id, float tiempo, int dia, String temporada){
        this.id = id;
        this.tiempo = tiempo;
        this.dia = dia;
        this.temporada = temporada;
    }

    /** Id del evento ("fuente:nombre"). */
    public String id(){ return id; }

    /** Tiempo de juego, en ticks, en el que el evento debía ocurrir (no el momento en que se entregó). */
    public float tiempo(){ return tiempo; }

    /** Día del mundo en el que ocurre (empieza en 0). */
    public int dia(){ return dia; }

    /** Temporada del día: primavera, verano, otoño o invierno. */
    public String temporada(){ return temporada; }
}
