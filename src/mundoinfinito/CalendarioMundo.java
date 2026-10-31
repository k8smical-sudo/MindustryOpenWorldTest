package mundoinfinito;

import arc.func.Cons;
import arc.util.Log;
import arc.util.serialization.Jval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CALENDARIO Y EVENTOS del mundo (fase 11 del plan).
 *
 * - El tiempo del mundo es el tiempo de JUEGO: avanza con el delta del fotograma, y no avanza en pausa ni con el juego cerrado. Es lo que sale en la
 *   partida guardada, así que al reabrir el mundo el tiempo sigue exactamente donde estaba. Día y temporada se derivan de él.
 * - Un evento se PROGRAMA con un tiempo objetivo. Se dispara en el fotograma en que el tiempo alcanza ese objetivo, y se entrega con el tiempo al que
 *   correspondía (no al del fotograma). Se dispara UNA vez: se quita de la lista antes de ejecutarlo.
 * - Los eventos programados se guardan con el mundo (id y tiempo). La acción no se guarda: la registra cada mod al cargarse, por id. Si un evento
 *   pendiente no tiene acción registrada, se queda pendiente (no se pierde) y avisa una vez: al registrar la acción se disparará.
 * - Los suscriptores son opcionales: sin suscriptores para un evento, no se construye ningún objeto de evento para él.
 *
 * Lo que este calendario NO hace: no se dispara con el juego cerrado. El tiempo solo avanza jugando, para que la simulación siga siendo determinista.
 */
final class CalendarioMundo{
    /** Días por temporada y temporadas por año (el año entero son 28 días). */
    static final int DIAS_POR_TEMPORADA = 7;
    static final String[] TEMPORADAS = {"primavera", "verano", "otoño", "invierno"};

    /** Acciones registradas por los mods, por id. Son de la sesión: las vuelve a registrar cada mod al cargarse. */
    private static final Map<String, Cons<EventoTiempo>> ACCIONES = new HashMap<String, Cons<EventoTiempo>>();
    /** Suscriptores por id de evento. */
    private static final Map<String, List<Cons<EventoTiempo>>> SUSCRIPTORES = new HashMap<String, List<Cons<EventoTiempo>>>();
    private static final Set<String> AVISOS = new HashSet<String>();

    /** Un evento programado y pendiente. */
    static final class Programado{
        final String id;
        final float tiempo;

        Programado(String id, float tiempo){
            this.id = id;
            this.tiempo = tiempo;
        }
    }

    private float tiempo;
    /** Pendientes, ordenados por (tiempo, id). */
    private final List<Programado> pendientes = new ArrayList<Programado>();

    static int diaEn(float tiempo){
        return (int)Math.floor(tiempo / CicloDiaNoche.DIA_TICKS);
    }

    static String temporadaEn(float tiempo){
        return TEMPORADAS[Math.floorMod(diaEn(tiempo) / DIAS_POR_TEMPORADA, TEMPORADAS.length)];
    }

    static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    private static void aviso(String clave, String texto){
        if(AVISOS.add(clave)) Log.warn("[MundoInfinito] Tiempo: " + texto);
    }

    // ================================================================== sesión (lo registran los mods)

    /** Registra la acción de un evento. Un id ya registrado no se cambia. */
    static boolean registrarAccion(String id, Cons<EventoTiempo> accion){
        if(!idValido(id) || accion == null || ACCIONES.containsKey(id)) return false;
        ACCIONES.put(id, accion);
        return true;
    }

    /** Suscribe a los eventos de un id. Los suscriptores no cambian el resultado del evento: solo reciben una copia. */
    static boolean suscribir(String id, Cons<EventoTiempo> suscriptor){
        if(!idValido(id) || suscriptor == null) return false;
        List<Cons<EventoTiempo>> l = SUSCRIPTORES.get(id);
        if(l == null){
            l = new ArrayList<Cons<EventoTiempo>>();
            SUSCRIPTORES.put(id, l);
        }
        return l.add(suscriptor);
    }

    // ================================================================== el mundo

    float tiempo(){
        return tiempo;
    }

    int dia(){
        return diaEn(tiempo);
    }

    String temporada(){
        return temporadaEn(tiempo);
    }

    int pendientes(){
        return pendientes.size();
    }

    /** Programa un evento para el tiempo indicado. Un tiempo ya pasado no se acepta. */
    boolean programar(String id, float tiempoObjetivo){
        if(!idValido(id) || Float.isNaN(tiempoObjetivo) || Float.isInfinite(tiempoObjetivo) || tiempoObjetivo < tiempo) return false;
        int i = 0;
        while(i < pendientes.size()){
            final Programado p = pendientes.get(i);
            if(p.tiempo > tiempoObjetivo || (p.tiempo == tiempoObjetivo && p.id.compareTo(id) > 0)) break;
            i++;
        }
        pendientes.add(i, new Programado(id, tiempoObjetivo));
        return true;
    }

