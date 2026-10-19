package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

/** Recorre las 1024 casillas de un chunk una sola vez aplicando las etapas del terreno base en el orden recibido (el canónico). */
final class PipelineBase{
    private PipelineBase(){}

    /** @return casillas evaluadas (las que están dentro del borde del mundo). */
    static int ejecutar(ContextoAporte c, AporteTerreno o, EtapaBase[] etapas){
        final int sd = Muestreo.semDim(c.semilla, c.dimension);
        final CeldaBase ce = o.celda;
        ce.s = sd;
        ce.sp = Muestreo.spawn(sd, c.dimension);   // el spawn del mundo está en el origen virtual (0,0)
        ce.aux = o.aux;
        ce.nueva = Extensiones.generacionNueva();
        ce.par = c.params;
        o.aux[11] = 0f;   // caché de estructura de la celda: vacía al empezar el chunk
        final int T = AporteTerreno.T, lim = MundoInfinitoMod.LIMITE_MUNDO, ne = etapas.length;
        int unidades = 0;
        for(int ly = 0; ly < T; ly++){
            for(int lx = 0; lx < T; lx++){
                final int i = ly * T + lx, vx = c.x0 + lx, vy = c.y0 + ly;
                if(vx < -lim || vx > lim || vy < -lim || vy > lim){
                    // más allá del borde del mundo: lo resuelve el consumidor (muro del borde)
                    o.piso[i] = AporteTerreno.FUERA;
                    o.mena[i] = 0;
                    o.muro[i] = 0;
                    o.prop[i] = 0;
                    continue;
                }
                ce.iniciar(vx, vy);
                for(int k = 0; k < ne; k++) etapas[k].celda(ce);
                o.piso[i] = ce.p;
                o.mena[i] = ce.mena;
                o.muro[i] = ce.muro;
                o.prop[i] = ce.prop;
                unidades++;
            }
        }
        return unidades;
    }
}
