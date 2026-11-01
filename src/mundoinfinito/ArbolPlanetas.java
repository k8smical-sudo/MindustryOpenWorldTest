package mundoinfinito;

import arc.Core;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.game.Rules;
import mindustry.type.ItemStack;
import mindustry.ctype.UnlockableContent;
import mindustry.world.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * ÁRBOL TECNOLÓGICO POR PLANETA (plan v2, M3 y D1).
 *
 *  - MODELO: se construye leyendo {@link TechTree#roots} de Mindustry en tiempo de ejecución (un árbol por planeta; la raíz se llama igual que
 *    el planeta). Cada nodo tiene padre, costes y una clave estable "planeta/nombre". Un nodo repetido recibe "#2", "#3"... en orden de recorrido.
 *  - INTERRUPTOR (D1, decidido): es un AJUSTE DEL MUNDO ({@link #K_ACTIVO}, en el perfil). Se fija al crear el mundo con el valor por defecto del
 *    dispositivo ({@link #K_DEFECTO}) y se puede cambiar después. Los mundos antiguos no lo tienen y quedan sin árbol, como hoy. Así un mundo se
 *    comporta igual en cualquier dispositivo y sus desbloqueos siempre corresponden a sus reglas.
 *  - DESBLOQUEOS: en el perfil del mundo, por clave de nodo. Un nodo se desbloquea si su padre ya está desbloqueado (o no tiene padre).
 *  - BLOQUEO: los bloques sin desbloquear se añaden a {@code rules.bannedBlocks} con {@code hideBannedBlocks}. Mindustry ya rechaza construir
 *    un bloque prohibido ({@code Block.isPlaceable}), así que no hace falta parchear el core. La reconstrucción del menú de construcción al
 *    desbloquear en vivo no está verificada: es el punto a comprobar en dispositivo.
 *  - CAMBIOS DE ADDONS (añadir, quitar, mover, coste): son de SESIÓN. El addon los vuelve a aplicar al cargarse.
 *
 * Lo que este archivo NO hace: no cobra el coste al desbloquear (punto abierto del plan, sección 7).
 */
final class ArbolPlanetas{
    /** Ajuste DEL MUNDO (de juego, booleano, en el perfil): árbol activo. Por defecto apagado: los mundos antiguos no cambian. */
    static final String K_ACTIVO = "mundo-infinito:arbol-activo";
    /** Ajuste del DISPOSITIVO (Core.settings): valor que tendrán los mundos nuevos. Lo muestra Ajustes. */
    static final String K_DEFECTO = "ow-arbol-defecto";
    /** Prefijo de los desbloqueos en el perfil del mundo: prefijo + clave del nodo. */
    private static final String PREF_DESBLOQUEO = "mundo-infinito:arbol-desbloqueo:";

    private static boolean registrado;

    /** Un nodo del árbol. Los campos de cambio los modifican los addons a través de ApiArbol. */
    static final class Nodo{
        final String clave;
        final String planeta;
        /** Nombre del contenido de Mindustry (p. ej. "conveyor") o el id del addon ("fuente:nombre"). */
        final String id;
        /** El contenido de Mindustry al que corresponde el nodo, o null si lo añadió un addon. */
        final UnlockableContent contenido;
        final boolean propio;
        String padre;
        ItemStack[] coste;
        boolean quitado;

        Nodo(String clave, String planeta, String id, UnlockableContent contenido, String padre, ItemStack[] coste, boolean propio){
            this.clave = clave;
            this.planeta = planeta;
            this.id = id;
            this.contenido = contenido;
            this.padre = padre;
            this.coste = coste;
            this.propio = propio;
        }
    }

    /** Todos los nodos de todos los planetas, en orden de recorrido (incluidos los quitados, para conservar sus desbloqueos). */
    private static final LinkedHashMap<String, Nodo> NODOS = new LinkedHashMap<String, Nodo>();
    private static boolean construido;

    private ArbolPlanetas(){}

    /** Registra el ajuste del mundo. Llamar una vez al cargar el mod. */
    static void registrar(){
        if(registrado) return;
        registrado = true;
        RegistroAjustes.registrar(AjusteMundo.bool(K_ACTIVO, "Árbol tecnológico por planeta",
            "Cada planeta tiene su árbol: hay que investigar un bloque para construirlo. Se fija al crear el mundo y se puede cambiar después.",
            AjusteMundo.Ambito.JUEGO, false, null));
    }

    // ================================================================== modelo

    /** Lee los árboles de Mindustry la primera vez que hacen falta. Si el contenido aún no está cargado, no hace nada y se reintenta. */
    static synchronized void construir(){
        if(construido || TechTree.roots == null || TechTree.roots.isEmpty()) return;
        construido = true;
        for(TechNode raiz : TechTree.roots){
            if(raiz.name == null) continue;
            recorrer(raiz, raiz.name, null);
        }
    }

    private static void recorrer(TechNode n, String planeta, String padreClave){
        final String id = n.content == null ? "?" : n.content.name;
        String clave = planeta + "/" + id;
        for(int k = 2; NODOS.containsKey(clave); k++) clave = planeta + "/" + id + "#" + k;
        final ItemStack[] coste = n.requirements == null ? new ItemStack[0] : ItemStack.copy(n.requirements);
        NODOS.put(clave, new Nodo(clave, planeta, id, n.content, padreClave, coste, false));
        for(TechNode h : n.children) recorrer(h, planeta, clave);
    }

    /** Nodo por clave, o null. Incluye los quitados (usar {@link #visible} para lo que ve el jugador). */
    static Nodo nodo(String clave){
        construir();
        return clave == null ? null : NODOS.get(clave);
    }

    /** ¿Existe y no está quitado? */
    static boolean visible(String clave){
        final Nodo n = nodo(clave);
        return n != null && !n.quitado;
    }

    /** Nodos visibles de un planeta, en orden de recorrido. */
    static List<Nodo> nodosDe(String planeta){
        construir();
        final ArrayList<Nodo> out = new ArrayList<Nodo>();
        for(Nodo n : NODOS.values()) if(!n.quitado && n.planeta.equals(planeta)) out.add(n);
        return out;
    }

    /** Hijos visibles de un nodo, según el padre actual (con los cambios de los addons). */
    static List<Nodo> hijos(String clave){
        construir();
        final ArrayList<Nodo> out = new ArrayList<Nodo>();
        for(Nodo n : NODOS.values()) if(!n.quitado && clave != null && clave.equals(n.padre)) out.add(n);
        return Collections.unmodifiableList(out);
    }

    /** Un nodo se puede desbloquear si es visible y su padre está desbloqueado (o no tiene padre). */
    static boolean disponible(String clave){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        return n.padre == null || desbloqueado(n.padre);
    }

    // ================================================================== interruptor del mundo y del dispositivo

    /** Mundo abierto, o null. */
    private static MundoInfinitoMod.Meta meta(){
        return MundoInfinitoMod.Streamer.meta;
    }

    /** Valor por defecto del dispositivo: lo que tendrán los mundos nuevos. */
    static boolean activoPorDefecto(){
        return Core.settings != null && Core.settings.getBool(K_DEFECTO, false);
    }

    /**
     * ¿Tiene el mundo abierto el árbol activo? Sin mundo abierto, devuelve el valor por defecto del dispositivo.
     * Los mundos anteriores a este ajuste no lo tienen guardado, así que están sin árbol.
     */
    static boolean activo(){
        final MundoInfinitoMod.Meta m = meta();
        return m == null || m.perfil == null ? activoPorDefecto() : m.perfil.bool(K_ACTIVO, false);
    }

    /**
     * Cambia el árbol del mundo abierto. Al activarlo se prohíben los bloques sin desbloquear; al desactivarlo se quitan las prohibiciones del
     * planeta. Sin mundo abierto devuelve false.
     */
    static boolean fijarActivo(boolean valor){
        final MundoInfinitoMod.Meta m = meta();
        if(m == null || m.perfil == null) return false;
        if(!m.perfil.fijar(K_ACTIVO, valor ? 1.0 : 0.0)) return false;
        final String planeta = ApiPlaneta.planetaDeMundo(m);
        if(valor) aplicarReglas(m, Vars.state == null ? null : Vars.state.rules, planeta);
        else quitarReglas(Vars.state == null ? null : Vars.state.rules, planeta);
        return true;
    }

    /** Valor por defecto del dispositivo (lo guarda Ajustes). */
    static void fijarActivoPorDefecto(boolean valor){
        if(Core.settings != null) Core.settings.put(K_DEFECTO, valor);
    }

    // ================================================================== desbloqueos (en el perfil del mundo)

    /** ¿Está desbloqueado este nodo en el mundo abierto? Con el árbol desactivado, todo lo está. Sin mundo abierto, no. */
    static boolean desbloqueado(String clave){
        if(!activo()) return true;
        final MundoInfinitoMod.Meta m = meta();
        return m != null && clave != null && m.perfil.bool(PREF_DESBLOQUEO + clave, false);
    }

    /**
     * Desbloquea un nodo del mundo abierto si está disponible. Con el árbol desactivado no hace nada y devuelve true (no hay bloqueos).
     * @return true si queda desbloqueado
     */
    static boolean desbloquear(String clave){
        if(!activo()) return true;
        if(desbloqueado(clave)) return true;
        final MundoInfinitoMod.Meta m = meta();
        if(m == null || !disponible(clave)) return false;
        m.perfil.fijar(PREF_DESBLOQUEO + clave, 1.0);
        liberarBloque(clave);
        return true;
    }

    /**
     * ¿Está desbloqueado algún nodo visible con este nombre de contenido en el planeta? Con el árbol desactivado, siempre true.
     */
    static boolean desbloqueadoContenido(String planeta, String id){
        if(!activo()) return true;
        construir();
        for(Nodo n : NODOS.values()){
            if(!n.quitado && n.planeta.equals(planeta) && n.id.equals(id) && desbloqueado(n.clave)) return true;
        }
        return false;
    }

    // ================================================================== reglas del juego (prohibiciones)

    /**
     * Prohíbe en las reglas los bloques del planeta que no están desbloqueados. Solo si el árbol está activo. Llamar al crear las reglas del mundo.
     */
    static void aplicarReglas(MundoInfinitoMod.Meta m, Rules r, String planeta){
        if(r == null || planeta == null || m == null || m.perfil == null || !m.perfil.bool(K_ACTIVO, false)) return;
        construir();
        r.hideBannedBlocks = true;
        for(Nodo n : NODOS.values()){
            if(n.quitado || !n.planeta.equals(planeta) || !(n.contenido instanceof Block)) continue;
            if(!contenidoDesbloqueado(m, planeta, n.id)) r.bannedBlocks.add((Block)n.contenido);
        }
    }

    /** ¿Algún nodo visible de este contenido en el planeta está desbloqueado en ese mundo? */
    private static boolean contenidoDesbloqueado(MundoInfinitoMod.Meta m, String planeta, String id){
        for(Nodo n : NODOS.values()){
            if(!n.quitado && n.planeta.equals(planeta) && n.id.equals(id) && m.perfil.bool(PREF_DESBLOQUEO + n.clave, false)) return true;
        }
        return false;
    }

    /** Quita las prohibiciones que puso el árbol en el planeta (no toca las demás). */
    static void quitarReglas(Rules r, String planeta){
        if(r == null || planeta == null) return;
        construir();
        for(Nodo n : NODOS.values()){
            if(n.planeta.equals(planeta) && n.contenido instanceof Block) r.bannedBlocks.remove((Block)n.contenido);
        }
    }

    /** Al desbloquear en vivo: si ya no queda ningún nodo bloqueado de ese bloque, se quita la prohibición. */
    private static void liberarBloque(String clave){
        final Nodo n = NODOS.get(clave);
        if(n == null || !(n.contenido instanceof Block) || Vars.state == null || Vars.state.rules == null) return;
        if(desbloqueadoContenido(n.planeta, n.id)) Vars.state.rules.bannedBlocks.remove((Block)n.contenido);
    }

    // ================================================================== cambios de los addons (sesión)

    /** Quita un nodo: sus hijos pasan a su padre. Sus desbloqueos se conservan por si vuelve. */
    static boolean quitar(String clave){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        for(Nodo h : NODOS.values()){
            if(clave.equals(h.padre)) h.padre = n.padre;
        }
        n.quitado = true;
        return true;
    }

    /** Cambia el padre de un nodo (null = raíz). Rechaza ciclos y padres de otro planeta. */
    static boolean mover(String clave, String nuevoPadre){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        if(nuevoPadre != null){
            final Nodo p = nodo(nuevoPadre);
            if(p == null || p.quitado || !p.planeta.equals(n.planeta)) return false;
            // hay ciclo si subiendo desde el nuevo padre se llega al propio nodo
            for(Nodo a = p; a != null; a = a.padre == null ? null : NODOS.get(a.padre)){
                if(a == n) return false;
            }
        }
        n.padre = nuevoPadre;
        return true;
    }

    /** Cambia el coste de un nodo. Null = sin coste. */
    static boolean coste(String clave, ItemStack[] coste){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        n.coste = coste == null ? new ItemStack[0] : ItemStack.copy(coste);
        return true;
    }

    /**
     * Añade un nodo a un planeta. El id debe ser "fuente:nombre" con la fuente del propio addon, y la clave no puede existir ya. El padre, si lo
     * hay, debe ser visible en el mismo planeta.
     */
    static boolean anadir(String fuente, String planeta, String id, String padre, ItemStack[] coste){
        construir();
        if(fuente == null || id == null || !id.startsWith(fuente + ":") || id.length() == fuente.length() + 1) return false;
        if(!ApiPlaneta.existe(planeta)) return false;
        if(padre != null){
            final Nodo p = nodo(padre);
            if(p == null || p.quitado || !p.planeta.equals(planeta)) return false;
        }
        final String clave = planeta + "/" + id;
        if(NODOS.containsKey(clave)) return false;
        NODOS.put(clave, new Nodo(clave, planeta, id, null, padre, coste == null ? new ItemStack[0] : ItemStack.copy(coste), true));
        return true;
    }
}
