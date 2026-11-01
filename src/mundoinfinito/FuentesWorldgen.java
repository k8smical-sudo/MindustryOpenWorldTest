package mundoinfinito;

/**
 * FUENTES DEL PROPIO JUEGO (plan v5, D12-D13). Usan la misma API pública que cualquier addon, así que el juego se valida con sus propias reglas.
 *
 *  - {@link Bioma}: grupo de bioma de Erekir ({@link FenomenosBase#grupoBioma}). Prioridad {@link #PRIORIDAD}: cualquier addon con
 *    prioridad menor puede sustituirlo.
 *  - {@link MultiplicadorBioma}: multBioma / maxMult para los respiraderos: los ajustes de generación de siempre. Los mundos anteriores
 *    valen 1, así que su probabilidad no cambia.
 *  - {@link MenasRespiraderos}: solo en mundos de generación {@link MundoInfinitoMod#GEN_MENAS} o posterior. Con pocas menas cerca, la
 *    probabilidad baja hasta la mitad. Los mundos anteriores no tienen este efecto.
 *
 * VALORES SIN CALIBRAR: marcados con (calibrar). Se ajustan con las métricas de la sección C del plan.
 */
final class FuentesWorldgen{
    static final int PRIORIDAD = 1000;
    static final String ID_BIOMA = "mundo-infinito:bioma-erekir";
    static final String ID_MULTIPLICADOR = "mundo-infinito:respiraderos-bioma";
    static final String ID_MENAS = "mundo-infinito:respiraderos-menas";
    static final float MENAS_MINIMO = 0.5f;   // (calibrar) multiplicador sin menas cerca
    static final int MENAS_TOPE = 12;         // (calibrar) casillas con mena (de 49) para llegar al multiplicador completo

    private FuentesWorldgen(){}

    static void registrar(){
        ApiWorldgen.registrar(new Bioma());
        ApiWorldgen.registrar(new MultiplicadorBioma());
        ApiWorldgen.registrar(new MenasRespiraderos());
    }

    static final class Bioma implements ApiWorldgen.Fuente{
        @Override public String id(){ return ID_BIOMA; }
        @Override public int prioridad(){ return PRIORIDAD; }

        @Override
        public int bioma(ApiWorldgen.Lugar l){
            return l.dimension == MundoInfinitoMod.DIM_EREKIR ? FenomenosBase.grupoBioma(l.codigoPiso) : ApiWorldgen.BIOMA_NINGUNO;
        }
    }

    static final class MultiplicadorBioma implements ApiWorldgen.Fuente{
        @Override public String id(){ return ID_MULTIPLICADOR; }
        @Override public int prioridad(){ return PRIORIDAD; }

        @Override
        public float multiplicador(String fenomeno, int bioma, ApiWorldgen.Lugar l){
            if(!ApiWorldgen.FENOMENO_RESPIRADEROS.equals(fenomeno) || bioma < 0 || bioma > 4) return Float.NaN;
            return Nucleo.parametros().multBioma[bioma] / FenomenosBase.maxMult();
        }
    }

    static final class MenasRespiraderos implements ApiWorldgen.Fuente{
        @Override public String id(){ return ID_MENAS; }
        @Override public int prioridad(){ return PRIORIDAD; }

        @Override
        public float multiplicador(String fenomeno, int bioma, ApiWorldgen.Lugar l){
            if(!ApiWorldgen.FENOMENO_RESPIRADEROS.equals(fenomeno) || !Extensiones.generacionConMenas()) return Float.NaN;
            final float frac = Math.min(1f, l.menas() / (float)MENAS_TOPE);
            return MENAS_MINIMO + (1f - MENAS_MINIMO) * frac;
        }
    }
}
