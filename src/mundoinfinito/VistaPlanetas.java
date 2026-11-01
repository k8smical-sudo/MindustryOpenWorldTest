package mundoinfinito;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.type.Planet;
import mindustry.ui.Styles;

import java.util.List;

/**
 * VISTA DE PLANETAS (plan v2, M8). Modo "Planetas" del menú de Mundo abierto; el modo "Simple" es la lista de siempre.
 *
 *  - Las órbitas son las del juego (radio de cada planeta en Mindustry), escaladas para caber en la vista.
 *  - Con un mundo abierto, la posición de cada planeta es su fase real de la simulación ({@link ApiPlaneta#fase}). Sin mundo, avanza con el periodo
 *    por defecto de cada planeta, solo para verla moverse: no es el estado de ningún mundo.
 *  - Tocar un planeta abre su árbol tecnológico.
 *
 * Los planetas se leen de {@link ApiPlaneta#listar()}, así que un planeta de un addon aparece sin tocar esta clase.
 */
final class VistaPlanetas{
    /** Radio visual de la estrella y de cada planeta, en píxeles. */
    static final float RADIO_ESTRELLA = 16f, RADIO_PLANETA = 11f;
    /** Radio de un planeta de respaldo, para uno cuyo objeto Planet no se encuentra. */
    static final float RADIO_RESPALDO = 60f;

    private VistaPlanetas(){}

    /** Tabla con la vista, la leyenda y el acceso al árbol tecnológico. */
    static Table crear(){
        final Table t = new Table();
        final Lienzo lienzo = new Lienzo();
        t.add(lienzo).grow().minHeight(360f).row();

        final Table leyenda = new Table();
        for(ApiPlaneta.Ficha f : ApiPlaneta.listar()){
            leyenda.add("[lightgray]" + f.nombre + "[]").left().padRight(18f);
        }
        t.add(leyenda).padTop(6f).row();
        t.button("Árbol tecnológico", Styles.cleart, () -> ArbolUI.abrir(null)).size(260f, 52f).padTop(6f).row();
        return t;
    }

    /** Un planeta de la vista, con su radio de órbita y su periodo. */
    private static final class Orbita{
        final String tag;
        final float radio;
        final float periodo;
        final boolean delMundo;

        Orbita(String tag, float radio, float periodo, boolean delMundo){
            this.tag = tag;
            this.radio = radio;
            this.periodo = periodo;
            this.delMundo = delMundo;
        }
    }

    /** Dibuja las órbitas y los planetas. Se recalcula cada fotograma. */
    static final class Lienzo extends Element{
        private final List<Orbita> orbitas;

        Lienzo(){
            final java.util.ArrayList<Orbita> lista = new java.util.ArrayList<Orbita>();
            final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
            final String delMundo = m == null ? null : ApiPlaneta.planetaDeMundo(m);
            int i = 0;
            for(ApiPlaneta.Ficha f : ApiPlaneta.listar()){
                final Planet p = Vars.content == null ? null : Vars.content.planet(f.tag);
                final float radio = p != null && p.orbitRadius > 0f ? p.orbitRadius : RADIO_RESPALDO + 20f * i;
                final float periodo = Math.max(1, ApiPlaneta.orbita(f.tag));
                lista.add(new Orbita(f.tag, radio, periodo, f.tag.equals(delMundo)));
                i++;
            }
            this.orbitas = lista;
            addListener(new ClickListener(){
                @Override
                public void clicked(InputEvent e, float x, float y){
                    final String tag = planetaEn(x, y);
                    if(tag != null) ArbolUI.abrir(tag);
                }
            });
        }

        /** Escala para que la órbita más grande quepa en la vista. */
        private float escala(){
            float max = 1f;
            for(Orbita o : orbitas) max = Math.max(max, o.radio);
            return Math.min(getWidth(), getHeight()) * 0.42f / max;
        }

        /** Ángulo actual de un planeta: su fase real si hay mundo abierto, o un avance visual con su periodo si no. */
        private double angulo(Orbita o){
            if(MundoInfinitoMod.Streamer.meta != null) return ApiPlaneta.fase(o.tag) * 2.0 * Math.PI;
            return (Time.globalTime / o.periodo) * 2.0 * Math.PI;
        }

        /** Planeta bajo un punto (coordenadas locales de la vista), o null. */
        private String planetaEn(float x, float y){
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, k = escala();
            for(Orbita o : orbitas){
                final float px = cx + (float)Math.cos(angulo(o)) * o.radio * k;
                final float py = cy + (float)Math.sin(angulo(o)) * o.radio * k;
                if(Math.hypot(x - px, y - py) <= RADIO_PLANETA * 2.2f) return o.tag;
            }
            return null;
        }

        @Override
        public void draw(){
            if(orbitas.isEmpty()) return;
            final float cx = getX() + getWidth() / 2f, cy = getY() + getHeight() / 2f, k = escala();

            Draw.color(Pal.gray);
            Lines.stroke(2f);
            for(Orbita o : orbitas) Lines.circle(cx, cy, o.radio * k);

            Draw.color(Pal.accent);
            Fill.circle(cx, cy, RADIO_ESTRELLA);

            for(Orbita o : orbitas){
                final float px = cx + (float)Math.cos(angulo(o)) * o.radio * k;
                final float py = cy + (float)Math.sin(angulo(o)) * o.radio * k;
                Draw.color(o.delMundo ? Pal.heal : Color.white);
                Fill.circle(px, py, RADIO_PLANETA);
            }
            Draw.reset();
        }
    }
}
