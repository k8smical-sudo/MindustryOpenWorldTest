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
 * Fase 7: presupuestos y degradación. Cada etapa (y cada contribución de terreno) gasta unidades contadas; si excede su presupuesto se le aplica su
 * {@link ReglaDegradacion}, se registra en {@link Degradaciones} y la generación sigue. Nada se mide en tiempo.
 *
 * Hilos: {@link #terreno} se llama desde los hilos de generación (varios a la vez) y es seguro; el registro se cierra al abrir el primer mundo.
 */
final class Nucleo{
    /** Versión del contrato del núcleo (sube con cambios incompatibles). */
    static final int VERSION = 6;
    /** Fase 7: cuando una contribución excede su presupuesto se le aplica su regla de degradación y se registra. (false: solo se contaría.) */
    static final boolean DEGRADACION_ACTIVA = true;
    /** Una contribución es "sistemática" cuando se degrada en al menos la mitad de sus evaluaciones, y al menos en este número de chunks. */
    static final int MINIMO_SISTEMATICO = 32;
    /** Nombre bajo el que se cuenta la tubería de etapas del terreno base. */
    static final String TUBERIA_BASE = "mundo-infinito:terreno-base";

    /** Parámetros de generación del mundo abierto (perfil). Sin mundo abierto: los de por defecto. */
    private static volatile ParametrosGeneracion parametros = ParametrosGeneracion.DEFECTO;

    /** La tubería de etapas del terreno base, como candidata en las disputas de terreno (peso 1, prioridad negativa: va la primera). */
    private static final Contribucion BASE = new Contribucion(){
        @Override
        public String id(){
            return TUBERIA_BASE;
        }

        @Override
        public String fuente(){
            return "mundo-infinito";
        }

        @Override
        public int version(){
            return 1;
        }

        @Override
        public Tipo tipo(){
            return Tipo.TERRENO;
        }

        @Override
        public int prioridad(){
            return -1000;
        }
    };

    private static final Object CERROJO = new Object();
    private static volatile ContribucionTerreno[] terreno = new ContribucionTerreno[0];   // siempre en orden canónico
    private static volatile ContribucionParametro[] propuestas = new ContribucionParametro[0];   // siempre en orden canónico
    private static volatile boolean cerrado;
    private static final ConcurrentHashMap<String, Cuenta> cuentas = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> avisos = new ConcurrentHashMap<>();
    /** Por contribución: cuántas veces se evaluó y cuántas se degradó, en este mundo (para el aviso de las sistemáticas). */
    private static final ConcurrentHashMap<String, Evaluaciones> evaluaciones = new ConcurrentHashMap<>();

    private static final class Evaluaciones{
        final AtomicLong evaluadas = new AtomicLong(), degradadas = new AtomicLong();
    }

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
        final AtomicLong chunks = new AtomicLong(), unidades = new AtomicLong(), ns = new AtomicLong();

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
        evaluaciones.clear();
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

        // candidatas: la tubería del terreno base (si hay etapas) y las contribuciones de terreno que no son etapas
        final EtapaBase[] etapas = etapasAplicables(todas, dim);
        int no = 0;
        for(ContribucionTerreno c : todas) if(c.aplicaA(dim) && !(c instanceof EtapaBase)) no++;

        // fase 6: si hay más de un candidato, la macrocélula (2x2 chunks) tiene UN dueño elegido por cuota de peso; todos sus chunks usan el mismo
        ContribucionTerreno otra = null;
        boolean usaBase = etapas.length > 0;
        if(no > 0){
            final List<Contribucion> cand = new ArrayList<Contribucion>();
            if(etapas.length > 0) cand.add(BASE);
            for(ContribucionTerreno c : todas) if(c.aplicaA(dim) && !(c instanceof EtapaBase)) cand.add(c);
            final Contribucion dueno = Reparto.dueno(semilla, dim, Reparto.macro(vcx), Reparto.macro(vcy), cand);
            usaBase = dueno == BASE;
            if(dueno != null && dueno != BASE) otra = (ContribucionTerreno)dueno;
        }

        if(usaBase){
            final long t0 = System.nanoTime();
            final int unidades = PipelineBase.ejecutar(ctx, out, etapas);
            int declaradas = 0;
            for(EtapaBase e : etapas) declaradas = Math.max(declaradas, e.presupuesto().unidadesChunk);
            contar(TUBERIA_BASE, declaradas, "casilla", unidades, System.nanoTime() - t0);
            registrarEtapas(etapas, out.informe, vcx, vcy);
            return out;
        }
        if(otra != null) return evaluarOtra(otra, etapas, ctx, out);

        // Degradar, nunca rechazar: sin contribución aplicable, el chunk sale de piedra y se avisa una vez por mundo
        if(avisos.putIfAbsent("sin:" + dim, Boolean.TRUE) == null){
            Log.err("[MundoInfinito] Núcleo: ninguna contribución de terreno se aplica a la dimensión @; se genera solo piedra.", dim);
        }
        rellenarPiedra(out);
        return out;
    }

    /** Las etapas del terreno base aplicables a la dimensión, en orden canónico. */
    private static EtapaBase[] etapasAplicables(ContribucionTerreno[] todas, int dim){
        int ne = 0;
        for(ContribucionTerreno c : todas) if(c instanceof EtapaBase && c.aplicaA(dim)) ne++;
        final EtapaBase[] etapas = new EtapaBase[ne];
        int k = 0;
        for(ContribucionTerreno c : todas) if(c instanceof EtapaBase && c.aplicaA(dim)) etapas[k++] = (EtapaBase)c;
        return etapas;
    }

    private static void rellenarPiedra(AporteTerreno out){
        for(int i = 0; i < AporteTerreno.N; i++){
            out.piso[i] = MundoInfinitoMod.Muestreo.P_STONE;
            out.mena[i] = 0;
            out.muro[i] = 0;
            out.prop[i] = 0;
        }
    }

    // ================================================================== presupuestos y degradación (fase 7)

    /**
     * Evalúa una contribución de terreno que NO es una etapa (el caso de un mod), con su presupuesto. Su función no se puede detener a medias, así que si
     * gasta más unidades de las permitidas, o si falla, se aplica la OMISIÓN: su aporte se descarta y el chunk lo genera el terreno base (o piedra si no hay
     * base). Se registra, y la generación sigue. Mismas entradas, misma decisión: la cuenta es de unidades, no de tiempo.
     * Package-private: lo usa también el corpus.
     */
    static AporteTerreno evaluarOtra(ContribucionTerreno c, EtapaBase[] respaldo, ContextoAporte ctx, AporteTerreno out){
        final PresupuestoTrabajo p = c.presupuesto();
        final int pct = ctx.params == null ? 100 : ctx.params.presupuestoTerrenoPct;
        final int permitidas = (int)((long)p.unidadesChunk * pct / 100L);
        final long t0 = System.nanoTime();
        int unidades = 0;
        boolean fallo = false;
        try{
            unidades = c.aportar(ctx, out);
        }catch(Throwable e){
            fallo = true;
            if(avisos.putIfAbsent("fallo:" + c.id(), Boolean.TRUE) == null){
                Log.err("[MundoInfinito] Núcleo: la contribución '" + c.id() + "' falló; su aporte se omite y el terreno base genera el chunk (aviso una vez por mundo).", e);
            }
        }
        contar(c.id(), p.unidadesChunk, p.unidad, unidades, System.nanoTime() - t0);
        final boolean excede = !fallo && unidades > permitidas;
        anotarEvaluacion(c.id(), fallo || excede);
        if(fallo || excede){
            if(DEGRADACION_ACTIVA) Degradaciones.registrar(c.id(), ctx.vcx, ctx.vcy, unidades, permitidas, fallo ? "omision-por-fallo" : ReglaDegradacion.OMISION.nombre);
            avisarSiSistematica(c.id(), unidades, permitidas, p.unidad, ReglaDegradacion.OMISION.nombre);
            if(respaldo != null && respaldo.length > 0) PipelineBase.ejecutar(ctx, out, respaldo);
            else rellenarPiedra(out);
        }
        return out;
    }

    /** Anota lo que le pasó a cada etapa en el chunk recién evaluado: evaluaciones, degradaciones, registro y aviso de las sistemáticas. */
    private static void registrarEtapas(EtapaBase[] etapas, PipelineBase.Informe inf, int vcx, int vcy){
        for(int k = 0; k < etapas.length; k++){
            final String id = etapas[k].id();
            anotarEvaluacion(id, inf.degradada[k]);
            if(!inf.degradada[k]) continue;
            final String regla = inf.regla[k].nombre;
            if(DEGRADACION_ACTIVA) Degradaciones.registrar(id, vcx, vcy, inf.pedidas[k], inf.permitidas[k], regla);
            avisarSiSistematica(id, inf.pedidas[k], inf.permitidas[k], etapas[k].presupuesto().unidad, regla);
        }
    }

    private static void anotarEvaluacion(String id, boolean degradada){
        Evaluaciones e = evaluaciones.get(id);
        if(e == null){
            final Evaluaciones nueva = new Evaluaciones();
            e = evaluaciones.putIfAbsent(id, nueva);
            if(e == null) e = nueva;
        }
        e.evaluadas.incrementAndGet();
        if(degradada) e.degradadas.incrementAndGet();
    }

    private static boolean esSistematica(Evaluaciones e){
        final long d = e.degradadas.get();
        return d >= MINIMO_SISTEMATICO && d * 2L >= e.evaluadas.get();
    }

    private static void avisarSiSistematica(String id, int pedidas, int permitidas, String unidad, String regla){
        final Evaluaciones e = evaluaciones.get(id);
        if(e == null || !esSistematica(e)) return;
        if(avisos.putIfAbsent("sistematica:" + id, Boolean.TRUE) == null){
            Log.warn("[MundoInfinito] Núcleo: '@' excede su presupuesto de forma sistemática: se degrada en @ de @ chunks (por ejemplo pide @ y se le permiten @ @; regla: @). "
                + "Conviene subir su presupuesto declarado. Aviso una vez por mundo.", id, e.degradadas.get(), e.evaluadas.get(), pedidas, permitidas, unidad, regla);
        }
    }

    /** Ids de las contribuciones que se degradan de forma sistemática en el mundo abierto, en orden alfabético. */
    static List<String> sistematicas(){
        final List<String> l = new ArrayList<String>();
        for(Map.Entry<String, Evaluaciones> e : new TreeMap<String, Evaluaciones>(evaluaciones).entrySet()) if(esSistematica(e.getValue())) l.add(e.getKey());
        return l;
    }

    /**
     * Id de la contribución dueña del terreno de la macrocélula (mcx, mcy): el terreno base ({@link #TUBERIA_BASE}) o la contribución de terreno
     * que ganó la disputa. Es la misma elección que hace {@link #terreno}. Consulta pura de diagnóstico.
     */
    static String duenoTerreno(int semilla, int dim, int mcx, int mcy){
        final ContribucionTerreno[] todas = terreno;
        final List<Contribucion> cand = new ArrayList<Contribucion>();
        boolean hayEtapas = false;
        for(ContribucionTerreno c : todas){
            if(!c.aplicaA(dim)) continue;
            if(c instanceof EtapaBase) hayEtapas = true;
            else cand.add(c);
        }
        if(hayEtapas) cand.add(BASE);
        final Contribucion d = Reparto.dueno(semilla, dim, mcx, mcy, cand);
        return d == null ? "(ninguna)" : d.id();
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

    private static void contar(String id, int declaradas, String unidad, int unidades, long ns){
        Cuenta k = cuentas.get(id);
        if(k == null){
            Cuenta nueva = new Cuenta(declaradas, unidad);
            k = cuentas.putIfAbsent(id, nueva);
            if(k == null) k = nueva;
        }
        k.chunks.incrementAndGet();
        k.unidades.addAndGet(unidades);
        k.ns.addAndGet(ns);
    }

    // ================================================================== diagnóstico

    static boolean activo(){
        return !cuentas.isEmpty();
    }

    /** Una línea para el log: por contribución evaluada, chunks, unidades medias frente a las declaradas, tiempo medio, degradaciones y sistemáticas. */
    static String resumen(){
        final StringBuilder sb = new StringBuilder(160);
        sb.append("núcleo v").append(VERSION).append(" etapas=").append(terreno.length);
        for(Map.Entry<String, Cuenta> e : new TreeMap<String, Cuenta>(cuentas).entrySet()){
            final Cuenta k = e.getValue();
            final long n = Math.max(1L, k.chunks.get());
            final long d = (k.ns.get() / n) / 100_000L;   // décimas de ms
            sb.append(" | ").append(e.getKey()).append(" chunks=").append(k.chunks.get()).append(" unidades/chunk=").append(k.unidades.get() / n)
                .append(" (declaradas ").append(k.declaradas).append(") calc=").append(d / 10).append('.').append(d % 10).append("ms");
        }
        if(Degradaciones.total() > 0) sb.append(" | degradaciones=").append(Degradaciones.total()).append(" sistemáticas=").append(sistematicas().size());
        if(RegistroAjustes.totalRecortes() > 0) sb.append(" | ajustes recortados=").append(RegistroAjustes.totalRecortes());
        if(Resolucion.conflictosSinPolitica() > 0) sb.append(" | ajustes sin política=").append(Resolucion.conflictosSinPolitica());
        return sb.toString();
    }
}
