package mundoinfinito;

import arc.func.Prov;

/**
 * Definición de un SHADER propio. Declara los uniformes que ESCRIBE: cada uno tiene un solo dueño. Si dos shaders declaran el mismo, el núcleo avisa y
 * gana el primero en orden canónico (prioridad, id de fuente, id); el otro queda desactivado: nunca hay un comportamiento indefinido. Los uniformes que
 * solo se LEEN no se declaran. Conviene nombrar los uniformes con el prefijo del mod ("minmod_fuerza") para no chocar con otros.
 */
final class VisualShaderDef{
    final String id, fuente;
    final int prioridad;
    final String[] uniformesEscritos;
    /** Crea el programa. Se llama la primera vez que se usa, en el hilo de render; si lanza una excepción el shader se desactiva con aviso. */
    final Prov<VisualPrograma> fabrica;

    private VisualShaderDef(String id, String fuente, int prioridad, String[] uniformesEscritos, Prov<VisualPrograma> fabrica){
        this.id = id;
        this.fuente = fuente;
        this.prioridad = prioridad;
        this.uniformesEscritos = uniformesEscritos == null ? new String[0] : uniformesEscritos.clone();
        this.fabrica = fabrica;
    }

    static VisualShaderDef de(String fuente, String id, int prioridad, String[] uniformesEscritos, Prov<VisualPrograma> fabrica){
        return new VisualShaderDef(id, fuente, prioridad, uniformesEscritos, fabrica);
    }
}
