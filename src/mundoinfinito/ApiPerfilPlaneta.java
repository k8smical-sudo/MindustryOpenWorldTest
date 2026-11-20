package mundoinfinito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Perfil de assets de un planeta: qué decoraciones usa, en qué suelos aparecen con qué densidad y en qué capa de generación
 * se aplican (cuándo). El planeta sabe qué assets va a usar: no están fijos en el código de la etapa de decoración.
 *
 * Reglas (para devs y modders):
 *  - Un planeta tiene un solo perfil: registrar uno para un planeta que ya lo tiene se rechaza, para no pisar a otro add-on.
 *  - Un asset aparece una sola vez por perfil.
 *  - La densidad es el % de casillas de ese suelo que llevan el asset. Los valores por defecto vienen de medir los mapas
 *    de campaña (core/assets/maps).
 */
public final class ApiPerfilPlaneta {
    private ApiPerfilPlaneta(){}

    /** Capas de generación, en orden de aplicación. */
    public static final int CAPA_SUELO = 0, CAPA_PAREDES = 1, CAPA_ARBOLIFICACION = 2, CAPA_RESPIRADEROS = 3, CAPA_MENAS = 4;

    /** Un asset del perfil: sus suelos, su densidad en cada uno, su capa y de dónde sale el dato. */
    public static final class Colocacion {
        public final String asset;
        public final Map<String, Float> suelos;
        public final int capa;
        public final String origen;

        public Colocacion(String asset, Map<String, Float> suelos, int capa, String origen){
            if(asset == null || asset.isEmpty()) throw new IllegalArgumentException("la colocación necesita un asset");
            this.asset = asset;
            this.suelos = Collections.unmodifiableMap(new LinkedHashMap<String, Float>(suelos));
            this.capa = capa;
            this.origen = origen;
        }
    }

    /** Perfil completo de un planeta. */
    public static final class Perfil {
        public final String planeta;
        public final List<Colocacion> colocaciones;

        public Perfil(String planeta, List<Colocacion> colocaciones){
            if(planeta == null || planeta.isEmpty()) throw new IllegalArgumentException("el perfil necesita un planeta");
            final java.util.Set<String> vistos = new java.util.HashSet<String>();
            for(Colocacion c : colocaciones){
                if(!vistos.add(c.asset)) throw new IllegalArgumentException("asset repetido en el perfil de " + planeta + ": " + c.asset);
            }
            this.planeta = planeta;
            this.colocaciones = Collections.unmodifiableList(new ArrayList<Colocacion>(colocaciones));
        }
    }

    private static final Map<String, Perfil> PERFILES = new LinkedHashMap<String, Perfil>();

    /** Registra el perfil de un planeta. Un planeta con perfil ya registrado se rechaza: no se pisa. */
    public static synchronized void registrar(Perfil p){
        if(p == null) throw new IllegalArgumentException("perfil nulo");
        if(PERFILES.containsKey(p.planeta)) throw new IllegalStateException("el planeta " + p.planeta + " ya tiene perfil; no se pisa");
        PERFILES.put(p.planeta, p);
    }

    /** Perfil de un planeta, o null si no tiene. */
    public static synchronized Perfil de(String planeta){
        return PERFILES.get(planeta);
    }

    /** Todos los perfiles registrados. */
    public static synchronized List<Perfil> todos(){
        return new ArrayList<Perfil>(PERFILES.values());
    }

    private static Map<String, Float> suelos(Object... pares){
        final Map<String, Float> m = new LinkedHashMap<String, Float>();
        for(int i = 0; i < pares.length; i += 2) m.put((String)pares[i], (Float)pares[i + 1]);
        return m;
    }

    private static final String CAMPANA = "campaña Serpulo (43 mapas leídos)";
    private static final String PROVISIONAL = "provisional (5 mapas de campaña de Erekir legibles)";

    static {
        registrar(new Perfil("serpulo", Arrays.asList(
            new Colocacion("spore-pine", suelos("moss", 15.0f, "spore-moss", 20.2f, "darksand", 1.2f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("snow-pine", suelos("snow", 1.8f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("sand-boulder", suelos("sand-floor", 0.2f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("basalt-boulder", suelos("darksand", 0.1f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("snow-boulder", suelos("snow", 0.1f, "ice", 0.1f, "salt", 0.1f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("shale-boulder", suelos("shale", 0.1f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("spore-cluster", suelos("moss", 0.1f, "spore-moss", 0.1f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("boulder", suelos("stone", 0.1f, "char", 0.3f, "crater-stone", 0.3f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("white-tree-dead", suelos("dacite", 0.1f), CAPA_ARBOLIFICACION, CAMPANA),
            new Colocacion("dacite-boulder", suelos("dacite", 0.3f), CAPA_ARBOLIFICACION, CAMPANA)
        )));
        registrar(new Perfil("erekir", Arrays.asList(
            new Colocacion("crystalline-boulder", suelos("crystalline-stone", 0.3f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("crystal-cluster", suelos("crystalline-stone", 0.2f, "crystal-floor", 0.1f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("vibrant-crystal-cluster", suelos("crystal-floor", 0.3f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("crystal-orbs", suelos("arkyic-stone", 0.3f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("arkyic-boulder", suelos("arkyic-stone", 0.2f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("beryllic-boulder", suelos("beryllic-stone", 0.2f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("red-stone-boulder", suelos("red-stone", 0.7f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("rhyolite-boulder", suelos("rhyolite", 0.1f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("carbon-boulder", suelos("carbon-stone", 0.1f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("yellow-stone-boulder", suelos("yellow-stone", 0.1f, "regolith", 0.2f), CAPA_ARBOLIFICACION, PROVISIONAL),
            new Colocacion("crystal-blocks", suelos("regolith", 0.2f), CAPA_ARBOLIFICACION, PROVISIONAL)
        )));
    }
}
