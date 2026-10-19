package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

import static mundoinfinito.MundoInfinitoMod.Muestreo.esE;
import static mundoinfinito.MundoInfinitoMod.Muestreo.fbm;
import static mundoinfinito.MundoInfinitoMod.Muestreo.sem;

/**
 * ESTADO DE UNA CASILLA mientras la atraviesan las etapas del terreno base (fase 3). Un objeto por hilo, reutilizado.
 *
 * Servicio común de SOLO LECTURA (lo calcula {@link #iniciar} una vez por casilla; ninguna etapa lo modifica): semilla efectiva, spawn,
 * coordenadas, distancia al origen, deformación del ruido (wx, wy), dominio (Serpulo/Erekir) y buffer auxiliar.
 * Estado MUTABLE: lo que cada etapa deja para las siguientes (piso, mena, muro, decoración y las marcas que se pasan entre etapas).
 * Una etapa que falta deja su parte en el valor neutro de {@link #iniciar}.
 */
final class CeldaBase{
    // ---- servicio de solo lectura
    int s, x, y;
    Muestreo.Spawn sp;
    float[] aux;
    boolean nueva;
    double dx0, dy0, wx, wy;
    float dist;
    boolean ere;

    // ---- estado mutable (lo que se pasan las etapas)
    int p, mena, muro, prop;
    boolean spawn, enPozo, pzLibre, enCrater, inicial, depPared;
    int ventP, dep, tipoDep, radN;
    /** Altura, y temperatura y humedad ya enfriadas/secadas por la altura (Serpulo): las produce el suelo y las leen el volcánico y el agua. */
    float h, tt, mm;

    /** Prepara la casilla (x, y): calcula el servicio de solo lectura y deja el estado en neutro. Mismas fórmulas que el comienzo de Muestreo.muestrear. */
    void iniciar(int x, int y){
        this.x = x;
        this.y = y;
        dx0 = x;
        dy0 = y;
        dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
        wx = x + (fbm(sem(s, 50), 90f, 3, x, y) - 0.5) * 70.0;
        wy = y + (fbm(sem(s, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;
        ere = esE(s, x, y, sp);

        p = Muestreo.P_STONE;
        mena = 0;
        muro = 0;
        prop = 0;
        spawn = false;
        enPozo = false;
        pzLibre = false;
        enCrater = false;
        inicial = false;
        depPared = false;
        ventP = -1;
        dep = -1;
        tipoDep = -1;
        radN = 0;
        h = 0f;
        tt = 0f;
        mm = 0f;
    }
}
