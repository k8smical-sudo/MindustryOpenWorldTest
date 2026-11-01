package mundoinfinito;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.ElementGestureListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.type.Planet;
import mindustry.ui.Styles;

import java.util.List;

/**
 * VISTA DE PLANETAS (plan v2, M8; zoom y paneo en plan v6, F6 / D14). Modo "Planetas" del menú de Mundo abierto.
 *
 *  - Las órbitas son las del juego (radio de cada planeta en Mindustry), escaladas para caber en la vista.
 *  - Con un mundo abierto, la posición de cada planeta es su fase real de la simulación ({@link ApiPlaneta#fase}). Sin mundo, avanza con el
 *    periodo por defecto de cada planeta, solo para verla moverse: no es el estado de ningún mundo.
 *  - Tocar un planeta abre su árbol tecnológico y lo deja seleccionado para el botón Centrar.
 *  - Rueda y pellizco hacen zoom; arrastre mueve la vista (misma matemática que el árbol: {@link NavegacionVista}).
 *
 * Los planetas se leen de {@link ApiPlaneta#listar()}, así que un planeta de un addon aparece sin tocar esta clase. Esta vista todavía no
 * lee {@link EstadoSistemas}: eso llega con la entrega siguiente, cuando el estado se guarde con el mundo.
 */
final class VistaPlanetas{
    /** Radio visual de la estrella y de cada planeta, en píxeles. */
    static final float RADIO_ESTRELLA = 16f, RADIO_PLANETA = 11f;
    /** Radio de un planeta de respaldo, para uno cuyo objeto Planet no se encuentra. */
    static final float RADIO_RESPALDO = 60f;
    /** Límites de zoom y margen visible (mismos valores que el árbol). */
    static final float ESCALA_MIN = 0.5f, ESCALA_MAX = 4f, MARGEN = 40f;
    /** Escala al centrar en un planeta seleccionado. */
    static final float ESCALA_CENTRADO = 2f;

    private VistaPlanetas(){}

    /** Tabla con la vista, la leyenda y los botones (Centrar y acceso al árbol tecnológico). */
    static Table crear(){
        final Table t = new Table();
        final Lienzo lienzo = new Lienzo();
        t.add(lienzo).grow().minHeight(360f).row();

        final Table leyenda = new Table();
        for(ApiPlaneta.Ficha f : ApiPlaneta.listar()){
            leyenda.add("[lightgray]" + f.nombre + "[]").left().padRight(18f);
        }
        t.add(leyenda).padTop(6f).row();

        final Table botones = new Table();
        botones.button("Centrar", Styles.cleart, lienzo::centrar).size(160f, 52f).padRight(8f);
        botones.button("Árbol tecnológico", Styles.cleart, () -> ArbolUI.abrir(null)).size(260f, 52f);
        t.add(botones).padTop(6f).row();
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

    /** Dibuja las órbitas y los planetas, con zoom y paneo. Se recalcula cada fotograma. */
    static final class Lienzo extends Element{
        private final List<Orbita> orbitas;
        final NavegacionVista nav = new NavegacionVista(ESCALA_MIN, ESCALA_MAX);
        private float zoomInicial = -1f;
        private String seleccionado;

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

            // Rueda: zoom centrado en el puntero (mismo criterio que el árbol).
            addListener(new InputListener(){
                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                    nav.zoomEn(x, y, nav.escala * (1f - amountY / 10f));
                    ajustar();
                    return true;
                }
            });

            // Pellizco y arrastre.
            addCaptureListener(new ElementGestureListener(){
                @Override
                public void zoom(InputEvent event, float initialDistance, float distance){
                    if(zoomInicial < 0f) zoomInicial = nav.escala;
                    nav.zoomEn(getWidth() / 2f, getHeight() / 2f, zoomInicial * distance / initialDistance);
                    ajustar();
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    zoomInicial = -1f;
                }

                @Override
                public void pan(InputEvent event, float x, float y, float deltaX, float deltaY){
                    nav.paneo(deltaX, deltaY);
                    ajustar();
                }
            });

            // Toque: abre el árbol del planeta tocado y lo deja seleccionado.
            addListener(new ClickListener(){
                @Override
                public void clicked(InputEvent event, float x, float y){
                    final String tag = planetaEn(x, y);
                    if(tag != null){
                        seleccionado = tag;
                        ArbolUI.abrir(tag);
                    }
                }
            });
        }

