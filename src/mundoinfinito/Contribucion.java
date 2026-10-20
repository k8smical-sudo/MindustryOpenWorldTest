package mundoinfinito;

/**
 * CONTRIBUCIÓN (plan definitivo, sección 5): la unidad con la que cualquier fuente (el mundo base, un mod, un modpack) aporta algo
 * al mundo. El núcleo es el único que las conoce y las evalúa; el resto del juego no sabe quién aportó qué.
 *
 * Contrato (determinismo): la función de aporte es PURA. Su salida depende solo de la semilla, la dimensión, las coordenadas, la versión
 * de generación y esta contribución. No lee el reloj, no usa estado compartido mutable, no toca el mundo vivo ni la GPU.
 *
 * Una contribución por tipo de aporte: ver {@link ContribucionTerreno}. Los demás tipos (fenómenos, estructuras) llegan en fases posteriores.
 */
interface Contribucion{
    enum Tipo{ TERRENO, FENOMENO, ESTRUCTURA, PARAMETRO }

    /** Identificador único con el formato "fuente:nombre". */
    String id();

    /** Id de la fuente (el mod, o "mundo-infinito" para el base). Entra en el orden canónico. */
    String fuente();

    /** Versión de esta contribución. Un cambio que altere su resultado debe subirla (y, si afecta al mundo, subir la versión de generación). */
    int version();

    Tipo tipo();

    /** Alcance: ¿se aplica en esta dimensión? (-1 mundos antiguos mezclados, 0 Serpulo, 1 Erekir, >= 16 mundos registrados). Por defecto, solo el mundo base. */
    default boolean aplicaA(int dimension){
        return dimension <= 1;
    }

    /** Peso para las políticas de resolución (fases 5 y 6). */
    default float peso(){
        return 1f;
    }

    /** Orden canónico: menor = antes. Los del mundo base usan valores negativos. */
    default int prioridad(){
        return 0;
    }

    /** Qué se hace cuando pide más unidades de las permitidas (fase 7). Por defecto, el recorte por orden canónico (D-N4). */
    default ReglaDegradacion reglaDegradacion(){
        return ReglaDegradacion.RECORTE_CANONICO;
    }

    /** Presupuesto declarado por chunk. Si no declara nada recibe el conservador (decisión D-N4). */
    default PresupuestoTrabajo presupuesto(){
        return PresupuestoTrabajo.CONSERVADOR;
    }
}
