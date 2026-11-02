package mundoinfinito;

import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.ui.Bar;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;

/**
 * RADAR PLANETARIO por tiers (plan v8, P6). Con energía, barre el espacio desde el cuerpo de su mundo y detecta a los demás cuerpos dentro de su
 * alcance, de cercano a lejano ({@link BarridoRadar}, que usa {@link ApiRadar}). Lo detectado queda en {@link Radares} y se ve en el menú de
 * planetas. Al tocarlo lista lo detectado. Una barra muestra el avance del barrido (el tiempo de barrido).
 *
 * Defaults declarados (R8): energía 1, 3 y 8 por tick según el tier; coste solo con ítems que existen en Serpulo y en Erekir (silicio, grafito,
 * torio), como el portal; tamaños 2, 3 y 4. Los valores de alcance y tiempos son los de {@link ApiRadar}. A calibrar en P12.
 */
class RadarPlanetario extends Block{
    static Block radar1, radar2, radar3;

    final int tier;

    static void cargar(){
        radar1 = new RadarPlanetario("radar-planetario-1", 1, 2, 1f){{
            requirements(Category.effect, ItemStack.with(Items.silicon, 60, Items.graphite, 40));
            alwaysUnlocked = true;
            localizedName = "Radar planetario I";
            description = "Detecta los mundos cercanos desde el cuerpo de este mundo, de cercano a lejano. Alcance corto, barrido lento.";
        }};
        radar2 = new RadarPlanetario("radar-planetario-2", 2, 3, 3f){{
            requirements(Category.effect, ItemStack.with(Items.silicon, 150, Items.graphite, 100, Items.thorium, 40));
            alwaysUnlocked = true;
            localizedName = "Radar planetario II";
            description = "Detecta los mundos y sistemas a media distancia. Alcance medio, barrido más rápido.";
        }};
        radar3 = new RadarPlanetario("radar-planetario-3", 3, 4, 8f){{
            requirements(Category.effect, ItemStack.with(Items.silicon, 300, Items.graphite, 200, Items.thorium, 150));
            alwaysUnlocked = true;
            localizedName = "Radar planetario III";
            description = "Detecta mundos y sistemas lejanos. Alcance largo, barrido rápido.";
        }};
    }

    RadarPlanetario(String name, int tier, int tamano, float energia){
        super(name);
        this.tier = tier;
        size = tamano;
        health = 200 * tamano * tamano;
        update = true;
        solid = true;
        hasPower = true;
        configurable = false;
        consumePower(energia);
        buildType = RadarBuild::new;
    }

    @Override
    public void setBars(){
        super.setBars();
        addBar("barrido", (RadarBuild e) -> new Bar(e::textoBarrido, () -> Pal.accent, e::fraccion));
    }

    class RadarBuild extends Building{
        private BarridoRadar.Barrido barrido;
        private float progreso;
        private int registrados;
        private boolean fallo;

        private boolean activo(){
            return MundoInfinitoMod.Streamer.activo && MundoInfinitoMod.Mundos.actual != null && MundoInfinitoMod.Mundos.actual.dimensional();
        }

        private void iniciar(){
            progreso = 0f;
            registrados = 0;
            try{
                final MundoInfinitoMod.Meta m = MundoInfinitoMod.Mundos.actual;
                barrido = BarridoRadar.preparar(Radares.global(), m.id, tier, (long)m.calendario.tiempo(), p -> ApiPlaneta.orbita(p));
                fallo = false;
            }catch(Throwable t){
                if(!fallo) arc.util.Log.err("[MundoInfinito] El radar no pudo preparar su barrido; se reintenta.", t);
                fallo = true;
                barrido = BarridoRadar.preparar(null, "", tier, 0L, p -> 0.0);   // barrido vacío: no se detecta nada este tramo
            }
        }

        @Override
        public void updateTile(){
            if(!activo() || efficiency <= 0f) return;
            if(barrido == null) iniciar();
            progreso += edelta();
            final int n = barrido.detectadosEn((long)progreso);
            for(int i = registrados; i < n; i++) Radares.registrar(barrido.hallazgos.get(i).cuerpoId, barrido.hallazgos.get(i).orden + 1);
            registrados = n;
            if(progreso >= barrido.duracion()) barrido = null;   // el siguiente tramo vuelve a mirar el estado
        }

        float fraccion(){
            return barrido == null ? 0f : Math.min(1f, progreso / barrido.duracion());
        }

        String textoBarrido(){
            if(barrido == null) return "Barrido: en espera";
            final int total = barrido.hallazgos.size();
            final long restantes = Math.max(0L, (long)(barrido.duracion() - progreso) / 60L);
            return "Barrido: " + registrados + "/" + total + " (" + restantes + " s)";
        }

        @Override
        public void tapped(){
            if(Vars.headless || Vars.ui == null) return;
            final BaseDialog d = new BaseDialog("Radar planetario " + tier);
            final Table lista = new Table();
            final java.util.HashMap<String, String> nombres = new java.util.HashMap<String, String>();
            for(MundoInfinitoMod.Meta m : MundoInfinitoMod.Almacen.listar()) nombres.put(m.id, m.nombre);
            int k = 0;
            for(String id : Radares.enOrden()){
                k++;
                lista.add("[accent]" + k + ".[] " + nombres.getOrDefault(id, id)).left().row();
            }
            if(k == 0) lista.add("[lightgray]Aún no se detecta ningún cuerpo. El radar necesita energía.[]").row();
            d.cont.add(lista).row();
            d.addCloseButton();
            d.show();
        }
    }
}
