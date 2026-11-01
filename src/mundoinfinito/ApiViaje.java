package mundoinfinito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * API DE VIAJES (plan v7, entrega B; D26). Portales por tiers que enlazan dos cuerpos y permiten viajar entre ellos, dentro de un sistema
 * o entre sistemas. Sin Arc: se prueba en CI sin el juego.
 *
 * Flujo: un portal de tier T establece un enlace entre dos cuerpos si están dentro de su alcance. Un viaje usa un enlace existente y
 * cuesta {@code ticksBase + distancia · ticksPorUnidad} ticks. Las distancias se toman de la posición simulada de cada cuerpo (ApiRadar).
 *
 * Defaults declarados (tiers 1 a 3): alcance 60, 300 y 1000 unidades; ticks base 300, 900 y 2400; 2, 1 y 0.5 ticks por unidad. Los
 * costes en materiales quedan para cuando se decida qué piden los portales (pregunta 3 del plan v7).
 */
public final class ApiViaje{
    public static final int TIER_MINIMO = 1, TIER_MAXIMO = 3;

    private ApiViaje(){}

    /** Un portal: tier, alcance máximo del enlace, coste base en ticks y coste por unidad de distancia. */
    public static final class Portal{
        public final int tier;
        public final double alcance;
        public final long ticksBase;
        public final double ticksPorUnidad;

        public Portal(int tier, double alcance, long ticksBase, double ticksPorUnidad){
            if(!(alcance > 0.0) || !Double.isFinite(alcance)) throw new IllegalArgumentException("alcance no válido: " + alcance);
            if(ticksBase < 0L) throw new IllegalArgumentException("ticks base no válidos: " + ticksBase);
            if(!(ticksPorUnidad >= 0.0) || !Double.isFinite(ticksPorUnidad)) throw new IllegalArgumentException("ticks por unidad no válidos");
            this.tier = tier;
            this.alcance = alcance;
            this.ticksBase = ticksBase;
            this.ticksPorUnidad = ticksPorUnidad;
        }
    }

    /** Portal por defecto de un tier. Un tier fuera de [1, 3] se acota. */
    public static Portal portal(int tier){
        final int t = Math.max(TIER_MINIMO, Math.min(TIER_MAXIMO, tier));
        switch(t){
            case 1: return new Portal(1, 60.0, 300L, 2.0);
            case 2: return new Portal(2, 300.0, 900L, 1.0);
            default: return new Portal(3, 1000.0, 2400L, 0.5);
        }
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
        public final double distancia;
        public final Enlace enlace;

        Resultado(boolean ok, String mensaje, long ticks, double distancia, Enlace enlace){
            this.ok = ok;
            this.mensaje = mensaje;
            this.ticks = ticks;
            this.distancia = distancia;
            this.enlace = enlace;
        }
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
     * están fuera del alcance del portal. El resultado lleva el coste de establecerlo ({@code ticksBase}).
     */
    public static Resultado establecer(EstadoSistemas est, Enlaces enlaces, String id, String a, String b, Portal portal, double t,
                                       ToDoubleFunction<String> periodo){
        if(id == null || a == null || b == null || portal == null || enlaces == null) return new Resultado(false, "datos no válidos", 0L, 0.0, null);
        if(a.equals(b)) return new Resultado(false, "un portal no puede enlazar un cuerpo consigo mismo", 0L, 0.0, null);
        if(enlaces.buscar(id) != null) return new Resultado(false, "ya existe un enlace con el id " + id, 0L, 0.0, null);
        if(enlaces.entre(a, b) != null) return new Resultado(false, "ya hay un enlace entre " + a + " y " + b, 0L, 0.0, null);
        final double d = distancia(est, a, b, t, periodo);
        if(Double.isNaN(d)) return new Resultado(false, "cuerpo no encontrado", 0L, 0.0, null);
        if(d > portal.alcance){
            return new Resultado(false, "fuera de alcance del portal tier " + portal.tier + ": distancia " + d + ", alcance " + portal.alcance,
                0L, d, null);
        }
        final Enlace e = new Enlace(id, a, b, portal.tier);
        enlaces.lista.add(e);
        return new Resultado(true, "enlace establecido", portal.ticksBase, d, e);
    }

    /**
     * Viaje entre dos cuerpos enlazados. Falla si no hay un enlace entre ellos o si algún cuerpo no existe. El coste es
     * {@code ticksBase + distancia · ticksPorUnidad} del tier del enlace, redondeado.
     */
    public static Resultado viajar(EstadoSistemas est, Enlaces enlaces, String origen, String destino, double t,
                                   ToDoubleFunction<String> periodo){
        if(enlaces == null || origen == null || destino == null) return new Resultado(false, "datos no válidos", 0L, 0.0, null);
        final Enlace e = enlaces.entre(origen, destino);
        if(e == null) return new Resultado(false, "no hay enlace entre " + origen + " y " + destino, 0L, 0.0, null);
        final double d = distancia(est, origen, destino, t, periodo);
        if(Double.isNaN(d)) return new Resultado(false, "cuerpo no encontrado", 0L, 0.0, null);
        final Portal p = portal(e.tier);
        final long ticks = p.ticksBase + Math.round(d * p.ticksPorUnidad);
        return new Resultado(true, "viaje listo", ticks, d, e);
    }
}
