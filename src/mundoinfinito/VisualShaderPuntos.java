package mundoinfinito;

import arc.func.Boolp;
import arc.func.Prov;
import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PUNTOS DE SUSTITUCIÓN DE SHADERS DEL JUEGO (API visual): dejan que un mod cambie los shaders de superficie de Mindustry (agua, barro, brea, escoria,
 * arkycita, criofluido y espacio) sin pisarse con otros mods, y elegir por calidad cuál usar.
 *
 * Cómo funcionan los de Mindustry: cada capa de caché de suelo líquido dibuja el terreno en un búfer y lo vuelca a pantalla completa con su shader
 * ({@code CacheLayer.ShaderLayer.shader}, un campo público). Aquí se cambia ESE campo durante el dibujo de cada fotograma y se restaura al terminar: el
 * juego nunca se queda con un shader cambiado, y sin variantes registradas no se toca nada.
 *
 * Reglas para que varios mods convivan:
 *  - VARIANTE: un shader alternativo para un punto, válido para un rango de calidades. Si dos variantes aplicables compiten por el mismo punto y calidad,
 *    gana la primera en orden canónico (prioridad, id de fuente, id) y la otra no se aplica, con aviso: nunca un comportamiento indefinido. Variantes de
 *    calidades distintas no compiten (una puede dar la versión barata para baja y otra la bonita para alta).
 *  - LÍMITE DE ZOOM: ancho de cámara a partir del cual no se anima el shader de superficie (se dibuja el suelo plano). Varios límites se combinan con la
 *    media ponderada por peso, en orden canónico: idéntico con cualquier orden de carga.
 *  - Un shader que no se puede crear se descarta con aviso y se queda el original. Una variante o un límite pueden llevar una condición (se evalúa cada fotograma).
 * Es solo visual (no lo puede referenciar la simulación) y se usa desde el hilo de render.
 */
final class VisualShaderPuntos{
    /** Los shaders de superficie del juego que se pueden sustituir. */
    enum Punto{ AGUA, BARRO, BREA, ESCORIA, ARKYCITA, CRIOFLUIDO, ESPACIO }

    /** Una capa del juego con shader de superficie. La implementación real envuelve {@code CacheLayer.ShaderLayer}; las pruebas usan una falsa. */
    interface Capa{
        /** El shader que tenía la capa cuando se enlazó. */
        Object original();

        /** Pone un shader en la capa. */
        void poner(Object shader);
    }

    private static final VisualShaderPuntos GLOBAL = new VisualShaderPuntos();

    static VisualShaderPuntos global(){
        return GLOBAL;
    }

    private static final class Variante{
        final String id, fuente;
        final Punto punto;
        final int prioridad;
        final VisualCalidad minima, maxima;
        final Prov<Object> fabrica;
        final Boolp activa;
        Object shader;
        boolean fallo;

        Variante(String id, String fuente, Punto punto, int prioridad, VisualCalidad minima, VisualCalidad maxima, Prov<Object> fabrica, Boolp activa){
            this.id = id;
            this.fuente = fuente;
            this.punto = punto;
            this.prioridad = prioridad;
            this.minima = minima == null ? VisualCalidad.BAJA : minima;
            this.maxima = maxima == null ? VisualCalidad.ALTA : maxima;
            this.fabrica = fabrica;
            this.activa = activa;
        }

        boolean valeEn(VisualCalidad q){
            return q.ordinal() >= minima.ordinal() && q.ordinal() <= maxima.ordinal();
        }
    }

    private static final class Limite{
        final String id, fuente;
        final int prioridad;
        final float peso, ancho;
        final Boolp activa;

        Limite(String id, String fuente, int prioridad, float peso, float ancho, Boolp activa){
            this.id = id;
            this.fuente = fuente;
            this.prioridad = prioridad;
            this.peso = peso;
            this.ancho = ancho;
            this.activa = activa;
        }
    }

