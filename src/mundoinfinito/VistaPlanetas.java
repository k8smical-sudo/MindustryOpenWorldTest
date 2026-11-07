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
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VISTA DE PLANETAS Y SISTEMAS (plan v9, solo 2D). Modo "Planetas" del menú de Mundo abierto.
 *
 * FUENTE (P2): el estado de sistemas que cada mundo guarda en su meta (un cuerpo por mundo, ver {@link AsignacionSistemas}). La vista no calcula
 * posiciones: une los cuerpos guardados y los dibuja. Un mundo antiguo sin cuerpo lo recibe al abrir el modo (migración determinista) y queda
 * guardado, así que su sitio no cambia entre una apertura y otra. Sin mundos guardados, la vista muestra el estado vacío.
 * Se recarga al abrir el modo y tras borrar un mundo: así no quedan planetas de mundos que ya no existen.
 *
 * MOVIMIENTO: órbitas simuladas con el tiempo del mundo (ApiSistemas.anguloEn y el periodo de ApiPlaneta). Nada inventado.
 *
 * SOLO 2D (decisión del plan v9): órbitas circulares vistas desde arriba. No hay render 3D de planetas ni clon de la vista de planetas de
 * la campaña; el modo 3D aproximado de entregas anteriores se quitó.
 *
 * Tocar un mundo lo selecciona y abre el árbol tecnológico de su planeta. Rueda y pellizco hacen zoom; arrastre mueve la vista; Centrar
 * lleva la vista al mundo seleccionado. Misma matemática que el árbol: {@link NavegacionVista}.
 */
final class VistaPlanetas{
    static final float RADIO_ESTRELLA = 16f, RADIO_PLANETA = 9f;
    static final float ESCALA_MIN = 0.5f, ESCALA_MAX = 4f, MARGEN = 40f, ESCALA_CENTRADO = 2f;

    private static Panel ultimo;

    private VistaPlanetas(){}

    /** Vuelve a leer los mundos guardados. Llamar al abrir el modo Planetas y después de borrar un mundo. */
    static void recargar(){
        if(ultimo != null) ultimo.recargar();
    }

    /** Tabla con la vista, la leyenda, los botones y el estado vacío (que ofrece crear un mundo). */
    static Table crear(Runnable nuevoMundo){
        final Lienzo lienzo = new Lienzo();
        final Table vacio = new Table();
        vacio.add("[lightgray]Sin mundos creados. Crea uno para ver sus planetas y sistemas.[]").row();
        vacio.button("Nuevo mundo", Styles.cleart, nuevoMundo).size(260f, 52f).padTop(12f);
        final Label leyenda = new Label("");
        final Table cuerpo = new Table();

        final Table botones = new Table();
        botones.button("Centrar", Styles.cleart, lienzo::centrar).size(160f, 52f).padRight(8f);
        botones.button("Árbol tecnológico", Styles.cleart, () -> ArbolUI.abrir(lienzo.tagSeleccionado())).size(260f, 52f).padRight(8f);
        botones.button("Nombres", Styles.cleart, () -> RenombrarUI.abrir(lienzo.tagSeleccionado())).size(160f, 52f);

        final Table t = new Table();
        t.add(botones).padBottom(6f).row();
        t.add(cuerpo).grow().minHeight(360f).row();
        t.add(leyenda).padTop(6f).row();

        ultimo = new Panel(lienzo, vacio, cuerpo, leyenda);
        ultimo.recargar();
        return t;
    }

    /** Lo que se muestra: el lienzo si hay mundos, o el estado vacío. */
    private static final class Panel{
        final Lienzo lienzo;
        final Table vacio, cuerpo;
        final Label leyenda;

        Panel(Lienzo lienzo, Table vacio, Table cuerpo, Label leyenda){
            this.lienzo = lienzo;
            this.vacio = vacio;
            this.cuerpo = cuerpo;
            this.leyenda = leyenda;
        }

        void recargar(){
            lienzo.recargar();
            cuerpo.clearChildren();
            if(lienzo.hayMundos()) cuerpo.add(lienzo).grow().minHeight(360f);
            else cuerpo.add(vacio).grow();
            leyenda.setText(lienzo.textoLeyenda());
        }
    }

