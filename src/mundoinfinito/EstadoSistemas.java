package mundoinfinito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ESTADO DE SISTEMAS (plan v6, F2, sección 5). Sistemas solares y planetas solitarios con sus cuerpos. Sin Arc: se prueba en CI sin el juego.
 *
 * Formato 1.0: cabecera "ESS1", versión mayor y menor, y luego los sistemas en orden de creación. Un mundo sin esta sección (mundos
 * antiguos) se lee como estado vacío: el terreno no depende de este dato. Una versión mayor desconocida se rechaza en
 * {@link #leerEstricto}; {@link #leer} la trata como estado vacío.
 */
public final class EstadoSistemas{
    static final int MAGICO = 0x45535331;   // "ESS1"
    private static final int MAX_SISTEMAS = 1000000, MAX_CUERPOS = 1000000, MAX_DISTANCIAS = 64;

    private final ArrayList<SistemaSolar> sistemas = new ArrayList<SistemaSolar>();

    /**
     * Un cuerpo de un sistema. {@code planeta} es el tipo de planeta base (Serpulo, Erekir, o el de un addon). {@code orbita} es la clave de la
     * órbita compartida (D19), que es el mismo tipo base; null si el cuerpo no comparte órbita (planeta solitario).
     */
    public static final class Cuerpo{
        public final String id;
        public final String tipo;
        public final String planeta;
        /** Mundo creado asociado, o null. */
        public final String mundo;
        public final String orbita;
        /** Índice de slot dentro de la órbita compartida (0 = primero). -1 si no ocupa slot. */
        public final int slot;
        /** Radio de la órbita compartida en unidades del sistema solar. 0 si no comparte órbita. */
        public final double radioOrbita;
        /** Ángulo del slot en radianes, en [0, 2π). 0 si no comparte órbita. */
        public final double angulo;
        public final FichaFisica ficha;

        public Cuerpo(String id, String tipo, String planeta, String mundo, String orbita, int slot, double radioOrbita, double angulo,
                      FichaFisica ficha){
            this.id = id;
            this.tipo = tipo;
            this.planeta = planeta;
            this.mundo = mundo;
            this.orbita = orbita;
            this.slot = slot;
            this.radioOrbita = radioOrbita;
            this.angulo = angulo;
            this.ficha = ficha == null ? ApiFisica.estimada(tipo, Float.NaN) : ficha;
        }
    }

    /** Un sistema: una estrella (opcional) con sus cuerpos, o un planeta solitario (estrella null, un cuerpo). */
    public static final class SistemaSolar{
        public final String id;
        public final String estrella;
        public final double p0x, p0y, vx, vy, tiempoRef;
        private final ArrayList<Cuerpo> cuerpos = new ArrayList<Cuerpo>();

        public SistemaSolar(String id, String estrella, double p0x, double p0y, double vx, double vy, double tiempoRef){
            this.id = id;
            this.estrella = estrella;
            this.p0x = p0x;
            this.p0y = p0y;
            this.vx = vx;
            this.vy = vy;
            this.tiempoRef = tiempoRef;
        }

        public List<Cuerpo> cuerpos(){ return Collections.unmodifiableList(cuerpos); }

        void anadirCuerpo(Cuerpo c){ cuerpos.add(c); }
    }

    public List<SistemaSolar> sistemas(){ return Collections.unmodifiableList(sistemas); }

    /** Sistema con ese id, o null. */
    public SistemaSolar buscar(String id){
        for(SistemaSolar s : sistemas) if(s.id.equals(id)) return s;
        return null;
    }

    /** Cuerpo con ese id en cualquier sistema, o null. */
    public Cuerpo buscarCuerpo(String id){
        for(SistemaSolar s : sistemas){
            for(Cuerpo c : s.cuerpos) if(c.id.equals(id)) return c;
        }
        return null;
    }

    void anadir(SistemaSolar s){ sistemas.add(s); }

    /** Ángulos (ordenados, en [0, 2π)) de los cuerpos que comparten la órbita dada, en todos los sistemas. Vacío si no hay ninguno. */
    public double[] angulosOrbita(String orbita){
        final ArrayList<Double> lista = new ArrayList<Double>();
        if(orbita != null){
            for(SistemaSolar s : sistemas){
                for(Cuerpo c : s.cuerpos) if(orbita.equals(c.orbita)) lista.add(c.angulo);
            }
        }
        final double[] out = new double[lista.size()];
        for(int i = 0; i < out.length; i++) out[i] = lista.get(i);
        Arrays.sort(out);
        return out;
    }

    /** Radio de la órbita compartida dada (el que fijó su primer cuerpo), o NaN si no hay ningún cuerpo en ella. */
    public double radioOrbita(String orbita){
        if(orbita == null) return Double.NaN;
        for(SistemaSolar s : sistemas){
            for(Cuerpo c : s.cuerpos) if(orbita.equals(c.orbita)) return c.radioOrbita;
        }
        return Double.NaN;
    }

    // ================================================================== formato

    /** Bytes del estado en formato 1.0. El mismo estado da siempre los mismos bytes. */
    public byte[] serializar(){
        try{
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGICO);
            out.writeByte(ApiVersion.ESTADO_MAYOR);
            out.writeByte(ApiVersion.ESTADO_MENOR);
            out.writeInt(sistemas.size());
            for(SistemaSolar s : sistemas){
                out.writeUTF(s.id);
                escribirOpcional(out, s.estrella);
                out.writeDouble(s.p0x);
                out.writeDouble(s.p0y);
                out.writeDouble(s.vx);
                out.writeDouble(s.vy);
                out.writeDouble(s.tiempoRef);
                out.writeInt(s.cuerpos.size());
                for(Cuerpo c : s.cuerpos){
                    out.writeUTF(c.id);
                    out.writeUTF(c.tipo);
                    escribirOpcional(out, c.planeta);
                    escribirOpcional(out, c.mundo);
                    escribirOpcional(out, c.orbita);
                    out.writeInt(c.slot);
                    out.writeDouble(c.radioOrbita);
                    out.writeDouble(c.angulo);
                    out.writeFloat(c.ficha.diametro);
                    out.writeFloat(c.ficha.anchura);
                    out.writeFloat(c.ficha.altura);
                    out.writeUTF(c.ficha.origen);
                    out.writeInt(FichaFisica.NUM_DISTANCIAS);
                    for(int i = 0; i < FichaFisica.NUM_DISTANCIAS; i++) out.writeDouble(c.ficha.distancia(i));
                }
            }
            out.flush();
            return bytes.toByteArray();
        }catch(IOException e){
            throw new IllegalStateException("No se pudo escribir el estado de sistemas", e);   // ByteArrayOutputStream no lanza en la práctica
        }
    }

    /** Lee el estado. Null, vacío o corrupto: estado vacío (mundo antiguo o dato dañado). No lanza. */
    public static EstadoSistemas leer(byte[] datos){
        try{
            return leerEstricto(datos);
        }catch(IOException e){
            return new EstadoSistemas();
        }
    }

    /** Lee el estado y falla con IOException si la cabecera no es la nuestra, la versión mayor no se soporta o los datos están cortados. */
    public static EstadoSistemas leerEstricto(byte[] datos) throws IOException{
        final EstadoSistemas e = new EstadoSistemas();
        if(datos == null || datos.length == 0) return e;
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(datos));
        if(in.readInt() != MAGICO) throw new IOException("cabecera de sistemas no reconocida");
        final int mayor = in.readUnsignedByte(), menor = in.readUnsignedByte();
        if(mayor != ApiVersion.ESTADO_MAYOR) throw new IOException("formato de sistemas " + mayor + "." + menor + " no soportado");
        final int n = in.readInt();
        if(n < 0 || n > MAX_SISTEMAS) throw new IOException("número de sistemas no válido: " + n);
        for(int i = 0; i < n; i++){
            final String id = in.readUTF();
            final String estrella = leerOpcional(in);
            final double p0x = in.readDouble(), p0y = in.readDouble(), vx = in.readDouble(), vy = in.readDouble(), tiempoRef = in.readDouble();
            final SistemaSolar s = new SistemaSolar(id, estrella, p0x, p0y, vx, vy, tiempoRef);
            final int nc = in.readInt();
            if(nc < 0 || nc > MAX_CUERPOS) throw new IOException("número de cuerpos no válido: " + nc);
            for(int j = 0; j < nc; j++){
                final String cid = in.readUTF(), tipo = in.readUTF();
                final String planeta = leerOpcional(in), mundo = leerOpcional(in), orbita = leerOpcional(in);
                final int slot = in.readInt();
                final double radio = in.readDouble(), angulo = in.readDouble();
                final float diametro = in.readFloat(), anchura = in.readFloat(), altura = in.readFloat();
                final String origen = in.readUTF();
                final int nd = in.readInt();
                if(nd < 0 || nd > MAX_DISTANCIAS) throw new IOException("número de distancias no válido: " + nd);
                final double[] dist = new double[nd];
                for(int k = 0; k < nd; k++) dist[k] = in.readDouble();
                final FichaFisica ficha = new FichaFisica(diametro, anchura, altura, origen, dist);
                s.anadirCuerpo(new Cuerpo(cid, tipo, planeta, mundo, orbita, slot, radio, angulo, ficha));
            }
            e.anadir(s);
        }
        return e;
    }

    private static void escribirOpcional(DataOutputStream out, String v) throws IOException{
        out.writeBoolean(v != null);
        if(v != null) out.writeUTF(v);
    }

    private static String leerOpcional(DataInputStream in) throws IOException{
        return in.readBoolean() ? in.readUTF() : null;
    }
}
