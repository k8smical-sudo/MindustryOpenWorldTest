package mundoinfinito;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.type.Item;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tránsito orbital entre MUNDOS enlazados (logística fraccionada). Antes unía las dos "dimensiones" de un mundo; desde el plan v9 las dimensiones ya
 * no existen y cada mundo es independiente, así que la logística va de un mundo a otro mundo enlazado ({@link ApiViaje.Enlaces}).
 *
 * Los silos NO teletransportan nada: lo que meten en el silo del mundo A sale en DRONES invisibles (30 unidades por viaje, un solo tipo de ítem por
 * dron). Cada dron tarda VIAJE_TICKS en llegar y solo hay MAX_DRONES en vuelo hacia cada mundo, así que el caudal máximo hacia un mundo es
 * MAX_DRONES * CAPACIDAD / VIAJE. Al llegar, el dron espera en el "buzón" del mundo destino y su silo lo va vaciando poco a poco.
 *
 * Es solo dato (sin entidades) y avanza mientras se juega en CUALQUIER mundo, con un único reloj. Todo ocurre en el hilo principal. Es estado global
 * de todos los mundos guardados: vive en un archivo en la raíz de los datos del mod ({@code orbita-global.dat}), no dentro de un mundo.
 */
final class TransitoOrbital{
    static final int CAPACIDAD = 30;
    static final float VIAJE_TICKS = 60f * 25f;
    static final int MAX_DRONES = 4;
    private static final int FORMATO = 2;

    static final class Dron{
        Item item;
        int cantidad;
        float restante;
    }

    // Todo indexado por el ID DEL MUNDO DESTINO.
    private static final Map<String, Seq<Dron>> enRuta = new HashMap<String, Seq<Dron>>();
    private static final Map<String, Seq<Dron>> buzon = new HashMap<String, Seq<Dron>>();
    private static final Seq<Dron> libres = new Seq<>();   // pool: los drones se reciclan
    private static boolean cargado;

    private TransitoOrbital(){}

    private static Seq<Dron> ruta(String dest){
        Seq<Dron> s = enRuta.get(dest);
        if(s == null){
            s = new Seq<>();
            enRuta.put(dest, s);
        }
        return s;
    }

    private static Seq<Dron> buzon(String dest){
        Seq<Dron> s = buzon.get(dest);
        if(s == null){
            s = new Seq<>();
            buzon.put(dest, s);
        }
        return s;
    }

    /** Vacía todo el tránsito (solo pruebas y errores de lectura). */
    static void reiniciar(){
        for(Seq<Dron> s : enRuta.values()) libres.addAll(s);
        for(Seq<Dron> s : buzon.values()) libres.addAll(s);
        enRuta.clear();
        buzon.clear();
        cargado = false;
    }

    /** ¿Queda un dron libre para salir hacia ese mundo? */
    static boolean puedeLanzar(String destino){
        return destino != null && ruta(destino).size < MAX_DRONES;
    }

    static void lanzar(String destino, Item item, int cantidad){
        if(destino == null) return;
        Dron d = libres.isEmpty() ? new Dron() : libres.pop();
        d.item = item;
        d.cantidad = Math.min(CAPACIDAD, cantidad);
        d.restante = VIAJE_TICKS;
        ruta(destino).add(d);
    }

    /** Cada frame (Trigger.update). */
    static void tick(){
        if(!MundoInfinitoMod.Streamer.activo || !Vars.state.isPlaying()) return;
        for(Map.Entry<String, Seq<Dron>> e : enRuta.entrySet()){
            Seq<Dron> r = e.getValue();
            for(int i = r.size - 1; i >= 0; i--){
                Dron d = r.get(i);
                d.restante -= Time.delta;
                if(d.restante <= 0f){
                    r.remove(i);
                    buzon(e.getKey()).add(d);
                }
            }
        }
    }

