package mundoinfinito;

import arc.Core;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.type.Item;
import mindustry.ui.Styles;

/**
 * BARRA DE OBJETIVOS DE SECTOR (plan v9, P11). Capa de interfaz: lee el núcleo del jugador y pasa las cantidades a {@link ApiObjetivos}.
 * Se muestra solo con el gamerule activo, en partida, y con al menos un objetivo registrado.
 */
final class ObjetivosHud{
    private ObjetivosHud(){}

    /** Añade la barra a la pantalla. Llamar una vez al cargar el cliente. */
    static void crear(){
        if(Vars.headless || Core.scene == null) return;
        Core.scene.add(new Table(t -> {
            t.setFillParent(true);
            t.top();
            t.visible(ObjetivosHud::visible);
            t.label(ObjetivosHud::texto).style(Styles.outlineLabel).padTop(96f).touchable(arc.scene.Touchable.disabled);
        }));
    }

    private static boolean visible(){
        return Vars.state != null && Vars.state.isGame() && ApiObjetivos.activa() && !ApiObjetivos.objetivos().isEmpty();
    }

    private static CharSequence texto(){
        final StringBuilder sb = new StringBuilder();
        for(ApiObjetivos.Objetivo o : ApiObjetivos.objetivos()){
            if(sb.length() > 0) sb.append('\n');
            sb.append(ApiObjetivos.texto(o, cantidadEnNucleo(o.item)));
        }
        return sb;
    }

    /** Cantidad de un ítem en el núcleo del equipo del jugador. 0 si no hay núcleo o el ítem no existe. */
    static int cantidadEnNucleo(String nombre){
        try{
            if(Vars.player == null || Vars.player.team() == null || Vars.player.team().core() == null) return 0;
            final Item it = Vars.content.getByName(mindustry.ctype.ContentType.item, nombre);
            if(it == null) return 0;
            return Vars.player.team().core().items.get(it);
        }catch(Throwable t){
            return 0;
        }
    }
}
