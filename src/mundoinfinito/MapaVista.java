package mundoinfinito;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.scene.Element;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * Minimapa del HUD. Toca para cambiar el zoom (1, 2, 4 u 8 tiles por píxel). Se compone con las miniaturas del mod y se
 * sube a la GPU UNA vez cada pocos fotogramas (el del juego hacía una llamada por cada tile que cambiaba).
 */
final class MapaVista extends Element{
    static final int LADO = 144;
    static boolean creado;

    private final int[] buf = new int[LADO * LADO];
    private final Pixmap pixmap = new Pixmap(LADO, LADO);
    private final Texture textura = new Texture(pixmap);
    private final TextureRegion region = new TextureRegion(textura);
    private float ultimo = -999f;
    private int tpp = 2;

    MapaVista(){
        textura.setFilter(Texture.TextureFilter.nearest);
        clicked(() -> {
            tpp = tpp >= 8 ? 1 : tpp * 2;
            ultimo = -999f;
        });
    }

    static void crear(){
        if(creado || Vars.headless || Core.scene == null) return;
        creado = true;
        Core.scene.add(new Table(t -> {
            t.setFillParent(true);
            t.top().right();
            t.visible(() -> Streamer.activo && Vars.state.isGame() && Mundos.actual != null && Mundos.actual.dimensional());
            float lado = Scl.scl(LADO);
            t.add(new MapaVista()).size(lado).padTop(Scl.scl(64f)).padRight(Scl.scl(8f));
        }));
    }

    @Override
    public void draw(){
        double[] p = Mundos.posicionVirtual();
        if(Time.time - ultimo > 20f){
            ultimo = Time.time;
            Mapa.componer(buf, LADO, LADO, p[0], p[1], tpp, Mapa::get);
            for(int py = 0; py < LADO; py++){
                for(int px = 0; px < LADO; px++){
                    int c = buf[py * LADO + px];
                    pixmap.set(px, py, ((c & 0xff0000) << 8) | ((c & 0xff00) << 8) | ((c & 0xff) << 8) | 0xff);   // ARGB -> RGBA
                }
            }
            textura.draw(pixmap);   // una sola subida a la GPU
        }
        Draw.color(Color.white);
        Draw.alpha(parentAlpha);
        Draw.rect(region, x + width / 2f, y + height / 2f, width, height);
        float cx = x + width / 2f, cy = y + height / 2f, pxEscala = width / LADO;
        // núcleos propios
        Draw.color(Color.gold);
        for(Building n : Vars.state.teams.get(Team.sharded).cores){
            double vx = Coord.virtualX(n.x), vy = Coord.virtualY(n.y);
            float dx = (float)((vx - p[0]) / tpp) * pxEscala, dy = (float)((vy - p[1]) / tpp) * pxEscala;
            if(Math.abs(dx) < width / 2f - 3 && Math.abs(dy) < height / 2f - 3) Fill.square(cx + dx, cy + dy, 3f);
        }
        // jugador
        Draw.color(Color.white);
        Fill.square(cx, cy, 2.5f);
        Draw.color(Color.black);
        Lines.stroke(1f);
        Lines.square(cx, cy, 2.5f);
        Draw.color(Color.darkGray);
        Lines.stroke(2f);
        Lines.rect(x, y, width, height);
        Draw.reset();
    }
}
