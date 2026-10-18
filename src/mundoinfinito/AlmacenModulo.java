package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Regiones;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.TreeMap;

/**
 * Datos propios de un mod, por chunk, que se guardan junto al chunk (mismo archivo de región) sin pisar los del mundo base ni los de otros
 * mods. Cada mod usa su propio id de módulo (por ejemplo el id de su mod).
 *
 * SOLO HILO DEL JUEGO. Un generador de terreno no lo necesita ni puede usarlo: el terreno es una función pura de la semilla y nunca se
 * guarda; lo que sí hay que recordar (que una estructura ya se colocó, el estado de un campamento) se guarda desde el hilo del juego.
 *
 * Los datos de un chunk se leen de la región en memoria. Si esa región aún se está leyendo del disco, {@link #listo} da false, {@link #leer}
 * devuelve null y {@link #escribir} devuelve false: el llamador lo reintenta más tarde (leer el disco aquí congelaría el juego).
 */
public final class AlmacenModulo{
    private AlmacenModulo(){}

    /** Módulo reservado del mundo base: marcas de "ya generado" de las estructuras. */
    static final String MODULO_BASE = "mundo-infinito/generado";

    /** Coordenadas de chunk VIRTUALES. */
    public static boolean listo(int cx, int cy){
        return Regiones.meta != null && Regiones.lista(cx, cy);
    }

    /** Los bytes guardados por el módulo en el chunk, o null si no hay (o si todavía no {@link #listo}). */
    public static byte[] leer(String modulo, int cx, int cy){
        if(!listo(cx, cy)) return null;
        final Regiones.ChunkGuardado c = Regiones.chunk(cx, cy, false);
        return c == null || c.modulos == null ? null : c.modulos.get(modulo);
    }

    /** Guarda los bytes del módulo en el chunk (null o vacío los borra). @return false si la región aún no está lista. */
    public static boolean escribir(String modulo, int cx, int cy, byte[] datos){
        if(modulo == null || modulo.isEmpty()) throw new IllegalArgumentException("módulo vacío");
        if(!listo(cx, cy)) return false;
        final boolean vacio = datos == null || datos.length == 0;
        final Regiones.ChunkGuardado c = Regiones.chunk(cx, cy, !vacio);
        if(c == null) return true;
        if(vacio){
            if(c.modulos != null && c.modulos.remove(modulo) != null){
                if(c.modulos.isEmpty()) c.modulos = null;
                Regiones.chunk(cx, cy, true);   // marca la región como modificada
            }
            return true;
        }
        if(c.modulos == null) c.modulos = new TreeMap<>();
        c.modulos.put(modulo, datos.clone());   // se sustituye el array entero: nunca se modifica en sitio (lo serializa otro hilo)
        return true;
    }

    // ------------------------------------------------------------------ marcas "generado" (uso de las estructuras)
    static boolean generado(int cx, int cy, String clave){
        final byte[] b = leer(MODULO_BASE, cx, cy);
        if(b == null) return false;
        try{
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            final int n = in.readInt();
            for(int i = 0; i < n; i++) if(clave.equals(in.readUTF())) return true;
        }catch(Throwable ignored){}
        return false;
    }

    /** @return false si la región aún no está lista (se reintenta). */
    static boolean marcarGenerado(int cx, int cy, String clave){
        if(!listo(cx, cy)) return false;
        if(generado(cx, cy, clave)) return true;
        try{
            final byte[] b = leer(MODULO_BASE, cx, cy);
            final java.util.ArrayList<String> claves = new java.util.ArrayList<>();
            if(b != null){
                final DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
                final int n = in.readInt();
                for(int i = 0; i < n; i++) claves.add(in.readUTF());
            }
            claves.add(clave);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(claves.size());
            for(String s : claves) out.writeUTF(s);
            out.flush();
            return escribir(MODULO_BASE, cx, cy, bos.toByteArray());
        }catch(Throwable t){
            arc.util.Log.err("[MundoInfinito][API] No se pudo guardar la marca de estructura", t);
            return true;   // no se reintenta en bucle
        }
    }
}
