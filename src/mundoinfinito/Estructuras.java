package mundoinfinito;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.gen.Unit;
import mundoinfinito.MundoInfinitoMod.Almacen;
import mundoinfinito.MundoInfinitoMod.Meta;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Estado por dimensión que se guarda en estructuras.dat: los CHECKPOINTS de los portales y las unidades aliadas dormidas.
 *
 * Esta clase antes generaba bases enemigas, ruinas abandonadas y unidades de guardia. Todo eso se ELIMINÓ: el mod es ahora un mundo
 * abierto base; quien quiera enemigos, estructuras o abandonados los añade desde su propio mod (ver OpenWorldAPI). El formato de
 * estructuras.dat no cambia, así que los mundos guardados se siguen leyendo (la lista de estructuras colocadas se lee y se descarta).
 */
final class Estructuras{
    private Estructuras(){}

    static final class Punto{
        String nombre;
        double vx, vy;      // posición VIRTUAL del portal
    }

    static final Seq<Punto> puntos = new Seq<>();             // checkpoints de esta dimensión

    static void cargar(Meta m){
        puntos.clear();
        if(m == null || !m.dimensional()) return;
        Fi f = Almacen.archivoEstructuras(m);
        if(!f.exists()) return;
        try{
            DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
            in.readInt();
            int n = in.readInt();
            for(int i = 0; i < n; i++) in.readLong();   // estructuras colocadas por el generador antiguo: ya no existen, se descartan
            int np = in.readInt();
            for(int i = 0; i < np; i++){
                Punto p = new Punto();
                p.nombre = in.readUTF();
                p.vx = in.readDouble();
                p.vy = in.readDouble();
                puntos.add(p);
            }
            in.close();
        }catch(Throwable t){
            Log.err("[MundoInfinito] Checkpoints ilegibles; se reinician", t);
            puntos.clear();
        }
    }

    static byte[] bytes(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
            out.writeInt(1);
            out.writeInt(0);                 // sin estructuras colocadas (se conserva el campo por compatibilidad)
            out.writeInt(puntos.size);
            for(Punto p : puntos){
                out.writeUTF(p.nombre);
                out.writeDouble(p.vx);
                out.writeDouble(p.vy);
            }
            out.close();
            return bos.toByteArray();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudieron serializar los checkpoints", t);
            return null;
        }
    }

    static Punto puntoCerca(double vx, double vy){
        for(Punto p : puntos) if(Math.hypot(p.vx - vx, p.vy - vy) < 6.0) return p;
        return null;
    }

    /** Registra un checkpoint en un portal (si ya había uno ahí, no hace nada). @return true si es nuevo. */
    static boolean registrarPunto(double vx, double vy){
        if(puntoCerca(vx, vy) != null) return false;
        Punto p = new Punto();
        p.nombre = "Checkpoint " + (puntos.size + 1);
        p.vx = vx;
        p.vy = vy;
        puntos.add(p);
        return true;
    }

    /** Unidad guardada junto al chunk donde estaba. */
    static final class RegU{
        String tipo;
        int equipo;
        double vx, vy, hvx, hvy;
        float vida, fuga;
        int clase;                 // 0 = guardia enemiga de mundos antiguos (se descarta al leer), 1 = unidad aliada dormida (despierta cerca del jugador)
        float rot, escudo, municion;

        /** Unidad aliada que se manda a dormir: conserva vida, escudo, munición y orientación. */
        static RegU aliada(Unit u, int ox, int oy){
            return dormida(u, ox, oy, 1);
        }

        /** clase 1 = aliada del mundo base; clase 2 = unidad de un mod que su PoliticaSueno manda dormir. */
        static RegU dormida(Unit u, int ox, int oy, int clase){
            RegU r = new RegU();
            r.clase = clase;
            r.tipo = u.type.name;
            r.equipo = u.team.id;
            r.vx = ox + u.x / 8.0;
            r.vy = oy + u.y / 8.0;
            r.hvx = r.vx;
            r.hvy = r.vy;
            r.vida = u.health;
            r.escudo = u.shield;
            r.rot = u.rotation;
            return r;
        }

        void escribir(DataOutputStream out) throws java.io.IOException{
            out.writeUTF(tipo);
            out.writeByte(equipo);
            out.writeDouble(vx);
            out.writeDouble(vy);
            out.writeDouble(hvx);
            out.writeDouble(hvy);
            out.writeFloat(vida);
            out.writeFloat(fuga);
            out.writeByte(clase);
            out.writeFloat(rot);
            out.writeFloat(escudo);
            out.writeFloat(municion);
        }

        static RegU leer(DataInputStream in, int ver) throws java.io.IOException{
            RegU r = new RegU();
            r.tipo = in.readUTF();
            r.equipo = in.readUnsignedByte();
            r.vx = in.readDouble();
            r.vy = in.readDouble();
            r.hvx = in.readDouble();
            r.hvy = in.readDouble();
            r.vida = in.readFloat();
            r.fuga = in.readFloat();
            if(ver >= 6){
                r.clase = in.readUnsignedByte();
                r.rot = in.readFloat();
                r.escudo = in.readFloat();
                r.municion = in.readFloat();
            }
            return r;
        }
    }
}
