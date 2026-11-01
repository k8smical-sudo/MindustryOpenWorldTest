package mundoinfinito;

import arc.math.Mathf;
import mindustry.Vars;

final class CicloDiaNoche{
    static final String ID = "mundo-infinito:ciclo-dia-noche";
    static final float DIA_TICKS = 60f * 60f * 10f;
    static final float SRC_DESDE = 0f, SRC_HASTA = 0.8f, DST_DESDE = 0.2f, DST_HASTA = 1f;

    private static boolean registrado;

    private CicloDiaNoche(){}

    static void registrar(){
        if(registrado) return;
        registrado = true;
        VisualAmbiente.global().registrar("mundo-infinito", ID, 0, 1f);
    }

    static float luz(float tick){
        return luz(tick, DIA_TICKS);
    }

    /** Luz con el periodo de un día del planeta (su rotación). */
    static float luz(float tick, float periodo){
        final double fase = (tick % periodo) / periodo;
        return (float)(0.5 + 0.5 * Math.cos(2 * Math.PI * fase));
    }

    static float alfa(float luz){
        return Mathf.clamp(Mathf.map(luz, SRC_DESDE, SRC_HASTA, DST_DESDE, DST_HASTA));
    }

    /** Cada fotograma. Es un aporte de oscuridad al canal de iluminación: no escribe en las reglas del mundo. */
    static void tick(){
        if(Vars.state == null || !Vars.state.isGame()){
            VisualAmbiente.global().quitarOscuridad(ID);
            return;
        }
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        final boolean activo = m != null && m.dimensional() && m.dimGen() == MundoInfinitoMod.DIM_SERPULO && m.perfil.bool(ID, false);
        if(!activo){
            VisualAmbiente.global().quitarOscuridad(ID);
            return;
        }
        final float alpha = alfa(luz((float)Vars.state.tick, ApiPlaneta.rotacionMundo()));
        final String planeta = ApiPlaneta.planetaDeMundo(m);
        final float luzEf = Mathf.clamp(alpha * ApiLuz.factorPlaneta(planeta));   // eclipse por defecto y modificadores (plan v4, D11)
        VisualAmbiente.global().fijarOscuridad(ID, 1f - luzEf);
    }
}
