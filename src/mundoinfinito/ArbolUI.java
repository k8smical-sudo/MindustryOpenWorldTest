package mundoinfinito;

import arc.Core;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.type.ItemStack;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import java.util.HashMap;
import java.util.Map;

/**
 * INTERFAZ DEL ÁRBOL TECNOLÓGICO POR PLANETA (plan v2, M3).
 *
 *  - BOTÓN "Árbol" en la partida, arriba a la izquierda. Solo aparece con un mundo dimensional abierto y el árbol activo en ese mundo.
 *  - DIÁLOGO: un selector con los planetas que tienen árbol (detectados por {@link ApiArbol#planetas()}), y debajo los nodos del planeta actual.
 *    Cada nodo muestra su nombre, su estado, su coste, lo que falta en el núcleo y el botón de investigar. Un nodo que comparte sección con otros
 *    planetas lo indica.
 *
 * Para un modder: no necesita nada de esta clase. Los datos y las acciones están en {@link ApiArbol}; esta clase solo los muestra.
 */
final class ArbolUI{
    private static boolean creado;

    private ArbolUI(){}

    /** Crea el botón de la partida. Llamar una vez al cargar el cliente (ClientLoadEvent). */
    static void crear(){
        if(creado || Vars.headless || Core.scene == null) return;
        creado = true;
        Core.scene.add(new Table(t -> {
            t.setFillParent(true);
            t.top().left();
            t.visible(() -> MundoInfinitoMod.Streamer.activo && Vars.state.isGame() && ApiArbol.activo()
                && MundoInfinitoMod.Mundos.actual != null && MundoInfinitoMod.Mundos.actual.dimensional());
            t.button("Árbol", Styles.cleart, () -> abrir(null)).padTop(92f).padLeft(8f).size(150f, 52f);
        }));
    }

    /** Abre el diálogo. Con planeta null muestra el del mundo abierto. */
    static void abrir(String planetaElegido){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null) return;
        final String planeta = planetaElegido != null ? planetaElegido : ApiPlaneta.planetaDeMundo(m);
        final BaseDialog d = new BaseDialog("Árbol tecnológico · " + ApiPlaneta.nombre(planeta));

        // Selector: un botón por cada planeta con árbol detectado
        final Table selector = new Table();
        for(String p : ApiArbol.planetas()){
            final String etiqueta = p.equals(planeta) ? "[accent]" + ApiPlaneta.nombre(p) + "[]" : ApiPlaneta.nombre(p);
            selector.button(etiqueta, Styles.cleart, () -> { d.hide(); abrir(p); })
                .size(170f, 46f).pad(3f);
        }
        d.cont.add(selector).row();
        d.cont.add("[lightgray]Investigar gasta los materiales tal cual del núcleo. Los nodos de la misma sección en otros planetas se desbloquean a la vez.[]")
            .width(640f).wrap().padTop(6f).padBottom(6f).row();

        // Nodos del planeta actual, con sangría según su profundidad (el padre siempre va antes que sus hijos)
        final Table lista = new Table();
        final Map<String, Integer> profundidad = new HashMap<String, Integer>();
        for(ApiArbol.NodoInfo n : ApiArbol.nodos(planeta)){
            final Integer pp = n.padre == null ? null : profundidad.get(n.padre);
            final int prof = pp == null ? 0 : pp + 1;
            profundidad.put(n.clave, prof);
            fila(lista, d, n, prof, planeta);
        }
        d.cont.pane(lista).grow().maxHeight(Core.graphics.getHeight() * 0.62f).row();
        d.addCloseButton();
        d.show();
    }

    private static void fila(Table lista, BaseDialog d, ApiArbol.NodoInfo n, int prof, String planeta){
        final Table fila = new Table();
        fila.add("").width(prof * 22f);

        final String estado = n.desbloqueado ? "[green]desbloqueado[]" : n.disponible ? "[accent]disponible[]" : "[gray]bloqueado[]";
        final String comparte = n.copias > 1 ? "   [sky]comparte sección con " + (n.copias - 1) + " más[]" : "";
        fila.add(n.nombre + "   " + estado + comparte).left().growX();

        final ItemStack[] falta = ApiArbol.faltante(n.clave);
        final StringBuilder coste = new StringBuilder();
        for(ItemStack s : n.coste) coste.append(s.item.localizedName).append(' ').append(s.amount).append("   ");
        if(n.coste.length == 0) coste.append("[lightgray]gratis[]");
        if(!n.desbloqueado && falta.length > 0){
            coste.append("[scarlet]faltan: ");
            for(ItemStack s : falta) coste.append(s.item.localizedName).append(' ').append(s.amount).append("  ");
            coste.append("[]");
        }
        fila.add(coste.toString()).right().padLeft(10f).padRight(10f);

        final boolean puede = !n.desbloqueado && n.disponible && falta.length == 0;
        fila.button(n.desbloqueado ? "Hecho" : "Investigar", Styles.cleart, () -> {
            if(!ApiArbol.desbloquear(n.clave)){
                Vars.ui.showInfoToast("No se puede investigar: faltan materiales o el nodo aún no está disponible", 3f);
                return;
            }
            d.hide();
            abrir(planeta);
        }).size(140f, 44f).disabled(b -> !puede);

        lista.add(fila).growX().padBottom(4f).row();
    }
}
