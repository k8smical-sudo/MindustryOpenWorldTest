package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NÚCLEO (capa 2 del plan): registro de contribuciones en orden canónico, evaluación, presupuestos declarados y registro de degradaciones.
 * Es el único componente que conoce a los contribuyentes.
 *
 * Fase 3: el terreno base son 11 etapas independientes ({@link EtapasBase}) que se evalúan en una sola pasada por las casillas, en orden canónico.
 * Todo lo demás sigue en "modo vacío": se cuentan unidades y excesos, pero no se recorta a nadie (eso es la fase 7).
 *
 * Hilos: {@link #terreno} se llama desde los hilos de generación (varios a la vez) y es seguro; el registro se cierra al abrir el primer mundo.
 */
final class Nucleo{
    /** Versión del contrato del núcleo (sube con cambios incompatibles). */
    static final int VERSION = 4;
    /** Fase 7: mientras sea false solo se cuentan unidades y excesos; nunca se recorta ni se registra una degradación. */
    static final boolean DEGRADACION_ACTIVA = false;
    /** Nombre bajo el que se cuenta la tubería de etapas del terreno base. */
    static final String TUBERIA_BASE = "mundo-infinito:terreno-base";

    /** Parámetros de generación del mundo abierto (perfil). Sin mundo abierto: los de por defecto. */
    private static volatile ParametrosGeneracion parametros = ParametrosGeneracion.DEFECTO;

    private static final Object CERROJO = new Object();
    private static volatile ContribucionTerreno[] terreno = new ContribucionTerreno[0];   // siempre en orden canónico
    private static volatile ContribucionParametro[] propuestas = new ContribucionParametro[0];   // siempre en orden canónico
    private static volatile boolean cerrado;
    private static final ConcurrentHashMap<String, Cuenta> cuentas = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> avisos = new ConcurrentHashMap<>();

    /** Un buffer de aporte por hilo (cero asignaciones por chunk). */
    private static final ThreadLocal<AporteTerreno> LOCAL = new ThreadLocal<AporteTerreno>(){
        @Override
        protected AporteTerreno initialValue(){
            return new AporteTerreno();
        }
    };

    private static final class Cuenta{
        final int declaradas;
        final String unidad;
        final AtomicLong chunks = new AtomicLong(), unidades = new AtomicLong(), ns = new AtomicLong(), excesos = new AtomicLong();

        Cuenta(int declaradas, String unidad){
            this.declaradas = declaradas;
            this.unidad = unidad;
        }
    }

    static{
        for(EtapaBase e : EtapasBase.todas()) registrar(e);
    }

    private Nucleo(){}

    // ================================================================== registro

    /**
     * Registra una contribución de terreno. Falla (false) si el registro ya está cerrado, el id está repetido o no es válido.
     * Se mantiene siempre ordenado por {@link Orden#CANONICO}: el orden de registro no cuenta.
     */
    static boolean registrar(ContribucionTerreno c){
        if(c == null || c.id() == null || c.fuente() == null || c.id().isEmpty() || c.fuente().isEmpty()) return false;
        synchronized(CERROJO){
            if(cerrado){
                Log.warn("[MundoInfinito] Núcleo: el registro está cerrado; '@' no se registra (hay que registrar antes de abrir el primer mundo).", c.id());
                return false;
            }
            for(ContribucionTerreno e : terreno) if(e.id().equals(c.id())) return false;
            final List<ContribucionTerreno> l = new ArrayList<ContribucionTerreno>();
            Collections.addAll(l, terreno);
            l.add(c);
            Collections.sort(l, Orden.CANONICO);
            terreno = l.toArray(new ContribucionTerreno[0]);
            return true;
        }
    }

    /**
     * Registra una propuesta de valor para un ajuste de generación (fase 5). Se resuelve al CREAR un mundo, junto con las demás propuestas
     * sobre el mismo ajuste, y el resultado queda guardado en el perfil de ese mundo. Mismas reglas que {@link #registrar}: antes de abrir
     * el primer mundo, id único, orden de registro irrelevante.
     */
    static boolean registrarParametro(ContribucionParametro c){
        if(c == null || c.id() == null || c.fuente() == null || c.parametro() == null || c.id().isEmpty() || c.fuente().isEmpty() || c.parametro().isEmpty()) return false;
        synchronized(CERROJO){
            if(cerrado){
                Log.warn("[MundoInfinito] Núcleo: el registro está cerrado; la propuesta '@' no se registra (hay que registrar antes de abrir el primer mundo).", c.id());
                return false;
            }
            for(ContribucionParametro e : propuestas) if(e.id().equals(c.id())) return false;
            final List<ContribucionParametro> l = new ArrayList<ContribucionParametro>();
            Collections.addAll(l, propuestas);
            l.add(c);
            Collections.sort(l, Orden.CANONICO);
            propuestas = l.toArray(new ContribucionParametro[0]);
            return true;
        }
    }

    /** Las propuestas registradas para un ajuste, en orden canónico. */
    static List<ContribucionParametro> propuestasPara(String parametro){
        final List<ContribucionParametro> l = new ArrayList<ContribucionParametro>();
        for(ContribucionParametro c : propuestas) if(c.parametro().equals(parametro)) l.add(c);
        return l;
    }

    /** Parámetros de generación del mundo abierto. */
    static ParametrosGeneracion parametros(){
        return parametros;
    }

    /**
     * HILO PRINCIPAL, al abrir un mundo: cierra los registros (contribuciones y ajustes de generación), fija los parámetros de generación del
     * perfil del mundo y reinicia lo que es por mundo (degradaciones, contadores, avisos).
     */
    static void alReiniciarMundo(ParametrosGeneracion par){
        cerrado = true;
        RegistroAjustes.cerrarGeneracion();
        parametros = par == null ? ParametrosGeneracion.DEFECTO : par;
        Degradaciones.limpiar();
        cuentas.clear();
        avisos.clear();
    }

    /** Contribuciones de terreno registradas, en orden canónico. */
    static List<ContribucionTerreno> contribucionesTerreno(){
        return new ArrayList<ContribucionTerreno>(Arrays.asList(terreno));
    }

    // ================================================================== evaluación

    /**
     * Hilo de fondo. Aporte de terreno del chunk cuyo origen virtual es (x0, y0). Devuelve el buffer DEL HILO: solo es válido hasta la
     * siguiente llamada en este hilo.
     * Se evalúan, en una sola pasada y en orden canónico, todas las etapas del terreno base aplicables a la dimensión. Si no hay ninguna se
     * usa la primera contribución de terreno aplicable; si tampoco, el chunk sale de piedra. (Combinar contribuciones de mods es la fase 5.)
     */
    static AporteTerreno terreno(int semilla, int dim, int x0, int y0, int vcx, int vcy){
        return terreno(parametros, semilla, dim, x0, y0, vcx, vcy);
    }

    /** Igual, con los parámetros de generación indicados (pruebas y mundos que no son el abierto). */
    static AporteTerreno terreno(ParametrosGeneracion par, int semilla, int dim, int x0, int y0, int vcx, int vcy){
        final AporteTerreno out = LOCAL.get();
        final ContextoAporte ctx = new ContextoAporte(semilla, dim, x0, y0, vcx, vcy, par);
        final ContribucionTerreno[] todas = terreno;

        int ne = 0;
        for(ContribucionTerreno c : todas) if(c instanceof EtapaBase && c.aplicaA(dim)) ne++;
        if(ne > 0){
            final EtapaBase[] etapas = new EtapaBase[ne];
            int k = 0, declaradas = 0;
            for(ContribucionTerreno c : todas){
                if(c instanceof EtapaBase && c.aplicaA(dim)){
                    etapas[k++] = (EtapaBase)c;
                    declaradas = Math.max(declaradas, c.presupuesto().unidadesChunk);
                }
            }
            final long t0 = System.nanoTime();
            final int unidades = PipelineBase.ejecutar(ctx, out, etapas);
            contar(TUBERIA_BASE, declaradas, "casilla", vcx, vcy, unidades, System.nanoTime() - t0);
            return out;
        }

        for(ContribucionTerreno c : todas){
            if(c.aplicaA(dim)){
                final long t0 = System.nanoTime();
                final int unidades = c.aportar(ctx, out);
                final PresupuestoTrabajo p = c.presupuesto();
                contar(c.id(), p.unidadesChunk, p.unidad, vcx, vcy, unidades, System.nanoTime() - t0);
                return out;
            }
        }

        // Degradar, nunca rechazar: sin contribución aplicable, el chunk sale de piedra y se avisa una vez por mundo
        if(avisos.putIfAbsent("sin:" + dim, Boolean.TRUE) == null){
            Log.err("[MundoInfinito] Núcleo: ninguna contribución de terreno se aplica a la dimensión @; se genera solo piedra.", dim);
        }
        for(int i = 0; i < AporteTerreno.N; i++){
            out.piso[i] = MundoInfinitoMod.Muestreo.P_STONE;
            out.mena[i] = 0;
            out.muro[i] = 0;
            out.prop[i] = 0;
        }
        return out;
    }

    /**
     * PRUEBAS (lo usa el corpus): igual que {@link #terreno} pero evaluando SOLO las etapas cuyos ids estén en {@code ids}, en orden canónico.
     * No cuenta estadísticas.
     */
    static AporteTerreno terrenoSolo(Set<String> ids, int semilla, int dim, int x0, int y0, int vcx, int vcy){
        final AporteTerreno out = LOCAL.get();
        final ContextoAporte ctx = new ContextoAporte(semilla, dim, x0, y0, vcx, vcy, parametros);
        final List<EtapaBase> l = new ArrayList<EtapaBase>();
        for(ContribucionTerreno c : terreno) if(c instanceof EtapaBase && ids.contains(c.id()) && c.aplicaA(dim)) l.add((EtapaBase)c);
        PipelineBase.ejecutar(ctx, out, l.toArray(new EtapaBase[0]));
        return out;
    }

    private static void contar(String id, int declaradas, String unidad, int vcx, int vcy, int unidades, long ns){
        Cuenta k = cuentas.get(id);
        if(k == null){
            Cuenta nueva = new Cuenta(declaradas, unidad);
            k = cuentas.putIfAbsent(id, nueva);
            if(k == null) k = nueva;
        }
        k.chunks.incrementAndGet();
        k.unidades.addAndGet(unidades);
        k.ns.addAndGet(ns);
        if(unidades > declaradas){
            k.excesos.incrementAndGet();
            if(avisos.putIfAbsent("exceso:" + id, Boolean.TRUE) == null){
                Log.warn("[MundoInfinito] Núcleo: '@' gastó @ unidades (@) en un chunk y declaró @. Aún no se recorta (fase 7); se avisa una vez por mundo.", id, unidades, unidad, declaradas);
            }
            if(DEGRADACION_ACTIVA) Degradaciones.registrar(id, vcx, vcy, unidades, declaradas, "recorte-canonico");
        }
    }

    // ================================================================== diagnóstico

    static boolean activo(){
        return !cuentas.isEmpty();
    }

    /** Una línea para el log: por contribución evaluada, chunks, unidades medias frente a las declaradas, tiempo medio y excesos. */
    static String resumen(){
        final StringBuilder sb = new StringBuilder(160);
        sb.append("núcleo v").append(VERSION).append(" etapas=").append(terreno.length);
        for(Map.Entry<String, Cuenta> e : new TreeMap<String, Cuenta>(cuentas).entrySet()){
            final Cuenta k = e.getValue();
            final long n = Math.max(1L, k.chunks.get());
            final long d = (k.ns.get() / n) / 100_000L;   // décimas de ms
            sb.append(" | ").append(e.getKey()).append(" chunks=").append(k.chunks.get()).append(" unidades/chunk=").append(k.unidades.get() / n)
                .append(" (declaradas ").append(k.declaradas).append(") calc=").append(d / 10).append('.').append(d % 10).append("ms");
            if(k.excesos.get() > 0) sb.append(" excesos=").append(k.excesos.get());
        }
        if(Degradaciones.total() > 0) sb.append(" | degradaciones=").append(Degradaciones.total());
        if(RegistroAjustes.totalRecortes() > 0) sb.append(" | ajustes recortados=").append(RegistroAjustes.totalRecortes());
        if(Resolucion.conflictosSinPolitica() > 0) sb.append(" | ajustes sin política=").append(Resolucion.conflictosSinPolitica());
        return sb.toString();
    }
}
