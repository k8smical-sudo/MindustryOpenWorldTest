package mundoinfinito;

import arc.util.Time;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.meta.BuildVisibility;

/**
 * PORTAL DE VIAJE POR TIERS (plan v9, P7). Esta clase es el tier 1 del sistema de portales (el silo de antes, 4x4) y la base de los
 * tiers 2 y 3 (5x5 y 6x6). El silo antiguo ("silo-interdimensional") sigue registrado, oculto, para leer los mundos guardados; al restaurarlos se
 * convierte en el tier 1 ({@link MigracionPortales}). Los tiers 2 y 3 no hacen logística orbital: solo son portales de viaje.
 *
 * Silo Interdimensional 4x4 (tier 1). Cumple dos papeles a la vez (en cada mundo puede haber uno o más):
 *
 *  - ENVÍO: lo que se le mete (cintas, unidades) sale hacia el mundo ENLAZADO que se elija como destino, en drones de 30 unidades.
 *    Necesita energía y combustible líquido (consumidores nativos: el silo solo gasta mientras tiene carga).
 *  - RECEPCIÓN: descarga poco a poco lo que los drones traen a este mundo, directamente a los bloques
 *    de alrededor (cintas, contenedores...). Lo recibido NO pasa por el inventario del silo, así que nunca
 *    se reenvía por error.
 *  - PORTAL: al tocarlo abre un diálogo para ir a la base principal, a un checkpoint o, con enlaces, a otros mundos.
 *    Hay portales derelictos dentro de las bases enemigas y en las ruinas; este bloque también se puede construir.
 *
 * El coste de construcción usa solo ítems que existen en AMBOS planetas (silicio, grafito, torio): los demás
 * están ocultos en el otro planeta (Item.shownPlanets / Rules.planet) y el silo no se podría construir allí.
 */
public class SiloInterdimensional extends StorageBlock{
    /** Tier 1 (el silo de siempre), tier 2, tier 3 y el nombre antiguo (oculto, solo para leer mundos guardados). */
    static Block silo, portal2, portal3, antiguo;

    public float descargaTicks = 12f;       // 5 ítems/s máximo de recepción
    public float esperaLote = 60f * 6f;     // si el lote no se llena, sale lo que haya tras 6 s
    public float combustibleViaje = 60f;
    /** Tier del portal (1 a 3). */
    public int tier = 1;

    static void cargar(){
        silo = nuevo(MigracionPortales.TIER_1, 1, "Portal de mundos I",
            "Toca para viajar: base principal, checkpoints u otros mundos enlazados. Envía y recibe recursos de los mundos enlazados.");
        portal2 = nuevo(MigracionPortales.TIER_2, 2, "Portal de mundos II",
            "Portal de largo alcance: enlaces a mundos más lejanos y viajes más rápidos. No envía recursos.");
        portal3 = nuevo(MigracionPortales.TIER_3, 3, "Portal de mundos III",
            "Portal de alcance máximo y viajes muy rápidos. No envía recursos.");
        // El bloque de antes, solo para leer los mundos guardados: no se ofrece en el menú de construcción
        antiguo = new SiloInterdimensional(MigracionPortales.ANTIGUO, 1){{
            requirements(Category.effect, pila(Calibracion.PORTAL_CONSTRUCCION[0]));
            alwaysUnlocked = true;
            buildVisibility = BuildVisibility.hidden;
            localizedName = "Portal de mundos (antiguo)";
            description = "Bloque de mundos anteriores: se convierte en el portal tier 1 al abrir el mundo.";
        }};
    }

    private static Block nuevo(String nombre, int nivel, String titulo, String texto){
        return new SiloInterdimensional(nombre, nivel){{
            requirements(Category.effect, pila(Calibracion.PORTAL_CONSTRUCCION[Calibracion.indice(nivel)]));
            alwaysUnlocked = true;   // no está en ningún árbol tecnológico
            buildVisibility = ApiContenido.visibilidad(nombre);   // oculto en campaña salvo que ApiContenido lo permita
            localizedName = titulo;
            description = texto;
        }};
    }

