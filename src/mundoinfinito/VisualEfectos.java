package mundoinfinito;

import arc.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * EFECTOS Y PARTÍCULAS (fase 10): registro de efectos con capa y duración, y un POOL de instancias de tamaño fijo (cero asignaciones al emitir).
 *
 *  - Emitir: {@link #emitir} coloca un efecto en una posición. Los opcionales se adelgazan con la densidad de la calidad (de forma determinista:
 *    un acumulador, nunca un azar), y si el pool está lleno solo entra uno de mejor prioridad que el peor vivo, al que desaloja.
 *  - Dibujar: {@link #dibujar} envejece las instancias, libera las que terminaron y dibuja el resto en su capa, por encima de los dibujos normales de
 *    esa capa. Si el coste declarado de los vivos excede el presupuesto de la calidad se omiten los opcionales (por prioridad, los más antiguos primero).
 *  - Aislamiento: un efecto cuyo dibujo falla se desactiva con aviso, sus instancias se liberan y el juego sigue.
 * Es solo visual: nada de aquí puede cambiar la simulación (el corpus lo comprueba mirando el código compilado). Se usa desde el hilo de render.
 */
final class VisualEfectos{
    /** Desplazamiento z sobre la capa: por encima de los dibujos normales (que llegan a 0.09). */
    static final float Z_EFECTO = 0.095f;
    private static final float PASO_Z = 0.00001f, MAX_Z_EXTRA = 0.004f;

    private static final VisualEfectos GLOBAL = new VisualEfectos(VisualCalidad.ALTA.efectosMax);

    static VisualEfectos global(){
        return GLOBAL;
    }

    private final Object cerrojo = new Object();
    private final Map<String, VisualEfectoDef> defs = new HashMap<String, VisualEfectoDef>();
    private final Set<String> desactivados = new HashSet<String>();
    private final Map<String, Double> acumulador = new HashMap<String, Double>();
    private final Set<String> avisos = new HashSet<String>();

    private final int capacidad;
    private final VisualEfectoInst[] todas;
    private final VisualEfectoInst[] vivos, libres, orden, porPresupuesto;
    private int nVivos, nLibres;
    private long seq;
    private boolean dibujando;
    private VisualCalidad calidad = VisualCalidad.ALTA;
    private int ultimosDibujados, ultimosOmitidos, ultimoCoste;

    /** @param capacidad instancias del pool: el máximo de efectos vivos que cualquier calidad puede pedir */
    VisualEfectos(int capacidad){
        this.capacidad = capacidad;
        todas = new VisualEfectoInst[capacidad];
        vivos = new VisualEfectoInst[capacidad];
        libres = new VisualEfectoInst[capacidad];
        orden = new VisualEfectoInst[capacidad];
        porPresupuesto = new VisualEfectoInst[capacidad];
        for(int i = 0; i < capacidad; i++){
            todas[i] = new VisualEfectoInst();
            libres[i] = todas[i];
        }
        nLibres = capacidad;
    }

    // ================================================================== registro

    /** @return false si no es válido (id "fuente:nombre", capa, duración > 0, coste >= 0 y función) o el id ya existe. */
    boolean registrarEfecto(VisualEfectoDef d){
        final boolean idOk = d != null && d.id != null && d.id.indexOf(':') > 0 && d.id.indexOf(':') < d.id.length() - 1 && d.id.indexOf(' ') < 0;
        if(!idOk || d.fuente == null || d.fuente.isEmpty() || d.capa == null || d.dibujar == null || !(d.duracion > 0f) || Float.isInfinite(d.duracion) || d.coste < 0){
            Log.err("[MundoInfinito] Visual: efecto rechazado (id \"fuente:nombre\", capa, duración > 0, coste >= 0 y función son obligatorios): @", d == null ? "null" : d.id);
            return false;
        }
        synchronized(cerrojo){
            if(defs.containsKey(d.id)) return false;
            defs.put(d.id, d);
            return true;
        }
    }

    boolean activo(String id){
        synchronized(cerrojo){
            return defs.containsKey(id) && !desactivados.contains(id);
        }
    }

    /** Calidad con la que se emite y se dibuja a partir de ahora (la efectiva del registro visual). */
    void configurar(VisualCalidad q){
        synchronized(cerrojo){
            calidad = q == null ? VisualCalidad.ALTA : q;
        }
    }

    // ================================================================== emitir

    /**
     * Dispara un efecto. Devuelve false si se descartó: efecto desconocido o desactivado, adelgazado por la densidad de la calidad, o sin sitio en el pool.
     * @param rot rotación en grados (la interpreta el dibujo)
     * @param datos lo que el dibujo necesite (puede ser null)
     */
    boolean emitir(String id, float x, float y, float rot, float r, float g, float b, float a, Object datos){
        synchronized(cerrojo){
            final VisualEfectoDef d = defs.get(id);
            if(d == null || desactivados.contains(id)) return false;

            // densidad: de cada emisión opcional se acepta la fracción que dice la calidad, con un acumulador (determinista, sin azar)
            if(!d.esencial){
                final Double previo = acumulador.get(id);
                double acum = (previo == null ? 0.0 : previo.doubleValue()) + calidad.densidadEfectos;
                if(acum < 1.0 - 1e-9){
                    acumulador.put(id, acum);
                    return false;
                }
                acumulador.put(id, acum - 1.0);
            }

            // sitio: el pool tiene un tope por calidad; si está lleno, solo entra uno de MEJOR prioridad que el peor vivo, al que desaloja
            if(nVivos >= calidad.efectosMax || nLibres == 0){
                int peor = -1;
                for(int i = 0; i < nVivos; i++){
                    final VisualEfectoInst v = vivos[i];
                    if(peor < 0 || v.def.prioridad > vivos[peor].def.prioridad || (v.def.prioridad == vivos[peor].def.prioridad && v.seq < vivos[peor].seq)) peor = i;
                }
                if(dibujando || peor < 0 || vivos[peor].def.prioridad <= d.prioridad) return false;
                liberar(peor);
            }
            if(nLibres == 0) return false;

            final VisualEfectoInst e = libres[--nLibres];
            e.def = d;
            e.x = x;
            e.y = y;
            e.rot = rot;
            e.r = r;
            e.g = g;
            e.b = b;
            e.a = a;
            e.tiempo = 0f;
            e.datos = datos;
            e.seq = ++seq;
            e.dibujar = false;
            vivos[nVivos++] = e;
            return true;
        }
    }

    /** Devuelve la instancia vivo[i] al pool (sustituyéndola por la última). Con el cerrojo. */
    private void liberar(int i){
        final VisualEfectoInst e = vivos[i];
        vivos[i] = vivos[--nVivos];
        vivos[nVivos] = null;
        e.limpiar();
        libres[nLibres++] = e;
    }

    // ================================================================== dibujar

    private static boolean antesPorPrioridad(VisualEfectoInst a, VisualEfectoInst b){
        if(a.def.prioridad != b.def.prioridad) return a.def.prioridad < b.def.prioridad;
        return a.seq < b.seq;
    }

    private static boolean antesPorCapa(VisualEfectoInst a, VisualEfectoInst b){
        final int c = Float.compare(a.def.capa.z, b.def.capa.z);
        if(c != 0) return c < 0;
        if(a.def.prioridad != b.def.prioridad) return a.def.prioridad < b.def.prioridad;
        final int f = a.def.fuente.compareTo(b.def.fuente);
        if(f != 0) return f < 0;
        final int i = a.def.id.compareTo(b.def.id);
        if(i != 0) return i < 0;
        return a.seq < b.seq;
    }

    /** Ordenación por inserción sin asignaciones (los datos están casi ordenados de un fotograma al siguiente). */
    private static void ordenar(VisualEfectoInst[] v, int n, boolean porCapa){
        for(int i = 1; i < n; i++){
            final VisualEfectoInst x = v[i];
            int j = i - 1;
            while(j >= 0 && (porCapa ? antesPorCapa(x, v[j]) : antesPorPrioridad(x, v[j]))){
                v[j + 1] = v[j];
                j--;
            }
            v[j + 1] = x;
        }
    }

    /**
     * Envejece y dibuja los efectos vivos.
     * @param delta ticks transcurridos desde el fotograma anterior (0 con el juego en pausa: los efectos se quedan quietos)
     */
    void dibujar(VisualSalida salida, float delta){
        final VisualCalidad q;
        final int n;
        synchronized(cerrojo){
            q = calidad;
            // 1) envejecer; se liberan los que terminaron y los de un efecto desactivado
            for(int i = nVivos - 1; i >= 0; i--){
                final VisualEfectoInst e = vivos[i];
                e.tiempo += delta;
                if(e.tiempo >= e.def.duracion || desactivados.contains(e.def.id)) liberar(i);
            }
            n = nVivos;

            // 2) presupuesto: por prioridad (los más antiguos primero) mientras quepan; los esenciales siempre se dibujan
            System.arraycopy(vivos, 0, porPresupuesto, 0, n);
            ordenar(porPresupuesto, n, false);
            int coste = 0, dibujados = 0, omitidos = 0;
            for(int i = 0; i < n; i++){
                final VisualEfectoInst e = porPresupuesto[i];
                if(e.def.esencial || coste + e.def.coste <= q.costeEfectos){
                    coste += e.def.coste;
                    e.dibujar = true;
                    dibujados++;
                }else{
                    e.dibujar = false;
                    omitidos++;
                }
            }
            ultimosDibujados = dibujados;
            ultimosOmitidos = omitidos;
            ultimoCoste = coste;

            // 3) orden de dibujo: capa, prioridad, fuente, id, emisión
            System.arraycopy(vivos, 0, orden, 0, n);
            ordenar(orden, n, true);
            dibujando = true;
        }

        try{
            VisualCapa capaActual = null;
            int enCapa = 0;
            for(int i = 0; i < n; i++){
                final VisualEfectoInst e = orden[i];
                if(!e.dibujar || e.def == null) continue;
                if(e.def.capa != capaActual){
                    capaActual = e.def.capa;
                    enCapa = 0;
                }
                final float z = e.def.capa.z + Z_EFECTO + Math.min(MAX_Z_EXTRA, PASO_Z * enCapa++);
                salida.z(z);
                try{
                    e.def.dibujar.get(e);
                }catch(Throwable t){
                    desactivar(e.def.id, t);
                }finally{
                    salida.reiniciar();
                }
            }
        }finally{
            synchronized(cerrojo){
                dibujando = false;
            }
        }
    }

    private void desactivar(String id, Throwable t){
        final boolean nuevo;
        synchronized(cerrojo){
            desactivados.add(id);
            nuevo = avisos.add(id);
        }
        if(nuevo) Log.warn("[MundoInfinito] Visual: el efecto '" + id + "' falló al dibujar (" + t + ") y se desactiva para esta sesión; el juego continúa sin él.");
    }

    // ================================================================== diagnóstico

    int vivos(){
        synchronized(cerrojo){
            return nVivos;
        }
    }

    int libres(){
        synchronized(cerrojo){
            return nLibres;
        }
    }

    /** Instancias que existen en total: es siempre la capacidad del pool; si creciera, algo estaría asignando al emitir. */
    int creadas(){
        return todas.length;
    }

    int capacidad(){
        return capacidad;
    }

    int ultimosDibujados(){
        return ultimosDibujados;
    }

    int ultimosOmitidos(){
        return ultimosOmitidos;
    }

    int ultimoCoste(){
        return ultimoCoste;
    }

    String resumen(){
        return "efectos: vivos=" + vivos() + "/" + calidad.efectosMax + " dibujados=" + ultimosDibujados + " omitidos=" + ultimosOmitidos + " coste=" + ultimoCoste + "/" + calidad.costeEfectos;
    }
}
