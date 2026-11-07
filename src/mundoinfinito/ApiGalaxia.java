package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * API PÚBLICA DE LA GALAXIA (versión {@link ApiVersion#GALAXIA_MAYOR}.{@link ApiVersion#GALAXIA_MENOR}). Plan v9. Un GRUPO es lo que el jugador llama un
 * "sistema solar": un conjunto de mundos con nombre propio que ocupa un lugar en el espacio. Varios grupos forman la Vía Láctea. Todo es modificable:
 * el jugador renombra y crea grupos desde el juego; un dev o un modder cambia las reglas desde su addon.
 *
 *   ApiGalaxia.fijarMaximoGrupos(30);                       // cuántos grupos caben (por defecto {@link Calibracion#GALAXIA_MAX_GRUPOS})
 *   ApiGalaxia.fijarDistanciaEntreGrupos(500.0);            // distancia por defecto entre vecinos
 *   ApiGalaxia.fijarRadioGrupo(90.0);                       // espacio que ocupa cada grupo nuevo
 *   ApiGalaxia.proponerPolitica("miaddon:espiral", 0, (existentes, indice, radio, distancia) -> new double[]{x, y});   // dónde se coloca un grupo nuevo
 *   ApiGalaxia.crearGrupo("Cinturón de Orión");              // un grupo nuevo (respeta el máximo y no se solapa con otro)
 *   ApiGalaxia.renombrarGrupo("grupo-2", "Mi hogar");        // el nombre lo elige quien juega; el id no cambia nunca
 *
 * El primer grupo ({@link #GRUPO_INICIAL}) existe siempre, en el origen: es donde viven los mundos que no eligen otro. Un mundo pertenece a un grupo y
 * su cuerpo se coloca en la posición del grupo, así que la distancia entre mundos de grupos distintos es la distancia entre sus grupos: los portales,
 * los radares y los viajes la usan sin cambios. Con el estado guardado en {@code galaxia.txt} (global, de todos los mundos).
 *
 * Una política de posición que falla se desactiva con aviso, como el resto de la API. La lógica ({@link #posicionPorDefecto}, {@link #crearGrupo},
 * {@link #aTexto}/{@link #desdeTexto}) es pura: se prueba en CI sin el juego.
 */
public final class ApiGalaxia{
    /** Id del grupo que siempre existe, en (0, 0). Su nombre por defecto es "Sistema solar"; se puede renombrar como cualquier otro. */
    public static final String GRUPO_INICIAL = "grupo-1";
    /** Longitud máxima de un nombre, en caracteres. */
    public static final int NOMBRE_MAX = 32;
    /** Máximo absoluto de grupos que se acepta como límite (acota el coste). */
    public static final int MAXIMO_ABSOLUTO = 10000;

    private ApiGalaxia(){}

    /** Un grupo: id fijo, nombre editable, posición y espacio que ocupa. */
    public static final class Grupo{
        public final String id;
        private String nombre;
        public final double x, y, radio;

        Grupo(String id, String nombre, double x, double y, double radio){
            this.id = id;
            this.nombre = nombre;
            this.x = x;
            this.y = y;
            this.radio = radio;
        }

        public String nombre(){ return nombre; }
    }

    /** Resultado de crear un grupo. */
    public static final class Resultado{
        public final boolean ok;
        public final String mensaje;
        public final Grupo grupo;

        Resultado(boolean ok, String mensaje, Grupo grupo){
            this.ok = ok;
            this.mensaje = mensaje;
            this.grupo = grupo;
        }
    }

    /** Decide dónde se coloca un grupo nuevo. */
    public interface PoliticaPosicion{
        /**
         * @param existentes grupos ya creados (solo lectura)
         * @param indice número del grupo nuevo (los existentes son 0 a indice-1)
         * @param radio espacio que ocupará
         * @param distancia distancia por defecto entre vecinos
         * @return {x, y}, o null para no opinar (decide la siguiente política y, al final, la espiral por defecto). Una posición que se solape con un
         *         grupo existente se rechaza y cuenta como no opinar.
         */
        double[] posicion(List<Grupo> existentes, int indice, double radio, double distancia);
    }

    // ================================================================== reglas modificables

    private static final Object CERROJO = new Object();
    private static final List<Grupo> GRUPOS = new ArrayList<Grupo>();
    private static int maximo = Calibracion.GALAXIA_MAX_GRUPOS;
    private static double distancia = Calibracion.GALAXIA_DISTANCIA_GRUPOS, radio = Calibracion.GALAXIA_RADIO_GRUPO;
    private static int contador = 1;

    private static final class EntradaPol{
        final String id;
        final int prio;
        final PoliticaPosicion politica;
        boolean activa = true;

        EntradaPol(String id, int prio, PoliticaPosicion politica){ this.id = id; this.prio = prio; this.politica = politica; }
    }

    private static final List<EntradaPol> POLITICAS = new ArrayList<EntradaPol>();

    /** Cambia el máximo de grupos (de 1 a {@link #MAXIMO_ABSOLUTO}). No borra grupos que ya existan por encima del nuevo máximo: solo impide crear más. */
    public static boolean fijarMaximoGrupos(int n){
        if(n < 1 || n > MAXIMO_ABSOLUTO) return false;
        synchronized(CERROJO){ maximo = n; }
        return true;
    }

    public static int maximoGrupos(){
        synchronized(CERROJO){ return maximo; }
    }

    /** Distancia por defecto entre los centros de grupos vecinos. Debe ser positiva y finita. Solo afecta a los grupos que se creen después. */
    public static boolean fijarDistanciaEntreGrupos(double d){
        if(!(d > 0.0) || !Double.isFinite(d)) return false;
        synchronized(CERROJO){ distancia = d; }
        return true;
    }

    /** Espacio (radio) que ocupa cada grupo que se cree después. Debe ser positivo y finito. */
    public static boolean fijarRadioGrupo(double r){
        if(!(r > 0.0) || !Double.isFinite(r)) return false;
        synchronized(CERROJO){ radio = r; }
        return true;
    }

    /** Registra una política de posición. Gana la primera por prioridad (menor primero) e id que dé una posición válida. false si el id es inválido o repetido. */
    public static boolean proponerPolitica(String id, int prioridad, PoliticaPosicion politica){
        if(id == null || id.indexOf(':') <= 0 || id.indexOf(':') >= id.length() - 1 || id.indexOf(' ') >= 0 || politica == null) return false;
        synchronized(CERROJO){
            for(EntradaPol e : POLITICAS) if(e.id.equals(id)) return false;
            POLITICAS.add(new EntradaPol(id, prioridad, politica));
            Collections.sort(POLITICAS, new Comparator<EntradaPol>(){
                @Override public int compare(EntradaPol a, EntradaPol b){ return a.prio != b.prio ? Integer.compare(a.prio, b.prio) : a.id.compareTo(b.id); }
            });
        }
        return true;
    }

    public static boolean quitarPolitica(String id){
        synchronized(CERROJO){
            for(int i = 0; i < POLITICAS.size(); i++){
                if(POLITICAS.get(i).id.equals(id)){
                    POLITICAS.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================== consulta

    /** Copia de los grupos, en orden de creación. Siempre incluye {@link #GRUPO_INICIAL}. */
    public static List<Grupo> grupos(){
        synchronized(CERROJO){
            asegurarInicial();
            return new ArrayList<Grupo>(GRUPOS);
        }
    }

    /** Grupo por id, o null. */
    public static Grupo buscar(String id){
        synchronized(CERROJO){
            asegurarInicial();
            for(Grupo g : GRUPOS) if(g.id.equals(id)) return g;
        }
        return null;
    }

    /** Nombre visible de un grupo, o el id si no existe. */
    public static String nombreDe(String id){
        final Grupo g = buscar(id);
        return g == null ? id : g.nombre;
    }

    // ================================================================== cambios

    /**
     * Crea un grupo nuevo con el nombre dado. Falla si se llegó al máximo de grupos o el nombre queda vacío tras limpiarlo. La posición la da la primera
     * política que opine, o la espiral por defecto ({@link #posicionPorDefecto}), sin solaparse con ningún grupo.
     */
    public static Resultado crearGrupo(String nombre){
        final Resultado r = crearGrupoSinAviso(nombre);
        if(r.ok) avisarCambio();
        return r;
    }

    private static Resultado crearGrupoSinAviso(String nombre){
        synchronized(CERROJO){
            asegurarInicial();
            if(GRUPOS.size() >= maximo) return new Resultado(false, "Se alcanzó el máximo de grupos (" + maximo + ").", null);
            final String limpio = limpiarNombre(nombre);
            if(limpio.isEmpty()) return new Resultado(false, "El nombre del grupo no puede estar vacío.", null);
            final double[] pos = elegirPosicion();
            if(pos == null) return new Resultado(false, "No hay sitio libre en la galaxia.", null);
            String id;
            do{
                id = "grupo-" + (++contador);
            }while(buscarSinCerrojo(id) != null);
            final Grupo g = new Grupo(id, limpio, pos[0], pos[1], radio);
            GRUPOS.add(g);
            return new Resultado(true, "", g);
        }
    }

    /** Cambia el nombre visible de un grupo; un nombre vacío tras limpiarlo no se acepta. El id no cambia nunca. */
    public static boolean renombrarGrupo(String id, String nuevo){
        final String limpio = limpiarNombre(nuevo);
        if(limpio.isEmpty()) return false;
        synchronized(CERROJO){
            final Grupo g = buscarSinCerrojo(id);
            if(g == null) return false;
            g.nombre = limpio;
        }
        avisarCambio();
        return true;
    }

    /** Lo llama el juego para guardar la galaxia en disco cada vez que cambia. Null en las pruebas. */
    static volatile Runnable alCambiar;

    private static void avisarCambio(){
        final Runnable r = alCambiar;
        if(r == null) return;
        try{
            r.run();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo guardar la galaxia.", t);
        }
    }

    // ================================================================== lógica pura

    /** Nombre sin caracteres de control, sin '|' ni saltos de línea, con los espacios compactados y de a lo sumo {@link #NOMBRE_MAX} caracteres. */
    public static String limpiarNombre(String n){
        if(n == null) return "";
        final StringBuilder sb = new StringBuilder();
        boolean espacio = false;
        for(int i = 0; i < n.length(); i++){
            final char c = n.charAt(i);
            if(c == '|' || Character.isISOControl(c)) continue;
            if(Character.isWhitespace(c)){
                espacio = sb.length() > 0;
                continue;
            }
            if(espacio){
                sb.append(' ');
                espacio = false;
            }
            sb.append(c);
            if(sb.length() >= NOMBRE_MAX) break;
        }
        return sb.toString();
    }

    /** ¿Se solapa un círculo (x, y, r) con algún grupo, dejando el margen? */
    static boolean solapa(List<Grupo> existentes, double x, double y, double r){
        for(Grupo g : existentes){
            if(Math.hypot(g.x - x, g.y - y) < g.radio + r + Calibracion.GALAXIA_MARGEN - 1e-9) return true;
        }
        return false;
    }

    /**
     * Posición por defecto de un grupo nuevo: una espiral de ángulo áureo alrededor del origen, el primer punto libre. Determinista: la misma lista de
     * grupos da siempre la misma posición. Con {@code existentes} vacío da el origen. Null si no encuentra sitio en 10000 intentos.
     */
    public static double[] posicionPorDefecto(List<Grupo> existentes, double radio, double distancia){
        if(existentes.isEmpty()) return new double[]{0.0, 0.0};
        final double dorado = 2.399963229728653;
        for(int k = existentes.size(); k < existentes.size() + 10000; k++){
            final double a = k * dorado, d = distancia * Math.sqrt(k);
            final double x = Math.cos(a) * d, y = Math.sin(a) * d;
            if(!solapa(existentes, x, y, radio)) return new double[]{x, y};
        }
        return null;
    }

    private static double[] elegirPosicion(){
        for(EntradaPol e : POLITICAS){
            if(!e.activa) continue;
            try{
                final double[] p = e.politica.posicion(Collections.unmodifiableList(new ArrayList<Grupo>(GRUPOS)), GRUPOS.size(), radio, distancia);
                if(p != null && p.length == 2 && Double.isFinite(p[0]) && Double.isFinite(p[1]) && !solapa(GRUPOS, p[0], p[1], radio)) return p;
            }catch(Throwable t){
                e.activa = false;
                Log.err("[MundoInfinito] Política de galaxia '@' desactivada por un fallo.", e.id, t);
            }
        }
        return posicionPorDefecto(GRUPOS, radio, distancia);
    }

    private static void asegurarInicial(){
        if(buscarSinCerrojo(GRUPO_INICIAL) == null) GRUPOS.add(0, new Grupo(GRUPO_INICIAL, "Sistema solar", 0.0, 0.0, radio));
    }

    private static Grupo buscarSinCerrojo(String id){
        for(Grupo g : GRUPOS) if(g.id.equals(id)) return g;
        return null;
    }

    // ================================================================== guardado

    /** Texto para guardar la galaxia: una línea por grupo, "id|nombre|x|y|radio". Los números se escriben con ida y vuelta exacta. */
    public static String aTexto(){
        final StringBuilder sb = new StringBuilder();
        synchronized(CERROJO){
            asegurarInicial();
            for(Grupo g : GRUPOS){
                sb.append(g.id).append('|').append(g.nombre).append('|').append(Double.toString(g.x)).append('|')
                    .append(Double.toString(g.y)).append('|').append(Double.toString(g.radio)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Carga la galaxia guardada, sustituyendo la actual. Texto nulo o vacío: solo el grupo inicial. Una línea mal formada se descarta con aviso, y el
     * resto se conserva; nunca lanza. El máximo y las demás reglas NO se guardan: las fija el addon al cargar.
     */
    public static void desdeTexto(String texto){
        synchronized(CERROJO){
            GRUPOS.clear();
            contador = 1;
            if(texto != null){
                for(String linea : texto.split("\n")){
                    if(linea.isEmpty()) continue;
                    final String[] c = linea.split("\\|", -1);
                    try{
                        if(c.length != 5) throw new NumberFormatException("columnas");
                        final String nombre = limpiarNombre(c[1]);
                        final double x = Double.parseDouble(c[2]), y = Double.parseDouble(c[3]), r = Double.parseDouble(c[4]);
                        if(c[0].isEmpty() || nombre.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !(r > 0.0) || !Double.isFinite(r)
                            || buscarSinCerrojo(c[0]) != null) throw new NumberFormatException("datos");
                        GRUPOS.add(new Grupo(c[0], nombre, x, y, r));
                        if(c[0].startsWith("grupo-")){
                            try{
                                contador = Math.max(contador, Integer.parseInt(c[0].substring(6)));
                            }catch(NumberFormatException ignorar){
                                // un id propio que no sigue el patrón no afecta al contador
                            }
                        }
                    }catch(NumberFormatException e){
                        Log.warn("[MundoInfinito] Grupo de la galaxia descartado (línea mal formada): @", linea);
                    }
                }
            }
            asegurarInicial();
        }
    }

    /** Solo para pruebas: galaxia recién cargada, con las reglas por defecto. */
    static void reiniciar(){
        synchronized(CERROJO){
            GRUPOS.clear();
            POLITICAS.clear();
            maximo = Calibracion.GALAXIA_MAX_GRUPOS;
            distancia = Calibracion.GALAXIA_DISTANCIA_GRUPOS;
            radio = Calibracion.GALAXIA_RADIO_GRUPO;
            contador = 1;
        }
        alCambiar = null;
    }
}
