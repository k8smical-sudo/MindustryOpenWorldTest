package mundoinfinito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * API DE RADARES (plan v7, entrega A2; respuesta 2 de la sección 10). Un radar de tier T busca los cuerpos dentro de su alcance y los
 * reporta en orden de detección: primero los sistemas de cercano a lejano, y dentro de cada sistema, los cuerpos de cercano a lejano.
 * Cada cuerpo tarda {@code ticksPorCuerpo} en detectarse, así que el k-ésimo detectado aparece en {@code tiempo + (k+1)·ticksPorCuerpo}.
 * Sin Arc: se prueba en CI sin el juego.
 *
 * Valores por defecto declarados (tiers 1 a 3): alcance 40, 120 y 400 unidades; 600, 240 y 60 ticks por cuerpo. Un dev puede declarar
 * sus propios niveles con el constructor público y pasarlos a {@link #escanear}. Los portales por tiers llegan en la entrega siguiente.
 */
public final class ApiRadar{
    public static final int TIER_MINIMO = 1, TIER_MAXIMO = 3;

    private ApiRadar(){}

    /** Un nivel de radar: tier, alcance (unidades del sistema solar) y ticks de barrido por cuerpo detectado. */
    public static final class Nivel{
        public final int tier;
        public final double alcance;
        public final long ticksPorCuerpo;

        public Nivel(int tier, double alcance, long ticksPorCuerpo){
            if(!(alcance > 0.0) || !Double.isFinite(alcance)) throw new IllegalArgumentException("alcance no válido: " + alcance);
            if(ticksPorCuerpo < 1L) throw new IllegalArgumentException("ticks por cuerpo no válidos: " + ticksPorCuerpo);
            this.tier = tier;
            this.alcance = alcance;
            this.ticksPorCuerpo = ticksPorCuerpo;
        }
    }

    /** Un cuerpo detectado: en qué sistema está, a qué distancia del radar, cuándo se detecta y en qué posición de la secuencia. */
    public static final class Hallazgo{
        public final String sistemaId;
        public final String cuerpoId;
        public final double distancia;
        public final long tickDeteccion;
        public final int orden;

        Hallazgo(String sistemaId, String cuerpoId, double distancia, long tickDeteccion, int orden){
            this.sistemaId = sistemaId;
            this.cuerpoId = cuerpoId;
            this.distancia = distancia;
            this.tickDeteccion = tickDeteccion;
            this.orden = orden;
        }
    }

    /** Nivel por defecto de un tier. Un tier fuera de [1, 3] se acota. */
    public static Nivel nivel(int tier){
        final int t = Math.max(TIER_MINIMO, Math.min(TIER_MAXIMO, tier));
        switch(t){
            case 1: return new Nivel(1, 40.0, 600L);
            case 2: return new Nivel(2, 120.0, 240L);
            default: return new Nivel(3, 400.0, 60L);
        }
    }

    /**
     * Posición de un cuerpo en el plano en el tiempo t: la del sistema más el desplazamiento de su órbita (ángulo simulado con el periodo
     * del planeta base). Un cuerpo solitario o sin órbita está en la posición de su sistema.
     */
    public static double[] posicionCuerpo(EstadoSistemas.SistemaSolar s, EstadoSistemas.Cuerpo c, double t,
                                          ToDoubleFunction<String> periodoPlaneta){
        final double[] p = ApiSistemas.posicion(s, t);
        if(c.orbita == null || !(c.radioOrbita > 0.0)) return p;
        final double a = ApiSistemas.anguloEn(c, t, periodoPlaneta.applyAsDouble(c.planeta));
        return new double[]{p[0] + c.radioOrbita * Math.cos(a), p[1] + c.radioOrbita * Math.sin(a)};
    }

    /** Candidato interno: un cuerpo dentro del alcance, con las distancias que lo ordenan. */
    private static final class Candidato{
        final String sistemaId, cuerpoId;
        final double distanciaSistema, distanciaCuerpo;

        Candidato(String sistemaId, String cuerpoId, double distanciaSistema, double distanciaCuerpo){
            this.sistemaId = sistemaId;
            this.cuerpoId = cuerpoId;
            this.distanciaSistema = distanciaSistema;
            this.distanciaCuerpo = distanciaCuerpo;
        }
    }

    /**
     * Barrido en el tiempo {@code tiempo} desde el origen (ox, oy). Solo se reportan los cuerpos dentro del alcance del nivel. Orden: sistemas
     * de cercano a lejano; dentro de cada sistema, cuerpos de cercano a lejano; desempate por id. Determinista: mismos datos, mismo resultado.
     */
    public static List<Hallazgo> escanear(EstadoSistemas estado, double ox, double oy, Nivel nivel, long tiempo,
                                          ToDoubleFunction<String> periodoPlaneta){
        final List<Candidato> cand = new ArrayList<Candidato>();
        for(EstadoSistemas.SistemaSolar s : estado.sistemas()){
            final double[] ps = ApiSistemas.posicion(s, tiempo);
            final double dSistema = ApiFisica.distancia(ps[0], ps[1], ox, oy);
            for(EstadoSistemas.Cuerpo c : s.cuerpos()){
                final double[] pc = posicionCuerpo(s, c, tiempo, periodoPlaneta);
                final double dCuerpo = ApiFisica.distancia(pc[0], pc[1], ox, oy);
                if(dCuerpo <= nivel.alcance) cand.add(new Candidato(s.id, c.id, dSistema, dCuerpo));
            }
        }
        Collections.sort(cand, Comparator
            .comparingDouble((Candidato c) -> c.distanciaSistema)
            .thenComparing(c -> c.sistemaId)
            .thenComparingDouble(c -> c.distanciaCuerpo)
            .thenComparing(c -> c.cuerpoId));

        final List<Hallazgo> out = new ArrayList<Hallazgo>(cand.size());
        for(int k = 0; k < cand.size(); k++){
            final Candidato c = cand.get(k);
            out.add(new Hallazgo(c.sistemaId, c.cuerpoId, c.distanciaCuerpo, tiempo + (k + 1) * nivel.ticksPorCuerpo, k));
        }
        return out;
    }
}
