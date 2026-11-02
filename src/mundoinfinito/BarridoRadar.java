package mundoinfinito;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * BARRIDO DE UN RADAR EN SU MUNDO (plan v8, P6). Lógica pura sobre {@link ApiRadar}: el radar está en un mundo, el origen del barrido es el
 * cuerpo de ese mundo, y el propio mundo no se detecta. Sin Arc: se prueba en CI sin el juego. Clase de paquete (R4).
 *
 * Cuándo se detecta cada cuerpo lo fija {@link ApiRadar} (k-ésimo = (k+1) · ticks por cuerpo del tier). Aquí el barrido se mide en ticks
 * desde que empieza, y {@link Barrido#detectadosEn} dice cuántos cuerpos se han detectado a esa altura.
 */
final class BarridoRadar{
    private BarridoRadar(){}

    /** Resultado de preparar un barrido: los hallazgos en orden y la duración total del barrido. */
    static final class Barrido{
        final List<ApiRadar.Hallazgo> hallazgos;
        final ApiRadar.Nivel nivel;
        final long tickInicio;

        Barrido(List<ApiRadar.Hallazgo> hallazgos, ApiRadar.Nivel nivel, long tickInicio){
            this.hallazgos = hallazgos;
            this.nivel = nivel;
            this.tickInicio = tickInicio;
        }

        /** Duración del barrido en ticks: un tramo por cuerpo; sin cuerpos, un tramo (para volver a mirar). Nunca menos de 1. */
        long duracion(){
            return Math.max(1L, Math.max(1, hallazgos.size()) * nivel.ticksPorCuerpo);
        }

        /** Cuántos cuerpos se han detectado cuando han pasado {@code transcurridos} ticks desde el inicio del barrido. */
        int detectadosEn(long transcurridos){
            int n = 0;
            for(ApiRadar.Hallazgo h : hallazgos) if(h.tickDeteccion - tickInicio <= transcurridos) n++;
            return n;
        }
    }

    /** Copia del estado sin el cuerpo dado (y sin los sistemas que se queden vacíos). */
    static EstadoSistemas sin(EstadoSistemas e, String idCuerpo){
        final EstadoSistemas out = new EstadoSistemas();
        for(EstadoSistemas.SistemaSolar s : e.sistemas()){
            final EstadoSistemas.SistemaSolar copia = new EstadoSistemas.SistemaSolar(s.id, s.estrella, s.p0x, s.p0y, s.vx, s.vy, s.tiempoRef);
            for(EstadoSistemas.Cuerpo c : s.cuerpos()) if(!c.id.equals(idCuerpo)) copia.anadirCuerpo(c);
            if(!copia.cuerpos().isEmpty()) out.anadir(copia);
        }
        return out;
    }

    /**
     * Prepara el barrido de un radar del tier dado, en el mundo {@code idMundo}, empezando en el tiempo del mundo {@code tiempo}.
     * Si el mundo no tiene cuerpo en el estado, el barrido sale vacío (el radar no falla).
     */
    static Barrido preparar(EstadoSistemas global, String idMundo, int tier, long tiempo, ToDoubleFunction<String> periodoPlaneta){
        final ApiRadar.Nivel nivel = ApiRadar.nivel(tier);
        final EstadoSistemas.Cuerpo propio = global == null ? null : global.buscarCuerpo(idMundo);
        final EstadoSistemas.SistemaSolar sistema = propio == null ? null : AsignacionSistemas.sistemaDe(global, idMundo);
        if(propio == null || sistema == null) return new Barrido(new ArrayList<ApiRadar.Hallazgo>(), nivel, tiempo);
        final double[] origen = ApiRadar.posicionCuerpo(sistema, propio, tiempo, periodoPlaneta);
        return new Barrido(ApiRadar.escanear(sin(global, idMundo), origen[0], origen[1], nivel, tiempo, periodoPlaneta), nivel, tiempo);
    }
}
