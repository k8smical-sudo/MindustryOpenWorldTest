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
import java.util.TreeMap;

/**
 * REGISTRO VISUAL (fase 9): capas de dibujo con orden declarado, shaders con propiedad de uniformes, cadena de post-procesado con coste declarado,
 * calidad y degradación visual sin error.
 *
 * Reglas de la capa visual que cumple:
 *  1. Sin efecto sobre la simulación: nada de aquí se lee para decidir algo del mundo. Esta clase y todas las {@code Visual*} no las puede referenciar
 *     ninguna clase de simulación o de generación (lo comprueba el corpus mirando el código compilado).
 *  2. Coste declarado: se suman las llamadas de dibujo, las pasadas y los bytes de textura; si se exceden se omite lo opcional, de atrás hacia delante.
 *  3. Calidad configurable: baja, media y alta. Una medición automática de fotogramas puede bajarla (no subirla por encima de lo elegido).
 *  4. Todo el trabajo de GPU va en el hilo de render: las operaciones de fotograma ({@link #dibujarFotograma}, {@link #ejecutarPasadas}) solo se llaman desde
 *     él; el registro (registrar, desactivar) se puede hacer desde cualquier hilo.
 *  5. Orden declarado: (capa, prioridad, id de fuente, id). Nunca el orden de carga.
 *  6. Degradación sin error: un dibujo, un shader o una pasada que fallan se desactivan para la sesión con un aviso, y el juego sigue.
 */
final class VisualRegistro{
    /** Separación z entre dos dibujos de la misma capa: mantiene su orden aunque el motor ordene por z. */
    private static final float PASO_Z = 0.001f, MAX_Z = 0.09f;

    private static final VisualRegistro GLOBAL = new VisualRegistro();

    /** El registro del juego. */
    static VisualRegistro global(){
        return GLOBAL;
    }

