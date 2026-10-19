package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NÚCLEO (capa 2 del plan): registro de contribuciones en orden canónico, evaluación, presupuestos declarados y registro de degradaciones.
 * Es el único componente que conoce a los contribuyentes. Fase 2: una sola contribución (el terreno base envuelto) y todo lo demás en
 * "modo vacío": se cuentan unidades y excesos, pero no se recorta a nadie (eso es la fase 7).
 *
 * Hilos: {@link #terreno} se llama desde los hilos de generación (varios a la vez) y es seguro; el registro se cierra al abrir el primer mundo.
 */
final class Nucleo{
    /** Versión del contrato del núcleo (sube con cambios incompatibles). */
    static final int VERSION = 1;
    /** Fase 7: mientras sea false solo se cuentan unidades y excesos; nunca se recorta ni se registra una degradación. */
    static final boolean DEGRADACION_ACTIVA = false;

    private static final Object CERROJO = new Object();
    private static volatile ContribucionTerreno[] terreno = new ContribucionTerreno[0];   // siempre en orden canónico
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
        final AtomicLong chunks = new AtomicLong(), unidades = new AtomicLong(), ns = new AtomicLong(), excesos = new AtomicLong();
    }

    static{
        registrar(new TerrenoBase());
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

    /** HILO PRINCIPAL, al abrir un mundo: cierra el registro y reinicia lo que es por mundo (degradaciones, contadores, avisos). */
    static void alReiniciarMundo(){
        cerrado = true;
        Degradaciones.limpiar();
        cuentas.clear();
        avisos.clear();
    }

    /** Contribuciones de terreno registradas, en orden canónico. */
    static List<ContribucionTerreno> contribucionesTerreno(){
        final ContribucionTerreno[] t = terreno;
        return new ArrayList<ContribucionTerreno>(java.util.Arrays.asList(t));
    }

    // ================================================================== evaluación

    /**
     * Hilo de fondo. Aporte de terreno del chunk cuyo origen virtual es (x0, y0). Devuelve el buffer DEL HILO: solo es válido hasta la
     * siguiente llamada en este hilo. Fase 2: se evalúa la primera contribución aplicable en orden canónico (la combinación de varias
     * y las políticas de resolución son la fase 5).
     */
    static AporteTerreno terreno(int semilla, int dim, int x0, int y0, int vcx, int vcy){
        final AporteTerreno out = LOCAL.get();
        final ContextoAporte ctx = new ContextoAporte(semilla, dim, x0, y0, vcx, vcy);
        ContribucionTerreno elegida = null;
        for(ContribucionTerreno c : terreno){
            if(c.aplicaA(dim)){
                elegida = c;
                break;
            }
        }
        if(elegida == null){
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
        final long t0 = System.nanoTime();
        final int unidades = elegida.aportar(ctx, out);
        final long dt = System.nanoTime() - t0;
        contar(elegida, vcx, vcy, unidades, dt);
        return out;
    }

    private static void contar(ContribucionTerreno c, int vcx, int vcy, int unidades, long ns){
        final String id = c.id();
        Cuenta k = cuentas.get(id);
        if(k == null){
            Cuenta nueva = new Cuenta();
            k = cuentas.putIfAbsent(id, nueva);
            if(k == null) k = nueva;
        }
        k.chunks.incrementAndGet();
        k.unidades.addAndGet(unidades);
        k.ns.addAndGet(ns);
        final PresupuestoTrabajo p = c.presupuesto();
        if(unidades > p.unidadesChunk){
            k.excesos.incrementAndGet();
            if(avisos.putIfAbsent("exceso:" + id, Boolean.TRUE) == null){
                Log.warn("[MundoInfinito] Núcleo: '@' gastó @ unidades (@) en un chunk y declaró @. Aún no se recorta (fase 7); se avisa una vez por mundo.", id, unidades, p.unidad, p.unidadesChunk);
            }
            if(DEGRADACION_ACTIVA) Degradaciones.registrar(id, vcx, vcy, unidades, p.unidadesChunk, "recorte-canonico");
        }
    }

    // ================================================================== diagnóstico

    static boolean activo(){
        return !cuentas.isEmpty();
    }

    /** Una línea para el log: por contribución, chunks, unidades medias, tiempo medio y excesos del presupuesto declarado. */
    static String resumen(){
        final StringBuilder sb = new StringBuilder(128);
        sb.append("núcleo v").append(VERSION);
        for(ContribucionTerreno c : terreno){
            final Cuenta k = cuentas.get(c.id());
            if(k == null) continue;
            final long n = Math.max(1L, k.chunks.get());
            final long d = (k.ns.get() / n) / 100_000L;   // décimas de ms
            sb.append(" | ").append(c.id()).append(" chunks=").append(k.chunks.get()).append(" unidades/chunk=").append(k.unidades.get() / n)
                .append(" (declaradas ").append(c.presupuesto().unidadesChunk).append(") calc=").append(d / 10).append('.').append(d % 10).append("ms");
            if(k.excesos.get() > 0) sb.append(" excesos=").append(k.excesos.get());
        }
        if(Degradaciones.total() > 0) sb.append(" | degradaciones=").append(Degradaciones.total());
        return sb.toString();
    }
}
