package mundoinfinito;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.Shader;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;

/**
 * ENLACE de la capa visual con Arc y Mindustry: engancha el registro visual a los triggers de dibujo del motor y envuelve los shaders de Arc.
 * Es lo único de la capa visual que toca la GPU, y solo se ejecuta en el hilo de render (el hilo principal). No hay nada de esto en servidores.
 * La lógica (orden, presupuestos, conflictos de uniformes, fallos) vive en {@link VisualRegistro} y se comprueba sin GPU.
 */
final class VisualArc{
    private static boolean registrado, aviso;

    private VisualArc(){}

    /** Vértice por defecto de los sprites de Arc (el mismo que {@code SpriteBatch.createShader}): los shaders propios de sprites lo reutilizan. */
    static final String VERTICE =
        "attribute vec4 a_position;\n" +
        "attribute vec4 a_color;\n" +
        "attribute vec2 a_texCoord0;\n" +
        "attribute vec4 a_mix_color;\n" +
        "uniform mat4 u_projTrans;\n" +
        "varying vec4 v_color;\n" +
        "varying vec4 v_mix_color;\n" +
        "varying vec2 v_texCoords;\n" +
        "\n" +
        "void main(){\n" +
        "   v_color = a_color;\n" +
        "   v_color.a = v_color.a * (255.0/254.0);\n" +
        "   v_mix_color = a_mix_color;\n" +
        "   v_mix_color.a *= (255.0/254.0);\n" +
        "   v_texCoords = a_texCoord0;\n" +
        "   gl_Position = u_projTrans * a_position;\n" +
        "}";

    /** Salida real de dibujo: las funciones de Arc. */
    static final VisualSalida SALIDA = new VisualSalida(){
        @Override
        public void z(float z){
            Draw.z(z);
        }

        @Override
        public void reiniciar(){
            Draw.reset();
        }

        @Override
        public void enCapa(float z, Runnable r){
            Draw.draw(z, r);   // el motor ordena por z y ejecuta r al volcar el lote: ahí se puede cambiar de shader
        }
    };

    /**
     * Shader de Arc cuyos uniformes se escriben en {@link #apply()}. Es OBLIGATORIO hacerlo ahí: {@code Draw.shader(...)} solo anota qué shader se
     * usará; el lote lo enlaza ({@code bind}) más tarde, al volcar, y justo después llama a {@code apply()}. Escribir un uniforme antes, con otro programa
     * activo, da "GL_INVALID_OPERATION" en cada fotograma (así lo mostró el primer log de un dispositivo real). Los shaders de Mindustry hacen lo mismo.
     */
    static final class ShaderPropio extends Shader{
        private final Cons<Shader> uniformes;

        ShaderPropio(String vertice, String fragmento, Cons<Shader> uniformes){
            super(vertice, fragmento);
            this.uniformes = uniformes;
        }

        @Override
        public void apply(){
            if(uniformes != null) uniformes.get(this);   // el programa ya está enlazado
        }
    }

    /** Un shader de Arc como programa del registro visual. */
    static final class ArcPrograma implements VisualPrograma{
        private final Shader shader;

        ArcPrograma(Shader shader){
            this.shader = shader;
        }

        @Override
        public void aplicar(Runnable dibujo){
            Draw.shader(shader);   // solo anota el shader: el lote lo enlaza y llama a shader.apply() al volcar
            try{
                dibujo.run();
                Draw.flush();      // vuelca con este shader activo
            }finally{
                Draw.shader();
            }
        }

        @Override
        public void liberar(){
            shader.dispose();
        }
    }

    /**
     * Crea un programa a partir del código GLSL. Si el shader no compila, el constructor de Arc lanza una excepción: el registro visual la recoge y
     * desactiva ese shader. Solo se llama desde el hilo de render.
     * @param uniformes lo que se escribe en el shader cada vez que se aplica (null = nada)
     */
    static VisualPrograma crear(String vertice, String fragmento, Cons<Shader> uniformes){
        return new ArcPrograma(new ShaderPropio(vertice, fragmento, uniformes));
    }

    /** Engancha el registro visual al dibujo del motor. Una sola vez; no hace nada en servidores. */
    static void registrar(){
        if(registrado || Vars.headless) return;
        registrado = true;
        Events.run(Trigger.draw, VisualArc::alDibujar);          // dentro de Renderer.draw(), con el dibujo ordenado activo
        Events.run(Trigger.postDraw, VisualArc::alPostProcesar);
        VisualPrueba.registrar();
    }

    private static void alDibujar(){
        if(Vars.state == null || !Vars.state.isGame()) return;
        try{
            final VisualRegistro r = VisualRegistro.global();
            r.configurar(Ajustes.calidadVisual(), Ajustes.calidadAuto());
            r.dibujarFotograma(SALIDA, Core.graphics.getDeltaTime() * 1000f);
        }catch(Throwable t){
            avisar(t);   // la capa visual nunca debe romper el fotograma
        }
    }

    private static void alPostProcesar(){
        if(Vars.state == null || !Vars.state.isGame()) return;
        try{
            VisualRegistro.global().ejecutarPasadas();
        }catch(Throwable t){
            avisar(t);
        }
    }

    private static void avisar(Throwable t){
        if(aviso) return;
        aviso = true;
        Log.err("[MundoInfinito] Visual: error en el enlace con el dibujo (aviso una vez)", t);
    }
}
