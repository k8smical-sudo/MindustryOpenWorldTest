package mundoinfinito;

import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.ui.dialogs.BaseDialog;

import java.util.List;

/**
 * Diálogo para ponerle nombre a los grupos (sistemas solares) y a los planetas, y para crear grupos nuevos de la galaxia. Lo abre el visor de planetas.
 * Los nombres los elige quien juega; los ids no cambian nunca. Toda la lógica es de {@link ApiGalaxia} y {@link ApiPlaneta}: aquí solo hay interfaz.
 * Clase de paquete.
 */
final class RenombrarUI{
    private RenombrarUI(){}

    /** @param planeta tag del planeta seleccionado en el visor, o null si no hay ninguno. */
    static void abrir(final String planeta){
        if(Vars.headless || Vars.ui == null) return;
        final BaseDialog d = new BaseDialog("Nombres de la galaxia");
        final Table c = new Table();

        // ---------- grupo ----------
        final List<ApiGalaxia.Grupo> grupos = ApiGalaxia.grupos();
        final int[] sel = {0};
        final String[] nombreGrupo = {grupos.get(0).nombre()};
        c.add("Grupo (sistema solar)").padBottom(4f).row();
        final TextButton gb = c.button("Grupo: " + grupos.get(0).nombre(), () -> {}).size(320f, 52f).padBottom(6f).get();
        c.row();
        c.add("Nuevo nombre del grupo").padBottom(2f).row();
        c.field(nombreGrupo[0], t -> nombreGrupo[0] = t).width(320f).padBottom(6f).row();
        gb.clicked(() -> {
            sel[0] = (sel[0] + 1) % grupos.size();
            gb.setText("Grupo: " + ApiGalaxia.nombreDe(grupos.get(sel[0]).id));
        });
        c.button("Guardar nombre del grupo", () -> {
            final boolean ok = ApiGalaxia.renombrarGrupo(grupos.get(sel[0]).id, nombreGrupo[0]);
            Vars.ui.showInfoToast(ok ? "Grupo renombrado" : "Nombre no válido", 2.5f);
            if(ok) gb.setText("Grupo: " + ApiGalaxia.nombreDe(grupos.get(sel[0]).id));
        }).size(320f, 48f).padBottom(14f).row();

        // ---------- grupo nuevo ----------
        final String[] nuevo = {""};
        c.add("Crear un grupo nuevo (" + grupos.size() + " de " + ApiGalaxia.maximoGrupos() + ")").padBottom(2f).row();
        c.field("", t -> nuevo[0] = t).width(320f).padBottom(6f).row();
        c.button("Crear grupo", () -> {
            final ApiGalaxia.Resultado r = ApiGalaxia.crearGrupo(nuevo[0]);
            Vars.ui.showInfoToast(r.ok ? "Grupo creado: " + r.grupo.nombre() : r.mensaje, 3f);
            if(r.ok){
                d.hide();
                abrir(planeta);
            }
        }).size(320f, 48f).padBottom(14f).row();

        // ---------- planeta ----------
        if(planeta != null){
            final String[] nombrePlaneta = {ApiPlaneta.nombre(planeta)};
            c.add("Planeta: [accent]" + ApiPlaneta.nombre(planeta) + "[]").padBottom(2f).row();
            c.field(nombrePlaneta[0], t -> nombrePlaneta[0] = t).width(320f).padBottom(6f).row();
            c.button("Guardar nombre del planeta", () -> {
                final boolean ok = ApiPlaneta.renombrar(planeta, nombrePlaneta[0]);
                Vars.ui.showInfoToast(ok ? "Planeta renombrado" : "Abre un mundo para renombrar planetas", 3f);
            }).size(320f, 48f).padBottom(6f).row();
        }

        d.cont.pane(c).grow();
        d.addCloseButton();
        d.show();
    }
}
