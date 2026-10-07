package mundoinfinito;

import arc.Events;
import arc.files.Fi;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.type.ItemStack;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Árbol tecnológico interplanetario, compartido por las dos dimensiones y guardado POR MUNDO.
 *
 * Reutiliza los árboles nativos (TechTree.all: nodos, padres y costes de Serpulo y Erekir) pero lleva su propio
 * registro de lo investigado: el sistema vanilla guarda en el perfil del jugador (valdría para todos los mundos y
 * solo funciona en modo campaña), y aquí hace falta que cada mundo abierto tenga su propio progreso.
 *
 * Reglas para poder investigar un plano:
 *   1) su nodo padre ya está investigado,
 *   2) si es de Erekir y aparece en PUENTES, el plano de Serpulo asociado ya está investigado,
 *   3) el núcleo tiene los materiales del nodo.
 * Lo no investigado se bloquea con Rules.bannedBlocks (mecanismo nativo: el menú de construcción lo respeta).
 */
final class Tecnologia{
    /** {plano de Erekir, plano de Serpulo que debe estar investigado antes}. Por nombre interno: lo que no exista se ignora. */
    static final String[][] PUENTES = {
        {"breach", "silicon-smelter"},
        {"plasma-bore", "laser-drill"},
        {"oxidation-chamber", "thorium-reactor"},
    };
    /** Planos disponibles desde el principio en cada mundo (además de los núcleos). */
    static final String[] INICIALES = {"conveyor", "junction", "router", "mechanical-drill", "duo", "duct", "beam-node"};

    private static final HashSet<String> hechos = new HashSet<>();

    // ------------------------------------------------------------ consulta
    static boolean esBloque(TechNode n){
        return n.content instanceof Block;
    }

    /** Investigado, o no aplicable (raíces y nodos que no son bloques cuentan como hechos). */
    static boolean hecho(TechNode n){
        return n.parent == null || !esBloque(n) || hechos.contains(n.content.name);
    }

    static boolean hechoNombre(String nombre){
        return hechos.contains(nombre);
    }

    static int dimDe(TechNode n){
        while(n.parent != null) n = n.parent;
        return n.content != null && "core-bastion".equals(n.content.name) ? 1 : 0;
    }

    static TechNode nodo(String nombre){
        UnlockableContent c = Vars.content.getByName(ContentType.block, nombre);
        if(c == null) return null;
        for(TechNode n : TechTree.all) if(n.content == c) return n;
        return null;
    }

    /** null = se puede investigar; si no, el motivo. */
    static String motivo(TechNode n){
        if(hecho(n)) return "Investigado";
        if(n.parent != null && !hecho(n.parent)) return "Antes: " + n.parent.content.localizedName;
        for(String[] p : PUENTES){
            if(!p[0].equals(n.content.name)) continue;
            UnlockableContent req = Vars.content.getByName(ContentType.block, p[1]);
            if(req != null && !hechos.contains(req.name)) return "Antes (Serpulo): " + req.localizedName;
        }
        CoreBlock.CoreBuild core = nucleo();
        if(core == null) return "Sin núcleo";
        if(n.requirements != null && !core.items.has(n.requirements)) return "Faltan materiales";
        return null;
    }

    static CoreBlock.CoreBuild nucleo(){
        Seq<CoreBlock.CoreBuild> c = Vars.state.teams.cores(Team.sharded);
        return c.isEmpty() ? null : c.first();
    }

    // ------------------------------------------------------------ acciones
    static void reiniciar(){
        hechos.clear();
        for(String s : INICIALES) hechos.add(s);
    }

    static void investigar(TechNode n){
        if(motivo(n) != null) return;
        CoreBlock.CoreBuild core = nucleo();
        if(core == null) return;
        if(n.requirements != null) core.items.remove(n.requirements);
        hechos.add(n.content.name);
        Vars.state.rules.bannedBlocks.remove((Block)n.content);
        try{
            Events.fire(new EventType.UnlockEvent(n.content));   // el menú de construcción se reconstruye solo
        }catch(Throwable ignored){}
        Vars.ui.showInfoToast("Investigado: " + n.content.localizedName, 3f);
    }

    /** Llamado al construir las reglas de una dimensión. */
    static void aplicarBloqueos(Rules r){
        for(TechNode n : TechTree.all){
            if(esBloque(n) && !hecho(n)) r.bannedBlocks.add((Block)n.content);
        }
    }

    // ------------------------------------------------------------ persistencia
    static byte[] bytes(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
            out.writeInt(1);
            out.writeInt(hechos.size());
            for(String s : hechos) out.writeUTF(s);
            out.close();
            return bos.toByteArray();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo serializar la tecnología", t);
            return null;
        }
    }

    static void cargar(MundoInfinitoMod.Meta m){
        reiniciar();
        if(!m.dimensional()) return;
        Fi f = MundoInfinitoMod.Almacen.archivoTecnologia(m);
        if(!f.exists()) return;
        try{
            DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
            in.readInt();
            int n = in.readInt();
            hechos.clear();
            for(int i = 0; i < n; i++) hechos.add(in.readUTF());
            in.close();
        }catch(Throwable t){
            Log.err("[MundoInfinito] Tecnología ilegible; se reinicia", t);
            reiniciar();
        }
    }

    // ------------------------------------------------------------ interfaz
    static void abrirDialogo(){
        BaseDialog d = new BaseDialog("Tecnología interplanetaria");
        Table lista = new Table();
        Runnable[] rellenar = new Runnable[1];
        rellenar[0] = () -> {
            lista.clear();
            for(int dim = 0; dim < 2; dim++){
                lista.add(dim == 0 ? "[accent]Serpulo[]" : "[accent]Erekir[]").left().padTop(14f).padBottom(4f).row();
                for(TechNode raiz : TechTree.all){
                    if(raiz.parent == null && dimDe(raiz) == dim) fila(lista, raiz, 0, rellenar[0]);
                }
            }
        };
        rellenar[0].run();
        d.cont.pane(lista).grow().minWidth(560f);
        d.addCloseButton();
        d.show();
    }

    private static void fila(Table lista, TechNode n, int prof, Runnable refrescar){
        boolean visible = n.parent != null && esBloque(n);
        if(visible){
            String mot = motivo(n);
            Table f = new Table();
            f.add().width(prof * 14f);
            f.image(n.content.uiIcon).size(32f).padRight(8f);
            f.add(n.content.localizedName).left().growX().minWidth(160f);
            if(mot != null && mot.equals("Investigado")){
                f.add("[green]✔[]").padLeft(8f);
            }else{
                f.add(costo(n)).padLeft(8f).padRight(8f);
                if(mot == null) f.button("Investigar", () -> { investigar(n); refrescar.run(); }).size(110f, 40f);
                else f.add("[gray]" + mot + "[]").width(200f).wrap();
            }
            lista.add(f).left().growX().padBottom(2f).row();
        }
        for(TechNode h : n.children) fila(lista, h, prof + (visible ? 1 : 0), refrescar);
    }

    private static String costo(TechNode n){
        if(n.requirements == null || n.requirements.length == 0) return "gratis";
        StringBuilder sb = new StringBuilder();
        for(ItemStack s : n.requirements){
            if(sb.length() > 0) sb.append(' ');
            sb.append(s.amount).append(' ').append(s.item.localizedName);
        }
        return sb.toString();
    }
}
