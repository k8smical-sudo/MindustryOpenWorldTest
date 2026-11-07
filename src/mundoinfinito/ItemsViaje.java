package mundoinfinito;

import arc.graphics.Color;
import mindustry.type.Item;

/**
 * ÍTEMS DE LOS BOOSTERS DE VIAJE (plan v9, P7). Tres ítems que se guardan en el núcleo y se consumen, uno de cada tipo usado, en cada viaje o enlace
 * que los pida ({@link ApiViaje#BOOSTER_ALCANCE}, {@link ApiViaje#BOOSTER_VELOCIDAD}, {@link ApiViaje#BOOSTER_ESTABILIDAD}).
 *
 * El juego base no trae recetas para fabricarlos: de dónde salen lo decide cada addon (fábricas, botín, recompensas). Con ninguno disponible, los
 * viajes funcionan igual, sin boosters. Los nombres internos son los de {@link ApiViaje}.
 */
final class ItemsViaje{
    static Item alcance, velocidad, estabilidad;

    private ItemsViaje(){}

    static void cargar(){
        alcance = nuevo(ApiViaje.ITEM_BOOSTER_ALCANCE, "7fd8ff", "Booster de alcance",
            "Amplía el alcance de un portal un 25 % durante un enlace. Se consume al usarlo.");
        velocidad = nuevo(ApiViaje.ITEM_BOOSTER_VELOCIDAD, "ffd37f", "Booster de velocidad",
            "Reduce un 20 % el tiempo de un viaje. Se consume al usarlo.");
        estabilidad = nuevo(ApiViaje.ITEM_BOOSTER_ESTABILIDAD, "9fff9f", "Booster de estabilidad",
            "Divide a la mitad la probabilidad de incidente de un viaje. Se consume al usarlo.");
    }

    private static Item nuevo(String nombre, String color, String titulo, String texto){
        final Item it = new ItemMod(nombre, Color.valueOf(color));   // se oculta en campaña salvo que ApiContenido lo permita
        it.localizedName = titulo;
        it.description = texto;
        it.alwaysUnlocked = true;
        return it;
    }
}
