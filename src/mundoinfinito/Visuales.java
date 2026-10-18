package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.g2d.Draw;
import arc.math.geom.Rect;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Building;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.liquid.Conduit;

/**
 * CUERPOS DE LOS EDIFICIOS "CACHEADOS" EN MODO ANILLO.
 *
 * Mindustry dibuja los bloques con {@code drawCached} (puentes de ductos, muros, nodos de energía, proyectores de reparación, cintas apiladas...)
 * en dos pasos distintos: el CUERPO (sprite base, flecha de dirección, textura de autotile) sale de {@code Building.drawCached()}, que solo se
 * llama al hornear la caché por chunk del BlockRenderer; lo que cambia cada fotograma (el enlace discontinuo de un puente, los ítems, el brillo
 * de un muro golpeado, los láseres de un nodo) sale de {@code Building.draw()}.
 *
 * En modo anillo el parche 0009 del motor desactiva esa caché (sus índices no soportan coordenadas virtuales) y manda estos bloques por el
 * dibujo normal, que solo llama a {@code draw()}. Resultado: se veía el enlace del Duct Bridge, pero no el puente. Aquí se dibuja el cuerpo
 * cada fotograma para los edificios visibles, con la misma capa que usaba la caché ({@code BuildingCacheLayer}).
 *
 * IMPORTANTE: los edificios con {@code update = false} (muros, nodos y fuente de energía, proyectores...) NO están en Groups.build (el motor los
 * crea con shouldAdd = block.update), solo en el árbol espacial de su equipo (TeamData.buildingTree). Por eso se consulta ese árbol.
 *
 * Se omiten los bloques cuyo {@code drawCached()} no está sobrescrito (por defecto llama a {@code draw()}, que ya se dibuja) y Duct y Conduit
 * (su {@code draw()} ya pinta el cuerpo completo). Solo hilo de render; no hace nada en servidores ni fuera del modo anillo.
 */
final class Visuales{
    private Visuales(){}

    private static boolean registrado;
    private static final Rect vista = new Rect();
    /** Clase de edificio -> ¿hay que dibujar su drawCached() aparte? (se decide una vez por clase) */
    private static final ObjectMap<Class<?>, Boolean> necesita = new ObjectMap<>();

    static void registrar(){
        if(registrado) return;
        registrado = true;
        Events.run(Trigger.draw, Visuales::alCuadro);   // se dispara dentro de Renderer.draw(), con Draw.sort(true) activo
    }

    private static boolean necesitaCuerpo(Building b){
        final Class<?> c = b.getClass();
        Boolean r = necesita.get(c);
        if(r == null){
            boolean sobrescrito = false;
            try{
                sobrescrito = c.getMethod("drawCached").getDeclaringClass() != Building.class;
            }catch(Throwable ignored){}
            // Duct y Conduit pintan su cuerpo completo también en draw(): dibujarlo otra vez sería trabajo repetido
            if(Duct.DuctBuild.class.isAssignableFrom(c) || Conduit.ConduitBuild.class.isAssignableFrom(c)) sobrescrito = false;
            necesita.put(c, r = sobrescrito);
        }
        return r;
    }

    private static mindustry.game.Team propio;

    /** Dibuja el cuerpo de un edificio visible si su clase lo necesita. */
    private static final Cons<Building> CUERPO = b -> {
        if(!b.block.drawCached) return;
        if(!necesitaCuerpo(b)) return;
        if(b.inFogTo(propio) && !b.wasVisible) return;   // bajo la niebla no se dibuja, igual que el renderizador vanilla

        Draw.z(b.block.buildingCacheLayer.layer);   // la misma capa en la que se hornearía (under = Layer.block - 0.5, normal = Layer.block)
        b.drawCached();
        Draw.reset();
    };

    private static void alCuadro(){
        if(Vars.headless || Vars.state == null || !Vars.state.isGame() || Vars.player == null) return;
        if(Vars.world == null || Vars.world.tiles == null || !Vars.world.tiles.ring) return;

        Core.camera.bounds(vista);
        vista.grow(Vars.tilesize * 2f);
        propio = Vars.player.team();
        final float z0 = Draw.z();

        // Se consulta el árbol espacial de cada equipo, NO Groups.build: los edificios que no actualizan (update = false: muros, nodos de
        // energía, fuente de energía, proyectores...) no entran en Groups.build, solo en el árbol de su equipo.
        final Seq<TeamData> equipos = Vars.state.teams.present;
        for(int i = 0; i < equipos.size; i++){
            final TeamData d = equipos.get(i);
            if(d.buildingTree != null) d.buildingTree.intersect(vista, CUERPO);
        }

        Draw.z(z0);
    }
}
