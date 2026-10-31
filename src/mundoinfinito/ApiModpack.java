package mundoinfinito;

import java.util.Map;
import java.util.TreeMap;

public final class ApiModpack{
    private ApiModpack(){}

    public static boolean activo(String idAporte){
        return ConfiguracionModpack.activo(ConfiguracionModpack.mapaActual(), idAporte);
    }

    public static boolean desactivar(String idAporte){
        return fijar(idAporte, false, null);
    }

    public static boolean activar(String idAporte){
        return fijar(idAporte, true, null);
    }

    public static boolean pesoAporte(String idAporte, float peso){
        if(!(peso > 0f) || Float.isInfinite(peso)) return false;
        return fijar(idAporte, activo(idAporte), Float.toString(peso));
    }

    public static void restablecer(String idAporte){
        final Map<String, String> m = new TreeMap<String, String>(ConfiguracionModpack.parsear(ConfiguracionModpack.leer()));
        m.remove(idAporte);
        ConfiguracionModpack.escribir(ConfiguracionModpack.firma(m));
    }

    private static boolean fijar(String idAporte, boolean activo, String peso){
        if(!ConfiguracionModpack.idValido(idAporte)) return false;
        final Map<String, String> base = ConfiguracionModpack.parsear(ConfiguracionModpack.leer());
        ConfiguracionModpack.escribir(ConfiguracionModpack.firma(ConfiguracionModpack.con(base, idAporte, activo, peso)));
        return true;
    }
}