    /** Avanza el tiempo y dispara los eventos cuyo objetivo se ha alcanzado, en orden. */
    void avanzar(float delta){
        if(!(delta > 0f) || Float.isInfinite(delta)) return;
        tiempo += delta;
        disparar();
    }

    private void disparar(){
        while(!pendientes.isEmpty() && pendientes.get(0).tiempo <= tiempo){
            final Programado p = pendientes.get(0);
            final Cons<EventoTiempo> accion = ACCIONES.get(p.id);
            if(accion == null){
                // sin acción: el evento se queda pendiente y se mantiene el orden (los siguientes esperan)
                aviso("sinaccion:" + p.id, "el evento '" + p.id + "' está programado pero su acción no está registrada; se queda pendiente hasta que se registre.");
                return;
            }
            pendientes.remove(0);   // se quita ANTES de ejecutar: no se repite aunque la acción falle
            final EventoTiempo e = new EventoTiempo(p.id, p.tiempo);
            try{
                accion.get(e);
            }catch(Throwable t){
                aviso("fallo:" + p.id, "la acción del evento '" + p.id + "' lanzó una excepción (" + t + "); el evento ya se entregó y no se repite.");
            }
            final List<Cons<EventoTiempo>> subs = SUSCRIPTORES.get(p.id);
            if(subs != null){
                for(Cons<EventoTiempo> s : subs){
                    try{
                        s.get(e);
                    }catch(Throwable t){
                        aviso("subfallo:" + p.id + ":" + s.hashCode(), "un suscriptor de '" + p.id + "' lanzó una excepción (" + t + ").");
                    }
                }
            }
        }
    }

    // ================================================================== persistencia

    /** Lo que se guarda con el mundo: el tiempo y los eventos pendientes (sin acciones). */
    Jval aJson(){
        final Jval o = Jval.newObject();
        o.put("tiempo", Double.valueOf(tiempo));
        final Jval lista = Jval.newObject();
        for(int i = 0; i < pendientes.size(); i++){
            final Programado p = pendientes.get(i);
            final Jval e = Jval.newObject();
            e.put("id", p.id);
            e.put("tiempo", Double.valueOf(p.tiempo));
            lista.put(String.valueOf(i), e);
        }
        o.put("pendientes", lista);
        return o;
    }

    /** Lee el calendario guardado. Lo que no se entiende se descarta con aviso; un mundo sin calendario empieza en 0 sin eventos. */
    static CalendarioMundo desde(Jval j){
        final CalendarioMundo c = new CalendarioMundo();
        if(j == null || !j.isObject()) return c;
        try{
            if(j.has("tiempo") && j.get("tiempo").isNumber()){
                final double t = j.get("tiempo").asDouble();
                if(!Double.isNaN(t) && !Double.isInfinite(t) && t >= 0) c.tiempo = (float)t;
            }
            if(j.has("pendientes") && j.get("pendientes").isObject()){
                final Jval.JsonMap mp = j.get("pendientes").asObject();
                for(int i = 0; i < mp.size; i++){
                    final Jval e = mp.getValueAt(i);
                    if(!e.isObject()) continue;
                    final String id = e.getString("id", "");
                    if(!e.has("tiempo") || !e.get("tiempo").isNumber()) continue;
                    final double t = e.get("tiempo").asDouble();
                    if(!idValido(id) || Double.isNaN(t) || Double.isInfinite(t)){
                        aviso("guardado:" + i, "un evento pendiente guardado no es válido y se descarta.");
                        continue;
                    }
                    c.programar(id, (float)t);   // el orden se recompone; un tiempo anterior al guardado se acepta solo en la carga
                }
            }
        }catch(Throwable t){
            Log.err("[MundoInfinito] Tiempo: el calendario guardado no se pudo leer; se empieza sin eventos.", t);
            return new CalendarioMundo();
        }
        return c;
    }

    // ================================================================== reloj del juego

    /** Cada fotograma (Trigger.update). Avanza el tiempo del mundo jugando; en pausa o sin partida no hace nada. */
    static void tick(){
        if(mindustry.Vars.state == null || !mindustry.Vars.state.isGame() || mindustry.Vars.state.isPaused()) return;
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null || m.calendario == null) return;
        m.calendario.avanzar(arc.util.Time.delta);
    }
}