        /** Escala base (sin zoom): la órbita más grande cabe en la vista. */
        private float escalaBase(){
            float max = 1f;
            for(Orbita o : orbitas) max = Math.max(max, o.radio);
            return Math.min(getWidth(), getHeight()) * 0.42f / max;
        }

        /** Ángulo actual de un planeta: su fase real si hay mundo abierto, o un avance visual con su periodo si no. */
        private double angulo(Orbita o){
            if(MundoInfinitoMod.Streamer.meta != null) return ApiPlaneta.fase(o.tag) * 2.0 * Math.PI;
            return (Time.globalTime / o.periodo) * 2.0 * Math.PI;
        }

        /** Posición de un planeta en coordenadas de contenido (la vista sin zoom ni paneo). */
        private float[] contenido(Orbita o){
            final float k = escalaBase(), cx = getWidth() / 2f, cy = getHeight() / 2f;
            final double a = angulo(o);
            return new float[]{cx + (float)Math.cos(a) * o.radio * k, cy + (float)Math.sin(a) * o.radio * k};
        }

        /** Posición de un planeta en coordenadas locales de la vista, con zoom y paneo aplicados. */
        private float[] local(Orbita o){
            final float[] c = contenido(o);
            return new float[]{nav.aPantallaX(c[0]), nav.aPantallaY(c[1])};
        }

        /** Planeta bajo un punto (coordenadas locales de la vista), o null. */
        private String planetaEn(float x, float y){
            for(Orbita o : orbitas){
                final float[] p = local(o);
                if(Math.hypot(x - p[0], y - p[1]) <= RADIO_PLANETA * 2.2f) return o.tag;
            }
            return null;
        }

        private Orbita buscar(String tag){
            for(Orbita o : orbitas) if(o.tag.equals(tag)) return o;
            return null;
        }

        /** Mantiene un margen visible de la vista dentro de la ventana. */
        private void ajustar(){
            if(getWidth() <= 0f || getHeight() <= 0f) return;
            nav.ajustar(getWidth(), getHeight(), getWidth(), getHeight(), MARGEN);
        }

        /** Centra la vista en el planeta seleccionado con zoom {@link #ESCALA_CENTRADO}; sin selección, vuelve a la vista completa. */
        void centrar(){
            final float cw = getWidth(), ch = getHeight();
            if(cw <= 0f || ch <= 0f) return;
            final Orbita o = seleccionado == null ? null : buscar(seleccionado);
            if(o == null){
                nav.escala = 1f;
                nav.panX = 0f;
                nav.panY = 0f;
            }else{
                final float[] c = contenido(o);
                nav.escala = NavegacionVista.clamp(ESCALA_CENTRADO, ESCALA_MIN, ESCALA_MAX);
                nav.panX = cw / 2f - c[0] * nav.escala;
                nav.panY = ch / 2f - c[1] * nav.escala;
            }
            ajustar();
        }

        @Override
        public void draw(){
            if(orbitas.isEmpty()) return;
            final float cw = getWidth(), ch = getHeight();
            final float k = escalaBase() * nav.escala;
            final float cx = this.x + nav.aPantallaX(cw / 2f), cy = this.y + nav.aPantallaY(ch / 2f);

            if(clipBegin()){
                Draw.color(Pal.gray);
                Lines.stroke(2f);
                for(Orbita o : orbitas) Lines.circle(cx, cy, o.radio * k);

                Draw.color(Pal.accent);
                Fill.circle(cx, cy, RADIO_ESTRELLA);

                for(Orbita o : orbitas){
                    final float[] p = local(o);
                    Draw.color(o.delMundo ? Pal.heal : Color.white);
                    Fill.circle(this.x + p[0], this.y + p[1], RADIO_PLANETA);
                }
                Draw.reset();
                clipEnd();
            }
        }
    }
}
