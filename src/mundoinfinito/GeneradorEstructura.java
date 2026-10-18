package mundoinfinito;

/**
 * PUNTO DE EXTENSIÓN: plantillas (bases, ruinas, campamentos) colocadas sobre el terreno ya final.
 *
 * Dos partes:
 *  - {@link #ancla}: PURA y barata. Dice si la celda tiene una plantilla y dónde está su ancla. Es determinista (sale de la semilla), así
 *    que el mundo sabe qué estructuras le tocan sin guardar nada.
 *  - {@link #colocar}: HILO DEL JUEGO. Pone los edificios y unidades. El motor la llama UNA SOLA VEZ por ancla (la marca "generada" se guarda
 *    en el chunk del ancla con {@link AlmacenModulo}) y solo cuando todos los chunks que cubre la plantilla están cargados y con sus edificios
 *    guardados ya restaurados, para no pisar lo que el jugador construyó. Si el chunk se descarga antes de poder colocarla, se reintenta
 *    cuando vuelva a cargarse.
 *
 * Lo que se coloca se guarda con el chunk como cualquier edificio del jugador; nunca se regenera.
 */
public interface GeneradorEstructura{
    String id();

    default int prioridad(){
        return 0;
    }

    default boolean aplicaA(int dimension){
        return dimension <= 1;
    }

    /** Lado de la celda de anclas, en casillas (mínimo 16). */
    int celda();

    /** Radio máximo de la plantilla desde el ancla, en casillas. Fija qué chunks deben estar cargados para colocarla. */
    int radio();

    /** PURA. Escribe el ancla VIRTUAL de la celda en out[0], out[1]. @return false si la celda no tiene estructura. */
    boolean ancla(ContextoFenomeno c, int celdaX, int celdaY, int[] out);

    /** HILO DEL JUEGO. Coloca la estructura. Si lanza una excepción el ancla queda marcada igualmente (no se reintenta en bucle). */
    void colocar(ContextoColocacion ctx);
}
