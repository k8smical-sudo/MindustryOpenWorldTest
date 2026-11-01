package mundoinfinito;

import arc.util.Log;
import mindustry.gen.Icon;
import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.Group;
import arc.input.KeyCode;
import arc.scene.event.ClickListener;
import arc.scene.event.ElementGestureListener;
import arc.scene.event.InputListener;
import arc.scene.event.InputEvent;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.type.ItemStack;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.layout.BranchTreeLayout;
import mindustry.ui.layout.TreeLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * INTERFAZ DEL ÁRBOL TECNOLÓGICO POR PLANETA (plan v2, M3).
 *
 *  - BOTÓN "Árbol" en la partida (arriba a la izquierda), con un mundo dimensional abierto y el árbol activo.
 *  - VISTA GRÁFICA del árbol de un planeta, con el mismo diseño que la investigación de Mindustry: un árbol por raíz, ramas repartidas a los dos
 *    lados, líneas en codo y nodos con su icono. Color del borde: verde = investigado, amarillo = disponible, rojo = bloqueado.
 *  - ZOOM Y PANEO (plan v6, D14): rueda o pellizco para el zoom, arrastre para mover, botón Centrar. Igual que el árbol de investigación de
 *    Mindustry. La matemática está en {@link NavegacionVista}, compartida con la vista 2D de planetas.
 *  - PANEL DE INFORMACIÓN al tocar un nodo: nombre, estado, coste, lo que falta en el núcleo y el botón de investigar. Un nodo que comparte sección
 *    con otros planetas lo indica.
 *  - Sin mundo abierto (desde el menú principal) el árbol se ve completo, pero no se puede investigar.
 *
 * Un modder no necesita nada de esta clase: los datos y las acciones están en {@link ApiArbol}.
 */
final class ArbolUI{
    /** Tamaño de un nodo en píxeles de la vista. */
    static final float TAM = 56f;
    /** Separación entre niveles y entre nodos hermanos. */
    static final float GAP = 22f;
    /** Margen alrededor del árbol dibujado. */
    static final float MARGEN = 40f;

    private static boolean creado;

    private ArbolUI(){}

    /** ¿Se ve el acceso al árbol? Solo en un mundo abierto con dimensiones y con el árbol activado (gamerule). */
    private static boolean accesoVisible(){
        return MundoInfinitoMod.Streamer.activo && Vars.state.isGame() && ApiArbol.activo()
            && MundoInfinitoMod.Mundos.actual != null && MundoInfinitoMod.Mundos.actual.dimensional();
    }

    /**
     * Crea el acceso al árbol. Llamar una vez al cargar el cliente (ClientLoadEvent). Plan v8, P4: el acceso es un icono dentro de la barra de
     * iconos del juego (la tabla "mobile buttons" del HUD, junto a menú, esquemas, pausa y árbol/base de datos), no un botón flotante. Solo si esa
     * barra no existe (por ejemplo en escritorio) se usa el botón flotante de antes, para que el árbol no quede inaccesible.
     */
    static void crear(){
        if(creado || Vars.headless || Core.scene == null) return;
        creado = true;
        if(insertarEnBarra()) return;
        Log.warn("[MundoInfinito] No se encontró la barra de iconos del HUD; se usa el botón flotante del árbol tecnológico.");
        Core.scene.add(new Table(t -> {
            t.setFillParent(true);
            t.top().left();
            t.visible(ArbolUI::accesoVisible);
            t.button("Árbol", Styles.cleart, () -> abrir(null)).padTop(92f).padLeft(8f).size(150f, 52f);
        }));
    }

    /** Añade el icono del árbol al final de la barra de iconos del HUD. false si la barra no existe. */
    private static boolean insertarEnBarra(){
        try{
            if(Vars.ui == null || Vars.ui.hudGroup == null) return false;
            final Element barra = Vars.ui.hudGroup.find("mobile buttons");
            if(!(barra instanceof Table)) return false;
            ((Table)barra).button(Icon.tree, Styles.clearNonei, () -> abrir(null)).visible(ArbolUI::accesoVisible);
            return true;
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo añadir el icono del árbol a la barra del HUD.", t);
            return false;
        }
    }

    // ================================================================== diseño (mismo algoritmo que Mindustry)

    /** Nodo del diseño. Hereda las posiciones del algoritmo de árbol de Mindustry. */
    static final class LNode extends TreeLayout.TreeNode<LNode>{
        final ApiArbol.NodoInfo info;

        LNode(ApiArbol.NodoInfo info){
            this.info = info;
            this.width = this.height = TAM;
        }
    }

