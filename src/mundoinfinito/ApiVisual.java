package mundoinfinito;

import arc.func.Boolp;
import arc.func.Cons;
import arc.func.Prov;
import arc.graphics.gl.Shader;
import mindustry.graphics.Shaders;

/**
 * API PÚBLICA VISUAL (versión {@link ApiVersion#VISUAL_MAYOR}.{@link ApiVersion#VISUAL_MENOR}). Todo lo que un mod necesita para dibujar, poner efectos,
 * cambiar shaders y preparar mallas, sin tocar el interior de la mod. Es solo visual: nada de aquí puede cambiar la simulación ni el mundo.
 *
 * Reglas que valen para todos los métodos:
 *  - Los ids tienen el formato "idmod:nombre". Un id repetido o inválido devuelve false (y avisa en el log); nunca lanza excepciones.
 *  - El orden entre contribuciones es siempre (capa, prioridad, id de fuente, id): nunca el orden de carga. Menor prioridad = antes.
 *  - Lo que falla (un dibujo, un shader, una pasada, un efecto, una malla) se desactiva con un aviso y el juego sigue.
 *  - Todo lo que toca la GPU corre en el hilo de render; se puede REGISTRAR desde cualquier hilo.
 *  - Una calidad visual (baja, media, alta) fija presupuestos; lo opcional se omite de atrás hacia delante cuando no cabe.
 * Ver docs/API.md.
 */
public final class ApiVisual{
    private ApiVisual(){}

    /** Los shaders de superficie del juego que se pueden sustituir. */
    public enum PuntoShader{ AGUA, BARRO, BREA, ESCORIA, ARKYCITA, CRIOFLUIDO, ESPACIO }

    // ================================================================== dibujos

    /**
     * Un dibujo que se pinta cada fotograma en una capa.
     * @param coste llamadas de dibujo que declara gastar por fotograma
     * @param esencial true: no se omite nunca por presupuesto
     * @param shader id de un shader registrado con el que dibujar (null = ninguno); si falla o no existe se dibuja sin él
     * @param activo condición por fotograma (null = siempre)
     */
    public static boolean registrarDibujo(String fuente, String id, VisualCapa capa, int prioridad, int coste, boolean esencial, String shader, Boolp activo, Runnable dibujar){
        return VisualRegistro.global().registrarDibujo(VisualDibujo.de(fuente, id, capa, prioridad, coste, esencial, shader, activo, dibujar));
    }

    /**
     * Un shader propio. Declara los uniformes que ESCRIBE: cada uno tiene un solo dueño (si dos shaders declaran el mismo, gana el primero en orden
     * canónico y el otro queda desactivado, con aviso). Conviene prefijar los uniformes con el id del mod.
     * @param fabrica crea el programa, la primera vez que se usa, en el hilo de render
     */
    public static boolean registrarShader(String fuente, String id, int prioridad, String[] uniformesEscritos, Prov<VisualPrograma> fabrica){
        return VisualRegistro.global().registrarShader(VisualShaderDef.de(fuente, id, prioridad, uniformesEscritos, fabrica));
    }

    /**
     * Crea un programa a partir de código GLSL. Los uniformes se escriben en {@code uniformes}, que se llama con el programa ya enlazado (es el único sitio
     * correcto para escribirlos). Si el código no compila lanza una excepción: se llama desde la fábrica de {@link #registrarShader} y el registro la recoge.
     */
    public static VisualPrograma programaGlsl(String vertice, String fragmento, Cons<Shader> uniformes){
        return VisualArc.crear(vertice, fragmento, uniformes);
    }

    /** Vértice por defecto de los sprites de Arc, para los shaders de dibujo. */
    public static String verticeSprites(){
        return VisualArc.VERTICE;
    }

    /** Vértice de pantalla completa que usan los shaders de superficie del juego (agua, escoria...), para escribir variantes de ellos. */
    public static String verticeSuperficie(){
        return Shaders.getShaderFi("screenspace.vert").readString();
    }

    /** Una pasada de post-procesado, tras dibujar el fotograma. Se ejecuta mientras quepa en el presupuesto de la calidad. */
    public static boolean registrarPasada(String fuente, String id, int prioridad, int pasadas, long bytesTextura, Runnable ejecutar){
        return VisualRegistro.global().registrarPasada(VisualPasada.de(fuente, id, prioridad, pasadas, bytesTextura, ejecutar));
    }

    /** ¿Está activo este dibujo, shader o pasada? (false si no existe, falló, o perdió un conflicto de uniformes) */
    public static boolean activo(String id){
        return VisualRegistro.global().activo(id);
    }

    /** Desactiva un dibujo, shader o pasada para la sesión. */
    public static void desactivar(String id, String motivo){
        VisualRegistro.global().desactivar(id, motivo);
    }

    /** La calidad visual que se aplica ahora (la elegida por el jugador, rebajada si la medición automática lo decidió). */
    public static VisualCalidad calidad(){
        return VisualRegistro.global().calidad();
    }

