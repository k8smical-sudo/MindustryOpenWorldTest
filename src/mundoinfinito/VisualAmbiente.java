package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VisualAmbiente{
    enum Politica{ MEDIA_PONDERADA, PRIORIDAD_ABSOLUTA }

    static final class Iluminacion{
        final float[] color;
        final Float oscuridad;

        Iluminacion(float[] color, Float oscuridad){
            this.color = color;
            this.oscuridad = oscuridad;
        }
    }

    private static final VisualAmbiente GLOBAL = new VisualAmbiente(Politica.MEDIA_PONDERADA);

    static VisualAmbiente global(){
        return GLOBAL;
    }

    private static final class Aporte{
        final String id, fuente;
        final int prioridad;
        final float peso;
        boolean colorPuesto, oscPuesta;
        float r, g, b, osc;

        Aporte(String id, String fuente, int prioridad, float peso){
            this.id = id;
            this.fuente = fuente;
            this.prioridad = prioridad;
            this.peso = peso;
        }
    }

    private static final Comparator<Aporte> CANONICO = new Comparator<Aporte>(){
        @Override
        public int compare(Aporte a, Aporte b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private final Politica politica;
    private final Object cerrojo = new Object();
    private final Map<String, Aporte> aportes = new HashMap<String, Aporte>();
    private final Set<String> avisos = new HashSet<String>();

    VisualAmbiente(Politica politica){
        this.politica = politica;
    }

    Politica politica(){
        return politica;
    }

    private void aviso(String clave, String texto){
        final boolean nuevo;
        synchronized(cerrojo){
            nuevo = avisos.add(clave);
        }
        if(nuevo) Log.warn("[MundoInfinito] Visual: " + texto);
    }

    boolean registrar(String fuente, String id, int prioridad, float peso){
        if(fuente == null || fuente.isEmpty() || id == null || id.indexOf(':') <= 0 || id.indexOf(':') >= id.length() - 1 || id.indexOf(' ') >= 0){
            Log.err("[MundoInfinito] Visual: contribución de iluminación rechazada (id \"fuente:nombre\" obligatorio): @", id);
            return false;
        }
        synchronized(cerrojo){
            if(aportes.containsKey(id)) return false;
            aportes.put(id, new Aporte(id, fuente, prioridad, peso));
            return true;
        }
    }

    private static boolean finito(float... vs){
        for(float v : vs) if(Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }

    /** Color y oscuridad a la vez. Si algún valor no es numérico, el aporte se ignora entero. */
    boolean fijar(String id, float r, float g, float b, float a){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p == null) return false;
            if(!finito(r, g, b, a)){
                p.colorPuesto = false;
                p.oscPuesta = false;
                return true;
            }
            p.r = r;
            p.g = g;
            p.b = b;
            p.colorPuesto = true;
            p.osc = a;
            p.oscPuesta = true;
            return true;
        }
    }

    /** Solo el color (RGB). No cambia la oscuridad de este aporte. */
    boolean fijarColor(String id, float r, float g, float b){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p == null) return false;
            p.r = r;
            p.g = g;
            p.b = b;
            p.colorPuesto = true;
            return true;
        }
    }

    /** Solo la oscuridad (0 = día, 1 = noche total). No cambia el color de este aporte. */
    boolean fijarOscuridad(String id, float oscuridad){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p == null) return false;
            p.osc = oscuridad;
            p.oscPuesta = true;
            return true;
        }
    }

    void quitar(String id){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p != null){
                p.colorPuesto = false;
                p.oscPuesta = false;
            }
        }
    }

    void quitarColor(String id){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p != null) p.colorPuesto = false;
        }
    }

    void quitarOscuridad(String id){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p != null) p.oscPuesta = false;
        }
    }

    private static float recorte(float v){
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    Iluminacion iluminacion(){
        return iluminacion(ConfiguracionModpack.mapaActual());
    }

    Iluminacion iluminacion(Map<String, String> cfg){
        final List<Aporte> color = new ArrayList<Aporte>(), osc = new ArrayList<Aporte>();
        synchronized(cerrojo){
            for(Aporte p : aportes.values()){
                if(!p.colorPuesto && !p.oscPuesta) continue;
                if(!ConfiguracionModpack.activo(cfg, p.id)) continue;
                final float pesoEf = ConfiguracionModpack.pesoEfectivo(cfg, p.id, p.peso);
                if(!(pesoEf > 0f) || Float.isInfinite(pesoEf)){
                    aviso("peso:" + p.id, "la contribución de iluminación '" + p.id + "' tiene un peso no válido (" + pesoEf + ") y se ignora.");
                    continue;
                }
                final Aporte copia = new Aporte(p.id, p.fuente, p.prioridad, pesoEf);
                copia.r = p.r;
                copia.g = p.g;
                copia.b = p.b;
                copia.osc = p.osc;
                if(p.colorPuesto){
                    if(finito(p.r, p.g, p.b)){
                        if(p.r < 0f || p.r > 1f || p.g < 0f || p.g > 1f || p.b < 0f || p.b > 1f)
                            aviso("rangoc:" + p.id, "el color de '" + p.id + "' está fuera de [0, 1] y se recorta.");
                        copia.colorPuesto = true;
                        color.add(copia);
                    }else{
                        aviso("nanc:" + p.id, "el color de '" + p.id + "' no es numérico y se ignora.");
                    }
                }
                if(p.oscPuesta){
                    if(finito(p.osc)){
                        if(p.osc < 0f || p.osc > 1f) aviso("rangoo:" + p.id, "la oscuridad de '" + p.id + "' está fuera de [0, 1] y se recorta.");
                        copia.oscPuesta = true;
                        osc.add(copia);
                    }else{
                        aviso("nano:" + p.id, "la oscuridad de '" + p.id + "' no es numérica y se ignora.");
                    }
                }
            }
        }
        Collections.sort(color, CANONICO);
        Collections.sort(osc, CANONICO);
        final float[] c = color.isEmpty() ? null : combinarColor(color);
        final Float o = osc.isEmpty() ? null : combinarOscuridad(osc);
        return c == null && o == null ? null : new Iluminacion(c, o);
    }

    private float[] combinarColor(List<Aporte> v){
        final float[] out = new float[3];
        if(politica == Politica.PRIORIDAD_ABSOLUTA){
            final Aporte p = v.get(0);
            out[0] = recorte(p.r);
            out[1] = recorte(p.g);
            out[2] = recorte(p.b);
            return out;
        }
        double r = 0.0, g = 0.0, b = 0.0, pesos = 0.0;
        for(Aporte p : v){
            final double w = p.peso;
            r += w * recorte(p.r);
            g += w * recorte(p.g);
            b += w * recorte(p.b);
            pesos += w;
        }
        out[0] = (float)(r / pesos);
        out[1] = (float)(g / pesos);
        out[2] = (float)(b / pesos);
        return out;
    }

    private Float combinarOscuridad(List<Aporte> v){
        if(politica == Politica.PRIORIDAD_ABSOLUTA) return recorte(v.get(0).osc);
        double suma = 0.0, pesos = 0.0;
        for(Aporte p : v){
            final double w = p.peso;
            suma += w * recorte(p.osc);
            pesos += w;
        }
        return (float)(suma / pesos);
    }

    /** Color y oscuridad juntos, como {r, g, b, oscuridad}, solo si ambas están fijadas. Compatibilidad con la API de color ambiental. */
    float[] combinar(){
        final Iluminacion il = iluminacion();
        if(il == null || il.color == null || il.oscuridad == null) return null;
        return new float[]{il.color[0], il.color[1], il.color[2], il.oscuridad};
    }

    int activas(){
        synchronized(cerrojo){
            int n = 0;
            for(Aporte p : aportes.values()) if(p.colorPuesto || p.oscPuesta) n++;
            return n;
        }
    }
}
