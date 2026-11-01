package mundoinfinito;

import arc.Core;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.type.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * ÁRBOL TECNOLÓGICO POR PLANETA (plan v2, M3 y D1).
 *
 *  - MODELO: se construye leyendo {@link TechTree#roots} de Mindustry (un árbol por planeta: Serpulo y Erekir). Cada nodo tiene un padre, unos
 *    costes y una clave estable "planeta/nombre". Un nodo repetido en el mismo planeta recibe "#2", "#3"... en orden de recorrido.
 *  - CAMBIOS DE ADDONS: añadir, quitar, mover (cambiar el padre) o cambiar el coste de un nodo. Son de SESIÓN: el addon los vuelve a aplicar al
 *    cargarse, como las acciones del calendario. Quitar un nodo une sus hijos a su padre, para que el árbol siga conectado.
 *  - DESBLOQUEOS: por planeta y por nodo, en Core.settings del dispositivo (es donde Mindustry guarda sus propios desbloqueos). Un nodo se
 *    desbloquea si su padre ya está desbloqueado (o si no tiene padre). Los desbloqueos persisten entre sesiones.
 *  - D1: con el árbol desactivado (ajuste K_ACTIVO), no hay bloqueos ni costes: el juego funciona como hoy. Es un ajuste en vivo, sin reinicio.
 *
 * Lo que este archivo NO hace: no impide construir un bloque. Mindustry decide eso en UnlockableContent.unlocked(), que no se puede sobrescribir
 * desde un mod. Ver {@link ApiArbol#desbloqueadoContenido} y la nota de docs de P4.
 */
final class ArbolPlanetas{
    /** Ajuste (Core.settings) que activa el árbol por planeta. Por defecto desactivado: el comportamiento actual no cambia. */
    static final String K_ACTIVO = "ow-arbol";
    /** Prefijo de los desbloqueos en Core.settings: prefijo + clave del nodo. */
    private static final String PREF_DESBLOQUEO = "mundo-infinito:arbol:";

    /** Un nodo del árbol. Los campos de cambio (padre, coste, quitado) los modifican los addons a través de ApiArbol. */
    static final class Nodo{
        final String clave;
        final String planeta;
        /** Nombre del contenido de Mindustry (p. ej. "conveyor") o el id del addon ("fuente:nombre"). */
        final String id;
        final boolean propio;
        String padre;
        ItemStack[] coste;
        boolean quitado;

        Nodo(String clave, String planeta, String id, String padre, ItemStack[] coste, boolean propio){
            this.clave = clave;
            this.planeta = planeta;
            this.id = id;
            this.padre = padre;
            this.coste = coste;
            this.propio = propio;
        }
    }

    /** Todos los nodos de todos los planetas, en orden de recorrido (incluidos los quitados, para no perder sus desbloqueos). */
    private static final LinkedHashMap<String, Nodo> NODOS = new LinkedHashMap<String, Nodo>();
    private static boolean construido;

    private ArbolPlanetas(){}

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
        NODOS.put(clave, new Nodo(clave, planeta, id, padreClave, coste, false));
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

    /** Hijos visibles de un nodo (según el padre actual, con los cambios de los addons). */
    static List<Nodo> hijos(String clave){
        construir();
        final ArrayList<Nodo> out = new ArrayList<Nodo>();
        for(Nodo n : NODOS.values()) if(!n.quitado && clave.equals(n.padre)) out.add(n);
        return Collections.unmodifiableList(out);
    }

    /** Un nodo se puede desbloquear si es visible y su padre está desbloqueado (o no tiene padre). */
    static boolean disponible(String clave){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        return n.padre == null || desbloqueado(n.padre);
    }

    // ================================================================== desbloqueos

    /** Con el árbol activado (ajuste del jugador). Sin Core.settings (aún no inicializado) se considera desactivado. */
    static boolean activo(){
        return Core.settings != null && Core.settings.getBool(K_ACTIVO, false);
    }

    /** ¿Está desbloqueado este nodo? Con el árbol desactivado, todo lo está. */
    static boolean desbloqueado(String clave){
        if(!activo()) return true;
        return clave != null && Core.settings != null && Core.settings.getBool(PREF_DESBLOQUEO + clave, false);
    }

    /**
     * Desbloquea un nodo si está disponible. Con el árbol desactivado no hace nada y devuelve true (no hay bloqueos).
     * @return true si queda desbloqueado
     */
    static boolean desbloquear(String clave){
        if(!activo()) return true;
        if(desbloqueado(clave)) return true;
        if(!disponible(clave)) return false;
        Core.settings.put(PREF_DESBLOQUEO + clave, true);
        return true;
    }

    /**
     * ¿Está desbloqueado algún nodo visible con este nombre de contenido en el planeta? Sirve para que un mod pregunte si puede usar un bloque.
     * Con el árbol desactivado, siempre true. Un contenido que no está en el árbol del planeta devuelve false (con el árbol activo).
     */
    static boolean desbloqueadoContenido(String planeta, String id){
        if(!activo()) return true;
        construir();
        for(Nodo n : NODOS.values()){
            if(!n.quitado && n.planeta.equals(planeta) && n.id.equals(id) && desbloqueado(n.clave)) return true;
        }
        return false;
    }

    // ================================================================== cambios de los addons (sesión)

    /** Quita un nodo: sus hijos pasan a su padre. Los desbloqueos del nodo se conservan por si vuelve. */
    static boolean quitar(String clave){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        for(Nodo h : NODOS.values()){
            if(clave.equals(h.padre)) h.padre = n.padre;
        }
        n.quitado = true;
        return true;
    }

    /** Cambia el padre de un nodo (null = raíz). Rechaza ciclos: un nodo no puede colgar de sí mismo ni de sus descendientes. */
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

    /** Cambia el coste de un nodo. Null o vacío = sin coste. */
    static boolean coste(String clave, ItemStack[] coste){
        final Nodo n = nodo(clave);
        if(n == null || n.quitado) return false;
        n.coste = coste == null ? new ItemStack[0] : ItemStack.copy(coste);
        return true;
    }

    /**
     * Añade un nodo nuevo a un planeta. El id debe empezar por la fuente y ":" (p. ej. "minmod:torre"), y su clave no puede existir ya.
     * El padre, si lo hay, debe ser visible en el mismo planeta.
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
        NODOS.put(clave, new Nodo(clave, planeta, id, padre, coste == null ? new ItemStack[0] : ItemStack.copy(coste), true));
        return true;
    }
}
