package mundoinfinito;

import mindustry.gen.Unit;

/**
 * PUNTO DE EXTENSIÓN: permite que las unidades de un mod duerman fuera de la zona activa (se guardan con su chunk y vuelven al acercarse el
 * jugador), igual que las unidades aliadas del mundo base, sin que el base conozca esas unidades.
 *
 * Se consulta en el hilo del juego, cada ~0,5 s, para cada unidad que no es aliada ni del jugador. Se conservan tipo, equipo, posición, vida,
 * escudo y orientación (no estado interno de IA ni cargamento).
 */
public interface PoliticaSueno{
    String id();

    /** ¿Debe dormir esta unidad cuando queda fuera de la zona activa? Debe ser barata y no tener efectos secundarios. */
    boolean duerme(Unit unidad);
}
