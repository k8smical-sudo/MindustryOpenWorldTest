package mundoinfinito;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FENÓMENOS CÓSMICOS DEL PROPIO JUEGO (plan v9, P10). Usan la misma API pública que cualquier addon.
 *
 * Agujero negro: solo declara sus variables. No tiene efectos ni visual por defecto: lo que hace lo decide el dev o el modder de un addon. Está
 * apagado por defecto en todo mundo, así que sin addon no se ve nada.
 *
 * Valores por defecto: en {@link Calibracion}.
 */
final class FenomenosCosmicos{
    static final String AGUJERO_NEGRO = "mundo-infinito:agujero-negro";

    private FenomenosCosmicos(){}

    static void registrar(){
        ApiFenomenoCosmico.registrar(new AgujeroNegro());
    }

    static final class AgujeroNegro implements ApiFenomenoCosmico.Fenomeno{
        @Override public String id(){ return AGUJERO_NEGRO; }

        @Override
        public Map<String, Double> variables(){
            final Map<String, Double> v = new LinkedHashMap<String, Double>();
            v.put("horizonte", Calibracion.AGUJERO_HORIZONTE);   // radio del horizonte, en unidades del sistema solar
            v.put("atraccion", Calibracion.AGUJERO_ATRACCION);   // intensidad relativa de la atracción
            v.put("intensidad", Calibracion.AGUJERO_INTENSIDAD);   // escala general de los efectos que el addon defina
            v.put("periodoCaos", Calibracion.AGUJERO_PERIODO_CAOS);   // ticks entre comprobaciones de incidente
            v.put("probabilidadIncidente", Calibracion.AGUJERO_PROBABILIDAD_INCIDENTE);   // probabilidad de incidente en cada comprobación
            return v;
        }
    }
}