    /**
     * Coloca los nodos de un planeta. Cada raíz es un árbol; los árboles se ponen uno al lado del otro. Devuelve los nodos y deja en {@code tam}
     * el ancho y el alto del conjunto, incluido el margen.
     */
    static List<LNode> disponer(List<ApiArbol.NodoInfo> nodos, float[] tam){
        final Map<String, LNode> porClave = new HashMap<String, LNode>();
        final List<LNode> todos = new ArrayList<LNode>();
        for(ApiArbol.NodoInfo n : nodos){
            final LNode l = new LNode(n);
            porClave.put(n.clave, l);
            todos.add(l);
        }
        final Map<LNode, List<LNode>> hijos = new HashMap<LNode, List<LNode>>();
        final List<LNode> raices = new ArrayList<LNode>();
        for(LNode l : todos){
            final LNode p = l.info.padre == null ? null : porClave.get(l.info.padre);
            if(p == null){
                raices.add(l);
            }else{
                l.parent = p;
                List<LNode> h = hijos.get(p);
                if(h == null){
                    h = new ArrayList<LNode>();
                    hijos.put(p, h);
                }
                h.add(l);
            }
        }
        for(LNode l : todos){
            final List<LNode> h = hijos.get(l);
            l.children = h == null ? null : h.toArray(new LNode[0]);
        }

        // Cada raíz se dispone como en Mindustry, y luego los árboles se colocan uno al lado del otro
        for(LNode r : raices) disponerRaiz(r);
        float corrimiento = 0f;
        for(LNode r : raices){
            final float[] b = limites(r);
            desplazarX(r, corrimiento - b[0]);
            corrimiento = (b[2] - b[0]) + corrimiento + GAP * 2f;
        }
        final float[] g = limitesDe(raices);
        for(LNode r : raices) desplazar(r, MARGEN - g[0], MARGEN - g[1]);
        tam[0] = (g[2] - g[0]) + MARGEN * 2f;
        tam[1] = (g[3] - g[1]) + MARGEN * 2f;
        return todos;
    }

    /** Como en ResearchDialog.treeLayout: la mitad de los hijos a un lado y la otra mitad al otro. */
    private static void disponerRaiz(LNode raiz){
        final LNode[] todos = raiz.children;
        if(todos == null || todos.length == 0) return;
        final int mitad = Mathf.ceil(todos.length / 2f);
        final LNode[] izq = Arrays.copyOfRange(todos, 0, mitad);
        final LNode[] der = Arrays.copyOfRange(todos, mitad, todos.length);

        raiz.children = izq;
        new BranchTreeLayout(){{
            gapBetweenLevels = gapBetweenNodes = GAP;
            rootLocation = BranchTreeLayout.TreeLocation.top;
        }}.layout(raiz);
        final float ultimoY = raiz.y;

        if(der.length > 0){
            raiz.children = der;
            new BranchTreeLayout(){{
                gapBetweenLevels = gapBetweenNodes = GAP;
                rootLocation = BranchTreeLayout.TreeLocation.bottom;
            }}.layout(raiz);
            final float d = raiz.y - ultimoY;
            for(LNode n : izq) desplazar(n, 0f, d);
        }
        raiz.children = todos;
    }

    private static void recorrer(LNode n, Cons<LNode> f){
        f.get(n);
        if(n.children != null) for(LNode c : n.children) recorrer(c, f);
    }

    /** [minX, minY, maxX, maxY] de un subárbol, en centros de nodo. */
    private static float[] limites(LNode raiz){
        final float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        recorrer(raiz, n -> {
            b[0] = Math.min(b[0], n.x);
            b[1] = Math.min(b[1], n.y);
            b[2] = Math.max(b[2], n.x);
            b[3] = Math.max(b[3], n.y);
        });
        return b;
    }

    private static float[] limitesDe(List<LNode> raices){
        final float[] g = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for(LNode r : raices){
            final float[] b = limites(r);
            g[0] = Math.min(g[0], b[0]);
            g[1] = Math.min(g[1], b[1]);
            g[2] = Math.max(g[2], b[2]);
            g[3] = Math.max(g[3], b[3]);
        }
        if(raices.isEmpty()) return new float[]{0f, 0f, 0f, 0f};
        return g;
    }

    private static void desplazar(LNode raiz, float dx, float dy){
        recorrer(raiz, n -> {
            n.x += dx;
            n.y += dy;
        });
    }

    private static void desplazarX(LNode raiz, float dx){
        desplazar(raiz, dx, 0f);
    }

    // ================================================================== vista

