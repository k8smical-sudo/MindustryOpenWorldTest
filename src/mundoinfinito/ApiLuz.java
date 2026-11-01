package mundoinfinito;

/**
 * API PÚBLICA DE LUZ SOLAR (plan v4, D11). La luz de cada planeta sale de su sistema solar: por defecto solo baja por eclipse (un cuerpo se
 * interpone entre la estrella y el planeta). Un addon puede añadir modificadores que bloquean, reflejan o añaden luz, y registrar cuerpos nuevos.
 * Los modificadores se multiplican en orden canónico (prioridad, id de fuente, id): el resultado no depende del orden de carga.
 * Distancias y radios son unidades relativas del sistema solar (la estrella tiene radio 0,4).
 */
public final class ApiLuz{
    private ApiLuz(){}

    /** Fase de un cuerpo en su órbita: 0 a 1 de vuelta. */
    public interface Fase{
        double fase();
    }

    /** Factor de luz de un planeta en este instante. 1 = sin cambio; menor = menos luz; mayor = más luz (limitado a 2). */
    public interface Modificador{
        float factor(String planeta, double fase);
    }

    /** Registra un cuerpo que proyecta sombra (planeta, luna, estación). Solo antes de crear mundos. */
    public static boolean registrarCuerpo(String id, float radioOrbita, float radioCuerpo, Fase fase){
        return LuzSolar.registrarCuerpo(id, radioOrbita, radioCuerpo, fase);
    }

    /** Registra un modificador de luz. Devuelve false si el id no es válido o ya existe. */
    public static boolean registrarModificador(String fuente, String id, int prioridad, Modificador modificador){
        return LuzSolar.registrarModificador(fuente, id, prioridad, modificador);
    }

    /** Quita un modificador registrado. */
    public static boolean quitarModificador(String id){
        return LuzSolar.quitarModificador(id);
    }

    /** Factor de luz de un planeta en este instante (eclipse por defecto por los modificadores registrados). */
    public static float factorPlaneta(String planeta){
        return LuzSolar.factorPlaneta(planeta);
    }
}
