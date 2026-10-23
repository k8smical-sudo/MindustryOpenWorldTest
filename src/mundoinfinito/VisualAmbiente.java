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

/**
 * COLOR AMBIENTAL Y LUZ como contribuciones con política (fase 10). Varios mods pueden aportar un color ambiental (RGBA: en Mindustry el alfa es cuánta
 * oscuridad añade); el canal los combina con la política que declara, y el enlace con el motor aplica el resultado solo mientras dibuja.
 *
 *  - {@link Politica#MEDIA_PONDERADA}: media de los colores ponderada por el peso, en orden canónico (prioridad, id de fuente, id): el resultado es idéntico
 *    bit a bit con cualquier orden de carga.
 *  - {@link Politica#PRIORIDAD_ABSOLUTA}: manda el primero en orden canónico.
 * Un valor fuera de [0, 1] se recorta (con aviso una vez); uno no numérico, o con peso menor o igual que 0, se ignora (con aviso). Una contribución que no
 * ha fijado un color, o que lo ha quitado, no cuenta: sin contribuciones no hay ningún cambio ({@link #combinar} devuelve null) y no queda nada residual.
 */
final class VisualAmbiente{
    enum Politica{ MEDIA_PONDERADA, PRIORIDAD_ABSOLUTA }

    private static final VisualAmbiente GLOBAL = new VisualAmbiente(Politica.MEDIA_PONDERADA);

    /** El canal de color ambiental del juego. */
    static VisualAmbiente global(){
        return GLOBAL;
    }

    private static final class Aporte{
        final String id, fuente;
        final int prioridad;
        final float peso;
        float r, g, b, a;
        boolean puesto;

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
    private final float[] resultado = new float[4];

    /** @param politica la que declara este canal para combinar a sus contribuyentes */
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

    /** Registra una contribución (aún sin color: no cuenta hasta que se fije uno). @param id con el formato "fuente:nombre" */
    boolean registrar(String fuente, String id, int prioridad, float peso){
        if(fuente == null || fuente.isEmpty() || id == null || id.indexOf(':') <= 0 || id.indexOf(':') >= id.length() - 1 || id.indexOf(' ') >= 0){
            Log.err("[MundoInfinito] Visual: contribución de ambiente rechazada (id \"fuente:nombre\" obligatorio): @", id);
            return false;
        }
        synchronized(cerrojo){
            if(aportes.containsKey(id)) return false;
            aportes.put(id, new Aporte(id, fuente, prioridad, peso));
            return true;
        }
    }

    /** Fija el color que aporta esta contribución (RGBA, 0 a 1). Se puede llamar cada fotograma. */
    boolean fijar(String id, float r, float g, float b, float a){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p == null) return false;
            p.r = r;
            p.g = g;
            p.b = b;
            p.a = a;
            p.puesto = true;
            return true;
        }
    }

    /** La contribución deja de aportar (sigue registrada). */
    void quitar(String id){
        synchronized(cerrojo){
            final Aporte p = aportes.get(id);
            if(p != null) p.puesto = false;
        }
    }

    private static float recorte(float v){
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Combina las contribuciones activas según la política del canal. Devuelve {r, g, b, a} (un arreglo que se reutiliza: vale hasta la siguiente llamada),
     * o null si nadie aporta (no hay que cambiar nada).
     */
    float[] combinar(){
        final List<Aporte> v = new ArrayList<Aporte>();
        synchronized(cerrojo){
            for(Aporte p : aportes.values()){
                if(!p.puesto) continue;
                final boolean finito = !Float.isNaN(p.r + p.g + p.b + p.a) && !Float.isInfinite(p.r + p.g + p.b + p.a);
                if(!finito){
                    avisoSinCerrojo("nan:" + p.id, "la contribución de ambiente '" + p.id + "' tiene un color no numérico y se ignora.");
                    continue;
                }
                if(!(p.peso > 0f) || Float.isInfinite(p.peso)){
                    avisoSinCerrojo("peso:" + p.id, "la contribución de ambiente '" + p.id + "' tiene un peso no válido (" + p.peso + ") y se ignora.");
                    continue;
                }
                if(p.r < 0f || p.r > 1f || p.g < 0f || p.g > 1f || p.b < 0f || p.b > 1f || p.a < 0f || p.a > 1f){
                    avisoSinCerrojo("rango:" + p.id, "la contribución de ambiente '" + p.id + "' tiene un color fuera de [0, 1] y se recorta.");
                }
                v.add(p);
            }
        }
        if(v.isEmpty()) return null;
        Collections.sort(v, CANONICO);   // el mismo orden siempre: la suma de decimales no es asociativa

        if(politica == Politica.PRIORIDAD_ABSOLUTA){
            final Aporte p = v.get(0);
            resultado[0] = recorte(p.r);
            resultado[1] = recorte(p.g);
            resultado[2] = recorte(p.b);
            resultado[3] = recorte(p.a);
            return resultado;
        }
        double r = 0.0, g = 0.0, b = 0.0, a = 0.0, pesos = 0.0;
        for(Aporte p : v){
            final double w = p.peso;
            r += w * recorte(p.r);
            g += w * recorte(p.g);
            b += w * recorte(p.b);
            a += w * recorte(p.a);
            pesos += w;
        }
        resultado[0] = (float)(r / pesos);
        resultado[1] = (float)(g / pesos);
        resultado[2] = (float)(b / pesos);
        resultado[3] = (float)(a / pesos);
        return resultado;
    }

    /** Aviso desde dentro del cerrojo (no se bloquea dos veces; el registro de avisos ya está protegido). */
    private void avisoSinCerrojo(String clave, String texto){
        if(avisos.add(clave)) Log.warn("[MundoInfinito] Visual: " + texto);
    }

    /** Cuántas contribuciones están aportando ahora. */
    int activas(){
        synchronized(cerrojo){
            int n = 0;
            for(Aporte p : aportes.values()) if(p.puesto) n++;
            return n;
        }
    }
}
