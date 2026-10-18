package mundoinfinito;

/**
 * Genera el terreno COMPLETO de un {@link MundoRegistrado}: sustituye al terreno base. Recibe el chunk con piso de piedra y sin mena ni
 * muros; debe rellenarlo con una función pura de (semilla, coordenadas virtuales). Corre en hilos de fondo (ver {@link ContextoTerreno}).
 */
public interface GeneradorMundo{
    void generar(ContextoTerreno ctx);
}
