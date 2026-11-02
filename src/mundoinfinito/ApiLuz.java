package mundoinfinito;

/**
 * API PÚBLICA DE LUZ SOLAR (plan v4, D11; estrellas en 1.2, plan v9 P8).
 * Estrellas: un cuerpo puede tener asignada una estrella con intensidad propia. Sin asignación la intensidad es 1, así que el comportamiento por
 * defecto no cambia.
 * La luz de cada planeta sale de su sistema solar: por defecto solo baja por eclipse (un cuerpo se
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

    /**
     * Factor de luz de un planeta en este instante. 1 = sin cambio; menor = menos luz; mayor = más luz (limitado a 2).
     * Si el modificador lanza una excepción o devuelve un valor no finito, queda desactivado (con un aviso en el log) hasta quitarlo o reiniciar el juego;
     * el resto de modificadores y el eclipse siguen funcionando.
     */
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

    /** Factor de luz de un planeta en este instante (eclipse por defecto por los modificadores registrados y la intensidad de su estrella). */
    public static float factorPlaneta(String planeta){
        return LuzSolar.factorPlaneta(planeta);
    }

    // ================================================================== estrellas (1.2, plan v9 P8)

    /**
     * Registra una estrella con su intensidad (0 a 2; 1 = la luz de referencia). Una estrella sin registrar no ilumina a nadie. Solo antes de
     * crear mundos. Devuelve false si el id no es válido, la intensidad está fuera de rango o el id ya existe.
     */
    public static boolean registrarEstrella(String id, float intensidad){
        return LuzSolar.registrarEstrella(id, intensidad);
    }

    /**
     * Asigna a un cuerpo (planeta base o cuerpo registrado con {@link #registrarCuerpo}) la estrella que lo ilumina. Pasar null quita la
     * asignación. Devuelve false si el cuerpo no existe o la estrella no está registrada.
     * Sin asignación, el cuerpo tiene intensidad 1: los mundos existentes no cambian de luz.
     */
    public static boolean asignarEstrella(String cuerpo, String estrella){
        return LuzSolar.asignarEstrella(cuerpo, estrella);
    }

    /** Intensidad de la estrella que ilumina un cuerpo. 1 si el cuerpo no tiene estrella asignada. */
    public static float intensidadEstrellaDe(String cuerpo){
        return LuzSolar.intensidadEstrellaDe(cuerpo);
    }
}
