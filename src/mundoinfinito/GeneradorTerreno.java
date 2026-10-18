package mundoinfinito;

/**
 * PUNTO DE EXTENSIÓN: modifica pisos, menas y muros de un chunk antes de publicarlo.
 *
 * Se ejecuta en HILOS DE FONDO, varios a la vez, y debe ser una FUNCIÓN PURA de (semilla, coordenadas): el mismo chunk tiene que salir
 * igual cada vez que se genere (el terreno nunca se guarda; se vuelve a calcular). Por eso:
 *   - solo se puede leer y escribir a través del {@link ContextoTerreno} (no existe acceso a Vars.world, edificios ni unidades);
 *   - la aleatoriedad sale de {@link ContextoTerreno#azar} / {@link ContextoTerreno#ruido}, que usan una semilla derivada del id del
 *     generador (dos mods nunca se pisan la aleatoriedad);
 *   - no se guarda estado entre llamadas que cambie el resultado.
 *
 * Las coordenadas que recibe son VIRTUALES (tile = chunk * 32 + 0..31), no locales.
 * Orden: de menor a mayor {@link #prioridad()}; si dos generadores escriben la misma casilla gana el último.
 * Registro: {@link OpenWorldAPI#registrarTerreno}, ANTES de abrir el primer mundo.
 */
public interface GeneradorTerreno{
    /** Identificador único, con el formato "idmod:nombre". De él sale la semilla del generador. */
    String id();

    /** Orden de ejecución (menor = antes). Los del mundo base usan valores negativos. */
    default int prioridad(){
        return 0;
    }

    /** ¿Se aplica en esta dimensión? (-1 mundos antiguos mezclados, 0 Serpulo, 1 Erekir, >= 16 mundos registrados). Por defecto: solo las del mundo base. */
    default boolean aplicaA(int dimension){
        return dimension <= 1;
    }

    /** Tiempo orientativo máximo por chunk. Si se supera se deja una advertencia en el registro (NO se difiere: eso rompería el determinismo). */
    default int presupuestoMs(){
        return 20;
    }

    /** Modifica el chunk. Cualquier excepción desactiva este generador para el mundo en curso (el chunk se publica igualmente). */
    void generar(ContextoTerreno ctx);
}
