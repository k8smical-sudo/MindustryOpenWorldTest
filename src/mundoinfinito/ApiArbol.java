package mundoinfinito;

import mindustry.type.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * API PÚBLICA DEL ÁRBOL TECNOLÓGICO POR PLANETA (versión {@link ApiVersion#ARBOL_MAYOR}.{@link ApiVersion#ARBOL_MENOR}).
 *
 * Cada planeta tiene su árbol: el de Mindustry por defecto (Serpulo y Erekir). Un nodo se identifica por su CLAVE "planeta/nombre": Serpulo y
 * Erekir usan el nombre de contenido de Mindustry (p. ej. "serpulo/conveyor"); un nodo de un addon usa su id "fuente:nombre".
 *
 * Los cambios de los addons (añadir, quitar, mover, coste) son de SESIÓN: cada mod los vuelve a aplicar al cargarse. Los desbloqueos sí se
 * guardan, en el dispositivo. Con el árbol desactivado (ajuste "ow-arbol", por defecto apagado) no hay bloqueos ni costes.
 */
public final class ApiArbol{
    private ApiArbol(){}

    /** Vista de un nodo para leerlo. Es una copia: cambiarla no cambia el árbol. */
    public static final class NodoInfo{
        public final String clave;
        public final String planeta;
        public final String id;
        public final String padre;
        public final ItemStack[] coste;
        /** true si lo añadió un addon, false si viene del árbol de Mindustry. */
        public final boolean propio;
        public final boolean desbloqueado;
        public final boolean disponible;

        NodoInfo(ArbolPlanetas.Nodo n){
            this.clave = n.clave;
            this.planeta = n.planeta;
            this.id = n.id;
            this.padre = n.padre;
            this.coste = ItemStack.copy(n.coste);
            this.propio = n.propio;
            this.desbloqueado = ArbolPlanetas.desbloqueado(n.clave);
            this.disponible = ArbolPlanetas.disponible(n.clave);
        }
    }

    /** ¿Está activado el árbol por planeta? Lo elige el jugador en Ajustes; sin él no hay bloqueos ni costes. */
    public static boolean activo(){
        return ArbolPlanetas.activo();
    }

    /** Nodos visibles de un planeta, en orden. Un planeta sin árbol devuelve lista vacía. */
    public static List<NodoInfo> nodos(String planeta){
        final ArrayList<NodoInfo> out = new ArrayList<NodoInfo>();
        for(ArbolPlanetas.Nodo n : ArbolPlanetas.nodosDe(planeta)) out.add(new NodoInfo(n));
        return out;
    }

    /** Un nodo por clave, o null si no existe o está quitado. */
    public static NodoInfo nodo(String clave){
        final ArbolPlanetas.Nodo n = ArbolPlanetas.nodo(clave);
        return n == null || n.quitado ? null : new NodoInfo(n);
    }

    /** ¿Está desbloqueado el nodo? Con el árbol desactivado, siempre true. */
    public static boolean desbloqueado(String clave){
        return ArbolPlanetas.desbloqueado(clave);
    }

    /** Desbloquea un nodo si su padre ya está desbloqueado. Con el árbol desactivado devuelve true sin guardar nada. */
    public static boolean desbloquear(String clave){
        return ArbolPlanetas.desbloquear(clave);
    }

    /** ¿Puede usarse el contenido de Mindustry con ese nombre en el planeta? Con el árbol desactivado, siempre true. */
    public static boolean desbloqueadoContenido(String planeta, String nombre){
        return ArbolPlanetas.desbloqueadoContenido(planeta, nombre);
    }

    /**
     * Añade un nodo a un planeta (solo de sesión). El id debe ser "fuente:nombre" con la fuente del propio addon.
     * @param padre clave de un nodo visible del mismo planeta, o null para una raíz
     * @return false si el planeta no existe, el id no es válido o ya existe, o el padre no es válido
     */
    public static boolean anadir(String fuente, String planeta, String id, String padre, ItemStack[] coste){
        return ArbolPlanetas.anadir(fuente, planeta, id, padre, coste);
    }

    /** Quita un nodo (solo de sesión). Sus hijos pasan a su padre. Sus desbloqueos se conservan. */
    public static boolean quitar(String clave){
        return ArbolPlanetas.quitar(clave);
    }

    /** Cambia el padre de un nodo (solo de sesión). Null = raíz. Rechaza ciclos. */
    public static boolean mover(String clave, String nuevoPadre){
        return ArbolPlanetas.mover(clave, nuevoPadre);
    }

    /** Cambia el coste de un nodo (solo de sesión). Null = sin coste. */
    public static boolean coste(String clave, ItemStack[] coste){
        return ArbolPlanetas.coste(clave, coste);
    }
}
