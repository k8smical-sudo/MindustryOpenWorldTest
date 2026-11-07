package mundoinfinito;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mundoinfinito.MundoInfinitoMod.Almacen;
import mundoinfinito.MundoInfinitoMod.Meta;

/**
 * MIGRACIÓN DE LAS DIMENSIONES A MUNDOS INDEPENDIENTES (plan v9). Antes, un mundo guardado tenía una subcarpeta por dimensión ("d0" Serpulo, "d1" Erekir,
 * "d16" y más de mundos de mods) y se saltaba de una a otra. Ahora no hay dimensiones: cada mundo tiene UN terreno y se viaja entre mundos enlazados.
 *
 * Un mundo anterior se migra UNA vez, al listarlo (la primera vez que se abre el menú o se continúa tras actualizar):
 *  - la dimensión en la que estaba ({@code Meta.dim}) pasa a ser el mundo mismo: su contenido sube de "dN" a la raíz de su carpeta;
 *  - cada OTRA dimensión que tuviera datos se convierte en un mundo nuevo, con id "{id}-dN", el nombre "{nombre} ({terreno})", la misma semilla, perfil y
 *    calendario, y sus datos movidos a su propia carpeta;
 *  - cada mundo nuevo queda ENLAZADO con el original (tier 1, ambos sentidos), para no perder el viaje que había;
 *  - se marca el mundo como disposición 2 y se guarda; migrar otra vez no hace nada.
 *
 * Qué NO se conserva: el tránsito orbital que hubiera en vuelo (los drones entre dimensiones; ahora es global y de otro formato) y el estado de sistemas
 * de los mundos nuevos (cada uno recibe su cuerpo al abrirse, como cualquier mundo antiguo sin cuerpo). El inventario guardado de cada dimensión se
 * conserva tal cual en su estado.
 *
 * Un fallo a medias no pierde datos: se mueve (no se borra) y la migración es reanudable; si algo falla, el mundo queda sin marcar y se reintenta.
 */
final class MigracionMundos{
    /** Disposición actual: todo el mundo en la raíz de su carpeta. */
    static final int LAYOUT_MUNDO_UNICO = 2;

    private MigracionMundos(){}

    /** Nombre del terreno para un nombre de mundo migrado. */
    static String nombreTerreno(int terreno){
        if(terreno == MundoInfinitoMod.DIM_SERPULO) return "Serpulo";
        if(terreno == MundoInfinitoMod.DIM_EREKIR) return "Erekir";
        final MundoRegistrado w = Extensiones.mundoPorDim(terreno);
        return w == null ? "Mundo " + terreno : w.nombre;
    }

    /** Id del mundo que sale de la dimensión {@code terreno} de {@code idOriginal}. Estable: migrar dos veces da el mismo id. */
    static String idDeDimension(String idOriginal, int terreno){
        return idOriginal + "-d" + terreno;
    }

    /** Número de una carpeta "dN", o -1 si no lo es. */
    static int terrenoDeCarpeta(String nombre){
        if(nombre == null || nombre.length() < 2 || nombre.charAt(0) != 'd') return -1;
        try{
            final int n = Integer.parseInt(nombre.substring(1));
            return n >= 0 && ("d" + n).equals(nombre) ? n : -1;
        }catch(NumberFormatException e){
            return -1;
        }
    }

    /**
     * Migra un mundo anterior. Devuelve los mundos NUEVOS que ha creado (vacío si no hubo otras dimensiones con datos). El mundo recibido queda con
     * disposición 2 y guardado. Nunca lanza: un fallo se registra y deja el mundo sin marcar para reintentar.
     */
    static Seq<Meta> migrar(Meta m){
        final Seq<Meta> nuevos = new Seq<>();
        try{
            final Fi raiz = Almacen.carpeta(m.id);
            final Fi[] hijos = raiz.list();
            for(Fi sub : hijos){
                if(!sub.isDirectory()) continue;
                final int k = terrenoDeCarpeta(sub.name());
                if(k < 0) continue;
                if(k == m.dim){
                    subirALaRaiz(sub, raiz);
                }else{
                    nuevos.add(crearMundoDeDimension(m, k, sub));
                }
            }
            // enlaces en ambos sentidos con cada mundo nuevo (se escriben tras crearlos todos)
            for(Meta n : nuevos){
                final String linea = "migracion:" + m.id + ">" + n.id + "|" + m.id + "|" + n.id + "|1\n";
                m.enlaces = ApiViaje.Enlaces.desdeTexto(m.enlaces.aTexto() + linea);
                n.enlaces = ApiViaje.Enlaces.desdeTexto(n.enlaces.aTexto() + linea);
                if(m.destinoLogistica == null || m.destinoLogistica.isEmpty()) m.destinoLogistica = n.id;
                n.destinoLogistica = m.id;
                Almacen.guardarMeta(n);
            }
            m.layout = LAYOUT_MUNDO_UNICO;
            Almacen.guardarMeta(m);
            if(nuevos.size > 0) Log.info("[MundoInfinito] Mundo '@' migrado: sus dimensiones ahora son @ mundo(s) independiente(s).", m.nombre, nuevos.size + 1);
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo migrar el mundo '@'; se reintentará la próxima vez.", m.nombre, t);
            nuevos.clear();
        }
        return nuevos;
    }

    /** Mueve el contenido de la carpeta de una dimensión a la raíz de su mundo y borra la carpeta vacía. Sin pisar nada que ya esté en la raíz. */
    private static void subirALaRaiz(Fi sub, Fi raiz){
        for(Fi c : sub.list()){
            final Fi destino = raiz.child(c.name());
            if(destino.exists()) continue;   // reanudado tras un fallo: ya está arriba
            c.moveTo(destino);
        }
        if(sub.list().length == 0) sub.deleteDirectory();
    }

    /** Crea el mundo que sale de otra dimensión: copia los datos de ajustes, mueve la carpeta y lo guarda. Reanudable: si ya existe, lo reutiliza. */
    private static Meta crearMundoDeDimension(Meta origen, int terreno, Fi carpetaDimension){
        final String id = idDeDimension(origen.id, terreno);
        final Fi destino = Almacen.carpeta(id);
        destino.mkdirs();
        final Fi yaExiste = destino.child("meta.json");
        Meta n;
        if(yaExiste.exists()){
            n = Meta.desde(id, arc.util.serialization.Jval.read(yaExiste.readString()));
        }else{
            n = Meta.desde(id, origen.aJson());
            n.id = id;
            n.dim = terreno;
            n.nombre = origen.nombre + " (" + nombreTerreno(terreno) + ")";
            n.creado = origen.creado + terreno + 1;
            n.ultimo = Math.max(0L, origen.ultimo - 1L);
            n.sistemas = new EstadoSistemas();              // recibe su propio cuerpo al abrirse
            n.enlaces = new ApiViaje.Enlaces();
            n.destinoLogistica = "";
        }
        n.layout = LAYOUT_MUNDO_UNICO;
        Almacen.guardarMeta(n);   // primero la meta, después mover los datos: si se corta a medias, el mundo nuevo ya existe y se reanuda
        subirALaRaiz(carpetaDimension, destino);
        return n;
    }
}
