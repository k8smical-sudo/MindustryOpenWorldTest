package mundoinfinito;

import arc.util.Log;
import arc.util.serialization.Jval;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * PERFIL DE UN MUNDO (fase 4): los valores de todos sus ajustes (reglas de juego y parámetros de generación), guardados con el mundo.
 *
 *  - Lo que no está en el perfil usa el valor por defecto del ajuste. Los mundos antiguos no tienen perfil: se abren con los valores por
 *    defecto, sin error. Cambiar un valor por defecto cambia los mundos que no lo guardaron, así que si afecta al terreno exige subir GEN_ACTUAL.
 *  - Todo valor que entra se valida contra la definición del ajuste: fuera de rango se recorta y queda registrado en {@link RegistroAjustes}.
 *  - Un id que ningún mod registra ahora (mod ausente, o de otra versión) se conserva tal cual para no perder datos al guardar.
 *  - Al crear o abrir un mundo el perfil se CONGELA: los parámetros de generación ya no cambian (cambiarlos abriría costuras en el terreno).
 *
 * Formato en el archivo del mundo: "ajustes": {id: valor} (booleano, entero o decimal según el tipo) y, como espejo para versiones anteriores
 * del mod, "gamerules": {id: booleano}.
 */
final class PerfilMundo{
    private final TreeMap<String, Double> valores = new TreeMap<>();
    /** Ids desconocidos que llegaron como booleanos: se vuelven a escribir como booleanos. */
    private final TreeSet<String> comoBool = new TreeSet<>();
    private boolean congelado;

    // ================================================================== lectura

    /** Valor válido del ajuste en este mundo (el guardado, o el por defecto). */
    double valor(AjusteMundo a){
        final Double v = valores.get(a.id);
        return v == null ? a.defecto : a.validar(v.doubleValue());
    }

    /** Valor de un ajuste booleano por id (sirve también para ids no registrados). */
    boolean bool(String id, boolean defecto){
        final Double v = valores.get(id);
        return v == null ? defecto : v.doubleValue() != 0.0;
    }

    boolean tiene(String id){
        return valores.containsKey(id);
    }

    // ================================================================== escritura

    /**
     * Fija un valor, validado contra la definición si el ajuste está registrado.
     * @return false si el perfil está congelado y el ajuste es de generación (no se cambia), o el id no es válido.
     */
    boolean fijar(String id, double v){
        if(id == null || id.isEmpty()) return false;
        final AjusteMundo a = RegistroAjustes.get(id);
        if(a != null && congelado && a.ambito == AjusteMundo.Ambito.GENERACION){
            Log.warn("[MundoInfinito] El ajuste de generación \"@\" no se puede cambiar: el perfil de este mundo ya está fijado.", id);
            return false;
        }
        guardar(id, v, a);
        return true;
    }

    private void guardar(String id, double v, AjusteMundo a){
        if(a == null){
            valores.put(id, Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : v);   // desconocido: se conserva, no se valida
            return;
        }
        final double ok = a.validar(v);
        if(a.fueraDeRango(v)) RegistroAjustes.anotarRecorte(id, v, ok);
        valores.put(id, ok);
    }

    /** Escribe en el perfil el valor vigente de cada parámetro de generación que aún no esté, y congela. Se llama al CREAR un mundo. */
    void sellarGeneracion(){
        for(AjusteMundo a : RegistroAjustes.todos(AjusteMundo.Ambito.GENERACION)){
            if(!valores.containsKey(a.id)) valores.put(a.id, a.defecto);
        }
        congelado = true;
    }

    void congelar(){
        congelado = true;
    }

    boolean congelado(){
        return congelado;
    }

    // ================================================================== generación

    /** Los parámetros que lee la generación, con los valores de este mundo. */
    ParametrosGeneracion parametros(){
        return new ParametrosGeneracion(
            (float)valorDe(ParametrosGeneracion.ID_ALTURA_SERPULO, ParametrosGeneracion.DEFECTO.estirarAlturaSerpulo),
            (float)valorDe(ParametrosGeneracion.ID_TEMPERATURA_SERPULO, ParametrosGeneracion.DEFECTO.estirarTemperaturaSerpulo),
            (float)valorDe(ParametrosGeneracion.ID_HUMEDAD_SERPULO, ParametrosGeneracion.DEFECTO.estirarHumedadSerpulo));
    }

    private double valorDe(String id, double respaldo){
        final AjusteMundo a = RegistroAjustes.get(id);
        return a == null ? respaldo : valor(a);
    }

    // ================================================================== persistencia

    /** "ajustes": todos los valores, con el tipo de su definición. */
    Jval aJson(){
        final Jval o = Jval.newObject();
        for(Map.Entry<String, Double> e : valores.entrySet()){
            final String id = e.getKey();
            final double v = e.getValue().doubleValue();
            final AjusteMundo a = RegistroAjustes.get(id);
            final AjusteMundo.Tipo t = a != null ? a.tipo : (comoBool.contains(id) ? AjusteMundo.Tipo.BOOL : AjusteMundo.Tipo.DECIMAL);
            if(t == AjusteMundo.Tipo.BOOL) o.put(id, v != 0.0);
            else if(t == AjusteMundo.Tipo.ENTERO) o.put(id, Integer.valueOf((int)Math.rint(v)));
            else o.put(id, Double.valueOf(v));
        }
        return o;
    }

    /** "gamerules": espejo de los booleanos, para que una versión anterior del mod siga leyendo el mundo. */
    Jval aJsonReglas(){
        final Jval o = Jval.newObject();
        for(Map.Entry<String, Double> e : valores.entrySet()){
            final String id = e.getKey();
            final AjusteMundo a = RegistroAjustes.get(id);
            final boolean esBool = a != null ? a.tipo == AjusteMundo.Tipo.BOOL && a.ambito == AjusteMundo.Ambito.JUEGO : comoBool.contains(id);
            if(esBool) o.put(id, e.getValue().doubleValue() != 0.0);
        }
        return o;
    }

    /**
     * Lee el perfil del archivo de un mundo ({@code j} = el JSON completo del mundo). Lee primero el formato antiguo ("gamerules") y después
     * "ajustes" (que manda). Un mundo sin ninguno de los dos queda con el perfil vacío = valores por defecto. Nunca lanza: lo que no se
     * entiende se ignora con un aviso. El perfil resultante queda congelado.
     */
    static PerfilMundo desde(Jval j){
        final PerfilMundo p = new PerfilMundo();
        try{
            if(j != null){
                if(j.has("gamerules") && j.get("gamerules").isObject()) p.cargar(j.get("gamerules").asObject());
                if(j.has("ajustes") && j.get("ajustes").isObject()) p.cargar(j.get("ajustes").asObject());
            }
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo leer el perfil del mundo; se usan los valores por defecto.", t);
        }
        p.congelar();
        return p;
    }

    private void cargar(Jval.JsonMap mp){
        for(int i = 0; i < mp.size; i++){
            final String id = mp.getKeyAt(i);
            final Jval v = mp.getValueAt(i);
            try{
                final AjusteMundo a = RegistroAjustes.get(id);
                if(v.isBoolean()){
                    guardar(id, v.asBool() ? 1.0 : 0.0, a);
                    if(a == null) comoBool.add(id);
                }else if(v.isNumber()){
                    guardar(id, v.asDouble(), a);
                }else{
                    Log.warn("[MundoInfinito] Ajuste \"@\" del mundo con un valor que no se entiende; se ignora.", id);
                }
            }catch(Throwable t){
                Log.warn("[MundoInfinito] Ajuste \"@\" del mundo ilegible; se ignora.", id);
            }
        }
    }
}
