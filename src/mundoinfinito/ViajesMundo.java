package mundoinfinito;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock;
import mundoinfinito.MundoInfinitoMod.Almacen;
import mundoinfinito.MundoInfinitoMod.Datos;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Sistemas;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * VIAJES Y ENLACES ENTRE CUERPOS EN EL JUEGO (plan v9, P7). Pega la lógica pura de {@link ApiViaje} con el mundo abierto: lee el inventario del
 * núcleo, consume boosters, guarda los enlaces en el mundo ({@code Meta.enlaces}), anima el teletransporte, lleva las unidades cercanas con el
 * jugador y abre el mundo de destino.
 *
 * REGLAS (decisiones de esta capa, ver docs/PORTALES.md):
 *  - Un enlace nuevo necesita que los materiales del portal estén en el núcleo y que el cuerpo lo haya detectado un radar ({@link Calibracion#PORTAL_REQUIERE_DETECCION}).
 *  - Un portal de tier T solo usa enlaces de tier T o menor.
 *  - Los materiales del portal tienen que estar en el núcleo para que funcione, pero no se gastan. El único consumo son los boosters (una unidad
 *    de cada uno usado). El coste del viaje ({@link ApiViaje.Resultado#coste}) se muestra y se pasa a las consecuencias de los addons.
 *  - Con el caos activo, un incidente de retraso o de coste ya viene en el resultado; uno de desvío cambia el destino a otro cuerpo enlazado.
 *
 * Todo ocurre en el hilo principal. Un fallo aquí nunca bloquea el juego: se avisa y se vuelve a un estado usable.
 */
final class ViajesMundo{
    /** Hay un enlace o un viaje en curso: no se admite otro hasta que termine. */
    static boolean enCurso;

    private ViajesMundo(){}

    // ================================================================== lectura del mundo

    /** Un cuerpo al que se puede intentar llegar desde este mundo. */
    static final class Destino{
        final String id, nombre;
        final double distancia;
        final ApiViaje.Enlace enlace;   // null = sin enlace
        final boolean detectado;

        Destino(String id, String nombre, double distancia, ApiViaje.Enlace enlace, boolean detectado){
            this.id = id;
            this.nombre = nombre;
            this.distancia = distancia;
            this.enlace = enlace;
            this.detectado = detectado;
        }
    }

    static ToDoubleFunction<String> periodo(){
        return p -> ApiPlaneta.orbita(p);
    }

    static CoreBlock.CoreBuild nucleo(){
        if(Vars.player == null || Vars.player.team() == null) return null;
        return Vars.player.team().core();
    }

    /** Ítems del núcleo del jugador por nombre. Vacío sin núcleo. */
    static Map<String, Integer> inventario(){
        final Map<String, Integer> out = new HashMap<String, Integer>();
        final CoreBlock.CoreBuild c = nucleo();
        if(c != null) c.items.each((item, n) -> { if(n > 0) out.put(item.name, n); });
        return out;
    }

    /** Quita ítems del núcleo. Cantidades que no están no se descuentan por debajo de cero. */
    static void quitar(Map<String, Integer> coste){
        final CoreBlock.CoreBuild c = nucleo();
        if(c == null || coste == null) return;
        for(Map.Entry<String, Integer> e : coste.entrySet()){
            final Item it = Vars.content.item(e.getKey());
            if(it != null) c.items.remove(it, Math.min(e.getValue(), c.items.get(it)));
        }
    }

    /**
     * Cuerpos de los demás mundos guardados, del más cercano al más lejano, con su distancia ahora, su enlace con este mundo (si lo hay) y si un
     * radar los detectó. Lee el estado de todos los mundos del disco: llamar al abrir un diálogo, no cada fotograma.
     */
    static List<Destino> destinos(){
        final List<Destino> out = new ArrayList<Destino>();
        final Meta m = Mundos.actual;
        if(m == null) return out;
        try{
            final EstadoSistemas est = Sistemas.global();
            final Map<String, String> nombres = new HashMap<String, String>();
            for(Meta o : Almacen.listar()) nombres.put(o.id, o.nombre);
            final double t = m.calendario.tiempo();
            for(EstadoSistemas.SistemaSolar s : est.sistemas()){
                for(EstadoSistemas.Cuerpo c : s.cuerpos()){
                    if(c.id.equals(m.id)) continue;
                    final double d = ApiViaje.distancia(est, m.id, c.id, t, periodo());
                    if(Double.isNaN(d)) continue;
                    out.add(new Destino(c.id, nombres.containsKey(c.id) ? nombres.get(c.id) : c.id, d, m.enlaces.entre(m.id, c.id), Radares.detectados.containsKey(c.id)));
                }
            }
            Collections.sort(out, (a, b) -> a.distancia == b.distancia ? a.id.compareTo(b.id) : Double.compare(a.distancia, b.distancia));
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudieron listar los destinos de viaje.", t);
        }
        return out;
    }

    static void aviso(String texto){
        if(!Vars.headless && Vars.ui != null) Vars.ui.showInfoToast(texto, 3.5f);
        else Log.info("[MundoInfinito] @", texto);
    }

    private static boolean puedeActuar(){
        return !enCurso && Streamer.activo && !Mundos.viajando && !Streamer.restaurando && Mundos.actual != null && Mundos.actual.dimensional()
            && Vars.player != null && !Vars.player.dead() && Vars.player.unit() != null;
    }

    // ================================================================== enlaces

    /**
     * Establece un enlace entre este mundo y otro con un portal de un tier. Valida ahora (materiales, detección, alcance); el enlace queda
     * efectivo tras los ticks del portal, y se vuelve a validar al terminar porque los cuerpos se mueven. Un booster de alcance elegido se gasta
     * al terminar con éxito.
     */
    static void establecer(final int tier, final String destino, final Set<String> boosters){
        if(!puedeActuar()) return;
        final Meta m = Mundos.actual;
        try{
            final Map<String, Integer> inv = inventario();
            final List<String> faltan = ApiViaje.faltan(tier, inv);
            if(!faltan.isEmpty()){
                aviso("Faltan materiales para el portal: " + faltan);
                return;
            }
            if(Calibracion.PORTAL_REQUIERE_DETECCION && !Radares.detectados.containsKey(destino)){
                aviso("Ese mundo aún no está detectado: necesitas un radar.");
                return;
            }
            final Set<String> usados = filtrar(boosters, inv);
            final boolean usaAlcance = usados.contains(ApiViaje.BOOSTER_ALCANCE);
            final ApiViaje.Portal portal = ApiViaje.portalEfectivo(ApiViaje.portal(tier), usados);
            final String id = "enlace-" + m.id + "-" + destino;
            // ensayo sobre una copia de los enlaces: avisa ya si no se puede, sin tocar los reales
            final ApiViaje.Enlaces ensayo = ApiViaje.Enlaces.desdeTexto(m.enlaces.aTexto());
            final ApiViaje.Resultado previo = ApiViaje.establecer(Sistemas.global(), ensayo, id, m.id, destino, portal, m.calendario.tiempo(), periodo());
            if(!previo.ok){
                aviso(previo.mensaje);
                return;
            }
            enCurso = true;
            aviso("Estableciendo el enlace (" + Math.max(1L, portal.ticksEstablecer / 60L) + " s)...");
            animar(portal.ticksEstablecer, () -> {
                try{
                    if(Mundos.actual != m){
                        aviso("El enlace se canceló: cambiaste de mundo.");
                        return;
                    }
                    final ApiViaje.Resultado r = ApiViaje.establecer(Sistemas.global(), m.enlaces, id, m.id, destino, portal, m.calendario.tiempo(), periodo());
                    if(r.ok){
                        if(usaAlcance) quitar(ApiViaje.costeBoosters(Collections.singleton(ApiViaje.BOOSTER_ALCANCE)));
                        Almacen.guardarMeta(m);
                        aviso("Enlace establecido.");
                    }else{
                        aviso(r.mensaje);
                    }
                }catch(Throwable t){
                    Log.err("[MundoInfinito] No se pudo terminar el enlace.", t);
                }finally{
                    enCurso = false;
                }
            });
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo iniciar el enlace.", t);
            enCurso = false;
        }
    }

    /** Quita un enlace de este mundo y lo guarda. */
    static void romper(String idEnlace){
        final Meta m = Mundos.actual;
        if(m == null || enCurso) return;
        if(m.enlaces.quitar(idEnlace)){
            Almacen.guardarMeta(m);
            aviso("Enlace eliminado.");
        }
    }

    // ================================================================== viajes

    /**
     * Viaja a otro mundo por un enlace. {@code tierPortal} es el del portal que se toca. Los boosters elegidos que estén en el núcleo se usan y se
     * gastan al partir; las unidades aliadas cercanas viajan con el jugador. Con el caos activo puede haber retraso, coste extra o desvío.
     */
    static void viajar(final int tierPortal, final String destino, final Set<String> boosters){
        if(!puedeActuar()) return;
        final Meta m = Mundos.actual;
        try{
            final Map<String, Integer> inv = inventario();
            final ApiViaje.Enlace enlace = m.enlaces.entre(m.id, destino);
            if(enlace != null && enlace.tier > tierPortal){
                aviso("Este enlace necesita un portal de tier " + enlace.tier + ".");
                return;
            }
            final Set<String> usados = filtrar(boosters, inv);
            final Unit pu = Vars.player.unit();
            final List<Unit> acompanantes = cercanas(pu);
            int tierUnidad = ApiViaje.tierUnidadPorVida(pu.type == null ? 0.0 : pu.type.health);
            for(Unit u : acompanantes) tierUnidad = Math.max(tierUnidad, ApiViaje.tierUnidadPorVida(u.type == null ? 0.0 : u.type.health));

            final double t = m.calendario.tiempo();
            final ApiViaje.Viaje v = ApiViaje.viajarConCaos(Sistemas.global(), m.enlaces, m.id, destino, tierUnidad, inv, t, periodo(),
                usados, m.semilla, Mundos.dimActual());
            if(!v.resultado.ok){
                aviso(v.resultado.mensaje);
                return;
            }
            String destinoFinal = destino;
            if("desvio".equals(v.caos)){
                final String otro = ApiViaje.elegirDesvio(m.enlaces, m.id, destino, m.semilla, Mundos.dimActual(), (int)Math.floor(t));
                if(otro != null){
                    destinoFinal = otro;
                    aviso("Incidente en el viaje: el portal te desvía.");
                }
            }else if(v.caos != null){
                aviso("Incidente en el viaje: " + v.caos + ".");
            }
            quitar(ApiViaje.costeBoosters(usados));   // los boosters se gastan al partir

            final String destinoElegido = destinoFinal;
            enCurso = true;
            aviso("Teletransporte en curso (" + Math.max(1L, v.resultado.ticks / 60L) + " s)...");
            animar(v.resultado.ticks, () -> completar(m, destinoElegido));
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo iniciar el viaje.", t);
            enCurso = false;
        }
    }

    /** Boosters pedidos que de verdad hay en el núcleo, en orden estable. */
    private static Set<String> filtrar(Set<String> pedidos, Map<String, Integer> inv){
        final Set<String> out = new LinkedHashSet<String>();
        if(pedidos == null) return out;
        final Set<String> hay = ApiViaje.boostersDisponibles(inv);
        for(String b : hay) if(pedidos.contains(b)) out.add(b);
        return out;
    }

    /** Unidades aliadas cercanas al jugador que viajan con él (sin avatares del jugador). */
    private static List<Unit> cercanas(Unit pu){
        final List<Unit> out = new ArrayList<Unit>();
        for(Unit u : Groups.unit){
            if(out.size() >= Calibracion.UNIDADES_MAX_VIAJE) break;
            if(u == pu || u.team != Team.sharded || !u.isValid() || u.isPlayer() || u.type == null) continue;
            if(Mundos.UNIDADES_NUCLEO.contains(u.type.name)) continue;
            if(u.dst(pu) > Calibracion.UNIDADES_RADIO_VIAJE * 8f) continue;
            out.add(u);
        }
        return out;
    }

    /** Animación del teletransporte: un destello cada medio segundo sobre el jugador, hasta que pasan los ticks; después, {@code fin}. */
    private static void animar(final long restantes, final Runnable fin){
        if(restantes <= 0L || Vars.player == null || Vars.player.unit() == null){
            fin.run();
            return;
        }
        final Unit pu = Vars.player.unit();
        Fx.launch.at(pu.x, pu.y);
        final long paso = Math.min(30L, restantes);
        Time.runTask((float)paso, () -> animar(restantes - paso, fin));
    }

    /** Fin del viaje: lleva al jugador y a sus unidades a otro mundo. */
    private static void completar(final Meta origen, final String destinoId){
        try{
            if(Mundos.actual != origen || Vars.player == null || Vars.player.dead() || Vars.player.unit() == null){
                aviso("El viaje se canceló.");
                enCurso = false;
                return;
            }
            Meta destino = null;
            for(Meta o : Almacen.listar()) if(o.id.equals(destinoId)) destino = o;
            if(destino == null || !destino.compatible()){
                aviso("No se puede abrir el mundo de destino.");
                enCurso = false;
                return;
            }
            final Meta d = destino;
            // las unidades salen del mundo de origen (así no quedan duplicadas) y se guardan con su posición relativa al jugador
            final Unit pu = Vars.player.unit();
            final Seq<Datos.RegUnidad> llevadas = new Seq<Datos.RegUnidad>();
            final List<Unit> aQuitar = cercanas(pu);
            for(Unit u : aQuitar){
                final Datos.RegUnidad r = new Datos.RegUnidad();
                r.tipo = u.type.name;
                r.vx = (u.x - pu.x) / 8.0;
                r.vy = (u.y - pu.y) / 8.0;
                r.rot = u.rotation;
                r.vida = u.health;
                llevadas.add(r);
            }
            for(Unit u : aQuitar) u.remove();

            Mundos.viajando = true;
            if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.show("Viajando a " + d.nombre + "...");
            Time.runTask(10f, () -> {
                try{
                    Mundos.guardar(true);   // síncrono: el mundo de origen queda en disco con el inventario ya descontado
                    final Datos o = Datos.leer(Almacen.archivoEstado(origen));
                    if(o != null){
                        Mundos.resN = new Seq<String>(o.itemsN);
                        Mundos.resC = new Seq<Integer>(o.itemsC);
                    }
                    Mundos.continuar(d, dd -> {
                        for(Datos.RegUnidad r : llevadas){
                            r.vx += dd.vx;
                            r.vy += dd.vy;
                            dd.unidades.add(r);
                        }
                    });
                }catch(Throwable t){
                    Log.err("[MundoInfinito] Error al viajar entre mundos", t);
                    if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.hide();
                    Mundos.viajando = false;
                }finally{
                    enCurso = false;
                }
            });
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo completar el viaje.", t);
            Mundos.viajando = false;
            enCurso = false;
        }
    }
}
