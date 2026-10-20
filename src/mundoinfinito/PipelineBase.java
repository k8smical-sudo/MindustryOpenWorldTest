package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

import java.util.Arrays;

/**
 * Recorre las 1024 casillas de un chunk una sola vez aplicando las etapas del terreno base en el orden recibido (el canónico).
 *
 * Fase 7, presupuestos: cada etapa tiene un presupuesto de unidades de trabajo por chunk (su {@link Contribucion#presupuesto()} escalado por el
 * porcentaje del perfil del mundo). Una unidad es una casilla. Si el chunk pide más unidades que las permitidas se aplica la regla de degradación de
 * la etapa ({@link ReglaDegradacion}), siempre por unidades contadas y en el mismo orden, y el chunk SE GENERA igual. Si ninguna etapa excede, el
 * recorrido es el de siempre, sin ningún cálculo extra por casilla.
 */
final class PipelineBase{
    private static final int NORMAL = 0, RECORTE = 1, REDUCIDA = 2, OMITIDA = 3;

    private PipelineBase(){}

    /** Qué ocurrió con el presupuesto de cada etapa en el último chunk evaluado en este hilo. Reutilizado: solo vale hasta la siguiente evaluación. */
    static final class Informe{
        int n;
        /** ¿Alguna etapa se degradó en este chunk? */
        boolean alguna;
        int[] pedidas = new int[16], permitidas = new int[16], usadas = new int[16], modo = new int[16], paso = new int[16];
        boolean[] degradada = new boolean[16];
        ReglaDegradacion[] regla = new ReglaDegradacion[16];

        void preparar(int m){
            if(m > pedidas.length){
                final int t = Math.max(m, pedidas.length * 2);
                pedidas = Arrays.copyOf(pedidas, t);
                permitidas = Arrays.copyOf(permitidas, t);
                usadas = Arrays.copyOf(usadas, t);
                modo = Arrays.copyOf(modo, t);
                paso = Arrays.copyOf(paso, t);
                degradada = Arrays.copyOf(degradada, t);
                regla = Arrays.copyOf(regla, t);
            }
            n = m;
            alguna = false;
        }
    }

    /** Casillas del chunk de origen (x0, y0) que están dentro del borde del mundo: las unidades que pide una etapa por casilla. */
    static int casillasEnMundo(int x0, int y0){
        final int T = AporteTerreno.T, lim = MundoInfinitoMod.LIMITE_MUNDO;
        final int ax = Math.max(0, Math.min(T, Math.min(x0 + T - 1, lim) - Math.max(x0, -lim) + 1));
        final int ay = Math.max(0, Math.min(T, Math.min(y0 + T - 1, lim) - Math.max(y0, -lim) + 1));
        return ax * ay;
    }

    /**
     * Paso k (potencia de 2) de la retícula de la reducción de resolución: el menor cuya retícula, en este chunk, cabe en {@code permitidas}.
     * 0 si ni la más gruesa cabe (la etapa se omite).
     */
    static int pasoReduccion(int x0, int y0, int permitidas){
        final int T = AporteTerreno.T;
        for(int k = 2; k <= T; k <<= 1){
            final long nx = Math.floorDiv(x0 + T - 1, k) - Math.floorDiv(x0 - 1, k), ny = Math.floorDiv(y0 + T - 1, k) - Math.floorDiv(y0 - 1, k);
            if(nx * ny <= permitidas) return k;
        }
        return 0;
    }

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

        // presupuesto de cada etapa en este chunk
        final Informe inf = o.informe;
        inf.preparar(ne);
        final int pct = c.params == null ? 100 : c.params.presupuestoTerrenoPct;
        final int pedidas = casillasEnMundo(c.x0, c.y0);
        for(int k = 0; k < ne; k++){
            final int permitidas = (int)((long)etapas[k].presupuesto().unidadesChunk * pct / 100L);
            inf.pedidas[k] = pedidas;
            inf.permitidas[k] = permitidas;
            inf.usadas[k] = 0;
            inf.modo[k] = NORMAL;
            inf.paso[k] = 1;
            inf.degradada[k] = false;
            inf.regla[k] = null;
            if(pedidas > permitidas){
                final ReglaDegradacion r = etapas[k].reglaDegradacion();
                inf.degradada[k] = true;
                inf.regla[k] = r;
                inf.alguna = true;
                if(r == ReglaDegradacion.OMISION){
                    inf.modo[k] = OMITIDA;
                }else if(r == ReglaDegradacion.REDUCCION_DE_RESOLUCION){
                    final int p = pasoReduccion(c.x0, c.y0, permitidas);
                    if(p == 0){
                        inf.modo[k] = OMITIDA;
                    }else{
                        inf.modo[k] = REDUCIDA;
                        inf.paso[k] = p;
                    }
                }else{
                    inf.modo[k] = RECORTE;
                }
            }
        }
        final boolean degradado = inf.alguna;

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
                if(!degradado){
                    for(int k = 0; k < ne; k++) etapas[k].celda(ce);   // el recorrido de siempre
                }else{
                    for(int k = 0; k < ne; k++){
                        switch(inf.modo[k]){
                            case NORMAL:
                                etapas[k].celda(ce);
                                break;
                            case RECORTE:
                                if(inf.usadas[k] < inf.permitidas[k]){
                                    inf.usadas[k]++;
                                    etapas[k].celda(ce);
                                }
                                break;
                            case REDUCIDA:{
                                final int p = inf.paso[k];
                                if(Math.floorMod(vx, p) == 0 && Math.floorMod(vy, p) == 0){
                                    inf.usadas[k]++;
                                    etapas[k].celda(ce);
                                }
                                break;
                            }
                            default:
                                break;   // OMITIDA
                        }
                    }
                }
                o.piso[i] = ce.p;
                o.mena[i] = ce.mena;
                o.muro[i] = ce.muro;
                o.prop[i] = ce.prop;
                unidades++;
            }
        }
        for(int k = 0; k < ne; k++) if(inf.modo[k] == NORMAL) inf.usadas[k] = unidades;
        return unidades;
    }
}
