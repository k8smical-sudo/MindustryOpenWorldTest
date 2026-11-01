package mundoinfinito;

import arc.util.Log;
import arc.util.serialization.Jval;

import java.util.Map;
import java.util.TreeMap;

/**
 * ESTADO DE PLANETAS DE UN MUNDO (plan v2, módulos M1 y M2): el nombre visible de cada planeta y su posición en la órbita.
 *
 *  - El TAG técnico (el id: "serpulo", "erekir", "minmod:nuevo") lo pone el código y no cambia. El nombre visible lo cambia el jugador y se
 *    guarda con el mundo. Sin nombre elegido se muestra el nombre por defecto.
 *  - La posición se guarda como FASE ACUMULADA (fracción de vuelta, en [0, 1)). Cada tick suma delta / órbita. Así, si el periodo cambia (un
 *    addon, o un ajuste nuevo), la posición sigue siendo continua y no salta. Calcular la posición como tiempo / órbita sí daría un salto.
 *  - Avanza solo jugando y sin pausa, igual que el calendario del mundo. Sin mundo abierto no hay estado: fase 0 y nombre por defecto.
 *  - Los planetas que un mod ya no tiene cargado conservan su nombre y su fase al guardar, para no perder datos.
 *
 * Se guarda dentro de meta.json del mundo, bajo la clave "planetas".
 */
final class PlanetasMundo{
    /** Longitud máxima de un nombre visible, en caracteres. */
    static final int NOMBRE_MAX = 32;

    /** Nombre visible por tag. Solo guarda los que el jugador ha cambiado: el resto usa el nombre por defecto. */
    private final TreeMap<String, String> nombres = new TreeMap<String, String>();
    /** Fase orbital por tag, en vueltas [0, 1). Se guarda en un array de un elemento para sumar sin crear objetos en cada tick. */
    private final TreeMap<String, double[]> fases = new TreeMap<String, double[]>();

    // ================================================================== nombres

    /** Nombre por defecto de un planeta: el de Serpulo y Erekir; un planeta de un addon se muestra con su tag. */
    static String nombrePorDefecto(String planeta){
        if(ApiPlaneta.SERPULO.equals(planeta)) return "Serpulo";
        if(ApiPlaneta.EREKIR.equals(planeta)) return "Erekir";
        return planeta;
    }

    /** Nombre visible de un planeta en este mundo: el elegido por el jugador, o el por defecto. */
    String nombre(String planeta){
        final String n = nombres.get(planeta);
        return n != null ? n : nombrePorDefecto(planeta);
    }

    /**
     * Cambia el nombre visible. Un nombre vacío (o que solo tenga caracteres no válidos) vuelve al nombre por defecto.
     * @return false si el planeta no está registrado. El tag nunca cambia.
     */
    boolean renombrar(String planeta, String nuevo){
        if(!ApiPlaneta.existe(planeta)) return false;
        final String limpio = limpiarNombre(nuevo);
        if(limpio == null) nombres.remove(planeta);
        else nombres.put(planeta, limpio);
        return true;
    }

    /**
     * Deja un nombre válido para mostrarlo: sin caracteres de control, sin corchetes (Mindustry los usa para los colores: "[accent]"),
     * con los espacios normalizados y como mucho NOMBRE_MAX caracteres. Null si no queda nada.
     */
    static String limpiarNombre(String s){
        if(s == null) return null;
        final StringBuilder sb = new StringBuilder(s.length());
        for(int i = 0; i < s.length(); i++){
            final char c = s.charAt(i);
            if(Character.isISOControl(c) || c == '[' || c == ']') continue;
            sb.append(c);
        }
        String t = sb.toString().trim().replaceAll("\\s+", " ");
        if(t.length() > NOMBRE_MAX) t = t.substring(0, NOMBRE_MAX).trim();
        return t.isEmpty() ? null : t;
    }

    // ================================================================== órbita

    /** Fase orbital de un planeta en este mundo, en vueltas [0, 1). 0 = el punto de partida de la órbita. */
    double fase(String planeta){
        final double[] f = fases.get(planeta);
        return f == null ? 0.0 : f[0];
    }

    /** Ángulo del planeta alrededor de la estrella, en radianes (0 = el punto de partida, sentido antihorario). */
    double anguloRad(String planeta){
        return fase(planeta) * 2.0 * Math.PI;
    }

    /**
     * Avanza la órbita de todos los planetas registrados. El avance es delta / periodo, con el periodo de este mundo (perfil).
     * @param delta ticks de juego transcurridos (Time.delta)
     */
    void avanzar(float delta, PerfilMundo perfil){
        if(!(delta > 0f) || Float.isInfinite(delta)) return;
        final java.util.List<String> ids = ApiPlaneta.ids();
        for(int i = 0; i < ids.size(); i++){
            final String planeta = ids.get(i);
            final double orbita = ApiPlaneta.orbitaEn(perfil, planeta);
            if(!(orbita > 0.0)) continue;
            double[] f = fases.get(planeta);
            if(f == null){
                f = new double[1];
                fases.put(planeta, f);
            }
            final double v = f[0] + delta / orbita;
            f[0] = v - Math.floor(v);
        }
    }

    /** Cada fotograma (Trigger.update). Solo avanza jugando y sin pausa, como el calendario del mundo. */
    static void tick(){
        if(mindustry.Vars.state == null || !mindustry.Vars.state.isGame() || mindustry.Vars.state.isPaused()) return;
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null || m.planetas == null) return;
        m.planetas.avanzar(arc.util.Time.delta, m.perfil);
    }

    // ================================================================== persistencia

    /** Lo que se guarda con el mundo: {"nombres": {tag: nombre}, "fases": {tag: vueltas}}. */
    Jval aJson(){
        final Jval o = Jval.newObject();
        final Jval n = Jval.newObject();
        for(Map.Entry<String, String> e : nombres.entrySet()) n.put(e.getKey(), e.getValue());
        o.put("nombres", n);
        final Jval f = Jval.newObject();
        for(Map.Entry<String, double[]> e : fases.entrySet()) f.put(e.getKey(), Double.valueOf(e.getValue()[0]));
        o.put("fases", f);
        return o;
    }

    /** Lee el estado guardado. Un mundo sin estado (anterior a los planetas) empieza sin nombres y con fase 0. Nunca lanza. */
    static PlanetasMundo desde(Jval j){
        final PlanetasMundo p = new PlanetasMundo();
        if(j == null || !j.isObject()) return p;
        try{
            if(j.has("nombres") && j.get("nombres").isObject()){
                final Jval.JsonMap mp = j.get("nombres").asObject();
                for(int i = 0; i < mp.size; i++){
                    final Jval v = mp.getValueAt(i);
                    final String limpio = v.isString() ? limpiarNombre(v.asString()) : null;
                    // se conserva aunque el planeta no esté registrado ahora (mod ausente)
                    if(limpio != null) p.nombres.put(mp.getKeyAt(i), limpio);
                }
            }
            if(j.has("fases") && j.get("fases").isObject()){
                final Jval.JsonMap mp = j.get("fases").asObject();
                for(int i = 0; i < mp.size; i++){
                    final Jval v = mp.getValueAt(i);
                    if(!v.isNumber()) continue;
                    final double f = v.asDouble();
                    if(Double.isNaN(f) || Double.isInfinite(f)) continue;
                    p.fases.put(mp.getKeyAt(i), new double[]{f - Math.floor(f)});
                }
            }
        }catch(Throwable t){
            Log.err("[MundoInfinito] Planetas: el estado guardado no se pudo leer; se usan los valores por defecto.", t);
            return new PlanetasMundo();
        }
        return p;
    }
}
