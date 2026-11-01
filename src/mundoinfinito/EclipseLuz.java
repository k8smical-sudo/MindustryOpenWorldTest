package mundoinfinito;

import arc.math.Mathf;
import mindustry.Vars;

/**
 * ECLIPSE POR DEFECTO EN TODO PLANETA (plan v4, D11): el aporte de oscuridad del eclipse de ApiLuz funciona en Erekir y en cualquier mundo que no tenga
 * el ciclo día/noche activo. Cuando el ciclo de Serpulo está activo, el eclipse ya entra dentro de su propio aporte (CicloDiaNoche), así que aquí se apaga
 * para no sumar dos veces. Es un aporte del canal de iluminación: sin eclipses, el factor es 1 y no cambia nada.
 */
final class EclipseLuz{
    static final String ID = "mundo-infinito:eclipse";

    private static boolean registrado;

    private EclipseLuz(){}

    static void registrar(){
        if(registrado) return;
        registrado = true;
        VisualAmbiente.global().registrar("mundo-infinito", ID, 0, 1f);
    }

    static boolean cicloActivo(MundoInfinitoMod.Meta m){
        return m.dimensional() && m.dimGen() == MundoInfinitoMod.DIM_SERPULO && m.perfil.bool(CicloDiaNoche.ID, false);
    }

    /** Cada fotograma. Sin mundo abierto, o con el ciclo activo, el aporte se quita. */
    static void tick(){
        if(Vars.state == null || !Vars.state.isGame()){
            VisualAmbiente.global().quitarOscuridad(ID);
            return;
        }
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null || m.perfil == null || cicloActivo(m)){
            VisualAmbiente.global().quitarOscuridad(ID);
            return;
        }
        final float factor = ApiLuz.factorPlaneta(ApiPlaneta.planetaDeMundo(m));
        VisualAmbiente.global().fijarOscuridad(ID, 1f - Mathf.clamp(factor));
    }
}
