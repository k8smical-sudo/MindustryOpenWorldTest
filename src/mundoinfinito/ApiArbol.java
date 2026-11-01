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
 * El interruptor y los desbloqueos son del MUNDO (se guardan con él). Los cambios de los addons (añadir, quitar, mover, coste) son de SESIÓN:
 * cada mod los vuelve a aplicar al cargarse. Con el árbol desactivado en el mundo no hay bloqueos ni costes.
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
        /** Nombre visible del contenido (el de Mindustry, o el id si es de un addon). */
        public final String nombre;
        /** Grupo de sección: los nodos con el mismo grupo se desbloquean a la vez, estén en el planeta que estén. */
        public final String grupo;
        /** Cuántos nodos visibles (de cualquier planeta) comparten esta sección, incluido este. */
        public final int copias;

        NodoInfo(ArbolPlanetas.Nodo n){
            this.clave = n.clave;
            this.planeta = n.planeta;
            this.id = n.id;
            this.padre = n.padre;
            this.coste = ItemStack.copy(n.coste);
            this.propio = n.propio;
            this.desbloqueado = ArbolPlanetas.desbloqueado(n.clave);
            this.disponible = ArbolPlanetas.disponible(n.clave);
            this.nombre = n.contenido != null && n.contenido.localizedName != null ? n.contenido.localizedName : n.id;
            this.grupo = ArbolPlanetas.grupoDe(n);
            this.copias = ArbolPlanetas.copias(n.clave);
        }
    }

    /** ¿Tiene el mundo abierto el árbol por planeta activo? Si no, no hay bloqueos ni costes. Sin mundo abierto: el valor por defecto. */
    public static boolean activo(){
        return ArbolPlanetas.activo();
    }

    /**
     * Activa o desactiva el árbol del mundo abierto. Al activarlo se prohíben los bloques sin desbloquear del planeta; al desactivarlo se quitan.
     * Sin mundo abierto devuelve false.
     */
    public static boolean fijarActivo(boolean valor){
        return ArbolPlanetas.fijarActivo(valor);
    }

    /**
     * Planetas que tienen árbol, detectados automáticamente (cada raíz del árbol de Mindustry o de un addon). Un planeta nuevo aparece aquí en cuanto
     * su árbol existe.
     */
    public static List<String> planetas(){
        return ArbolPlanetas.planetas();
    }

    /** Materiales que faltan en el núcleo para investigar un nodo. Vacío si ya se pueden pagar. */
    public static ItemStack[] faltante(String clave){
        final ArbolPlanetas.Nodo n = ArbolPlanetas.nodo(clave);
        return n == null ? new ItemStack[0] : ArbolPlanetas.faltante(n.coste);
    }

    /**
     * Fija el grupo de sección de un nodo. Dos nodos con el mismo grupo se desbloquean juntos, aunque sean de planetas distintos. Null o vacío
     * vuelve al grupo que calcula el código. Solo de sesión: el addon lo vuelve a fijar al cargarse.
     */
    public static boolean fijarGrupo(String clave, String grupo){
        return ArbolPlanetas.fijarGrupo(clave, grupo);
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
