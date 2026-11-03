package mundoinfinito;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.util.Time;
import mindustry.Vars;

import java.util.HashMap;
import java.util.Map;

/**
 * VISUAL 2D DE LAS COLISIONES (plan v9, P10). Un pulso rojo alrededor de los cuerpos implicados, en el visor 2D de planetas, y un efecto de
 * {@link ApiVisual} ({@link #EFECTO}) que cualquier addon puede emitir en el mundo con {@link ApiVisual#emitirEfecto}. Solo es visual: no cambia
 * nada de la simulación ni del mundo. Clase de paquete (R4).
 *
 * El pulso lo pide {@link ApiColision#iniciar}; el visor lo dibuja con {@link #dibujarPulso} dentro de su {@code draw()}, donde ya tiene la posición
 * del cuerpo en pantalla.
 */
final class ColisionVisual{
    /** Id del efecto registrado en la API visual. Su color y su posición los fija quien lo emite. */
    static final String EFECTO = "mundo-infinito:colision-pulso";

    /** Cuerpo -> Time.globalTime en que empezó su pulso. */
    private static final Map<String, Float> inicio = new HashMap<String, Float>();

    private ColisionVisual(){}

    /** Registra el efecto de la API visual. Llamar tras {@code VisualArc.registrar()}. */
    static void registrar(){
        ApiVisual.registrarEfecto("mundo-infinito", EFECTO, VisualCapa.INTERFAZ, 0, Calibracion.COLISION_PULSO_TICKS, 1, false, e -> {
            final float f = e.fin();
            Draw.color(e.r(), e.g(), e.b(), e.a() * (1f - f));
            Lines.stroke(3f * (1f - f) + 0.5f);
            Lines.circle(e.x(), e.y(), 8f + 40f * f);
            Draw.reset();
        });
    }

    /** Empieza el pulso rojo de dos cuerpos. Sin pantalla (servidor sin interfaz) no hace nada. */
    static void pulso(String cuerpoA, String cuerpoB){
        if(Vars.headless) return;
        inicio.put(cuerpoA, Time.globalTime);
        inicio.put(cuerpoB, Time.globalTime);
    }

    /** ¿Tiene este cuerpo un pulso en curso? */
    static boolean activo(String cuerpo){
        final Float t0 = inicio.get(cuerpo);
        return t0 != null && Time.globalTime - t0 <= Calibracion.COLISION_PULSO_TICKS;
    }

    /** Dibuja el pulso de un cuerpo en (x, y) de pantalla, con el radio del cuerpo. Quita los pulsos terminados. */
    static void dibujarPulso(String cuerpo, float x, float y, float radio){
        final Float t0 = inicio.get(cuerpo);
        if(t0 == null) return;
        final float p = (Time.globalTime - t0) / Calibracion.COLISION_PULSO_TICKS;
        if(p >= 1f){
            inicio.remove(cuerpo);
            return;
        }
        // dos ondas por pulso, desfasadas: se nota más en un visor pequeño
        for(int k = 0; k < 2; k++){
            final float q = (p * 2f + k * 0.5f) % 1f;
            Draw.color(Color.scarlet, 1f - p);
            Lines.stroke(3f * (1f - q) + 0.5f);
            Lines.circle(x, y, radio + 3f + q * radio * 3f);
        }
        Draw.reset();
    }

    /** Olvida todos los pulsos (al abrir otro mundo). */
    static void limpiar(){
        inicio.clear();
    }
}
