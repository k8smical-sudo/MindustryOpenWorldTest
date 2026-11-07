package mundoinfinito;

import arc.files.Fi;
import arc.util.Log;
import mundoinfinito.MundoInfinitoMod.Almacen;

/**
 * Guardado de la galaxia ({@link ApiGalaxia}) en {@code galaxia.txt}, en la raíz de los datos del mod: es de TODOS los mundos, no de uno. Clase de
 * paquete. Se carga al iniciar el mod y se guarda sola cada vez que cambia (crear o renombrar un grupo).
 */
final class GalaxiaDisco{
    private GalaxiaDisco(){}

    static Fi archivo(){
        return Almacen.raiz().child("galaxia.txt");
    }

    /** Lee la galaxia guardada (sin archivo: solo el grupo inicial) y engancha el guardado automático. */
    static void iniciar(){
        try{
            final Fi f = archivo();
            ApiGalaxia.desdeTexto(f.exists() ? f.readString("UTF-8") : "");
        }catch(Throwable t){
            Log.err("[MundoInfinito] La galaxia guardada no se pudo leer; se empieza con el grupo inicial.", t);
            ApiGalaxia.desdeTexto("");
        }
        ApiGalaxia.alCambiar = GalaxiaDisco::guardar;
    }

    static void guardar(){
        Disco.escribirYa(archivo().file(), ApiGalaxia.aTexto().getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    }
}