    // ================================================================== efectos

    /**
     * Un efecto: algo que se dispara en una posición, con capa y duración (ticks, 60 por segundo). Se dibuja cada fotograma mientras vive.
     * @param coste unidades de coste de dibujo por instancia viva
     * @param esencial los esenciales no se adelgazan con la densidad ni se omiten por presupuesto
     * @param dibujar recibe cada instancia; no debe guardarla (se reutiliza)
     */
    public static boolean registrarEfecto(String fuente, String id, VisualCapa capa, int prioridad, float duracionTicks, int coste, boolean esencial, Cons<VisualEfectoInst> dibujar){
        return VisualEfectos.global().registrarEfecto(VisualEfectoDef.de(fuente, id, capa, prioridad, duracionTicks, coste, esencial, dibujar));
    }

    /** Dispara un efecto. false si se descartó (desconocido, desactivado, adelgazado por la calidad o sin sitio). */
    public static boolean emitirEfecto(String id, float x, float y, float rotacion, float r, float g, float b, float a, Object datos){
        return VisualEfectos.global().emitir(id, x, y, rotacion, r, g, b, a, datos);
    }

    // ================================================================== color ambiental

    /**
     * Registra una contribución al color ambiental (RGBA; el alfa es cuánta oscuridad añade). Varias contribuciones se combinan por media ponderada
     * según el peso, en orden canónico. Solo se nota en mundos con la iluminación activada. No cuenta hasta que se fija un color.
     */
    public static boolean registrarAmbiente(String fuente, String id, int prioridad, float peso){
        return VisualAmbiente.global().registrar(fuente, id, prioridad, peso);
    }

    /** Fija el color que aporta (0 a 1; fuera de rango se recorta). Se puede llamar cada fotograma. */
    public static boolean fijarAmbiente(String id, float r, float g, float b, float a){
        return VisualAmbiente.global().fijar(id, r, g, b, a);
    }

    /** Deja de aportar color. Sin contribuciones activas no se cambia nada del juego. */
    public static void quitarAmbiente(String id){
        VisualAmbiente.global().quitar(id);
    }

    // ================================================================== mallas y texturas

    /**
     * Prepara datos (vértices, píxeles) en un hilo de fondo y los sube a la GPU en el hilo de render, unos pocos por fotograma y en orden de petición.
     * @param trabajo corre en un hilo de fondo; puro (ni GPU ni mundo vivo); no puede devolver null
     * @param subida corre SOLO en el hilo de render: aquí va la GPU
     * @return false si hay demasiados pendientes (se puede volver a pedir)
     */
    public static <T> boolean prepararMalla(String etiqueta, Prov<T> trabajo, Cons<T> subida){
        return VisualMallas.global().preparar(etiqueta, trabajo, subida);
    }

    // ================================================================== shaders del juego

    /**
     * Una variante del shader de un punto del juego (agua, escoria...), válida para un rango de calidades. Sirve para dar una versión más barata en baja y
     * la normal en alta. Si dos variantes compiten por el mismo punto y calidad gana la primera en orden canónico y la otra no se aplica, con aviso.
     * Cada fotograma se pone la ganadora y al terminar se restaura el original: el juego no queda cambiado.
     * @param minima calidad más baja en la que vale (null = baja)
     * @param maxima calidad más alta en la que vale (null = alta)
     * @param fabrica crea el shader (la primera vez que se usa, en el hilo de render); si falla se descarta y queda el original. Para variantes de pantalla
     *                completa use {@link #verticeSuperficie()} y declare {@code uniform sampler2D u_texture} (el búfer del suelo).
     * @param activa condición por fotograma (null = siempre)
     */
    public static boolean registrarVarianteShader(String fuente, String id, PuntoShader punto, int prioridad, VisualCalidad minima, VisualCalidad maxima, final Prov<Shader> fabrica, Boolp activa){
        if(punto == null || fabrica == null) return false;
        return VisualShaderPuntos.global().registrarVariante(fuente, id, VisualShaderPuntos.Punto.valueOf(punto.name()), prioridad, minima, maxima, new Prov<Object>(){
            @Override
            public Object get(){
                return fabrica.get();
            }
        }, activa);
    }

    /**
     * Límite de zoom de los shaders de superficie: cuando el ancho de la cámara (unidades de mundo) lo supera, esos shaders no se animan y el suelo líquido se
     * dibuja plano. Varios mods pueden pedirlo: se combinan por media ponderada según el peso, en orden canónico.
     * @param peso mayor que 0
     * @param activo condición por fotograma (null = siempre)
     */
    public static boolean registrarLimiteZoom(String fuente, String id, int prioridad, float peso, float anchoCamaraMax, Boolp activo){
        return VisualShaderPuntos.global().registrarLimiteZoom(fuente, id, prioridad, peso, anchoCamaraMax, activo);
    }
}