    /** Un mundo guardado, como cuerpo en la órbita de su planeta base. */
    private static final class Mundo{
        final String id, nombre, tag;
        final EstadoSistemas.SistemaSolar sistema;
        final EstadoSistemas.Cuerpo cuerpo;
        /** Órbita compartida del cuerpo, o null si es un planeta solitario (no ocupa órbita). */
        final Orbita orbita;
        final boolean actual;
        /** Tiempo del mundo al leerlo (ticks), periodo de giro del planeta base (ticks) y periodo de revolución PROPIO de este mundo (ticks). */
        final float tiempoMundo;
        final double periodoGiro;
        final double periodoOrbita;

        Mundo(String id, String nombre, String tag, EstadoSistemas.SistemaSolar sistema, EstadoSistemas.Cuerpo cuerpo, Orbita orbita,
              boolean actual, float tiempoMundo, double periodoGiro, double periodoOrbita){
            this.id = id;
            this.nombre = nombre;
            this.tag = tag;
            this.sistema = sistema;
            this.cuerpo = cuerpo;
            this.orbita = orbita;
            this.actual = actual;
            this.tiempoMundo = tiempoMundo;
            this.periodoGiro = periodoGiro;
            this.periodoOrbita = periodoOrbita;
        }
    }

    /** Órbita compartida de un planeta base en un grupo: solo el radio (unidades de Mindustry). El periodo es de CADA mundo (ver Mundo). */
    private static final class Orbita{
        final String tag;
        final float radio;

        Orbita(String tag, float radio){
            this.tag = tag;
            this.radio = radio;
        }
    }

    /** Lienzo con las órbitas y los mundos. */
    static final class Lienzo extends Element{
        final NavegacionVista nav = new NavegacionVista(ESCALA_MIN, ESCALA_MAX);
        private final Map<String, Orbita> orbitas = new LinkedHashMap<String, Orbita>();
        private final List<Mundo> mundos = new ArrayList<Mundo>();
        private EstadoSistemas est = new EstadoSistemas();
        private String seleccionado;
        private float zoomInicial = -1f;

        Lienzo(){
            addListener(new InputListener(){
                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                    nav.zoomEn(x, y, nav.escala * (1f - amountY / 10f));
                    ajustar();
                    return true;
                }
            });

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

            addListener(new ClickListener(){
                @Override
                public void clicked(InputEvent event, float x, float y){
                    final Mundo b = mundoEn(x, y);
                    if(b != null){
                        seleccionado = b.id;
                        ArbolUI.abrir(b.tag);
                    }
                }
            });
        }

        /**
         * Periodo de revolución (ticks) de la órbita de un planeta, con el mismo criterio que el juego (ApiPlaneta.orbitaEn y PlanetasMundo).
         * Se pide por el TAG del planeta: la clave de la órbita de un grupo ("grupo/planeta") no es un planeta registrado, y su valor por
         * defecto era 0, lo que daba un periodo de 1 tick y planetas girando sin control. Si el valor no es válido, usa el por defecto del planeta.
         */
        private double periodoOrbita(MundoInfinitoMod.Meta m, String tag){
            final double p = ApiPlaneta.orbitaEn(m.perfil, tag);
            if(p >= 1.0 && Double.isFinite(p)) return p;
            return Math.max(1.0, ApiPlaneta.orbita(tag));
        }

