package mundoinfinito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API PÚBLICA DE OBJETIVOS DE SECTOR (versión {@link ApiVersion#OBJETIVOS_MAYOR}.{@link ApiVersion#OBJETIVOS_MENOR}). Plan v9, P11.
 *
 * Un objetivo es un contador: conseguir una cantidad de un ítem en el núcleo, por ejemplo "Obtener berilio 58/60". Lo define el addon
 * (título, ítem y meta). El progreso no se guarda aparte: es lo que hay en el núcleo del jugador, así que se conserva con el mundo.
 *
 * DESACTIVADO POR DEFECTO. La barra solo se ve si el gamerule {@link #REGLA_ACTIVA} está activo. Sin objetivos registrados, la barra está vacía.
 *
 * Esta clase es lógica pura: no toca el juego. La lectura del núcleo la hace la capa de interfaz ({@code ObjetivosHud}).
 *
 * COMPLETADO UNA VEZ (1.1): al llegar a la meta el objetivo queda completado para siempre en ese mundo, aunque el jugador gaste después el ítem.
 * El estado ({@link Estado}) es un conjunto de ids que se guarda con el mundo (texto de {@link Estado#aTexto}); los mundos antiguos lo leen vacío.
 * Las funciones sin estado ({@link #completo(int, int)} y {@link #texto(Objetivo, int)}) siguen midiendo solo la cantidad actual.
 *
 * OBJETIVOS POR DEFECTO: el juego no trae ninguno. Todo objetivo lo define un addon (ver el ejemplo del berilio en ejemplo-mod).
 */
public final class ApiObjetivos{
    /** Gamerule que muestra la barra de objetivos. Apagada por defecto. */
    public static final String REGLA_ACTIVA = "mundo-infinito:objetivos-sector";
    /** Meta máxima de un objetivo. */
    public static final int META_MAX = 100000;

    /** Un objetivo declarado: título visible, ítem del núcleo y meta. Inmutable. */
    public static final class Objetivo{
        public final String id, titulo, item;
        public final int meta;

        Objetivo(String id, String titulo, String item, int meta){
            this.id = id;
            this.titulo = titulo;
            this.item = item;
            this.meta = meta;
        }
    }

    private static final Map<String, Objetivo> OBJETIVOS = new LinkedHashMap<String, Objetivo>();

    private ApiObjetivos(){}

    /**
     * Registra un objetivo. Devuelve false si el id no tiene formato "fuente:nombre", ya existe, el título está vacío, el ítem es vacío o tiene
     * espacios, o la meta está fuera de 1 a {@link #META_MAX}.
     */
    public static synchronized boolean registrar(String id, String titulo, String item, int meta){
        if(!idValido(id) || OBJETIVOS.containsKey(id)) return false;
        if(titulo == null || titulo.trim().isEmpty()) return false;
        if(item == null || item.trim().isEmpty() || item.indexOf(' ') >= 0) return false;
        if(meta < 1 || meta > META_MAX) return false;
        OBJETIVOS.put(id, new Objetivo(id, titulo, item, meta));
        return true;
    }

    /** Quita un objetivo. */
    public static synchronized boolean quitar(String id){
        return OBJETIVOS.remove(id) != null;
    }

    /** Objetivos registrados, en orden de registro. */
    public static synchronized List<Objetivo> objetivos(){
        return new ArrayList<Objetivo>(OBJETIVOS.values());
    }

    /** Progreso acotado a la meta: nunca negativo, nunca mayor que la meta. */
    public static int progreso(int cantidad, int meta){
        if(meta <= 0) return 0;
        return Math.max(0, Math.min(cantidad, meta));
    }

    /** ¿Está completo? Sí si la cantidad alcanza la meta. */
    public static boolean completo(int cantidad, int meta){
        return meta > 0 && cantidad >= meta;
    }

    /** Texto de la barra: "Obtener berilio 58/60", o con "(completo)" al llegar a la meta. */
    public static String texto(Objetivo o, int cantidad){
        final int p = progreso(cantidad, o.meta);
        return o.titulo + " " + p + "/" + o.meta + (completo(cantidad, o.meta) ? " (completo)" : "");
    }

    /**
     * Objetivos ya completados en un mundo. Se guarda con el mundo. Es lógica pura: quien lo posee (la capa de interfaz) lo guarda y lo restaura.
     * No es seguro entre hilos: se usa desde el hilo principal.
     */
    public static final class Estado{
        private final Set<String> completados = new LinkedHashSet<String>();

        /** ¿Está completado este objetivo? */
        public boolean completado(String id){
            return completados.contains(id);
        }

        /** Marca un objetivo como completado. Devuelve true si es nuevo. */
        boolean marcar(String id){
            return id != null && completados.add(id);
        }

        /** Ids completados, en el orden en que se completaron. */
        public List<String> ids(){
            return new ArrayList<String>(completados);
        }

        /** Texto para guardar con el mundo: los ids separados por salto de línea. Vacío si no hay ninguno. */
        public String aTexto(){
            final StringBuilder sb = new StringBuilder();
            for(String id : completados){
                if(sb.length() > 0) sb.append('\n');
                sb.append(id);
            }
            return sb.toString();
        }

        /** Lee el texto de {@link #aTexto}. Nulo o vacío: estado vacío. Una línea vacía o con espacios se descarta. Nunca lanza excepciones. */
        public static Estado desdeTexto(String texto){
            final Estado e = new Estado();
            if(texto == null || texto.isEmpty()) return e;
            for(String linea : texto.split("\n")){
                final String id = linea.trim();
                if(!id.isEmpty() && id.indexOf(' ') < 0) e.completados.add(id);
            }
            return e;
        }
    }

    /**
     * Actualiza el estado con la cantidad actual del núcleo: si alcanza la meta, el objetivo queda completado para siempre. Devuelve true solo en
     * el momento en que se completa (para avisar al jugador una vez). Un objetivo ya completado devuelve false.
     */
    public static boolean actualizar(Estado estado, Objetivo o, int cantidad){
        if(estado == null || o == null) return false;
        if(estado.completado(o.id)) return false;
        return completo(cantidad, o.meta) && estado.marcar(o.id);
    }

    /** ¿Está completo contando el estado guardado? Sí si ya se completó alguna vez o si la cantidad actual alcanza la meta. */
    public static boolean completo(Estado estado, Objetivo o, int cantidad){
        return o != null && ((estado != null && estado.completado(o.id)) || completo(cantidad, o.meta));
    }

    /**
     * Texto de la barra con el estado guardado: un objetivo completado alguna vez muestra la meta entera y "(completo)", aunque ahora haya menos
     * ítems en el núcleo.
     */
    public static String texto(Estado estado, Objetivo o, int cantidad){
        if(estado != null && estado.completado(o.id)) return texto(o, o.meta);
        return texto(o, cantidad);
    }

    /** ¿Está activa la barra en el mundo abierto? Falso si la regla no existe, si no hay mundo o si está apagada. */
    public static boolean activa(){
        final double v = ApiAjustes.valor(REGLA_ACTIVA);
        return !Double.isNaN(v) && v != 0.0;
    }

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }
}
