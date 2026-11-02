package mundoinfinito;

import arc.util.Time;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REGISTRO DE LO DETECTADO POR LOS RADARES (plan v8, P6). Los radares escriben aquí lo que detectan y el visor de planetas lo lee. Vive en
 * memoria durante la sesión: se vacía al abrir un mundo (cada mundo empieza sin detecciones). Un cuerpo detectado por varios radares
 * conserva su mejor orden. Clase de paquete (R4).
 */
final class Radares{
    /** Cuerpo detectado -> orden de detección (1 = el más cercano de su barrido). */
    static final Map<String, Integer> detectados = new LinkedHashMap<String, Integer>();
    private static final float VIGENCIA = 3600f;   // el estado global se vuelve a leer del disco como mucho cada minuto
    private static EstadoSistemas cache;
    private static float cacheT;

    private Radares(){}

    static void limpiar(){
        detectados.clear();
        cache = null;
    }

    static void registrar(String cuerpoId, int orden){
        final Integer o = detectados.get(cuerpoId);
        if(o == null || orden < o) detectados.put(cuerpoId, orden);
    }

    /** Ids detectados, del mejor orden al peor (empate por id). */
    static List<String> enOrden(){
        final List<String> ids = new ArrayList<String>(detectados.keySet());
        ids.sort((a, b) -> detectados.get(a).equals(detectados.get(b)) ? a.compareTo(b) : Integer.compare(detectados.get(a), detectados.get(b)));
        return ids;
    }

    /** Estado de todos los mundos guardados, con una vigencia corta para no leer el disco en cada barrido. */
    static EstadoSistemas global(){
        if(cache == null || Time.globalTime - cacheT > VIGENCIA){
            cache = MundoInfinitoMod.Sistemas.global();
            cacheT = Time.globalTime;
        }
        return cache;
    }
}
