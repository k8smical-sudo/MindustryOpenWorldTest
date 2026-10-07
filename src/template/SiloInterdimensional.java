package mundoinfinito;

import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.gen.Building;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.storage.StorageBlock;

/**
 * Silo Interdimensional 4x4. Cumple dos papeles a la vez (en cada dimensión hay uno o más):
 *
 *  - ENVÍO: lo que se le mete (cintas, unidades) sale hacia la OTRA dimensión en drones de 30 unidades.
 *    Necesita energía y combustible líquido (consumidores nativos: el silo solo gasta mientras tiene carga).
 *  - RECEPCIÓN: descarga poco a poco lo que los drones traen a esta dimensión, directamente a los bloques
 *    de alrededor (cintas, contenedores...). Lo recibido NO pasa por el inventario del silo, así que nunca
 *    se reenvía por error.
 *  - VIAJE: botón en su menú de configuración. Requiere energía y combustible; gasta 60 de combustible.
 *
 * El coste de construcción usa solo ítems que existen en AMBOS planetas (silicio, grafito, torio): los demás
 * están ocultos en el otro planeta (Rules.hiddenBuildItems) y el silo no se podría construir allí.
 */
public class SiloInterdimensional extends StorageBlock{
    static Block silo;

    public float descargaTicks = 12f;       // 5 ítems/s máximo de recepción
    public float esperaLote = 60f * 6f;     // si el lote no se llena, sale lo que haya tras 6 s
    public float combustibleViaje = 60f;

    static void cargar(){
        silo = new SiloInterdimensional("silo-interdimensional"){{
            requirements(Category.effect, ItemStack.with(Items.silicon, 400, Items.graphite, 300, Items.thorium, 200));
            alwaysUnlocked = true;   // no está en ningún árbol tecnológico
        }};
    }

    public SiloInterdimensional(String name){
        super(name);
        size = 4;
        health = 2400;
        itemCapacity = 300;
        update = true;
        solid = true;
        hasPower = true;
        hasLiquids = true;
        liquidCapacity = 300f;
        configurable = true;
        coreMerge = false;                  // jamás se fusiona con un núcleo
        consumePower(6f);
        consumeLiquid(Liquids.oil, 0.15f);  // combustible
        buildType = SiloBuild::new;
    }

    public class SiloBuild extends StorageBuild{
        float tLote, tDescarga;
        int cursor;

        int dim(){
            return MundoInfinitoMod.Mundos.dimActual();
        }

        boolean dimensional(){
            return MundoInfinitoMod.Mundos.actual != null && MundoInfinitoMod.Mundos.actual.dimensional();
        }

        boolean listoParaViajar(){
            return dimensional() && power.status > 0.95f && liquids.get(Liquids.oil) >= combustibleViaje;
        }

        @Override
        public boolean shouldConsume(){
            return dimensional() && items.total() > 0 && Orbita.puedeLanzar(dim());
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            return dimensional() && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public void updateTile(){
            if(!dimensional()) return;
            int d = dim();

            // ---------- ENVÍO ----------
            if(efficiency > 0.01f && items.total() > 0 && Orbita.puedeLanzar(d)){
                tLote += Time.delta * efficiency;
                Item mayor = null;
                int max = 0;
                for(Item i : mindustry.Vars.content.items()){
                    int a = items.get(i);
                    if(a > max){ max = a; mayor = i; }
                }
                if(mayor != null && (max >= Orbita.CAPACIDAD || tLote >= esperaLote)){
                    int n = Math.min(max, Orbita.CAPACIDAD);
                    items.remove(mayor, n);
                    Orbita.lanzar(d, mayor, n);
                    Fx.launch.at(this);
                    tLote = 0f;
                }
            }else if(items.total() == 0){
                tLote = 0f;
            }

            // ---------- RECEPCIÓN (descarga gradual del dron que llegó) ----------
            tDescarga += Time.delta;
            if(tDescarga >= descargaTicks){
                Item it = Orbita.mirar(d);
                if(it != null && entregar(it)){
                    Orbita.consumir(d);
                    tDescarga = 0f;
                }else{
                    tDescarga = descargaTicks;   // listo para el primer hueco que aparezca
                }
            }
        }

        /** Da 1 unidad al primer vecino que la acepte (rotando). Nunca a otro silo (evita rebotes). */
        boolean entregar(Item it){
            int n = proximity.size;
            for(int i = 0; i < n; i++){
                Building o = proximity.get((cursor + i) % n);
                if(o instanceof SiloBuild || o.team != team) continue;
                if(o.acceptItem(this, it)){
                    o.handleItem(this, it);
                    cursor = (cursor + i + 1) % n;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void buildConfiguration(Table table){
            final int destino = 1 - dim();
            table.label(() -> {
                if(!dimensional()) return "[scarlet]Solo en mundos nuevos[]";
                return "Energía " + (int)(power.status * 100f) + "%   Combustible " + (int)liquids.get(Liquids.oil)
                    + "\n" + "En camino hacia aquí: " + Orbita.pendientes(dim())
                    + "   hacia allá: " + Orbita.pendientes(1 - dim());
            }).pad(6f).row();
            table.button("Viajar a " + (destino == MundoInfinitoMod.DIM_SERPULO ? "Serpulo" : "Erekir"), () -> {
                if(!listoParaViajar()) return;
                liquids.remove(Liquids.oil, combustibleViaje);
                MundoInfinitoMod.Mundos.saltar(destino);
            }).size(260f, 56f).disabled(b -> !listoParaViajar()).row();
        }
    }
}
