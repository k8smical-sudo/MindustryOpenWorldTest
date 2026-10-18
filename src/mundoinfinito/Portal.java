package mundoinfinito;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mundoinfinito.MundoInfinitoMod.Almacen;
import mundoinfinito.MundoInfinitoMod.Datos;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Regiones;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * Diálogo del portal interdimensional y viajes dentro de una dimensión.
 * Opciones: base principal (el origen de la dimensión), un checkpoint ya hecho, u otra dimensión (Serpulo <-> Erekir).
 * Cada portal que se toca queda registrado como checkpoint.
 */
final class Portal{
    static void abrir(Building dev){
        Meta m = Mundos.actual;
        if(Vars.headless || m == null || !m.dimensional() || !Streamer.activo){
            if(!Vars.headless && Vars.ui != null) Vars.ui.showInfoToast("El portal solo funciona en mundos nuevos", 3f);
            return;
        }
        final double px = Coord.virtualX(dev.x), py = Coord.virtualY(dev.y);
        if(Estructuras.registrarPunto(px, py)) Vars.ui.showInfoToast("Checkpoint guardado", 2.5f);

        final int actualDim = Mundos.dimActual();
        BaseDialog d = new BaseDialog("Portal interdimensional");
        d.cont.add("[accent]" + m.nombreDim() + "[]   X " + (long)Math.floor(px) + "   Y " + (long)Math.floor(py)).padBottom(10f).row();

        d.cont.button("Base principal", Styles.defaultt, () -> { d.hide(); irA(0.0, 0.0); }).size(320f, 56f).padBottom(6f).row();

        Table lista = new Table();
        for(Estructuras.Punto pt : Estructuras.puntos.copy()){
            Table fila = new Table();
            long dist = Math.round(Math.hypot(pt.vx - px, pt.vy - py));
            boolean aqui = dist < 6;
            fila.button(pt.nombre + (aqui ? "  (aquí)" : "  " + dist + " tiles"), Styles.defaultt, () -> { d.hide(); irA(pt.vx, pt.vy); })
                .size(250f, 44f).disabled(b -> aqui);
            fila.button("X", Styles.defaultt, () -> { Estructuras.puntos.remove(pt); d.hide(); abrir(dev); }).size(50f, 44f).padLeft(4f);
            lista.add(fila).padBottom(3f).row();
        }
        d.cont.add("Checkpoints").padTop(6f).row();
        d.cont.pane(lista).maxHeight(Core.graphics.getHeight() * 0.38f).row();

        // Destinos: las dimensiones del mundo base que no son la actual y los mundos registrados por mods visibles desde aquí
        if(actualDim != MundoInfinitoMod.DIM_SERPULO){
            d.cont.button("Ir a Serpulo", Styles.defaultt, () -> { d.hide(); Mundos.saltar(MundoInfinitoMod.DIM_SERPULO); }).size(320f, 56f).padTop(8f).row();
        }
        if(actualDim != MundoInfinitoMod.DIM_EREKIR){
            d.cont.button("Ir a Erekir", Styles.defaultt, () -> { d.hide(); Mundos.saltar(MundoInfinitoMod.DIM_EREKIR); }).size(320f, 56f).padTop(8f).row();
        }
        for(final MundoRegistrado w : Extensiones.mundos()){
            boolean visible;
            try{
                visible = w.dimension() != actualDim && w.visible.desde(actualDim);
            }catch(Throwable t){
                visible = false;
            }
            if(!visible) continue;
            d.cont.button("Ir a " + w.nombre, Styles.defaultt, () -> { d.hide(); Mundos.saltar(w.dimension()); }).size(320f, 56f).padTop(8f).row();
        }

        // la logística orbital solo une Serpulo y Erekir
        if(dev.team == Vars.player.team() && actualDim <= MundoInfinitoMod.DIM_EREKIR){
            final int otra = 1 - actualDim;
            d.cont.add("[gray]Logística: en camino aquí " + Orbita.pendientes(actualDim)
                + "   hacia allá " + Orbita.pendientes(otra) + "[]").padTop(8f).row();
        }
        d.addCloseButton();
        d.show();
    }

    /** Viaja a una posición VIRTUAL de la dimensión actual. Cerca: salto directo; lejos: se reabre la ventana allí. */
    static void irA(double vx, double vy){
        if(!Streamer.activo || Mundos.viajando || Streamer.restaurando || Vars.player == null || Vars.player.dead() || Mundos.actual == null) return;
        final double lx = vx, ly = vy;   // sin ventana: la posición del mundo es la virtual
        Unit pu = Vars.player.unit();
        final int pcxD = Math.floorDiv((int)Math.floor(lx), MundoInfinitoMod.TAM_CHUNK), pcyD = Math.floorDiv((int)Math.floor(ly), MundoInfinitoMod.TAM_CHUNK);
        if(Streamer.enMapa(pcxD, pcyD) && Streamer.generados.contains(Streamer.clave(pcxD, pcyD))){
            // salto directo a un chunk ya cargado: el jugador y sus unidades cercanas van juntos
            float nx = (float)(lx * 8.0), ny = (float)(ly * 8.0);
            float ddx = nx - pu.x, ddy = ny - pu.y;
            Fx.launch.at(pu.x, pu.y);
            for(Unit u : Groups_unidades()){
                if(u != pu && u.team == Team.sharded && u.dst(pu) < 60f * 8f){ u.x += ddx; u.y += ddy; }
            }
            pu.set(nx, ny);
            if(Core.camera != null) Core.camera.position.set(nx, ny);
            Streamer.jugadorX = (float)lx;
            Streamer.jugadorY = (float)ly;
            Fx.launch.at(nx, ny);
            return;
        }
        Mundos.viajando = true;
        Carga_mostrar("Viajando...");
        Fx.launch.at(pu.x, pu.y);
        Time.runTask(40f, () -> {
            try{
                final Meta m = Mundos.actual;
                Mundos.guardar(true);
                Regiones.iniciar(m);
                Fi f = Almacen.archivoEstado(m);
                Datos d = f.exists() ? Datos.leer(f) : null;
                if(d == null){ Mundos.viajando = false; Carga_ocultar(); return; }
                // los seguidores viajan con el jugador
                for(Datos.RegUnidad u : d.unidades){
                    u.vx += vx - d.vx;
                    u.vy += vy - d.vy;
                }
                d.vx = vx;
                d.vy = vy;
                Mundos.abrir(m, d, vx, vy);
            }catch(Throwable t){
                arc.util.Log.err("[MundoInfinito] Error al viajar", t);
                Carga_ocultar();
                Mundos.viajando = false;
            }
        });
    }

    private static Iterable<Unit> Groups_unidades(){
        arc.struct.Seq<Unit> l = new arc.struct.Seq<>();
        for(Unit u : mindustry.gen.Groups.unit) l.add(u);
        return l;
    }

    private static void Carga_mostrar(String t){ if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.show(t); }
    private static void Carga_ocultar(){ if(!Vars.headless && Vars.ui != null) Vars.ui.loadfrag.hide(); }
}