        /** Vuelve a leer los mundos guardados (migrando los antiguos) y une sus cuerpos en un solo estado. No calcula ninguna posición nueva. */
        void recargar(){
            orbitas.clear();
            mundos.clear();
            final MundoInfinitoMod.Meta actual = MundoInfinitoMod.Streamer.meta;
            final arc.struct.Seq<MundoInfinitoMod.Meta> todos = MundoInfinitoMod.Almacen.listar();
            MundoInfinitoMod.Sistemas.migrar(todos);   // un mundo antiguo sin cuerpo lo recibe aquí y queda guardado
            final ArrayList<AsignacionSistemas.Entrada> entradas = new ArrayList<AsignacionSistemas.Entrada>();
            for(MundoInfinitoMod.Meta m : todos){
                if(!m.compatible()) continue;
                final EstadoSistemas.Cuerpo c = AsignacionSistemas.cuerpoPropio(m.sistemas, m.id);
                if(c != null) entradas.add(new AsignacionSistemas.Entrada(m.id, c.planeta, m.semilla, m.creado, m.sistemas));
            }
            est = AsignacionSistemas.unir(entradas);
            for(MundoInfinitoMod.Meta m : todos){
                final EstadoSistemas.Cuerpo c = est.buscarCuerpo(m.id);
                if(c == null || !m.compatible()) continue;
                final EstadoSistemas.SistemaSolar sistema = AsignacionSistemas.sistemaDe(est, m.id);
                final String tag = c.planeta == null ? ApiPlaneta.SERPULO : c.planeta;
                Orbita o = null;
                if(c.orbita != null){
                    o = orbitas.get(c.orbita);
                    if(o == null){
                        o = new Orbita(c.orbita, (float)est.radioOrbita(c.orbita));
                        orbitas.put(c.orbita, o);
                    }
                }
                mundos.add(new Mundo(m.id, m.nombre, tag, sistema, c, o, actual != null && actual.id.equals(m.id),
                    m.calendario.tiempo(), Math.max(1, ApiPlaneta.rotacion(tag)),
                    o == null ? 0.0 : periodoOrbita(m, tag)));
            }
            if(seleccionado != null && buscarSeleccionado() == null) seleccionado = null;
        }

        boolean hayMundos(){ return !mundos.isEmpty(); }

        /** Leyenda: cuántos cuerpos del estado hay de cada planeta base (con órbita o solitarios). */
        String textoLeyenda(){
            final Map<String, Integer> cuenta = new LinkedHashMap<String, Integer>();
            for(Mundo b : mundos) cuenta.merge(b.tag, 1, Integer::sum);
            final StringBuilder sb = new StringBuilder();
            for(Map.Entry<String, Integer> e : cuenta.entrySet()){
                if(sb.length() > 0) sb.append("   ");
                sb.append("[lightgray]").append(ApiPlaneta.nombre(e.getKey())).append(" (").append(e.getValue())
                    .append(e.getValue() == 1 ? " mundo)[]" : " mundos)[]");
            }
            final List<String> radar = Radares.enOrden();
            if(!radar.isEmpty()){
                sb.append("\n[accent]Radar:[] ");
                int k = 0;
                for(String id : radar){
                    for(Mundo b : mundos) if(b.id.equals(id)){
                        sb.append(k++ > 0 ? ", " : "").append(b.nombre);
                        break;
                    }
                }
            }
            return sb.toString();
        }

        /** Planeta base del mundo seleccionado, o null. */
        String tagSeleccionado(){
            final Mundo b = buscarSeleccionado();
            return b == null ? null : b.tag;
        }

        private Mundo buscarSeleccionado(){
            if(seleccionado == null) return null;
            for(Mundo b : mundos) if(b.id.equals(seleccionado)) return b;
            return null;
        }

        /** Escala base (sin zoom ni paneo): la órbita más grande cabe en la vista. */
        private float escalaBase(){
            float max = 1f;
            for(Orbita o : orbitas.values()) max = Math.max(max, o.radio);
            for(Mundo b : mundos) if(b.orbita == null) max = Math.max(max, (float)Math.hypot(b.sistema.p0x, b.sistema.p0y));   // solitarios
            return Math.min(getWidth(), getHeight()) * 0.42f / max;
        }

        /**
         * Tiempo visual del mundo: su tiempo al leerlo más el tiempo de pantalla transcurrido (Time.globalTime, en ticks). Así la órbita y el giro
         * se mueven en el menú, aunque el juego esté cerrado.
         */
        private double tiempoVisual(Mundo b){
            return b.tiempoMundo + Time.globalTime;
        }

        /** Ángulo del mundo en la órbita simulada. Un solitario no orbita: su ángulo es el de su posición. */
        private double anguloDe(Mundo b){
            if(b.orbita == null) return Math.atan2(b.sistema.p0y, b.sistema.p0x);
            return ApiSistemas.anguloEn(b.cuerpo, tiempoVisual(b), b.periodoOrbita);
        }

