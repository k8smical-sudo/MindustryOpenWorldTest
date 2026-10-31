package mundoinfinito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MANIFIESTO DE UN MUNDO (fase 13): los mods que lo crearon con su versión y la versión de la API. Funciones puras: sin Mindustry, probables en el corpus.
 * Solo avisa de diferencias; rechaza únicamente una API mayor que la instalada. Los mundos anteriores no guardaron versiones ni API: se comparan por nombre.
 */
final class ManifiestoMundo{
    private static final Pattern API = Pattern.compile("^api (\\d+)\\.");

    private ManifiestoMundo(){}

    /** "nombre@version,nombre2,..." -> nombre -> versión (null si el manifiesto no la guardó). */
    static Map<String, String> parsear(String firma){
        final Map<String, String> r = new LinkedHashMap<String, String>();
        if(firma == null) return r;
        for(String t : firma.split(",")){
            t = t.trim();
            if(t.isEmpty()) continue;
            final int at = t.lastIndexOf('@');
            if(at > 0) r.put(t.substring(0, at), t.substring(at + 1));
            else r.put(t, null);
        }
        return r;
    }

    /** Diferencias entre el manifiesto guardado y el actual. Vacía si son iguales. */
    static List<String> diferencias(String guardado, String actual){
        final Map<String, String> antes = parsear(guardado), ahora = parsear(actual);
        final List<String> out = new ArrayList<String>();
        for(Map.Entry<String, String> e : antes.entrySet()){
            if(!ahora.containsKey(e.getKey())){
                out.add("falta " + e.getKey() + (e.getValue() == null ? "" : " " + e.getValue()));
                continue;
            }
            final String va = e.getValue(), vb = ahora.get(e.getKey());
            if(va != null && vb != null && !va.equals(vb)) out.add(e.getKey() + ": versión " + va + " → " + vb);
        }
        for(Map.Entry<String, String> e : ahora.entrySet()){
            if(!antes.containsKey(e.getKey())) out.add("nuevo " + e.getKey() + (e.getValue() == null ? "" : " " + e.getValue()));
        }
        return out;
    }

    /** Versión mayor de la API con la que se guardó el mundo, o -1 si no se sabe. */
    static int apiMayor(String api){
        if(api == null) return -1;
        final Matcher m = API.matcher(api.trim());
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** true si el mundo necesita una API mayor que la instalada: en ese caso no se abre. */
    static boolean incompatible(String apiGuardada, int mayorInstalada){
        return apiMayor(apiGuardada) > mayorInstalada;
    }
}