    /** Pila de ítems desde "item:cantidad". Un ítem que no existe se omite con aviso. */
    static ItemStack[] pila(String[] requisitos){
        final arc.struct.Seq<ItemStack> out = new arc.struct.Seq<ItemStack>();
        for(String req : requisitos){
            final int sep = req.lastIndexOf(':');
            final Item it = Vars.content.item(req.substring(0, sep));
            if(it == null){
                arc.util.Log.warn("[MundoInfinito] Portal: el ítem @ no existe; se omite del coste.", req.substring(0, sep));
                continue;
            }
            out.add(new ItemStack(it, Integer.parseInt(req.substring(sep + 1))));
        }
        return out.toArray(ItemStack.class);
    }

    public SiloInterdimensional(String name){
        this(name, 1);
    }

    public SiloInterdimensional(String name, int nivel){
        super(name);
        tier = Math.max(ApiViaje.TIER_MINIMO, Math.min(ApiViaje.TIER_MAXIMO, nivel));
        size = Calibracion.PORTAL_TAMANO[Calibracion.indice(tier)];
        health = 2400 * tier;
        itemCapacity = 300;
        update = true;
        solid = true;
        configurable = false;               // al tocarlo se abre el diálogo de portal (tapped)
        coreMerge = false;                  // jamás se fusiona con un núcleo
        if(tier == 1){
            // solo el tier 1 hace logística orbital: necesita energía y combustible
            hasPower = true;
            hasLiquids = true;
            liquidCapacity = 300f;
            consumePower(6f);
            consumeLiquid(Liquids.oil, 0.15f);  // combustible
        }
        buildType = SiloBuild::new;
    }

    public class SiloBuild extends StorageBuild{
        float tLote, tDescarga;
        int cursor;

        /** Id del mundo abierto, o null sin mundo. Es la clave de su buzón de recepción. */
        String miMundo(){
            return MundoInfinitoMod.Mundos.actual == null ? null : MundoInfinitoMod.Mundos.actual.id;
        }

        /** Mundo al que este mundo envía su logística: el elegido en el portal, si sigue enlazado; si no, null (el silo recibe pero no envía). */
        String destino(){
            return ViajesMundo.destinoLogistica();
        }

        /** El silo funciona con un mundo abierto y con juego en marcha. */
        boolean dimensional(){
            return MundoInfinitoMod.Mundos.actual != null && MundoInfinitoMod.Streamer.activo;
        }

        /** Solo el tier 1 envía y recibe recursos entre mundos enlazados. */
        boolean logistica(){
            return tier == 1;
        }

        @Override
        public boolean shouldConsume(){
            return logistica() && dimensional() && items.total() > 0 && TransitoOrbital.puedeLanzar(destino());
        }

        @Override
        public boolean acceptItem(Building source, Item item){
            return logistica() && dimensional() && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public void updateTile(){
            if(!logistica() || !dimensional()) return;
            final String aqui = miMundo(), hacia = destino();

            // ---------- ENVÍO (solo con un mundo enlazado elegido como destino) ----------
            if(hacia != null && efficiency > 0.01f && items.total() > 0 && TransitoOrbital.puedeLanzar(hacia)){
                tLote += Time.delta * efficiency;
                Item mayor = null;
                int max = 0;
                for(Item i : mindustry.Vars.content.items()){
                    int a = items.get(i);
                    if(a > max){ max = a; mayor = i; }
                }
                if(mayor != null && (max >= TransitoOrbital.CAPACIDAD || tLote >= esperaLote)){
                    int n = Math.min(max, TransitoOrbital.CAPACIDAD);
                    items.remove(mayor, n);
                    TransitoOrbital.lanzar(hacia, mayor, n);
                    Fx.launch.at(this);
                    tLote = 0f;
                }
            }else if(items.total() == 0){
                tLote = 0f;
            }

            // ---------- RECEPCIÓN (descarga gradual del dron que llegó) ----------
            tDescarga += Time.delta;
            if(tDescarga >= descargaTicks){
                Item it = TransitoOrbital.mirar(aqui);
                if(it != null && entregar(it)){
                    TransitoOrbital.consumir(aqui);
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

        /** Tocar el portal abre el diálogo de viaje (base principal, checkpoints u otros mundos enlazados). Sirve también el derelicto. */
        @Override
        public void tapped(){
            Portal.abrir(this, tier);
        }
    }
}