        /** Posición del mundo en coordenadas de contenido (sin zoom ni paneo). */
        private float[] contenido(Mundo b){
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, esc = escalaBase();
            if(b.orbita == null){
                final double[] pos = ApiSistemas.posicion(b.sistema, tiempoVisual(b));   // sin deriva (por defecto) es su posición fija
                return new float[]{cx + (float)pos[0] * esc, cy + (float)pos[1] * esc};
            }
            final float r = b.orbita.radio * esc;
            final double a = anguloDe(b);
            return new float[]{cx + (float)Math.cos(a) * r, cy + (float)Math.sin(a) * r};
        }

        /** Posición local (con zoom y paneo) del mundo. */
        private float[] local(Mundo b){
            final float[] c = contenido(b);
            return new float[]{nav.aPantallaX(c[0]), nav.aPantallaY(c[1])};
        }

        /** Mundo bajo un punto (coordenadas locales), o null. */
        private Mundo mundoEn(float x, float y){
            Mundo mejor = null;
            float mejorDist = RADIO_PLANETA * 2.2f;
            for(Mundo b : mundos){
                final float[] p = local(b);
                final float d = (float)Math.hypot(x - p[0], y - p[1]);
                if(d <= mejorDist){
                    mejor = b;
                    mejorDist = d;
                }
            }
            return mejor;
        }

        /** Mantiene un margen visible de la vista dentro de la ventana. */
        private void ajustar(){
            if(getWidth() <= 0f || getHeight() <= 0f) return;
            nav.ajustar(getWidth(), getHeight(), getWidth(), getHeight(), MARGEN);
        }

        /** Centra en el mundo seleccionado con zoom {@link #ESCALA_CENTRADO}; sin selección, vuelve a la vista completa. */
        void centrar(){
            final float cw = getWidth(), ch = getHeight();
            if(cw <= 0f || ch <= 0f) return;
            final Mundo b = buscarSeleccionado();
            if(b == null){
                nav.escala = 1f;
                nav.panX = 0f;
                nav.panY = 0f;
            }else{
                final float[] c = contenido(b);
                nav.escala = NavegacionVista.clamp(ESCALA_CENTRADO, ESCALA_MIN, ESCALA_MAX);
                nav.panX = cw / 2f - c[0] * nav.escala;
                nav.panY = ch / 2f - c[1] * nav.escala;
            }
            ajustar();
        }

        @Override
        public void draw(){
            if(mundos.isEmpty()) return;
            final float cw = getWidth(), ch = getHeight();
            final float cx = this.x + nav.aPantallaX(cw / 2f), cy = this.y + nav.aPantallaY(ch / 2f);

            if(clipBegin()){
                Draw.color(Pal.gray);
                Lines.stroke(2f);
                for(Orbita o : orbitas.values()){
                    final float r = o.radio * escalaBase() * nav.escala;
                    Lines.circle(cx, cy, r);
                }

                Draw.color(Pal.accent);
                Fill.circle(cx, cy, RADIO_ESTRELLA);

                for(Mundo b : mundos){
                    final float[] p = local(b);
                    final float rad = RADIO_PLANETA;
                    Draw.color(b.actual ? Pal.heal : (b.id.equals(seleccionado) ? Pal.accent : Color.white));
                    Fill.circle(this.x + p[0], this.y + p[1], rad);
                    if(Radares.detectados.containsKey(b.id)){   // detectado por un radar (P6): un anillo alrededor
                        Draw.color(Pal.lancerLaser);
                        Lines.stroke(2f);
                        Lines.circle(this.x + p[0], this.y + p[1], rad + 4f);
                    }
                    ColisionVisual.dibujarPulso(b.id, this.x + p[0], this.y + p[1], rad);   // plan v9, P10: pulso rojo de una colisión en curso
                    // Giro: una marca en el disco que da la vuelta según el periodo de rotación; solo se ve la mitad de delante.
                    final double giro = ApiSistemas.rotacionEn(tiempoVisual(b), b.periodoGiro);
                    if(Math.cos(giro) > 0){
                        Draw.color(Color.gray);
                        Fill.circle(this.x + p[0] + (float)Math.sin(giro) * rad * 0.55f, this.y + p[1], rad * 0.22f);
                    }
                }
                Draw.reset();
                clipEnd();
            }
        }
    }
}
