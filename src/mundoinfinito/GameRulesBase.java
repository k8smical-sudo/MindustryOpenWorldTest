package mundoinfinito;

import mindustry.content.Planets;

/** Gamerules del propio mod base, registradas con la misma API que usarán los demás mods. */
final class GameRulesBase{
    private GameRulesBase(){}

    static final String CONSTRUCCION_GLOBAL = "mundo-infinito:construccion-global";

    static void registrar(){
        Extensiones.registrarGameRule(GameRule.nueva(
            CONSTRUCCION_GLOBAL,
            "Permitir construcción global",
            "Todo lo que el jugador pueda construir se puede construir en cualquier planeta, sin importar dónde esté. Se siguen necesitando los "
                + "materiales y se conserva el resto de la lógica de Mindustry.",
            false,
            (r, valor, dim) -> {
                if(!valor) return;
                // Mindustry decide qué se puede construir con dos filtros: el planeta de las reglas (Block.environmentBuildable -> isOnPlanet) y el
                // entorno (Block.isPlaceable -> supportsEnv). Planets.sun no filtra por planeta y el entorno combinado admite los bloques de ambos;
                // es lo mismo que ya usan los mundos antiguos de terreno mezclado. Los materiales los sigue pidiendo Build.validPlace.
                r.planet = Planets.sun;
                r.env |= MundoInfinitoMod.ENTORNO_COMBINADO;
            }));
    }
}