    private static final Comparator<VisualDibujo> ORDEN_DIBUJO = new Comparator<VisualDibujo>(){
        @Override
        public int compare(VisualDibujo a, VisualDibujo b){
            int c = Float.compare(a.capa.z, b.capa.z);
            if(c != 0) return c;
            c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private static final Comparator<VisualShaderDef> ORDEN_SHADER = new Comparator<VisualShaderDef>(){
        @Override
        public int compare(VisualShaderDef a, VisualShaderDef b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private static final Comparator<VisualPasada> ORDEN_PASADA = new Comparator<VisualPasada>(){
        @Override
        public int compare(VisualPasada a, VisualPasada b){
            int c = Integer.compare(a.prioridad, b.prioridad);
            if(c != 0) return c;
            c = a.fuente.compareTo(b.fuente);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    /** Estado de un shader registrado. */
    private static final class ShaderReg{
        final VisualShaderDef def;
        VisualPrograma programa;
        /** Perdió un uniforme contra otro shader (se recalcula al registrar). */
        boolean enConflicto;
        /** Falló al crearse o al aplicarse: desactivado para la sesión. */
        boolean fallo;
        /** Desactivado a propósito (por el mod o el jugador). */
        boolean manual;

        ShaderReg(VisualShaderDef def){
            this.def = def;
        }

        boolean activo(){
            return !enConflicto && !fallo && !manual;
        }
    }

    private final Object cerrojo = new Object();
    private volatile List<VisualDibujo> dibujos = new ArrayList<VisualDibujo>();
    private volatile List<VisualPasada> pasadas = new ArrayList<VisualPasada>();
    private final Map<String, ShaderReg> shaders = new HashMap<String, ShaderReg>();
    private final TreeMap<String, String> duenoUniforme = new TreeMap<String, String>();
    private final Map<String, String> desactivados = new HashMap<String, String>();   // dibujos y pasadas: id -> motivo
    private final Set<String> avisos = new HashSet<String>();

    private final VisualMonitor monitor = new VisualMonitor();
    private VisualCalidad elegida = VisualCalidad.ALTA;
    private boolean automatica = true;

    // diagnóstico del último fotograma
    private int ultimoCoste, ultimosOmitidos, ultimaPasadas, ultimasOmitidas;
    private long ultimosBytes;
    private VisualCalidad ultimaCalidad = VisualCalidad.ALTA;

    // ================================================================== registro

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    private void aviso(String clave, String texto){
        final boolean nuevo;
        synchronized(cerrojo){
            nuevo = avisos.add(clave);
        }
        if(nuevo) Log.warn("[MundoInfinito] Visual: " + texto);
    }

    /** @return false si no es válido (id con formato "fuente:nombre", capa, coste y función) o el id ya existe. */
    boolean registrarDibujo(VisualDibujo d){
        if(d == null || !idValido(d.id) || d.fuente == null || d.fuente.isEmpty() || d.capa == null || d.dibujar == null || d.coste < 0){
            Log.err("[MundoInfinito] Visual: dibujo rechazado (id con formato \"fuente:nombre\", capa, coste >= 0 y función son obligatorios): @", d == null ? "null" : d.id);
            return false;
        }
        synchronized(cerrojo){
            for(VisualDibujo e : dibujos) if(e.id.equals(d.id)) return false;
            final List<VisualDibujo> l = new ArrayList<VisualDibujo>(dibujos);
            l.add(d);
            Collections.sort(l, ORDEN_DIBUJO);
            dibujos = l;
            return true;
        }
    }

    boolean registrarPasada(VisualPasada p){
        if(p == null || !idValido(p.id) || p.fuente == null || p.fuente.isEmpty() || p.ejecutar == null || p.pasadas < 0 || p.bytesTextura < 0L){
            Log.err("[MundoInfinito] Visual: pasada rechazada (id con formato \"fuente:nombre\", costes >= 0 y función son obligatorios): @", p == null ? "null" : p.id);
            return false;
        }
        synchronized(cerrojo){
            for(VisualPasada e : pasadas) if(e.id.equals(p.id)) return false;
            final List<VisualPasada> l = new ArrayList<VisualPasada>(pasadas);
            l.add(p);
            Collections.sort(l, ORDEN_PASADA);
            pasadas = l;
            return true;
        }
    }

    /**
     * Registra un shader. Si declara un uniforme que escribe otro shader, el núcleo avisa y se queda con el primero en orden canónico, sea cual sea el
     * orden en que se registraron.
     */
    boolean registrarShader(VisualShaderDef s){
        if(s == null || !idValido(s.id) || s.fuente == null || s.fuente.isEmpty() || s.fabrica == null){
            Log.err("[MundoInfinito] Visual: shader rechazado (id con formato \"fuente:nombre\" y fábrica son obligatorios): @", s == null ? "null" : s.id);
            return false;
        }
        synchronized(cerrojo){
            if(shaders.containsKey(s.id)) return false;
            shaders.put(s.id, new ShaderReg(s));
            recalcularUniformes();
            return true;
        }
    }

    /** Recalcula desde cero quién es dueño de cada uniforme (por orden canónico), de modo que el resultado no depende del orden de registro. Con el cerrojo. */
    private void recalcularUniformes(){
        final List<ShaderReg> orden = new ArrayList<ShaderReg>(shaders.values());
        Collections.sort(orden, new Comparator<ShaderReg>(){
            @Override
            public int compare(ShaderReg a, ShaderReg b){
                return ORDEN_SHADER.compare(a.def, b.def);
            }
        });
        duenoUniforme.clear();
        for(ShaderReg r : orden) r.enConflicto = false;
        for(ShaderReg r : orden){
            for(String u : r.def.uniformesEscritos){
                final String dueno = duenoUniforme.get(u);
                if(dueno == null){
                    duenoUniforme.put(u, r.def.id);
                }else if(!dueno.equals(r.def.id)){
                    r.enConflicto = true;
                    final String clave = "uniforme:" + u + ":" + r.def.id;
                    if(avisos.add(clave)){
                        Log.warn("[MundoInfinito] Visual: el shader '" + r.def.id + "' escribe el uniforme '" + u + "', que ya escribe '" + dueno
                            + "' (gana el primero en orden canónico). '" + r.def.id + "' queda desactivado.");
                    }
                }
            }
        }
    }

    // ================================================================== consulta y control

    /** ¿Está activo este dibujo, shader o pasada? (false si no existe, fue desactivado, falló o perdió un conflicto de uniformes) */
    boolean activo(String id){
        synchronized(cerrojo){
            final ShaderReg s = shaders.get(id);
            if(s != null) return s.activo();
            if(desactivados.containsKey(id)) return false;
            for(VisualDibujo d : dibujos) if(d.id.equals(id)) return true;
            for(VisualPasada p : pasadas) if(p.id.equals(id)) return true;
            return false;
        }
    }

    /** Desactiva un dibujo, shader o pasada para la sesión. No afecta a nada más. */
    void desactivar(String id, String motivo){
        synchronized(cerrojo){
            final ShaderReg s = shaders.get(id);
            if(s != null){
                s.manual = true;
                return;
            }
            desactivados.put(id, motivo == null ? "desactivado" : motivo);
        }
    }

    /** Vuelve a activar lo desactivado a propósito (un shader o dibujo que falló NO se reactiva en la misma sesión). */
    void reactivar(String id){
        synchronized(cerrojo){
            final ShaderReg s = shaders.get(id);
            if(s != null){
                s.manual = false;
                return;
            }
            final String motivo = desactivados.get(id);
            if(motivo != null && !motivo.startsWith("fallo")) desactivados.remove(id);
        }
    }

    String motivoDesactivado(String id){
        synchronized(cerrojo){
            final ShaderReg s = shaders.get(id);
            if(s != null) return s.enConflicto ? "conflicto de uniforme" : (s.fallo ? "fallo" : (s.manual ? "desactivado" : null));
            return desactivados.get(id);
        }
    }

    /** Cuántos shaders están desactivados por un conflicto de uniformes. */
    int conflictosUniforme(){
        synchronized(cerrojo){
            int n = 0;
            for(ShaderReg r : shaders.values()) if(r.enConflicto) n++;
            return n;
        }
    }

    /** Quién es el dueño de un uniforme (id del shader), o null. */
    String duenoDeUniforme(String uniforme){
        synchronized(cerrojo){
            return duenoUniforme.get(uniforme);
        }
    }

    /** Ids de los dibujos registrados, en orden de dibujo. */
    List<String> ordenDibujo(){
        final List<String> l = new ArrayList<String>();
        for(VisualDibujo d : dibujos) l.add(d.id);
        return l;
    }

    // ================================================================== calidad

    /** Calidad elegida por el jugador y si se permite que la medición automática la baje. */
    void configurar(VisualCalidad elegida, boolean automatica){
        synchronized(cerrojo){
            this.elegida = elegida == null ? VisualCalidad.ALTA : elegida;
            this.automatica = automatica;
        }
    }

    /** La calidad que se aplica ahora. */
    VisualCalidad calidad(){
        synchronized(cerrojo){
            return automatica ? monitor.efectiva(elegida) : elegida;
        }
    }

    // ================================================================== fotograma (solo hilo de render)

    /**
     * Dibuja todo lo registrado, en orden canónico y respetando el presupuesto de la calidad.
     * @param msFotograma lo que tardó el fotograma anterior (para la medición automática)
     */
    void dibujarFotograma(VisualSalida salida, float msFotograma){
        final VisualCalidad q;
        synchronized(cerrojo){
            if(automatica) monitor.muestra(msFotograma);
            q = automatica ? monitor.efectiva(elegida) : elegida;
        }
        ultimaCalidad = q;
        final List<VisualDibujo> todos = dibujos;

        // 1) los que quieren dibujar este fotograma (condición cierta y no desactivados), y su coste
        final List<VisualDibujo> activos = new ArrayList<VisualDibujo>();
        int coste = 0;
        for(VisualDibujo d : todos){
            if(!activoDibujo(d)) continue;
            activos.add(d);
            coste += d.coste;
        }
        // 2) si no caben: se omiten los opcionales, empezando por el último en orden canónico; los esenciales no se omiten nunca
        final boolean[] omitir = new boolean[activos.size()];
        int omitidos = 0;
        for(int i = activos.size() - 1; i >= 0 && coste > q.llamadasDibujo; i--){
            final VisualDibujo d = activos.get(i);
            if(d.esencial) continue;
            omitir[i] = true;
            coste -= d.coste;
            omitidos++;
        }
        ultimoCoste = coste;
        ultimosOmitidos = omitidos;

        // 3) dibujar en orden; cada dibujo en su capa, con un desplazamiento mínimo para conservar el orden dentro de la capa
        VisualCapa capaActual = null;
        int enCapa = 0;
        for(int i = 0; i < activos.size(); i++){
            if(omitir[i]) continue;
            final VisualDibujo d = activos.get(i);
            if(d.capa != capaActual){
                capaActual = d.capa;
                enCapa = 0;
            }
            final float z = d.capa.z + Math.min(MAX_Z, PASO_Z * enCapa++);
            dibujarUno(salida, d, z);
        }
    }

    private boolean activoDibujo(VisualDibujo d){
        synchronized(cerrojo){
            if(desactivados.containsKey(d.id)) return false;
        }
        if(d.activo == null) return true;
        try{
            return d.activo.get();
        }catch(Throwable t){
            desactivarPorFallo(d.id, "su condición de dibujo lanzó una excepción", t);
            return false;
        }
    }

    private void dibujarUno(VisualSalida salida, final VisualDibujo d, float z){
        try{
            if(d.shader == null){
                salida.z(z);
                try{
                    d.dibujar.run();
                }finally{
                    salida.reiniciar();
                }
            }else{
                // con shader: el dibujo se ejecuta cuando el motor vuelca el lote, y ahí se aplica el programa
                salida.enCapa(z, new Runnable(){
                    @Override
                    public void run(){
                        final Throwable fallo = conShader(d.shader, d.dibujar);
                        if(fallo != null) desactivarPorFallo(d.id, "falló al dibujar", fallo);
                    }
                });
            }
        }catch(Throwable t){
            desactivarPorFallo(d.id, "falló al dibujar", t);
        }
    }

    /**
     * Ejecuta {@code dibujo} con el shader si está activo; si no existe, está desactivado o falla, lo ejecuta sin él.
     * @return la excepción que lanzó el DIBUJO (no el shader), o null
     */
    private Throwable conShader(String idShader, final Runnable dibujo){
        final ShaderReg s;
        synchronized(cerrojo){
            s = shaders.get(idShader);
        }
        final Throwable[] errDibujo = new Throwable[1];
        final boolean[] hecho = new boolean[1];
        final Runnable envuelto = new Runnable(){
            @Override
            public void run(){
                hecho[0] = true;
                try{
                    dibujo.run();
                }catch(Throwable t){
                    errDibujo[0] = t;
                }
            }
        };
        if(s == null || !s.activo()){
            envuelto.run();
            return errDibujo[0];
        }
        try{
            if(s.programa == null){
                s.programa = s.def.fabrica.get();
                if(s.programa == null) throw new IllegalStateException("la fábrica devolvió null");
            }
            s.programa.aplicar(envuelto);
        }catch(Throwable t){
            desactivarShader(s, t);
            if(!hecho[0]) envuelto.run();   // el fotograma no pierde el dibujo: sale sin shader
        }
        return errDibujo[0];
    }

    private void desactivarShader(ShaderReg s, Throwable t){
        synchronized(cerrojo){
            s.fallo = true;
        }
        aviso("fallo:" + s.def.id, "el shader '" + s.def.id + "' falló (" + t + ") y se desactiva para esta sesión; el juego continúa sin él.");
        final VisualPrograma p = s.programa;
        s.programa = null;
        if(p != null){
            try{ p.liberar(); }catch(Throwable ignorado){}
        }
    }

    private void desactivarPorFallo(String id, String que, Throwable t){
        synchronized(cerrojo){
            desactivados.put(id, "fallo: " + que);
        }
        aviso("fallo:" + id, "'" + id + "' " + que + " (" + t + ") y se desactiva para esta sesión; el juego continúa sin él.");
    }

    /** Ejecuta la cadena de post-procesado en orden canónico mientras quepa en el presupuesto de la calidad. Solo hilo de render. */
    void ejecutarPasadas(){
        final VisualCalidad q = calidad();
        int usadas = 0, omitidas = 0;
        long bytes = 0L;
        for(VisualPasada p : pasadas){
            synchronized(cerrojo){
                if(desactivados.containsKey(p.id)) continue;
            }
            if(usadas + p.pasadas > q.pasadasPost || bytes + p.bytesTextura > q.bytesTextura){
                omitidas++;
                continue;
            }
            try{
                p.ejecutar.run();
                usadas += p.pasadas;
                bytes += p.bytesTextura;
            }catch(Throwable t){
                desactivarPorFallo(p.id, "falló en el post-procesado", t);
            }
        }
        ultimaPasadas = usadas;
        ultimosBytes = bytes;
        ultimasOmitidas = omitidas;
    }

    // ================================================================== diagnóstico

    int ultimoCoste(){
        return ultimoCoste;
    }

    int ultimosOmitidos(){
        return ultimosOmitidos;
    }

    int ultimasPasadas(){
        return ultimaPasadas;
    }

    int ultimasPasadasOmitidas(){
        return ultimasOmitidas;
    }

    long ultimosBytes(){
        return ultimosBytes;
    }

    int nivelesBajadosPorMedicion(){
        synchronized(cerrojo){
            return monitor.niveles();
        }
    }

    String resumen(){
        int desact;
        synchronized(cerrojo){
            desact = desactivados.size();
            for(ShaderReg r : shaders.values()) if(!r.activo()) desact++;
        }
        return "visual: calidad=" + ultimaCalidad.nombre + " dibujos=" + dibujos.size() + " coste=" + ultimoCoste + "/" + ultimaCalidad.llamadasDibujo
            + " omitidos=" + ultimosOmitidos + " pasadas=" + ultimaPasadas + "/" + ultimaCalidad.pasadasPost + " shaders=" + shaders.size()
            + " desactivados=" + desact + " conflictosUniforme=" + conflictosUniforme();
    }
}
