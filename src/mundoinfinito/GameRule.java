package mundoinfinito;

import mindustry.game.Rules;

/**
 * PUNTO DE EXTENSIÓN (v2): una regla de juego que el jugador activa o desactiva al CREAR un mundo (botón "Gamerules" del diálogo de nuevo
 * mundo). El valor elegido se guarda en el mundo y se aplica sobre las reglas de Mindustry cada vez que se abre y al cruzar un portal.
 *
 * El motor solo guarda y entrega el valor; qué hace la regla lo decide el mod que la registra en {@link #aplicacion}.
 * Registro: {@link OpenWorldAPI#registrarGameRule}, en cualquier momento antes de crear el mundo (normalmente al cargar el mod).
 * Consulta en juego: {@link OpenWorldAPI#gameRule(String)}.
 *
 * Los mundos creados antes de que existiera la regla (o sin ella) usan {@link #porDefecto}.
 */
public final class GameRule{
    /** Cómo se aplica la regla. Se llama en el hilo del juego cada vez que se configuran las reglas del mundo (abrir, continuar, portal). */
    public interface Aplicacion{
        /**
         * @param reglas    reglas de Mindustry del mundo, ya configuradas por el mod base (planeta, entorno, niebla...) y todavía sin los ajustes de
         *                  {@link OpenWorldAPI#registrarReglas}, que se aplican después
         * @param valor     valor elegido por el jugador para este mundo
         * @param dimension dimensión actual: -1 mundo antiguo mezclado, 0 Serpulo, 1 Erekir, >= 16 mundo registrado por un mod
         */
        void aplicar(Rules reglas, boolean valor, int dimension);
    }

    /** Identificador único con el formato "idmod:nombre". Es la clave con la que se guarda el valor en el mundo. */
    public final String id;
    public final String nombre;
    public final String descripcion;
    public final boolean porDefecto;
    public final Aplicacion aplicacion;

    private GameRule(String id, String nombre, String descripcion, boolean porDefecto, Aplicacion aplicacion){
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion == null ? "" : descripcion;
        this.porDefecto = porDefecto;
        this.aplicacion = aplicacion;
    }

    /**
     * @param descripcion texto corto que se muestra bajo el interruptor (puede ser null)
     * @param aplicacion  qué cambia la regla en las reglas de Mindustry; se llama siempre, con el valor elegido (también si es false)
     */
    public static GameRule nueva(String id, String nombre, String descripcion, boolean porDefecto, Aplicacion aplicacion){
        return new GameRule(id, nombre, descripcion, porDefecto, aplicacion);
    }
}
