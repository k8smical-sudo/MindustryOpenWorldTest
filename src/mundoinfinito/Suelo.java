package mundoinfinito;

import arc.Core;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * REPARACIÓN DEL SUELO (render). Tras reescribir cientos de chunks de golpe, la caché de geometría del suelo del renderizador
 * puede quedar con sprites de más (el mismo suelo dibujado varias veces dentro de una casilla). Esta clase le pide al
 * renderizador que descarte esa caché y la reconstruya solo con lo visible.
 *
 * Todo por reflexión y a prueba de fallos: si esta versión del juego no tiene el método, se anota una vez y no pasa nada.
 * Se ejecuta (1) al terminar una reubicación de la ventana, (2) tras un tirón largo detectado, y (3) con el botón
 * "Reparar texturas" de Ajustes -> OpenWorld.
 */
final class Suelo{
    private static java.lang.reflect.Method metodo;
    private static Object suelo;
    private static boolean sinMetodo;
    private static float ultimo = -999999f;
    private static boolean pendiente;

    private Suelo(){}

    private static Object campo(Object o, String nombre) throws Exception{
        for(Class<?> c = o.getClass(); c != null; c = c.getSuperclass()){
            try{
                java.lang.reflect.Field f = c.getDeclaredField(nombre);
                f.setAccessible(true);
                return f.get(o);
            }catch(NoSuchFieldException ignored){}
        }
        throw new NoSuchFieldException(nombre);
    }

    /** Descarta la caché de suelo del renderizador; se reconstruye sola en los siguientes fotogramas. */
    static void sanear(){
        if(Vars.headless || Vars.renderer == null || sinMetodo) return;
        try{
            if(metodo == null){
                Object bloques = campo(Vars.renderer, "blocks");
                suelo = campo(bloques, "floor");
                metodo = suelo.getClass().getDeclaredMethod("clearTiles");
                metodo.setAccessible(true);
            }
            metodo.invoke(suelo);
            ultimo = Time.time;
            pendiente = false;
        }catch(Throwable t){
            sinMetodo = true;
            Log.warn("[MundoInfinito] No se pudo reiniciar la caché de suelo del renderizador: @", t.toString());
        }
    }

    /** Cada fotograma: si hubo un tirón largo, repara el suelo en cuanto no se esté reubicando (y no más de una vez por 90 s). */
    static void vigilarTirones(){
        if(!Streamer.activo || Vars.headless || Core.graphics == null) return;
        if(Core.graphics.getDeltaTime() > 0.6f) pendiente = true;
        if(pendiente && Time.time - ultimo > 5400f && Mundos.rebase == null && !Mundos.viajando) sanear();
    }
}
