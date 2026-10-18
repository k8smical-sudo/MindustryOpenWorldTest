package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.math.geom.Rect;
import arc.struct.ObjectMap;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
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

    private static void alCuadro(){
        if(Vars.headless || Vars.state == null || !Vars.state.isGame() || Vars.player == null) return;
        if(Vars.world == null || Vars.world.tiles == null || !Vars.world.tiles.ring) return;

        Core.camera.bounds(vista);
        vista.grow(Vars.tilesize * 2f);
        final mindustry.game.Team propio = Vars.player.team();
        final float z0 = Draw.z();
        final Rect v = vista;

        Groups.build.each(b -> {
            if(!b.block.drawCached) return;
            final float m = b.block.size * Vars.tilesize / 2f;
            if(!v.overlaps(b.x - m, b.y - m, m * 2f, m * 2f)) return;
            if(!necesitaCuerpo(b)) return;
            if(b.inFogTo(propio) && !b.wasVisible) return;   // bajo la niebla no se dibuja, igual que el renderizador vanilla

            Draw.z(b.block.buildingCacheLayer.layer);   // la misma capa en la que se hornearía (under = Layer.block - 0.5, normal = Layer.block)
            b.drawCached();
            Draw.reset();
        });

        Draw.z(z0);
    }
}
