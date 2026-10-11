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

    /** Circunferencia recortada al cuadrado del minimapa (solo se pintan los tramos que caen dentro). */
    private void arco(float cx, float cy, float r, float lim){
        final int seg = 72;
        float ox = 0f, oy = 0f;
        boolean antes = false;
        for(int i = 0; i <= seg; i++){
            float a = i * 6.2831855f / seg;
            float qx = (float)Math.cos(a) * r, qy = (float)Math.sin(a) * r;
            boolean dentro = Math.abs(qx) <= lim && Math.abs(qy) <= lim;
            if(i > 0 && dentro && antes) Lines.line(cx + ox, cy + oy, cx + qx, cy + qy);
            ox = qx; oy = qy; antes = dentro;
        }
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
        float cx = x + width / 2f, cy = y + height / 2f, pxEscala = width / LADO, lim = width / 2f - 3f;
        // zonas activas: carga (blanco) y simulación (cian), según Ajustes. Solo se dibuja lo que cae dentro del minimapa.
        Lines.stroke(1f);
        Draw.color(Color.white);
        Draw.alpha(0.55f * parentAlpha);
        arco(cx, cy, Ajustes.carga() * Coord.CHUNK / (float)tpp * pxEscala, lim);
        Draw.color(Color.cyan);
        Draw.alpha(0.55f * parentAlpha);
        arco(cx, cy, Ajustes.sim() * Coord.CHUNK / (float)tpp * pxEscala, lim);
        Draw.alpha(parentAlpha);
        // núcleos propios REALES (registro persistente en virtuales; el núcleo proxy oculto nunca está aquí)
        for(int[] n : Mapa.nucleos.values()){
            float dx = (float)((n[0] + 0.5 - p[0]) / tpp) * pxEscala, dy = (float)((n[1] + 0.5 - p[1]) / tpp) * pxEscala;
            if(Math.abs(dx) < lim && Math.abs(dy) < lim){
                Draw.color(Color.black);
                Fill.square(cx + dx, cy + dy, 4f);
                Draw.color(Color.gold);
                Fill.square(cx + dx, cy + dy, 3f);
            }else{
                // fuera del minimapa: flecha en el borde apuntando hacia el núcleo
                float k = lim / Math.max(Math.abs(dx), Math.abs(dy));
                float ax = cx + dx * k, ay = cy + dy * k, ang = (float)Math.atan2(dy, dx);
                float c = (float)Math.cos(ang), s = (float)Math.sin(ang);
                Draw.color(Color.gold);
                Fill.tri(ax + c * 3f, ay + s * 3f, ax - c * 3f - s * 3f, ay - s * 3f + c * 3f, ax - c * 3f + s * 3f, ay - s * 3f - c * 3f);
            }
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
