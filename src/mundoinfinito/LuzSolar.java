package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Núcleo de la luz solar: posiciones de los cuerpos del sistema solar (la estrella en el origen), eclipse por defecto y modificadores.
 * Estimaciones por defecto: Serpulo a radio 3,0 con cuerpo de 0,12; Erekir a radio 1,8 con cuerpo de 0,10. Los valores se revisan en P1.
 */
final class LuzSolar{
    static final float RADIO_ESTRELLA = 0.4f;
    static final float FACTOR_MAX = 2f;

    private static final class Mod{
        final String fuente, id;
        final int prioridad;
        final ApiLuz.Modificador fn;
        /** Un modificador que falla se desactiva (plan v5, P3): no vuelve a llamarse hasta quitarlo o reiniciar el juego. */
        boolean activo = true;

        Mod(String fuente, String id, int prioridad, ApiLuz.Modificador fn){
            this.fuente = fuente;
            this.id = id;
            this.prioridad = prioridad;
            this.fn = fn;
        }
    }

    private static final class Cuerpo{
        final String id;
        final float radioOrbita, radioCuerpo;
        final ApiLuz.Fase fase;

        Cuerpo(String id, float radioOrbita, float radioCuerpo, ApiLuz.Fase fase){
            this.id = id;
            this.radioOrbita = radioOrbita;
            this.radioCuerpo = radioCuerpo;
            this.fase = fase;
        }
    }

    private static boolean avisoTope;
    private static final Map<String, Cuerpo> CUERPOS = new HashMap<String, Cuerpo>();
    private static final List<Mod> MODS = new ArrayList<Mod>();
    private static final Comparator<Mod> ORDEN = new Comparator<Mod>(){
        @Override
        public int compare(Mod a, Mod b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    static {
        CUERPOS.put(ApiPlaneta.SERPULO, new Cuerpo(ApiPlaneta.SERPULO, 3.0f, 0.12f, new ApiLuz.Fase(){
            @Override public double fase(){ return ApiPlaneta.fase(ApiPlaneta.SERPULO); }
        }));
        CUERPOS.put(ApiPlaneta.EREKIR, new Cuerpo(ApiPlaneta.EREKIR, 1.8f, 0.10f, new ApiLuz.Fase(){
            @Override public double fase(){ return ApiPlaneta.fase(ApiPlaneta.EREKIR); }
        }));
    }

    private LuzSolar(){}

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    static synchronized boolean registrarCuerpo(String id, float radioOrbita, float radioCuerpo, ApiLuz.Fase fase){
        if(!idValido(id) && !ApiPlaneta.SERPULO.equals(id) && !ApiPlaneta.EREKIR.equals(id)) return false;
        if(fase == null || !(radioOrbita > 0f) || !(radioCuerpo > 0f) || Float.isInfinite(radioOrbita) || Float.isInfinite(radioCuerpo)) return false;
        if(CUERPOS.containsKey(id)) return false;
        CUERPOS.put(id, new Cuerpo(id, radioOrbita, radioCuerpo, fase));
        return true;
    }

    static synchronized boolean registrarModificador(String fuente, String id, int prioridad, ApiLuz.Modificador fn){
        if(fuente == null || fuente.isEmpty() || !idValido(id) || fn == null) return false;
        for(Mod m : MODS) if(m.id.equals(id)) return false;
        MODS.add(new Mod(fuente, id, prioridad, fn));
        Collections.sort(MODS, ORDEN);
        return true;
    }

    static synchronized boolean quitarModificador(String id){
        for(int i = 0; i < MODS.size(); i++){
            if(MODS.get(i).id.equals(id)){
                MODS.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Posición de un cuerpo en el plano del sistema solar (la estrella en el origen). */
    static float[] posicion(Cuerpo c){
        final double a = 2.0 * Math.PI * c.fase.fase();
        return new float[]{(float)(c.radioOrbita * Math.cos(a)), (float)(c.radioOrbita * Math.sin(a))};
    }

    /**
     * Fracción del disco de la estrella tapada por un cuerpo, vista desde el planeta. Cero si el cuerpo no está entre el planeta y la estrella.
     * Aproximación lineal del solapamiento de dos discos, suficiente para un efecto visual.
     */
    static float cobertura(float[] planeta, float[] cuerpo, float radioCuerpo){
        final float dx = -planeta[0], dy = -planeta[1];
        final float len2 = dx * dx + dy * dy;
        if(len2 <= 1e-9f) return 0f;
        final float t = ((cuerpo[0] - planeta[0]) * dx + (cuerpo[1] - planeta[1]) * dy) / len2;
        if(t <= 0f || t >= 1f) return 0f;
        final float px = planeta[0] + t * dx, py = planeta[1] + t * dy;
        final float d = (float)Math.sqrt((cuerpo[0] - px) * (cuerpo[0] - px) + (cuerpo[1] - py) * (cuerpo[1] - py));
        final float r = RADIO_ESTRELLA;
        if(d >= radioCuerpo + r) return 0f;
        if(d <= radioCuerpo - r) return 1f;
        return clamp01((radioCuerpo + r - d) / (2f * r));
    }

    /** Factor por eclipse por defecto: producto de (1 - cobertura) de todos los demás cuerpos. */
    static float eclipse(String planeta){
        final Cuerpo p = CUERPOS.get(planeta);
        if(p == null) return 1f;
        final float[] pp = posicion(p);
        float f = 1f;
        for(Cuerpo b : CUERPOS.values()){
            if(b == p) continue;
            f *= 1f - cobertura(pp, posicion(b), b.radioCuerpo);
        }
        return clamp01(f);
    }

    static float factorPlaneta(String planeta){
        final Cuerpo p = CUERPOS.get(planeta);
        final double fase = p == null ? 0.0 : p.fase.fase();
        float f = eclipse(planeta);
        synchronized(LuzSolar.class){
            for(Mod m : MODS){
                if(!m.activo) continue;
                try{
                    final float v = m.fn.factor(planeta, fase);
                    if(Float.isNaN(v) || Float.isInfinite(v)) throw new IllegalStateException("valor no finito: " + v);
                    f *= v;
                }catch(Throwable t){
                    // Un fallo desactiva el modificador y se avisa una sola vez; el resto de modificadores y el eclipse siguen.
                    m.activo = false;
                    Log.err("[MundoInfinito] Luz: el modificador '@' (fuente '@') falló (@) y queda desactivado hasta quitarlo o reiniciar el juego.", m.id, m.fuente, t.toString());
                }
            }
        }
        if(f > FACTOR_MAX){
            if(!avisoTope){
                avisoTope = true;
                Log.warn("[MundoInfinito] Luz: el factor de un planeta supera el tope (@) y se limita. Revisa los modificadores de luz.", FACTOR_MAX);
            }
            f = FACTOR_MAX;
        }
        return f < 0f ? 0f : f;
    }

    private static float clamp01(float v){
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
