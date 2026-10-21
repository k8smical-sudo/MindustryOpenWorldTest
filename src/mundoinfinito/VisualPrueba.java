package mundoinfinito;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.util.Time;
import mindustry.Vars;

/**
 * SHADER DE PRUEBA de la capa visual (fase 9): sirve para comprobar en un dispositivo, sin ningún mod, que un shader se registra, se aplica y se
 * desactiva sin afectar a nada más, y que uno que falla se desactiva con un aviso y el juego sigue.
 *
 * Todo está APAGADO por defecto y no cuesta nada mientras lo esté (Ajustes -> OpenWorld: "Prueba visual"):
 *  - "Prueba visual: shader de prueba": un cuadrado sobre el dron, teñido de naranja con un shader que pulsa.
 *  - "Prueba visual: shader que falla": un cuadrado con un shader a propósito mal escrito: debe salir igual (sin shader), el log debe avisar una sola vez y
 *    el resto de dibujos debe seguir.
 */
final class VisualPrueba{
    static final String FUENTE = "mundo-infinito";
    static final String SHADER = "mundo-infinito:prueba-tinte", SHADER_FALLA = "mundo-infinito:prueba-shader-roto";
    static final String DIBUJO = "mundo-infinito:prueba-dibujo", DIBUJO_FALLA = "mundo-infinito:prueba-dibujo-roto";
    /** Uniforme que escribe el shader de prueba (con el prefijo del mod, para no chocar con otros). */
    static final String U_FUERZA = "mundo_infinito_prueba_fuerza";

    private static final String FRAGMENTO =
        "varying lowp vec4 v_color;\n" +
        "varying lowp vec4 v_mix_color;\n" +
        "varying highp vec2 v_texCoords;\n" +
        "uniform highp sampler2D u_texture;\n" +
        "uniform float " + U_FUERZA + ";\n" +
        "\n" +
        "void main(){\n" +
        "  vec4 c = texture2D(u_texture, v_texCoords);\n" +
        "  vec4 t = vec4(mix(c.rgb, vec3(1.0, 0.55, 0.1), " + U_FUERZA + "), c.a);\n" +
        "  gl_FragColor = v_color * t;\n" +
        "}";

    /** Mal escrito a propósito (falta el punto y coma y la llave): no compila. */
    private static final String FRAGMENTO_ROTO = "void main(){ gl_FragColor = vec4(1.0) ";

    private static boolean registrado;

    private VisualPrueba(){}

    static void registrar(){
        if(registrado) return;
        registrado = true;
        final VisualRegistro r = VisualRegistro.global();

        r.registrarShader(VisualShaderDef.de(FUENTE, SHADER, 0, new String[]{U_FUERZA},
            () -> VisualArc.crear(VisualArc.VERTICE, FRAGMENTO, sh -> sh.setUniformf(U_FUERZA, 0.5f + 0.5f * (float)Math.sin(Time.time / 20f)))));
        r.registrarShader(VisualShaderDef.de(FUENTE, SHADER_FALLA, 1, new String[0],
            () -> VisualArc.crear(VisualArc.VERTICE, FRAGMENTO_ROTO, null)));

        r.registrarDibujo(VisualDibujo.de(FUENTE, DIBUJO, VisualCapa.SOBRE_UNIDADES, 0, 1, false, SHADER, Ajustes::pruebaVisual, () -> cuadro(28f, 8f)));
        r.registrarDibujo(VisualDibujo.de(FUENTE, DIBUJO_FALLA, VisualCapa.SOBRE_UNIDADES, 1, 1, false, SHADER_FALLA, Ajustes::pruebaVisualFalla, () -> cuadro(-28f, 5f)));
    }

    /** Un cuadrado blanco junto al jugador. */
    private static void cuadro(float desplazamientoY, float radio){
        if(Vars.player == null) return;
        Draw.color(1f, 1f, 1f, 1f);
        Fill.square(Vars.player.x, Vars.player.y + desplazamientoY, radio);
    }
}
