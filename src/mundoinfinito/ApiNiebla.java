package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * API PÚBLICA DE MEMORIA DE NIEBLA DE GUERRA (versión {@link ApiVersion#NIEBLA_MAYOR}.{@link ApiVersion#NIEBLA_MENOR}). Plan v9: al salir y volver a
 * entrar en un mundo (o solo alejarse de una zona y volver), el mod recuerda lo que el jugador ya exploró: la niebla de esa zona no vuelve a
 * taparlo. Esta clase hace ese comportamiento público y manipulable para los addons, sin tocar el motor directamente.
 *
 * DEFAULT (sin ningún addon): se recuerda todo, para siempre, tal como ya hace el mod. Nada de esto cambia ese comportamiento si no se usa.
 *
 *  - **Apagar la memoria**, del todo o por mundo: {@link #registrarPolitica}, o el ajuste {@link #AJUSTE_MEMORIA} del diálogo de creación de mundo
 *    (lee el jugador, no hace falta código).
 *  - **Tocar lo que se recuerda**: {@link #suscribirGuardado} (justo antes de escribirlo en disco) y {@link #suscribirRestauracion} (justo antes de
 *    aplicarlo a la niebla viva), para apagado parcial, "memoria que se borra con el tiempo", niebla compartida entre mundos, etc.
 *  - **Manipulación directa**: {@link #revelarChunk}, {@link #olvidarChunk}, {@link #leerBits} y {@link #escribirBits}, para mecánicas propias
 *    (un objeto que revela el mapa, un efecto que hace "olvidar" una zona...).
 *
 * Todo se identifica por chunk (32x32 casillas, {@link MundoInfinitoMod#TAM_CHUNK}) del equipo del jugador. El formato de los bits es el que usa el
 * motor (1024 bits por chunk, opaco); los addons que no necesiten el detalle usan {@link #revelarChunk} y {@link #olvidarChunk}.
 *
 * Una política o un hook que lanza una excepción se desactiva con un aviso en el log, sin afectar a los demás (como el resto de la API).
 * Solo actúa con un mundo abierto y niebla activa en el motor; sin eso, todo es un no-op seguro. Ver docs/API.md.
 */
public final class ApiNiebla{
    /** Ajuste de mundo (se elige al crear el mundo): si está apagado, la niebla no se recuerda entre visitas. Encendido por defecto. */
    public static final String AJUSTE_MEMORIA = "mundo-infinito:niebla-memoria";

    private ApiNiebla(){}

    /** Decide si la niebla de un mundo se recuerda entre visitas. */
    public interface Politica{
        /**
         * @return true para recordar, false para no recordar, null para no opinar (sigue preguntando a la siguiente política, y al final al ajuste
         * de mundo). Una política que lanza una excepción se desactiva con aviso, como si devolviera null siempre.
         */
        Boolean persiste(String mundoId, int dimension);
    }

    /** Se llama justo antes de guardar lo explorado de un chunk, con los bits que se van a escribir: se pueden modificar en el array. */
    public interface AlGuardar{
        void alGuardar(String mundoId, int dimension, int cx, int cy, long[] bits);
    }

    /** Se llama justo antes de aplicar a la niebla viva lo explorado guardado de un chunk, con los bits que se van a aplicar: se pueden modificar. */
    public interface AlRestaurar{
        void alRestaurar(String mundoId, int dimension, int cx, int cy, long[] bits);
    }

    // ================================================================== registro

    private static final class EntradaPol{
        final String id;
        final int prio;
        final Politica politica;
        boolean activa = true;

        EntradaPol(String id, int prio, Politica politica){ this.id = id; this.prio = prio; this.politica = politica; }
    }

    private static final class EntradaHook{
        final String id;
        final int prio;
        final AlGuardar guardar;
        final AlRestaurar restaurar;
        boolean activa = true;

        EntradaHook(String id, int prio, AlGuardar guardar, AlRestaurar restaurar){ this.id = id; this.prio = prio; this.guardar = guardar; this.restaurar = restaurar; }
    }

    private static final Comparator<EntradaPol> ORDEN_POL = (a, b) -> a.prio != b.prio ? Integer.compare(a.prio, b.prio) : a.id.compareTo(b.id);
    private static final Comparator<EntradaHook> ORDEN_HOOK = (a, b) -> a.prio != b.prio ? Integer.compare(a.prio, b.prio) : a.id.compareTo(b.id);

    private static final Object CERROJO = new Object();
    private static final List<EntradaPol> POLITICAS = new ArrayList<EntradaPol>();
    private static final List<EntradaHook> HOOKS = new ArrayList<EntradaHook>();
    private static volatile EntradaPol[] ordenPol = new EntradaPol[0];
    private static volatile EntradaHook[] ordenHook = new EntradaHook[0];

    /** Registra una política de persistencia. Devuelve false si el id es inválido o repetido. */
    public static boolean registrarPolitica(String id, int prioridad, Politica politica){
        if(!idValido(id) || politica == null) return false;
        synchronized(CERROJO){
            for(EntradaPol e : POLITICAS) if(e.id.equals(id)) return false;
            POLITICAS.add(new EntradaPol(id, prioridad, politica));
            reordenarPol();
        }
        return true;
    }

    /** Quita una política registrada con {@link #registrarPolitica}. */
    public static boolean quitarPolitica(String id){
        synchronized(CERROJO){
            for(int i = 0; i < POLITICAS.size(); i++){
                if(POLITICAS.get(i).id.equals(id)){
                    POLITICAS.remove(i);
                    reordenarPol();
                    return true;
                }
            }
        }
        return false;
    }

    /** Se suscribe a los dos momentos (guardar y restaurar). Cualquiera de los dos puede ser null. Devuelve false si el id es inválido o repetido. */
    public static boolean suscribir(String id, int prioridad, AlGuardar alGuardar, AlRestaurar alRestaurar){
        if(!idValido(id) || (alGuardar == null && alRestaurar == null)) return false;
        synchronized(CERROJO){
            for(EntradaHook e : HOOKS) if(e.id.equals(id)) return false;
            HOOKS.add(new EntradaHook(id, prioridad, alGuardar, alRestaurar));
            reordenarHook();
        }
        return true;
    }

    /** Solo el momento de guardar. Azúcar sobre {@link #suscribir}. */
    public static boolean suscribirGuardado(String id, int prioridad, AlGuardar hook){
        return suscribir(id, prioridad, hook, null);
    }

    /** Solo el momento de restaurar. Azúcar sobre {@link #suscribir}. */
    public static boolean suscribirRestauracion(String id, int prioridad, AlRestaurar hook){
        return suscribir(id, prioridad, null, hook);
    }

    /** Quita una suscripción de {@link #suscribir}. */
    public static boolean quitarSuscripcion(String id){
        synchronized(CERROJO){
            for(int i = 0; i < HOOKS.size(); i++){
                if(HOOKS.get(i).id.equals(id)){
                    HOOKS.remove(i);
                    reordenarHook();
                    return true;
                }
            }
        }
        return false;
    }

    private static void reordenarPol(){
        EntradaPol[] a = POLITICAS.toArray(new EntradaPol[0]);
        java.util.Arrays.sort(a, ORDEN_POL);
        ordenPol = a;
    }

    private static void reordenarHook(){
        EntradaHook[] a = HOOKS.toArray(new EntradaHook[0]);
        java.util.Arrays.sort(a, ORDEN_HOOK);
        ordenHook = a;
    }

    /** Solo para pruebas: deja la API como recién cargada. */
    static void reiniciar(){
        synchronized(CERROJO){
            POLITICAS.clear();
            HOOKS.clear();
            ordenPol = new EntradaPol[0];
            ordenHook = new EntradaHook[0];
        }
    }

    // ================================================================== resolución (lógica pura: se prueba sin el juego)

    /**
     * ¿Se recuerda la niebla de este mundo entre visitas? Pregunta a las políticas registradas en orden (prioridad, id); gana la primera que
     * decida (no devuelva null). Si ninguna decide, gana el ajuste de mundo ({@link #AJUSTE_MEMORIA}, true si no se puede leer: mismo comportamiento
     * de siempre). Una política que falla se desactiva con aviso y cuenta como si no hubiera decidido.
     */
    public static boolean persiste(String mundoId, int dimension, boolean valorAjuste){
        for(EntradaPol e : ordenPol){
            if(!e.activa) continue;
            try{
                final Boolean r = e.politica.persiste(mundoId, dimension);
                if(r != null) return r;
            }catch(Throwable t){
                e.activa = false;
                Log.err("[MundoInfinito] Política de niebla '@' desactivada por un fallo.", e.id, t);
            }
        }
        return valorAjuste;
    }

    /** Aplica los hooks de guardado a los bits de un chunk, en orden. Un hook que falla se desactiva con aviso y los demás siguen. */
    static void ejecutarAlGuardar(String mundoId, int dimension, int cx, int cy, long[] bits){
        for(EntradaHook e : ordenHook){
            if(!e.activa || e.guardar == null) continue;
            try{
                e.guardar.alGuardar(mundoId, dimension, cx, cy, bits);
            }catch(Throwable t){
                e.activa = false;
                Log.err("[MundoInfinito] Hook de niebla (guardar) '@' desactivado por un fallo.", e.id, t);
            }
        }
    }

    /** Igual que {@link #ejecutarAlGuardar} para el momento de restaurar. */
    static void ejecutarAlRestaurar(String mundoId, int dimension, int cx, int cy, long[] bits){
        for(EntradaHook e : ordenHook){
            if(!e.activa || e.restaurar == null) continue;
            try{
                e.restaurar.alRestaurar(mundoId, dimension, cx, cy, bits);
            }catch(Throwable t){
                e.activa = false;
                Log.err("[MundoInfinito] Hook de niebla (restaurar) '@' desactivado por un fallo.", e.id, t);
            }
        }
    }

    private static boolean idValido(String id){
        return id != null && id.indexOf(':') > 0 && id.indexOf(':') < id.length() - 1 && id.indexOf(' ') < 0;
    }

    // ================================================================== mundo abierto

    /** Registra el ajuste de creación de mundo. Lo llama el mod al cargar, antes de abrir el primer mundo. */
    static void iniciarAjustes(){
        ApiAjustes.registrarBooleano(AJUSTE_MEMORIA, "Recordar la niebla explorada",
            "Si está apagado, cada visita a una zona empieza sin explorar, como si fuera la primera vez.", true, null);
    }

    /** ¿Se recuerda la niebla en el mundo abierto? Sin mundo o sin el ajuste: true (el comportamiento de siempre). */
    public static boolean memoriaActiva(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null) return true;
        final double v = ApiAjustes.valor(AJUSTE_MEMORIA);
        final boolean deAjuste = Double.isNaN(v) || v != 0.0;
        return persiste(m.id, m.dim, deAjuste);
    }

    /**
     * Bits de lo explorado de un chunk (32x32 casillas), tal y como los guarda el motor: 16 longs, 1024 bits. No destructivo: no cambia la niebla
     * viva. Sin mundo o sin niebla en el motor: null.
     */
    public static long[] leerBits(int cx, int cy){
        return NieblaMundo.leer(cx, cy);
    }

    /** Aplica estos bits (ver {@link #leerBits}) a la niebla viva de un chunk. Sin mundo o sin niebla en el motor: no hace nada. */
    public static void escribirBits(int cx, int cy, long[] bits){
        NieblaMundo.escribir(cx, cy, bits);
    }

    /** Marca un chunk entero como explorado. */
    public static void revelarChunk(int cx, int cy){
        final long[] b = new long[16];
        java.util.Arrays.fill(b, -1L);
        escribirBits(cx, cy, b);
    }

    /** Marca un chunk entero como no explorado (lo "olvida"): a partir de ahora vuelve a estar tapado por la niebla hasta que se vuelva a visitar. */
    public static void olvidarChunk(int cx, int cy){
        escribirBits(cx, cy, new long[16]);
    }
}
