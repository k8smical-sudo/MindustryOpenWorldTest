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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Tránsito orbital entre dimensiones (logística fraccionada).
 *
 * Los silos NO teletransportan nada: lo que meten en el silo de la dimensión A sale en DRONES invisibles
 * (30 unidades por viaje, un solo tipo de ítem por dron). Cada dron tarda VIAJE_TICKS en llegar y solo hay
 * MAX_DRONES en vuelo por sentido, así que el caudal máximo de un sentido es MAX_DRONES * CAPACIDAD / VIAJE.
 * Al llegar, el dron espera en el "buzón" de la dimensión destino y el silo receptor lo va vaciando poco a poco.
 *
 * Es solo dato (sin entidades) y se actualiza siempre, también mientras juegas en la otra dimensión.
 * Todo ocurre en el hilo principal. Estado compartido por las dos dimensiones: se guarda en orbita.dat (raíz del mundo).
 */
final class Orbita{
    static final int CAPACIDAD = 30;
    static final float VIAJE_TICKS = 60f * 25f;
    static final int MAX_DRONES = 4;

    static final class Dron{
        Item item;
        int cantidad;
        float restante;
    }

    // Todo indexado por DIMENSIÓN DESTINO (0 = Serpulo, 1 = Erekir).
    private static final Seq<Dron> enRuta0 = new Seq<>(), enRuta1 = new Seq<>();
    private static final Seq<Dron> buzon0 = new Seq<>(), buzon1 = new Seq<>();
    private static final Seq<Dron> libres = new Seq<>();   // pool: los drones se reciclan

    private static Seq<Dron> ruta(int dest){ return dest == 0 ? enRuta0 : enRuta1; }
    private static Seq<Dron> buzon(int dest){ return dest == 0 ? buzon0 : buzon1; }

    static void reiniciar(){
        for(int d = 0; d < 2; d++){
            libres.addAll(ruta(d));
            libres.addAll(buzon(d));
            ruta(d).clear();
            buzon(d).clear();
        }
    }

    /** ¿Queda un dron libre para salir desde 'origen'? */
    static boolean puedeLanzar(int origen){
        return ruta(1 - origen).size < MAX_DRONES;
    }

    static void lanzar(int origen, Item item, int cantidad){
        Dron d = libres.isEmpty() ? new Dron() : libres.pop();
        d.item = item;
        d.cantidad = Math.min(CAPACIDAD, cantidad);
        d.restante = VIAJE_TICKS;
        ruta(1 - origen).add(d);
    }

    /** Cada frame (Trigger.update). */
    static void tick(){
        if(!MundoInfinitoMod.Streamer.activo || !Vars.state.isPlaying()) return;
        for(int dest = 0; dest < 2; dest++){
            Seq<Dron> r = ruta(dest);
            for(int i = r.size - 1; i >= 0; i--){
                Dron d = r.get(i);
                d.restante -= Time.delta;
                if(d.restante <= 0f){
                    r.remove(i);
                    buzon(dest).add(d);
                }
            }
        }
    }

    /** Siguiente ítem que el silo de 'destino' puede descargar (sin quitarlo todavía), o null. */
    static Item mirar(int destino){
        Seq<Dron> b = buzon(destino);
        return b.isEmpty() ? null : b.first().item;
    }

    /** Confirma que se entregó 1 unidad del ítem devuelto por mirar(). */
    static void consumir(int destino){
        Seq<Dron> b = buzon(destino);
        if(b.isEmpty()) return;
        Dron d = b.first();
        if(--d.cantidad <= 0){
            b.remove(0);
            libres.add(d);
        }
    }

    /** Unidades en vuelo o esperando descarga hacia 'destino' (para mostrar en la interfaz). */
    static int pendientes(int destino){
        int n = 0;
        for(Dron d : ruta(destino)) n += d.cantidad;
        for(Dron d : buzon(destino)) n += d.cantidad;
        return n;
    }

    // ------------------------------------------------------------ persistencia
    static byte[] bytes(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
            out.writeInt(1);
            for(int dest = 0; dest < 2; dest++){
                Seq<Dron> r = ruta(dest), b = buzon(dest);
                out.writeInt(r.size + b.size);
                for(Dron d : r) escribir(out, d);
                for(Dron d : b){
                    // los del buzón ya llegaron: se guardan con restante 0
                    out.writeUTF(d.item.name);
                    out.writeInt(d.cantidad);
                    out.writeFloat(0f);
                }
            }
            out.close();
            return bos.toByteArray();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo serializar la órbita", t);
            return null;
        }
    }

    private static void escribir(DataOutputStream out, Dron d) throws java.io.IOException{
        out.writeUTF(d.item.name);
        out.writeInt(d.cantidad);
        out.writeFloat(d.restante);
    }

    static void cargar(MundoInfinitoMod.Meta m){
        reiniciar();
        if(!m.dimensional()) return;
        Fi f = MundoInfinitoMod.Almacen.archivoOrbita(m);
        if(!f.exists()) return;
        try{
            DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
            in.readInt();
            for(int dest = 0; dest < 2; dest++){
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
        }
    }
}
