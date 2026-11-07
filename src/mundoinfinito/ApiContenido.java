package mundoinfinito;

import arc.util.Log;
import mindustry.Vars;
import mindustry.world.meta.BuildVisibility;

import java.util.HashSet;
import java.util.Set;

/**
 * API PÚBLICA DE VISIBILIDAD DEL CONTENIDO DEL MOD EN CAMPAÑA (versión {@link ApiVersion#CONTENIDO_MAYOR}.{@link ApiVersion#CONTENIDO_MENOR}).
 *
 * POR DEFECTO el contenido de este mod (ítems de boosters, portales, radares...) NO se muestra en el modo campaña: ni en el menú de construcción, ni
 * en las listas de ítems, ni en la base de datos del sector. En los mundos del mod y en el modo sandbox se ve con normalidad. Un dev o un modder puede
 * decidir lo contrario, ítem por ítem o bloque por bloque, con {@link #mostrarEnCampana}.
 *
 *   ApiContenido.mostrarEnCampana("portal-viaje-1", true);       // este portal sí se ofrece en campaña
 *   ApiContenido.mostrarTodoEnCampana(true);                     // todo el contenido del mod, de golpe
 *
 * Es solo visibilidad: nada de aquí cambia la simulación. Lo que un addon registre con {@link #visibilidad(String)} o {@link #contenidoDelMod} sigue la
 * misma regla. Las llamadas se hacen al cargar el mod (o antes de abrir un mundo); el cambio se nota en el siguiente fotograma.
 */
public final class ApiContenido{
    private static final Object CERROJO = new Object();
    private static final Set<String> PERMITIDOS = new HashSet<String>();
    private static final Set<String> PROHIBIDOS = new HashSet<String>();
    private static volatile boolean todo;

    private ApiContenido(){}

    /**
     * Permite (true) o vuelve a prohibir (false) un contenido del mod en campaña, por su nombre interno ("portal-viaje-1", "booster-alcance"...). Un
     * nombre concreto manda sobre {@link #mostrarTodoEnCampana}. Devuelve false si el nombre es nulo o vacío.
     */
    public static boolean mostrarEnCampana(String nombre, boolean mostrar){
        if(nombre == null || nombre.trim().isEmpty()) return false;
        synchronized(CERROJO){
            if(mostrar){
                PERMITIDOS.add(nombre);
                PROHIBIDOS.remove(nombre);
            }else{
                PROHIBIDOS.add(nombre);
                PERMITIDOS.remove(nombre);
            }
        }
        return true;
    }

    /** Permite o prohíbe en campaña todo el contenido del mod que no tenga una decisión propia. Por defecto, prohibido. */
    public static void mostrarTodoEnCampana(boolean mostrar){
        todo = mostrar;
    }

    /** ¿Se muestra este contenido en campaña? Una decisión concreta gana; si no la hay, vale {@link #mostrarTodoEnCampana}. Lógica pura. */
    public static boolean visibleEnCampana(String nombre){
        synchronized(CERROJO){
            if(nombre != null){
                if(PERMITIDOS.contains(nombre)) return true;
                if(PROHIBIDOS.contains(nombre)) return false;
            }
        }
        return todo;
    }

    /** ¿Estamos ahora en el modo campaña? Falso sin partida. */
    static boolean enCampana(){
        try{
            return Vars.state != null && Vars.state.isCampaign();
        }catch(Throwable t){
            return false;
        }
    }

    /** ¿Debe ocultarse ahora este contenido? Solo en campaña y solo si no se permitió. */
    public static boolean ocultoAhora(String nombre){
        return enCampana() && !visibleEnCampana(nombre);
    }

    /** Visibilidad de construcción para un bloque: se ofrece siempre, salvo en campaña si no se permitió. Para los bloques del mod y de los addons. */
    public static BuildVisibility visibilidad(final String nombreBloque){
        return new BuildVisibility(() -> !ocultoAhora(nombreBloque));
    }

    /** Solo para pruebas. */
    static void reiniciar(){
        synchronized(CERROJO){
            PERMITIDOS.clear();
            PROHIBIDOS.clear();
        }
        todo = false;
    }
}
