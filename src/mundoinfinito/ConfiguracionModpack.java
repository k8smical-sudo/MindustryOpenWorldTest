package mundoinfinito;

import arc.Core;

import java.util.Map;
import java.util.TreeMap;

final class ConfiguracionModpack{
    static final String CLAVE = "ow-modpack";

    private static String ultimoTexto = null;
    private static Map<String, String> ultimoMapa = new TreeMap<String, String>();

    private ConfiguracionModpack(){}

    static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0 && id.indexOf('|') < 0 && id.indexOf(';') < 0;
    }

    static String leer(){
        try{
            return Core.settings == null ? "" : Core.settings.getString(CLAVE, "");
        }catch(Throwable t){
            return "";
        }
    }

    static void escribir(String texto){
        if(Core.settings != null) Core.settings.put(CLAVE, texto == null ? "" : texto);
    }

    static Map<String, String> parsear(String texto){
        final Map<String, String> m = new TreeMap<String, String>();
        if(texto == null) return m;
        for(String t : texto.split(";")){
            t = t.trim();
            if(t.isEmpty()) continue;
            final String[] p = t.split("\\|");
            if(p.length != 3 || p[0].trim().isEmpty()) continue;
            m.put(p[0].trim(), p[1].trim() + "|" + p[2].trim());
        }
        return m;
    }

    static Map<String, String> mapaActual(){
        final String t = leer();
        if(!t.equals(ultimoTexto)){
            ultimoTexto = t;
            ultimoMapa = parsear(t);
        }
        return ultimoMapa;
    }

    static String firma(Map<String, String> m){
        final StringBuilder sb = new StringBuilder();
        for(Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('|').append(e.getValue()).append(';');
        return sb.toString();
    }

    static String firma(){
        return firma(parsear(leer()));
    }

    static Map<String, String> con(Map<String, String> base, String id, boolean activo, String peso){
        final Map<String, String> m = new TreeMap<String, String>(base);
        m.put(id, (activo ? "1" : "0") + "|" + (peso == null ? "-" : peso));
        return m;
    }

    static float pesoEfectivo(Map<String, String> cfg, String id, float base){
        final String v = cfg == null ? null : cfg.get(id);
        if(v == null) return base;
        final String[] p = v.split("\\|");
        if(p.length != 2 || p[1].equals("-")) return base;
        try{
            final float w = Float.parseFloat(p[1]);
            return w > 0f && !Float.isInfinite(w) ? w : base;
        }catch(NumberFormatException e){
            return base;
        }
    }

    static boolean activo(Map<String, String> cfg, String id){
        final String v = cfg == null ? null : cfg.get(id);
        return v == null || !v.startsWith("0|");
    }
}
