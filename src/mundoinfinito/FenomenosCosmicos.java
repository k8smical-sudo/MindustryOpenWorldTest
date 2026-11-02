package mundoinfinito;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FENÓMENOS CÓSMICOS DEL PROPIO JUEGO (plan v9, P10). Usan la misma API pública que cualquier addon.
 *
 * Agujero negro: solo declara sus variables. No tiene efectos ni visual por defecto: lo que hace lo decide el dev o el modder de un addon. Está
 * apagado por defecto en todo mundo, así que sin addon no se ve nada.
 *
 * Valores por defecto sin calibrar: marcados con (calibrar).
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
            v.put("horizonte", 0.4);               // (calibrar) radio del horizonte, en unidades del sistema solar
            v.put("atraccion", 1.0);               // (calibrar) intensidad relativa de la atracción
            v.put("intensidad", 1.0);              // (calibrar) escala general de los efectos que el addon defina
            v.put("periodoCaos", 3600.0);          // (calibrar) ticks entre comprobaciones de incidente
            v.put("probabilidadIncidente", 0.02);  // (calibrar) probabilidad de incidente en cada comprobación
            return v;
        }
    }
}