    /** Contenedor de los nodos y las líneas. Las coordenadas de los nodos son las de su centro. */
    static final class Vista extends Group{
        final List<LNode> nodos;

        Vista(List<LNode> nodos, float ancho, float alto, Cons<ApiArbol.NodoInfo> alPulsar){
            this.nodos = nodos;
            setSize(ancho, alto);
            for(LNode l : nodos){
                final NodoVista v = new NodoVista(l.info, alPulsar);
                v.setPosition(l.x - TAM / 2f, l.y - TAM / 2f);
                addChild(v);
            }
        }

        @Override
        protected void drawChildren(){
            for(LNode l : nodos){
                if(l.parent == null) continue;
                final LNode p = (LNode)l.parent;
                Lines.stroke(4f, l.info.desbloqueado ? Pal.accent : Pal.gray);
                final float my = (p.y + l.y) / 2f;
                Lines.line(p.x, p.y, p.x, my);
                Lines.line(p.x, my, l.x, my);
                Lines.line(l.x, my, l.x, l.y);
            }
            super.drawChildren();
        }
    }

    /** Un nodo: icono dentro de un cuadrado, borde de color según su estado. Al tocarlo informa a la vista. */
    static final class NodoVista extends Element{
        final ApiArbol.NodoInfo n;

        NodoVista(ApiArbol.NodoInfo n, Cons<ApiArbol.NodoInfo> alPulsar){
            this.n = n;
            setSize(TAM, TAM);
            addListener(new ClickListener(){
                @Override
                public void clicked(InputEvent e, float x, float y){
                    alPulsar.get(n);
                }
            });
        }

        @Override
        public void draw(){
            final float parentAlpha = color.a;
            final float w = getWidth(), h = getHeight(), x = this.x, y = this.y;
            Draw.color(0f, 0f, 0f, 0.75f * parentAlpha);
            Fill.rect(x + w / 2f, y + h / 2f, w, h);
            final Color borde = n.desbloqueado ? Pal.heal : n.disponible ? Pal.accent : Pal.remove;
            Draw.color(borde, parentAlpha);
            Lines.stroke(3f);
            Lines.rect(x, y, w, h);
            if(n.icono != null){
                Draw.color(1f, 1f, 1f, (n.desbloqueado ? 1f : (n.disponible ? 0.85f : 0.45f)) * parentAlpha);
                Draw.rect(n.icono, x + w / 2f, y + h / 2f, w * 0.72f, h * 0.72f);
            }
            Draw.reset();
        }
    }

    // ================================================================== gestos de vista (zoom y paneo)

    /**
     * Ventana con recorte y gestos: rueda y pellizco para el zoom, arrastre para mover, botón Centrar. El árbol es un hijo transformado: nodos y
     * líneas se escalan como un todo. La matemática es {@link NavegacionVista}; aquí solo hay eventos de Arc.
     */
    static final class Lienzo extends Group{
        static final float ESCALA_MIN = 0.25f, ESCALA_MAX = 1.5f, MARGEN = 40f;
        final Vista vista;
        final float cw, ch;
        final NavegacionVista nav = new NavegacionVista(ESCALA_MIN, ESCALA_MAX);
        private float zoomInicial = -1f;
        private boolean encajado;

        Lienzo(Vista vista, float cw, float ch){
            this.vista = vista;
            this.cw = cw;
            this.ch = ch;
            addChild(vista);
            vista.setOrigin(0f, 0f);
            vista.setTransform(true);

            addListener(new InputListener(){
                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                    nav.zoomEn(x, y, nav.escala * (1f - amountY / 10f));
                    aplicar();
                    return true;
                }
            });

            addCaptureListener(new ElementGestureListener(){
                @Override
                public void zoom(InputEvent event, float initialDistance, float distance){
                    if(zoomInicial < 0f) zoomInicial = nav.escala;
                    nav.zoomEn(getWidth() / 2f, getHeight() / 2f, zoomInicial * distance / initialDistance);
                    aplicar();
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    zoomInicial = -1f;
                }

                @Override
                public void pan(InputEvent event, float x, float y, float deltaX, float deltaY){
                    nav.paneo(deltaX, deltaY);
                    aplicar();
                }
            });
        }

        /** Vuelve a encajar el árbol en la ventana (sin pasar de escala 1) y lo centra. */
        void centrar(){
            if(getWidth() <= 0f || getHeight() <= 0f) return;
            nav.encajar(getWidth(), getHeight(), cw, ch, MARGEN);
            aplicar();
        }

