package mundoinfinito;

/**
 * PUNTO DE EXTENSIÓN: fenómenos puntuales con reglas de distancia (respiraderos, pozos, cráteres, géiseres...).
 *
 * El mod solo dice DÓNDE puede haber uno ({@link #proponer}), si el sitio es válido ({@link #valido}) y QUÉ escribe ({@link #aplicar});
 * la selección la hace el motor con muestreo de disco de Poisson por celdas: ningún par de fenómenos aceptados del mismo generador queda a
 * menos de {@link #distanciaMinima()}, el resultado es idéntico desde cualquier chunk y se puede garantizar un mínimo por macrocelda.
 *
 * Todo corre en hilos de fondo y debe ser puro (ver {@link GeneradorTerreno}). Coordenadas VIRTUALES.
 */
public interface GeneradorFenomeno{
    String id();

    default int prioridad(){
        return 0;
    }

    default boolean aplicaA(int dimension){
        return dimension <= 1;
    }

    /**
     * Peso en los conflictos de espacio entre generadores cuando el mundo tiene distancia mínima global entre fenómenos: cuando dos fenómenos de
     * distintas fuentes quedan más cerca que esa distancia, gana el de más peso con probabilidad proporcional (con peso 1 y 3, el segundo gana el 75 %).
     * Un peso menor o igual que 0 hace que sus fenómenos pierdan siempre los conflictos (pero se colocan donde no estorban).
     */
    default float peso(){
        return 1f;
    }

    default int presupuestoMs(){
        return 20;
    }

    /** Lado de la celda de candidatos, en casillas (mínimo 8). Cada celda produce como mucho un candidato. */
    int celda();

    /** Distancia mínima entre los centros de dos fenómenos aceptados de este generador, en casillas. */
    int distanciaMinima();

    /** Alcance máximo de lo que {@link #aplicar} escribe, medido desde el centro (determina qué chunks lo ven). */
    int radio();

    /** Intentos por celda: si el candidato de un intento no es válido se prueba el siguiente (reintentos acotados). */
    default int intentos(){
        return 3;
    }

    /** Lado de la macrocelda sobre la que se garantiza un mínimo. 0 = sin garantía. */
    default int macro(){
        return 0;
    }

    /** Mínimo de fenómenos por macrocelda (solo si {@link #macro()} > 0). Se alcanza con candidatos extra que respetan la distancia mínima. */
    default int minimoPorMacro(){
        return 0;
    }

    /**
     * PURA. Propone el centro del candidato de la celda (celdaX, celdaY) para el intento dado: escribe x e y VIRTUALES en out[0], out[1].
     * @return false si esa celda no tiene candidato en ese intento (p. ej. la probabilidad de la celda no se cumplió).
     */
    boolean proponer(ContextoFenomeno c, int celdaX, int celdaY, int intento, int[] out);

    /** PURA. ¿Puede haber un fenómeno centrado en (x, y)? (suelo permitido, zona libre, lejos del spawn...) */
    boolean valido(ContextoFenomeno c, int x, int y);

    /**
     * Escribe en el chunk de {@code ctx} lo que le toque al fenómeno centrado en (x, y). Se llama una vez por fenómeno aceptado cuyo
     * alcance toca el chunk; hay que ignorar las casillas fuera de él ({@link ContextoTerreno#contiene}). Debe depender solo de (x, y).
     */
    void aplicar(ContextoTerreno ctx, ContextoFenomeno c, int x, int y);
}
