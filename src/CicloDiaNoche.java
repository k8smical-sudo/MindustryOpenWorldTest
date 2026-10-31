package mundoinfinito;

import arc.math.Mathf;
import mindustry.Vars;

/**
 * CICLO DÍA/NOCHE de Serpulo, como en campaña.
 *
 * Mindustry (Universe.update) calcula la luz del sector según su posición sobre la esfera del planeta y, a partir de ella, fija la oscuridad del
 * ambiente. La mod no tiene sectores ni esfera: su mapa es plano. Por eso aquí la luz sale del tiempo del mundo (una curva coseno de un día completo),
 * y se transforma con la MISMA curva de Universe: alpha = clamp(map(luz, src, dst)), oscuridad = 1 - alpha, y la iluminación solo se activa cuando
 * alpha no es 1 (de día, sin luz de noche).
 *
 * Solo se aplica a la dimensión de Serpulo de un mundo con el ajuste activado. Los mundos anteriores no lo tienen (la clave no está en su perfil) y no cambian.
 * Es visual: no entra en la generación ni en la simulación.
 */
final class CicloDiaNoche{
    static final String ID = "mundo-infinito:ciclo-dia-noche";
    /** Duración de un día completo, en ticks (60 por segundo): 10 minutos. */
    static final float DIA_TICKS = 60f * 60f * 10f;
    /** Curva de luz de Serpulo: los valores por defecto de Planet (Serpulo no los sobreescribe; Erekir usa otros). */
    static final float SRC_DESDE = 0f, SRC_HASTA = 0.8f, DST_DESDE = 0.2f, DST_HASTA = 1f;

    /** Si el ciclo estaba aplicado, para restaurar el ambiente original al apagarlo. */
    private static boolean aplicado;
    private static float ambienteOriginal = 1f;

    private CicloDiaNoche(){}

    /** Luz en [0, 1]: 1 al empezar el día (mediodía), 0 a mitad del día (medianoche). */
    static float luz(float tick){
        final double fase = (tick % DIA_TICKS) / DIA_TICKS;
        return (float)(0.5 + 0.5 * Math.cos(2 * Math.PI * fase));
    }

    /** alpha como en Universe.update: 1 = día completo, menor = más oscuridad. */
    static float alfa(float luz){
        return Mathf.clamp(Mathf.map(luz, SRC_DESDE, SRC_HASTA, DST_DESDE, DST_HASTA));
    }

    /** Se llama cada fotograma del juego (Trigger.update). */
    static void tick(){
        if(Vars.state == null || !Vars.state.isGame()) return;
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        final boolean activo = m != null && m.dimensional() && m.dimGen() == MundoInfinitoMod.DIM_SERPULO && m.perfil.bool(ID, false);
        final mindustry.game.Rules r = Vars.state.rules;
        if(!activo){
            if(aplicado){
                aplicado = false;
                r.ambientLight.a = ambienteOriginal;
            }
            r.lighting = false;
            return;
        }
        if(!aplicado){
            aplicado = true;
            ambienteOriginal = r.ambientLight.a;
        }
        final float alpha = alfa(luz((float)Vars.state.tick));
        r.ambientLight.a = 1f - alpha;            // igual que Universe: oscuridad = 1 - alpha
        r.lighting = !Mathf.equal(alpha, 1f);     // de día no hay iluminación
    }
}