        private void aplicar(){
            if(getWidth() <= 0f || getHeight() <= 0f) return;
            nav.ajustar(getWidth(), getHeight(), cw, ch, MARGEN);
            vista.setScale(nav.escala);
            vista.setPosition(nav.panX, nav.panY);
        }

        @Override
        protected void drawChildren(){
            if(!encajado && getWidth() > 0f && getHeight() > 0f){
                encajado = true;
                centrar();
            }
            if(clipBegin()){
                super.drawChildren();
                clipEnd();
            }
        }
    }

    // ================================================================== diálogo

    /** Planeta que se muestra al abrir: el del mundo abierto, o el primero con árbol. Null si no hay ninguno. */
    static String planetaInicial(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m != null) return ApiPlaneta.planetaDeMundo(m);
        final List<String> ps = ApiArbol.planetas();
        return ps.isEmpty() ? null : ps.get(0);
    }

    /** Abre la vista gráfica. Con planeta null, muestra el del mundo abierto (o el primero con árbol). */
    static void abrir(String planetaElegido){
        final String planeta = planetaElegido != null ? planetaElegido : planetaInicial();
        if(planeta == null){
            if(!Vars.headless && Vars.ui != null) Vars.ui.showInfoToast("No hay planetas con árbol tecnológico", 3f);
            return;
        }
        final BaseDialog d = new BaseDialog("Árbol tecnológico · " + ApiPlaneta.nombre(planeta));

        final Table selector = new Table();
        for(String p : ApiArbol.planetas()){
            final String etiqueta = p.equals(planeta) ? "[accent]" + ApiPlaneta.nombre(p) + "[]" : ApiPlaneta.nombre(p);
            selector.button(etiqueta, Styles.cleart, () -> { d.hide(); abrir(p); }).size(200f, 52f).pad(4f);
        }
        d.cont.add(selector).row();

        final float[] tam = new float[2];
        final List<LNode> nodos = disponer(ApiArbol.nodos(planeta), tam);
        final Table info = new Table();
        info.add("[lightgray]Toca un nodo para ver su coste y su estado.[]").left().row();
        final Vista vista = new Vista(nodos, tam[0], tam[1], n -> pintarInfo(info, d, n, planeta));
        final Lienzo lienzo = new Lienzo(vista, tam[0], tam[1]);
        d.cont.add(lienzo).grow().minHeight(Core.graphics.getHeight() * 0.4f).row();
        selector.button("Centrar", Styles.cleart, lienzo::centrar).size(120f, 44f).pad(4f);
        d.cont.add(info).growX().padTop(6f).row();
        d.addCloseButton();
        d.show();
    }

    /** Panel con el detalle del nodo tocado, su coste, lo que falta y el botón de investigar. */
    private static void pintarInfo(Table info, BaseDialog d, ApiArbol.NodoInfo n, String planeta){
        info.clear();
        final String estado = n.desbloqueado ? "[green]desbloqueado[]" : n.disponible ? "[accent]disponible[]" : "[gray]bloqueado[]";
        final String comparte = n.copias > 1 ? "   [sky]comparte sección con " + (n.copias - 1) + " más[]" : "";
        info.add("[accent]" + n.nombre + "[]   " + estado + comparte).left().row();

        final StringBuilder coste = new StringBuilder("Coste: ");
        if(n.coste.length == 0) coste.append("gratis");
        for(ItemStack s : n.coste) coste.append(s.item.localizedName).append(' ').append(s.amount).append("   ");
        info.add(coste.toString()).left().row();

        final ItemStack[] falta = ApiArbol.faltante(n.clave);
        if(!n.desbloqueado && falta.length > 0){
            final StringBuilder f = new StringBuilder("[scarlet]Faltan en el núcleo: ");
            for(ItemStack s : falta) f.append(s.item.localizedName).append(' ').append(s.amount).append("   ");
            info.add(f.toString() + "[]").left().row();
        }

        if(n.desbloqueado){
            info.add("[green]Ya investigado.[]").left().row();
            return;
        }
        if(MundoInfinitoMod.Streamer.meta == null){
            info.add("[lightgray]Abre un mundo para investigar.[]").left().row();
            return;
        }
        final boolean puede = n.disponible && falta.length == 0;
        final String texto = puede ? "Investigar" : (n.disponible ? "Faltan materiales" : "Aún no disponible");
        info.button(texto, Styles.cleart, () -> {
            if(!ApiArbol.desbloquear(n.clave)){
                Vars.ui.showInfoToast("No se puede investigar ahora", 3f);
                return;
            }
            d.hide();
            abrir(planeta);
        }).size(280f, 52f).padTop(6f).disabled(b -> !puede).row();
    }
}