    private static final Comparator<Variante> ORDEN_VARIANTE = new Comparator<Variante>(){
        @Override
        public int compare(Variante a, Variante b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private static final Comparator<Limite> ORDEN_LIMITE = new Comparator<Limite>(){
        @Override
        public int compare(Limite a, Limite b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private final Object cerrojo = new Object();
    private final List<Variante> variantes = new ArrayList<Variante>();
    private final List<Limite> limites = new ArrayList<Limite>();
    private final Set<String> avisos = new HashSet<String>();
    private final List<Punto> puestos = new ArrayList<Punto>();
    private int conflictos;

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    private void aviso(String clave, String texto){
        if(avisos.add(clave)) Log.warn("[MundoInfinito] Visual: " + texto);
    }

    // ================================================================== registro

    /**
     * @param minima calidad más baja en la que vale (null = baja)
     * @param maxima calidad más alta en la que vale (null = alta)
     * @param fabrica crea el shader, la primera vez que se usa y en el hilo de render
     * @param activa condición por fotograma (null = siempre)
     */
    boolean registrarVariante(String fuente, String id, Punto punto, int prioridad, VisualCalidad minima, VisualCalidad maxima, Prov<Object> fabrica, Boolp activa){
        if(!idValido(id) || fuente == null || fuente.isEmpty() || punto == null || fabrica == null
            || (minima != null && maxima != null && minima.ordinal() > maxima.ordinal())){
            Log.err("[MundoInfinito] Visual: variante de shader rechazada (id \"fuente:nombre\", punto, fábrica y un rango de calidad válido son obligatorios): @", id);
            return false;
        }
        synchronized(cerrojo){
            for(Variante v : variantes) if(v.id.equals(id)) return false;
            variantes.add(new Variante(id, fuente, punto, prioridad, minima, maxima, fabrica, activa));
            Collections.sort(variantes, ORDEN_VARIANTE);
            return true;
        }
    }

    /**
     * Límite de zoom: cuando el ancho de la cámara (en unidades de mundo) supera el límite combinado, no se animan los shaders de superficie.
     * @param peso peso en la media de varios límites (debe ser mayor que 0)
     */
    boolean registrarLimiteZoom(String fuente, String id, int prioridad, float peso, float anchoCamaraMax, Boolp activa){
        if(!idValido(id) || fuente == null || fuente.isEmpty() || !(peso > 0f) || Float.isInfinite(peso) || !(anchoCamaraMax > 0f) || Float.isInfinite(anchoCamaraMax)){
            Log.err("[MundoInfinito] Visual: límite de zoom rechazado (id \"fuente:nombre\", peso > 0 y ancho > 0 son obligatorios): @", id);
            return false;
        }
        synchronized(cerrojo){
            for(Limite l : limites) if(l.id.equals(id)) return false;
            limites.add(new Limite(id, fuente, prioridad, peso, anchoCamaraMax, activa));
            Collections.sort(limites, ORDEN_LIMITE);
            return true;
        }
    }

    // ================================================================== por fotograma (hilo de render)

    private boolean condicion(Boolp b, String id){
        if(b == null) return true;
        try{
            return b.get();
        }catch(Throwable t){
            aviso("cond:" + id, "la condición de '" + id + "' lanzó una excepción (" + t + ") y se ignora esa contribución.");
            return false;
        }
    }

    /**
     * Pone, en cada capa, el shader de la variante ganadora para la calidad {@code q}. Sin variantes aplicables no toca nada. Hay que llamar a
     * {@link #restaurar} al terminar el fotograma.
     */
    void aplicar(Map<Punto, Capa> capas, VisualCalidad q){
        synchronized(cerrojo){
            puestos.clear();
            conflictos = 0;
            if(variantes.isEmpty()) return;
            for(Map.Entry<Punto, Capa> e : capas.entrySet()){
                final Punto p = e.getKey();
                Variante ganadora = null;
                for(Variante v : variantes){   // ya en orden canónico
                    if(v.punto != p || v.fallo || !v.valeEn(q) || !condicion(v.activa, v.id)) continue;
                    if(ganadora == null){
                        ganadora = v;
                    }else{
                        conflictos++;
                        aviso("conflicto:" + v.id + ":" + p, "la variante de shader '" + v.id + "' compite por el punto " + p + " con '" + ganadora.id
                            + "' (gana el primero en orden canónico); '" + v.id + "' no se aplica en calidad " + q.nombre + ".");
                    }
                }
                if(ganadora == null) continue;
                if(ganadora.shader == null){
                    try{
                        ganadora.shader = ganadora.fabrica.get();
                        if(ganadora.shader == null) throw new IllegalStateException("la fábrica devolvió null");
                    }catch(Throwable t){
                        ganadora.fallo = true;
                        aviso("fallo:" + ganadora.id, "la variante de shader '" + ganadora.id + "' no se pudo crear (" + t + "); se descarta y se queda el shader original.");
                        continue;
                    }
                }
                e.getValue().poner(ganadora.shader);
                puestos.add(p);
            }
        }
    }

    /** Devuelve a cada capa su shader original. */
    void restaurar(Map<Punto, Capa> capas){
        synchronized(cerrojo){
            for(Punto p : puestos){
                final Capa c = capas.get(p);
                if(c != null) c.poner(c.original());
            }
            puestos.clear();
        }
    }

    /** Límite de zoom combinado (media ponderada en orden canónico), o NaN si no hay ninguno activo. */
    float limiteZoom(){
        final List<Limite> v = new ArrayList<Limite>();
        synchronized(cerrojo){
            for(Limite l : limites) if(condicion(l.activa, l.id)) v.add(l);
        }
        if(v.isEmpty()) return Float.NaN;
        double suma = 0.0, pesos = 0.0;
        for(Limite l : v){
            suma += (double)l.peso * l.ancho;
            pesos += l.peso;
        }
        return (float)(suma / pesos);
    }

    /** ¿Hay que dibujar el suelo líquido plano con esta cámara? */
    boolean omitirSuperficies(float anchoCamara){
        final float l = limiteZoom();
        return !Float.isNaN(l) && anchoCamara > l;
    }

    int conflictos(){
        synchronized(cerrojo){
            return conflictos;
        }
    }

    int variantes(){
        synchronized(cerrojo){
            return variantes.size();
        }
    }
}
