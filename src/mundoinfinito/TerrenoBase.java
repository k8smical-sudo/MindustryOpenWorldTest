package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

/**
 * El terreno base del mundo, envuelto TAL CUAL como una contribución (fase 2). Mismas llamadas, mismo orden de casillas, mismo spawn y
 * mismo aux[11] a cero por chunk que el bucle que tenía {@code ChunkData.generar}: el corpus de regresión debe dar los mismos hashes.
 * La descomposición en contribuciones independientes (suelo, menas, muros...) es la fase 3.
 */
final class TerrenoBase implements ContribucionTerreno{
    static final String ID = "mundo-infinito:terreno-base";

    private static final PresupuestoTrabajo PRESUPUESTO = new PresupuestoTrabajo(AporteTerreno.N, 0, 24 * 1024, "casilla muestreada");

    @Override
    public String id(){
        return ID;
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
    public int prioridad(){
        return -1000;
    }

    @Override
    public PresupuestoTrabajo presupuesto(){
        return PRESUPUESTO;
    }

    @Override
    public int aportar(ContextoAporte c, AporteTerreno o){
        final int sd = Muestreo.semDim(c.semilla, c.dimension);
        final Muestreo.Spawn sp = Muestreo.spawn(sd, c.dimension);   // el spawn del mundo está en el origen virtual (0,0)
        final float[] aux = o.aux;
        final int[] out = o.out;
        aux[11] = 0f;   // caché de estructura de la celda: vacía al empezar el chunk
        final int T = AporteTerreno.T, lim = MundoInfinitoMod.LIMITE_MUNDO;
        int unidades = 0;
        for(int ly = 0; ly < T; ly++){
            for(int lx = 0; lx < T; lx++){
                final int i = ly * T + lx, vx = c.x0 + lx, vy = c.y0 + ly;
                if(vx < -lim || vx > lim || vy < -lim || vy > lim){
                    // más allá del borde del mundo (±LIMITE_MUNDO): lo resuelve el consumidor (muro del borde)
                    o.piso[i] = AporteTerreno.FUERA;
                    o.mena[i] = 0;
                    o.muro[i] = 0;
                    o.prop[i] = 0;
                    continue;
                }
                Muestreo.muestrear(sd, vx, vy, sp, aux, out);
                o.piso[i] = out[0];
                o.mena[i] = out[1];
                o.muro[i] = out[2];
                o.prop[i] = out[3];
                unidades++;
            }
        }
        return unidades;
    }
}
