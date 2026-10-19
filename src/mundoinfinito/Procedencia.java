package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Paleta;

import java.util.TreeMap;

/**
 * PROCEDENCIA POR MACROCÉLULA (fase 6): de quién es lo que hay en una macrocélula (64x64 casillas). Es una consulta pura de diagnóstico:
 * recalcula la respuesta, no guarda nada, y da lo mismo desde cualquier hilo.
 *  - terreno: el id de la contribución dueña del terreno (el terreno base o la contribución que ganó la disputa del reparto).
 *  - fenómenos: cuántos fenómenos de cada generador quedan dentro, ya con la distancia mínima global aplicada.
 */
final class Procedencia{
    /** Lado de la macrocélula en casillas. */
    static final int LADO = Reparto.MACRO_CHUNKS * ContextoTerreno.TAM;

    private Procedencia(){}

    static final class Macro{
        final int mcx, mcy;
        final String terreno;
        /** id del generador -> fenómenos suyos dentro de la macrocélula (solo los que tienen alguno), por orden alfabético de id. */
        final TreeMap<String, Integer> fenomenos;

        Macro(int mcx, int mcy, String terreno, TreeMap<String, Integer> fenomenos){
            this.mcx = mcx;
            this.mcy = mcy;
            this.terreno = terreno;
            this.fenomenos = fenomenos;
        }

        int totalFenomenos(){
            int t = 0;
            for(int n : fenomenos.values()) t += n;
            return t;
        }

        @Override
        public String toString(){
            return "macrocélula (" + mcx + ", " + mcy + "): terreno de " + terreno + ", fenómenos " + fenomenos;
        }
    }

    /**
     * @param fs los generadores de fenómenos registrados (cualquier orden)
     * @param pal paleta del mundo (la usan los contextos; en pruebas puede ser null)
     * @param distanciaGlobal distancia mínima global entre fenómenos (0 = sin ella)
     */
    static Macro consultar(GeneradorFenomeno[] fs, int semilla, int dim, int mcx, int mcy, Paleta pal, int distanciaGlobal){
        final int x0 = mcx * LADO, y0 = mcy * LADO, x1 = x0 + LADO - 1, y1 = y0 + LADO - 1;
        final TreeMap<String, Integer> f = new TreeMap<String, Integer>();
        if(fs != null){
            for(GeneradorFenomeno g : fs){
                if(!g.aplicaA(dim)) continue;
                final ContextoFenomeno c = new ContextoFenomeno(pal, semilla, dim, g.id());
                final int n = Fenomenos.puntosFinales(g, fs, c, x0, y0, x1, y1, distanciaGlobal).length;
                if(n > 0) f.put(g.id(), n);
            }
        }
        return new Macro(mcx, mcy, Nucleo.duenoTerreno(semilla, dim, mcx, mcy), f);
    }
}
