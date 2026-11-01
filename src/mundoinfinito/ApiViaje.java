package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * API DE VIAJES (plan v8, entrega B; respuesta 3 de la sección 10). Portales por tiers que enlazan dos cuerpos, y viajes entre ellos
 * dentro de un sistema o entre sistemas. Sin Arc salvo el log de avisos: se prueba en CI sin el juego.
 *
 * COSTE Y TIEMPO (respuesta 3): el coste de un viaje es proporcional a su distancia y al tier de la unidad que viaja:
 *     coste = distancia · tierUnidad · COSTE_POR_UNIDAD
 * El teletransporte tarda proporcional a la distancia y al tier de la unidad, y se acelera con el tier del portal (igual que subir el tier
 * de una fábrica):
 *     ticks = distancia · TICKS_POR_UNIDAD · tierUnidad / tierPortal
 *
 * REQUISITOS: cada tier de portal pide materiales ("item:cantidad"). Un viaje falla si el inventario no los cubre.
 *
 * CONSECUENCIAS: por defecto, ninguna. Un addon puede registrar consecuencias (interferencias, daños) con {@link #proponerConsecuencia}.
 * Una consecuencia que falla se desactiva con aviso.
 *
 * Valores por defecto declarados (pendientes de calibrar en F17): alcance 60, 300 y 1000; ticks para establecer 300, 900 y 2400;
 * materiales de los tiers 1 a 3 (ver {@link #portal}).
 */
public final class ApiViaje{
    public static final int TIER_MINIMO = 1, TIER_MAXIMO = 3;
    /** Ticks de teletransporte por unidad de distancia, con tier de unidad 1 y tier de portal 1. Default declarado. */
    public static final double TICKS_POR_UNIDAD = 2.0;
    /** Coste por unidad de distancia, con tier de unidad 1. Default declarado. */
    public static final double COSTE_POR_UNIDAD = 1.0;

    private ApiViaje(){}

    /** Un portal: tier, alcance del enlace, ticks para establecerlo y materiales que pide para funcionar ("item:cantidad"). */
    public static final class Portal{
        public final int tier;
        public final double alcance;
        public final long ticksEstablecer;
        private final String[] requisitos;

        public Portal(int tier, double alcance, long ticksEstablecer, String... requisitos){
            if(!(alcance > 0.0) || !Double.isFinite(alcance)) throw new IllegalArgumentException("alcance no válido: " + alcance);
            if(ticksEstablecer < 0L) throw new IllegalArgumentException("ticks para establecer no válidos: " + ticksEstablecer);
            this.tier = tier;
            this.alcance = alcance;
            this.ticksEstablecer = ticksEstablecer;
            this.requisitos = requisitos == null ? new String[0] : requisitos.clone();
        }

        public String[] requisitos(){ return requisitos.clone(); }
    }

    /** Acota un tier al rango válido. */
    static int acotarTier(int t){ return Math.max(TIER_MINIMO, Math.min(TIER_MAXIMO, t)); }

    /**
     * Portal por defecto de un tier. Materiales declarados (nombres internos de Mindustry): tier 1, cobre y plomo; tier 2, silicio, titanio
     * y plomo; tier 3, aleación de sobrecarga, tejido de fase y silicio.
     */
    public static Portal portal(int tier){
        switch(acotarTier(tier)){
            case 1: return new Portal(1, 60.0, 300L, "copper:50", "lead:30");
            case 2: return new Portal(2, 300.0, 900L, "silicon:40", "titanium:20", "lead:40");
            default: return new Portal(3, 1000.0, 2400L, "surge-alloy:20", "phase-fabric:20", "silicon:60");
        }
    }

    /** Materiales que faltan para un portal de ese tier, con la cantidad que falta ("item cantidad"). Vacío si está completo. */
    public static List<String> faltan(int tier, Map<String, Integer> inventario){
        final List<String> out = new ArrayList<String>();
        for(String req : portal(tier).requisitos()){
            final int sep = req.lastIndexOf(':');
            final String item = req.substring(0, sep);
            final int cantidad = Integer.parseInt(req.substring(sep + 1));
            final Integer tiene = inventario == null ? null : inventario.get(item);
            final int disponible = tiene == null ? 0 : tiene;
            if(disponible < cantidad) out.add(item + " " + (cantidad - disponible));
        }
        return out;
    }

    /** Enlace entre dos cuerpos creado por un portal de un tier. */
    public static final class Enlace{
        public final String id, cuerpoA, cuerpoB;
        public final int tier;

        Enlace(String id, String cuerpoA, String cuerpoB, int tier){
            this.id = id;
            this.cuerpoA = cuerpoA;
            this.cuerpoB = cuerpoB;
            this.tier = tier;
        }
    }

    /** Resultado de establecer un enlace o de un viaje. Si no es ok, {@link #mensaje} explica el motivo. */
    public static final class Resultado{
        public final boolean ok;
        public final String mensaje;
        public final long ticks;
        public final double coste;
        public final double distancia;
        public final Enlace enlace;
        /** Efectos de las consecuencias registradas (vacío por defecto). */
        public final List<String> consecuencias;

        Resultado(boolean ok, String mensaje, long ticks, double coste, double distancia, Enlace enlace, List<String> consecuencias){
            this.ok = ok;
            this.mensaje = mensaje;
            this.ticks = ticks;
            this.coste = coste;
            this.distancia = distancia;
            this.enlace = enlace;
            this.consecuencias = Collections.unmodifiableList(consecuencias);
        }
    }

    private static Resultado fallo(String mensaje){
        return new Resultado(false, mensaje, 0L, 0.0, 0.0, null, new ArrayList<String>());
    }

    /** Enlaces existentes. Un enlace une dos cuerpos en cualquier sentido. */
    public static final class Enlaces{
        private final ArrayList<Enlace> lista = new ArrayList<Enlace>();

        public List<Enlace> todos(){ return Collections.unmodifiableList(lista); }

        public Enlace buscar(String id){
            for(Enlace e : lista) if(e.id.equals(id)) return e;
            return null;
        }

        /** Enlace entre dos cuerpos, en cualquier orden, o null. */
        public Enlace entre(String a, String b){
            for(Enlace e : lista){
                if((e.cuerpoA.equals(a) && e.cuerpoB.equals(b)) || (e.cuerpoA.equals(b) && e.cuerpoB.equals(a))) return e;
            }
            return null;
        }

        public boolean quitar(String id){
            for(int i = 0; i < lista.size(); i++){
                if(lista.get(i).id.equals(id)){
                    lista.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    /** Posición de un cuerpo en el tiempo t (la de su sistema más su órbita), o null si no existe. */
    static double[] posicionDe(EstadoSistemas est, String cuerpo, double t, ToDoubleFunction<String> periodo){
        for(EstadoSistemas.SistemaSolar s : est.sistemas()){
            for(EstadoSistemas.Cuerpo c : s.cuerpos()){
                if(c.id.equals(cuerpo)) return ApiRadar.posicionCuerpo(s, c, t, periodo);
            }
        }
        return null;
    }

    /** Distancia entre dos cuerpos en el tiempo t. NaN si alguno no existe. */
    public static double distancia(EstadoSistemas est, String a, String b, double t, ToDoubleFunction<String> periodo){
        final double[] pa = posicionDe(est, a, t, periodo), pb = posicionDe(est, b, t, periodo);
        if(pa == null || pb == null) return Double.NaN;
        return ApiFisica.distancia(pa[0], pa[1], pb[0], pb[1]);
    }

    /**
     * Establece un enlace entre dos cuerpos con un portal de un tier. Falla si los cuerpos no existen, son el mismo, ya están enlazados, o
     * están fuera del alcance del portal. Lleva el coste de establecerlo en ticks ({@code ticksEstablecer}).
     */
    public static Resultado establecer(EstadoSistemas est, Enlaces enlaces, String id, String a, String b, Portal portal, double t,
                                       ToDoubleFunction<String> periodo){
        if(id == null || a == null || b == null || portal == null || enlaces == null) return fallo("datos no válidos");
        if(a.equals(b)) return fallo("un portal no puede enlazar un cuerpo consigo mismo");
        if(enlaces.buscar(id) != null) return fallo("ya existe un enlace con el id " + id);
        if(enlaces.entre(a, b) != null) return fallo("ya hay un enlace entre " + a + " y " + b);
        final double d = distancia(est, a, b, t, periodo);
        if(Double.isNaN(d)) return fallo("cuerpo no encontrado");
        if(d > portal.alcance){
            return fallo("fuera de alcance del portal tier " + portal.tier + ": distancia " + d + ", alcance " + portal.alcance);
        }
        final Enlace e = new Enlace(id, a, b, portal.tier);
        enlaces.lista.add(e);
        return new Resultado(true, "enlace establecido", portal.ticksEstablecer, 0.0, d, e, new ArrayList<String>());
    }

    /**
     * Viaje entre dos cuerpos enlazados. Falla si no hay enlace, si falta algún cuerpo o material del portal, o si el tier de la unidad no
     * es válido. Coste y ticks según la fórmula de la cabecera. Al completar el viaje se aplican las consecuencias registradas.
     */
    public static Resultado viajar(EstadoSistemas est, Enlaces enlaces, String origen, String destino, int tierUnidad,
                                   Map<String, Integer> inventario, double t, ToDoubleFunction<String> periodo){
        if(enlaces == null || origen == null || destino == null) return fallo("datos no válidos");
        final Enlace e = enlaces.entre(origen, destino);
        if(e == null) return fallo("no hay enlace entre " + origen + " y " + destino);
        final double d = distancia(est, origen, destino, t, periodo);
        if(Double.isNaN(d)) return fallo("cuerpo no encontrado");
        final List<String> faltan = faltan(e.tier, inventario);
        if(!faltan.isEmpty()) return fallo("faltan materiales para el portal tier " + e.tier + ": " + faltan);
        final int u = acotarTier(tierUnidad);
        final long ticks = Math.max(1L, (long)Math.ceil(d * TICKS_POR_UNIDAD * u / e.tier));
        final double coste = d * u * COSTE_POR_UNIDAD;
        final Resultado base = new Resultado(true, "viaje listo", ticks, coste, d, e, new ArrayList<String>());
        return new Resultado(true, "viaje listo", ticks, coste, d, e, aplicarConsecuencias(base));
    }

    // ================================================================== consecuencias (API lista para addons; por defecto, ninguna)

    /** Efecto de un viaje completado (interferencias, daños, etc.). Devuelve el texto del efecto, o null si no aplica. */
    @FunctionalInterface
    public interface Consecuencia{
        String aplicar(Resultado viaje);
    }

    private static final class Registrada{
        final String id;
        final Consecuencia consecuencia;
        boolean activa = true;

        Registrada(String id, Consecuencia consecuencia){
            this.id = id;
            this.consecuencia = consecuencia;
        }
    }

    private static final ArrayList<Registrada> consecuencias = new ArrayList<Registrada>();

    /** Registra una consecuencia de viajes. Devuelve false si el id es nulo, la consecuencia es nula o el id ya existe. */
    public static boolean proponerConsecuencia(String id, Consecuencia consecuencia){
        if(id == null || consecuencia == null) return false;
        for(Registrada r : consecuencias) if(r.id.equals(id)) return false;
        consecuencias.add(new Registrada(id, consecuencia));
        return true;
    }

    /** Solo para pruebas. */
    static void reiniciarConsecuencias(){ consecuencias.clear(); }

    private static List<String> aplicarConsecuencias(Resultado viaje){
        final List<String> out = new ArrayList<String>();
        for(Registrada r : new ArrayList<Registrada>(consecuencias)){
            if(!r.activa) continue;
            try{
                final String efecto = r.consecuencia.aplicar(viaje);
                if(efecto != null) out.add(efecto);
            }catch(RuntimeException e){
                r.activa = false;
                Log.err("[MundoInfinito] La consecuencia de viaje '@' falla y se desactiva: @", r.id, e.toString());
            }
        }
        return out;
    }
}