    /** Siguiente ítem que el silo del mundo 'destino' puede descargar (sin quitarlo todavía), o null. */
    static Item mirar(String destino){
        Seq<Dron> b = buzon.get(destino);
        return b == null || b.isEmpty() ? null : b.first().item;
    }

    /** Confirma que se entregó 1 unidad del ítem devuelto por mirar(). */
    static void consumir(String destino){
        Seq<Dron> b = buzon.get(destino);
        if(b == null || b.isEmpty()) return;
        Dron d = b.first();
        if(--d.cantidad <= 0){
            b.remove(0);
            libres.add(d);
        }
    }

    /** Unidades en vuelo o esperando descarga hacia ese mundo (para mostrar en la interfaz). */
    static int pendientes(String destino){
        int n = 0;
        Seq<Dron> r = enRuta.get(destino), b = buzon.get(destino);
        if(r != null) for(Dron d : r) n += d.cantidad;
        if(b != null) for(Dron d : b) n += d.cantidad;
        return n;
    }

    /** Descarta lo que iba hacia un mundo que se borró: no llegaría nunca. */
    static void olvidarDestino(String destino){
        Seq<Dron> r = enRuta.remove(destino), b = buzon.remove(destino);
        if(r != null) libres.addAll(r);
        if(b != null) libres.addAll(b);
    }

    // ------------------------------------------------------------ persistencia
    static byte[] bytes(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
            out.writeInt(FORMATO);
            int destinos = 0;
            for(String k : unionDestinos()) if(ruta(k).size + buzon(k).size > 0) destinos++;
            out.writeInt(destinos);
            for(String k : unionDestinos()){
                Seq<Dron> r = ruta(k), b = buzon(k);
                if(r.size + b.size == 0) continue;
                out.writeUTF(k);
                out.writeInt(r.size + b.size);
                for(Dron d : r) escribir(out, d, d.restante);
                for(Dron d : b) escribir(out, d, 0f);   // los del buzón ya llegaron: se guardan con restante 0
            }
            out.close();
            return bos.toByteArray();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo serializar la órbita", t);
            return null;
        }
    }

    private static java.util.Set<String> unionDestinos(){
        final java.util.TreeSet<String> s = new java.util.TreeSet<String>(enRuta.keySet());
        s.addAll(buzon.keySet());
        return s;
    }

    private static void escribir(DataOutputStream out, Dron d, float restante) throws java.io.IOException{
        out.writeUTF(d.item.name);
        out.writeInt(d.cantidad);
        out.writeFloat(restante);
    }

    /**
     * Lee el tránsito global del disco, una sola vez por sesión (abrir otro mundo no lo vuelve a leer: ya está en memoria y es de todos). Un archivo
     * ilegible o de un formato anterior se descarta con aviso; el formato 1 era el de las dimensiones y no se puede traducir a mundos.
     */
    static void cargar(MundoInfinitoMod.Meta m){
        if(cargado) return;
        cargado = true;
        Fi f = MundoInfinitoMod.Almacen.archivoOrbita();
        if(!f.exists()) return;
        try{
            DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
            final int formato = in.readInt();
            if(formato != FORMATO){
                Log.warn("[MundoInfinito] Tránsito orbital de un formato anterior (@): se descarta.", formato);
                in.close();
                return;
            }
            final int destinos = in.readInt();
            for(int k = 0; k < destinos; k++){
                final String dest = in.readUTF();
                int n = in.readInt();
                for(int i = 0; i < n; i++){
                    Item it = Vars.content.item(in.readUTF());
                    int c = in.readInt();
                    float rest = in.readFloat();
                    if(it == null || c <= 0) continue;
                    Dron d = libres.isEmpty() ? new Dron() : libres.pop();
                    d.item = it;
                    d.cantidad = c;
                    d.restante = rest;
                    (rest > 0f ? ruta(dest) : buzon(dest)).add(d);
                }
            }
            in.close();
        }catch(Throwable t){
            Log.err("[MundoInfinito] Órbita ilegible; se descarta el tránsito", t);
            reiniciar();
            cargado = true;
        }
    }
}
