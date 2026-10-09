package mundoinfinito;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitFactory;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * Producción en ausencia ("estilo Factorio"). Un mundo infinito solo mantiene cargada una ventana, así que las fábricas que
 * quedan fuera dejaban de trabajar. Aquí, al volver, cada edificio restaurado SE PONE AL DÍA: se ejecuta su propia lógica de
 * juego acelerada (pasos de PASO ticks) durante el tiempo que estuvo fuera, un poco por fotograma. Es la lógica real:
 * taladros, cintas, fábricas, generadores, consumo y cuellos de botella se comportan igual que en directo.
 * Lo que excede de MAX_SIM se extrapola con lo que llegó al núcleo durante la simulación.
 */
final class Ausencia{
    static float PASO = 10f;                                // ticks que avanza cada paso simulado
    static final double MIN = 60.0 * 20;                    // menos de 20 s fuera: no vale la pena
    static final double MAX_SIM = 60.0 * 60 * 6;            // ticks simulados de verdad (6 min)
    static final double MAX_TOTAL = 60.0 * 60 * 60 * 8;     // tope de lo que se reconoce de ausencia (8 h)

    static final class Pend{
        Building b;
        double resta;
    }

    static final Seq<Pend> lista = new Seq<>();
    static final Seq<PowerGraph> grafos = new Seq<>();
    static ObjectIntMap<Item> antes;
    static boolean iniciado;
    static int cursor;
    static double maxDt, simulado, rondas;

    static boolean simulable(Building b){
        if(b == null || b.team != Team.sharded || b.block == null) return false;
        if(b.block instanceof Turret || b.block instanceof UnitFactory || b.block instanceof Reconstructor) return false;
        if(b.block instanceof CoreBlock || b.block instanceof LogicBlock) return false;
        return b.block.update;
    }

    /** Registra un edificio recién restaurado que estuvo 'dt' ticks fuera de la ventana. */
    static void agregar(Building b, double dt){
        if(dt < MIN || !simulable(b)) return;
        double d = Math.min(dt, MAX_TOTAL);
        Pend p = new Pend();
        p.b = b;
        p.resta = Math.min(d, MAX_SIM);
        lista.add(p);
        maxDt = Math.max(maxDt, d);
    }

    static void reiniciar(){
        lista.clear();
        grafos.clear();
        antes = null;
        iniciado = false;
        cursor = 0;
        maxDt = 0;
        simulado = 0;
        rondas = 0;
    }

    static boolean activa(){
        return !lista.isEmpty();
    }

    /** Avanza la puesta al día dentro del presupuesto de tiempo del fotograma. */
    static void tick(long finNs){
        if(lista.isEmpty()) return;
        if(Streamer.restaurando || !Streamer.colaRestaurar.isEmpty()) return;   // esperar a que la base esté completa
        if(!iniciado){
            iniciado = true;
            antes = new ObjectIntMap<>();
            for(var e : Vars.state.stats.coreItemCount) antes.put(e.key, e.value);
            ObjectSet<PowerGraph> vistos = new ObjectSet<>();
            for(Pend p : lista) if(p.b.power != null && vistos.add(p.b.power.graph)) grafos.add(p.b.power.graph);
        }
        float delta0 = Time.delta, time0 = Time.time;
        Time.delta = PASO;
        boolean quedan = true;
        while(quedan && System.nanoTime() < finNs){
            quedan = false;
            int n = lista.size;
            for(; cursor < n; cursor++){
                Pend p = lista.get(cursor);
                if(p.resta <= 0) continue;
                Building b = p.b;
                if(!b.isValid() || b.tile == null || b.tile.build != b){ p.resta = 0; continue; }
                try{
                    b.update();
                }catch(Throwable e){
                    p.resta = 0;
                    Log.warn("[MundoInfinito] Puesta al día: @ falló y se omite", b.block.name);
                    continue;
                }
                p.resta -= PASO;
                if((cursor & 63) == 63 && System.nanoTime() >= finNs){ cursor++; break; }
            }
            if(cursor >= n){
                // ronda completa: avanza el reloj de los temporizadores, la red eléctrica y el contador de lo simulado
                cursor = 0;
                Time.time += PASO;
                for(PowerGraph g : grafos){
                    try{ g.update(); }catch(Throwable ignored){}
                }
                simulado += PASO;
                rondas++;
                for(Pend p : lista){ if(p.resta > 0){ quedan = true; break; } }
                if(!quedan){ break; }
            }else{
                quedan = true;
                break;   // se acabó el presupuesto a mitad de ronda
            }
        }
        Time.delta = delta0;
        Time.time = time0;
        if(!quedan) finalizar();
    }

    /** Lo que excede de la simulación (hasta 8 h) se acredita según lo que llegó al núcleo mientras se simulaba. */
    static void finalizar(){
        try{
            double total = Math.min(maxDt, MAX_TOTAL);
            if(simulado > 0 && total > simulado){
                double factor = (total - simulado) / simulado;
                var nucs = Vars.state.teams.cores(Team.sharded);
                if(!nucs.isEmpty()){
                    CoreBlock.CoreBuild nuc = nucs.first();
                    for(var e : Vars.state.stats.coreItemCount){
                        int dif = e.value - antes.get(e.key, 0);
                        if(dif <= 0) continue;
                        int extra = (int)Math.min(dif * factor, Integer.MAX_VALUE / 2.0);
                        int hueco = Math.max(0, nuc.storageCapacity - nuc.items.get(e.key));
                        nuc.items.add(e.key, Math.min(extra, hueco));
                    }
                }
            }
            Log.info("[MundoInfinito] Puesta al día terminada: @ edificios, @ s simulados de @ s fuera", lista.size, (int)(simulado / 60), (int)(total / 60));
        }catch(Throwable t){
            Log.err("[MundoInfinito] Error al cerrar la puesta al día", t);
        }
        reiniciar();
    }
}
