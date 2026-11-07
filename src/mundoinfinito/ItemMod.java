package mundoinfinito;

import arc.graphics.Color;
import mindustry.type.Item;
import mindustry.type.Planet;

/**
 * Ítem del mod. Igual que un {@link Item} normal, pero se oculta en el modo campaña salvo que {@link ApiContenido} lo permita. Clase de paquete: los
 * addons crean sus ítems como quieran y, si les interesa la misma regla, llaman a {@link ApiContenido#ocultoAhora}.
 */
final class ItemMod extends Item{
    ItemMod(String nombre, Color color){
        super(nombre, color);
    }

    @Override
    public boolean isHidden(){
        return super.isHidden() || ApiContenido.ocultoAhora(name);
    }

    @Override
    public boolean isOnPlanet(Planet planeta){
        return super.isOnPlanet(planeta) && !ApiContenido.ocultoAhora(name);
    }
}
