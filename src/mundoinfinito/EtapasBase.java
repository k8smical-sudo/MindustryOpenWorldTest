package mundoinfinito;

import arc.math.Mathf;

import static mundoinfinito.MundoInfinitoMod.Muestreo.*;

/**
 * Las etapas del terreno base (fase 3). Cada una es una contribución de terreno independiente y registrada en el núcleo; el orden canónico
 * (prioridad) es el orden en que antes se ejecutaba el cuerpo de Muestreo.muestrear:
 *
 *   -900 suelo-serpulo       clima y salinas              -500 vetas              depósitos y su efecto en el piso
 *   -900 suelo-erekir        piso de Erekir               -400 muros              macizos, pasillos y muros de las vetas
 *   -880 volcanico-serpulo   magma y roca caliente        -300 menas              grafito, arenal, menas y menas de pared
 *   -870 brea-agua-serpulo   brea, agua y playa           -200 decoracion         rocas y cristales sueltos
 *   -800 claro-inicial       el claro del spawn           -700 pozos-spawn        pozos garantizados cerca del spawn
 *   -600 crateres            cráteres y respiraderos
 *
 * El código de cada etapa es el de Muestreo.muestrear, repartido: las condiciones y las fórmulas no cambian. Muestreo se conserva como
 * referencia del corpus de regresión (la comparación etapas-completas contra Muestreo debe dar idéntico).
 */
final class EtapasBase{
    private EtapasBase(){}

    /** Todas las etapas, en cualquier orden (el núcleo las ordena). */
    static EtapaBase[] todas(){
        return new EtapaBase[]{
            new SueloSerpulo(), new SueloErekir(), new VolcanicoSerpulo(), new BreaAguaSerpulo(), new ClaroInicial(),
            new PozosSpawn(), new Crateres(), new Vetas(), new Muros(), new Menas(), new Decoracion()
        };
    }

    // ================================================================== suelo (Serpulo y Erekir)

    /** Serpulo: relieve, temperatura y humedad (con los estiramientos del perfil del mundo) -> tabla climática -> salinas. (Primera parte de pisoSerpulo.) */
    static final class SueloSerpulo extends EtapaBase{
        SueloSerpulo(){
            super("suelo-serpulo", -900);
        }

        @Override
        void celda(CeldaBase ce){
            if(ce.ere) return;
            final int s = ce.s, x = ce.x, y = ce.y;
            final double wx = ce.wx, wy = ce.wy;
            float hA = fbm(sem(s, 1), 190f, 4, wx, wy);
            float hB = fbm(sem(s, 2), 100f, 3, wx, wy);
            float wh = suave((fbm(sem(s, 14), 700f, 2, x, y) - 0.35f) * 3.3f);
            float h = est(lerp(hA, hB, wh * 0.60f), ce.par.estirarAlturaSerpulo);
            float t = est(fbm(sem(s, 11), 520f, 3, wx, wy), ce.par.estirarTemperaturaSerpulo);
            float m = est(fbm(sem(s, 12), 430f, 3, wx + 900.0, wy - 300.0), ce.par.estirarHumedadSerpulo);

            int p = climaSerpulo(s, x, y, wx, wy, h, t, m);
            final float alt = Math.max(0f, h - 0.45f);
            ce.h = h;
            ce.tt = t - 0.55f * alt;
            ce.mm = m - 0.45f * alt;

            // salinas: manchas amplias de sal en la arena
            if(p == P_SAND && fbm(sem(s, 90), 90f, 2, x, y) > 0.70f) p = P_SALT;
            ce.p = p;
        }
    }

    /** Erekir: temperatura -> cristal ... terreno por altura ... zonas especiales. Es pisoErekir entera. */
    static final class SueloErekir extends EtapaBase{
        SueloErekir(){
            super("suelo-erekir", -900);
        }

        @Override
        void celda(CeldaBase ce){
            if(!ce.ere) return;
            ce.p = pisoErekir(ce.s, ce.x, ce.y, ce.dist, ce.wx, ce.wy);
        }
    }

    /** Serpulo: campos volcánicos (núcleo de magma rodeado de roca caliente) sobre basalto o carbonizado en zonas muy cálidas. */
    static final class VolcanicoSerpulo extends EtapaBase{
        VolcanicoSerpulo(){
            super("volcanico-serpulo", -880);
        }

        @Override
        void celda(CeldaBase ce){
            if(ce.ere) return;
            int p = ce.p;
            if((p == P_BASALT || p == P_CHARR) && ce.tt > 0.55f){
                float vol = fbm(sem(ce.s, 43), 120f, 3, ce.x, ce.y);
                if(vol > 0.715f) p = P_MAGMA;
                else if(vol > 0.645f) p = P_HOT;
            }
            ce.p = p;
        }
    }

    /** Serpulo: pozas de brea, océanos y lagos con anillos de profundidad, orillas y playas. (Última parte de pisoSerpulo.) */
    static final class BreaAguaSerpulo extends EtapaBase{
        BreaAguaSerpulo(){
            super("brea-agua-serpulo", -870);
        }

        @Override
        void celda(CeldaBase ce){
            if(ce.ere) return;
            final int s = ce.s, x = ce.x, y = ce.y;
            final float dist = ce.dist, h = ce.h, tt = ce.tt, mm = ce.mm;
            int p = ce.p;

            // brea: pozas en suelo oscuro de zonas cálidas
            if(dist > 40f && (p == P_DARKSAND || p == P_CHARR) && tt > 0.5f){
                if(fbm(sem(s, 41), 110f, 3, x, y) > 0.715f) p = P_TAR;
            }

            // agua: océanos (relieve bajo) y lagos interiores, con anillos de profundidad y orilla
            float agua = suave((0.215f - h) / 0.215f);                          // 0 tierra .. 1 mar abierto
            if(dist > 60f){
                float lk = fbm(sem(s, 44), 210f, 4, x, y);
                agua = Math.max(agua, Mathf.clamp((lk - 0.62f) / 0.12f, 0f, 1f) * 0.78f);   // los lagos nunca llegan a "mar profundo"
            }
            final boolean manchada = tt > 0.58f && mm > 0.42f;                   // agua cenagosa (tainted) en zonas cálidas y húmedas
            final boolean fria = p == P_SNOW || p == P_ICESNOW || p == P_ICE || p == P_BLUEMAT;   // la costa depende del suelo que rodea el agua
            if(agua > 0.10f){
                final boolean arenoso = p == P_SAND || p == P_SALT || p == P_DACITE || p == P_SHALE || p == P_STONE;
                if(agua > 0.80f) p = manchada ? P_DEEPT : P_DEEP;
                else if(agua > 0.34f) p = manchada ? P_TW : P_WATER;
                else if(manchada) p = P_DTW;
                else if(fria) p = P_ICE;                                          // costa helada: la orilla de un lago frío es hielo
                else p = arenoso ? P_SANDW : P_DSW;
            }else if(agua > 0.025f){
                // PLAYA: franja de tierra junto al agua con un suelo que combina con la orilla
                if(p == P_GRASS || p == P_DIRT || p == P_MOSS || p == P_SPORE || p == P_MUD) p = tt < 0.38f ? P_DIRT : P_MUD;
                else if(p == P_BASALT || p == P_CHARR || p == P_REDMAT || p == P_HOT || p == P_MAGMA || p == P_TAR) p = P_DARKSAND;
                else if(p == P_BLUEMAT) p = P_ICESNOW;
            }
            ce.p = p;
        }
    }

    // ================================================================== spawn

    /** El claro inicial: elipse orientada con borde irregular. Conserva el bioma que toque y quita solo lo peligroso o intransitable. */
    static final class ClaroInicial extends EtapaBase{
        ClaroInicial(){
            super("claro-inicial", -800);
        }

        @Override
        void celda(CeldaBase ce){
            final Spawn sp = ce.sp;
            final double dx0 = ce.dx0, dy0 = ce.dy0;
            float ecx = (float)(dx0 * sp.cClaro + dy0 * sp.sClaro), ecy = (float)(-dx0 * sp.sClaro + dy0 * sp.cClaro) / sp.razonClaro;
            float dClaro = (float)Math.sqrt(ecx * ecx + ecy * ecy);
            boolean spawn = dClaro < sp.radioLibre * (0.80f + 0.50f * fbm(sem(ce.s, 9006), 14f, 2, ce.x, ce.y));
            ce.spawn = spawn;
            if(spawn){
                int p = ce.p;
                final boolean ere = ce.ere;
                // el spawn conserva el bioma que le toque; solo se quita lo peligroso o intransitable
                if(liquido(p)) p = ere ? P_REGO : secoDe(p);
                if(p == P_MAGMA || p == P_SLAG || p == P_TAR || p == P_HOT) p = ere ? P_YELLOW : P_BASALT;
                ce.p = p;
            }
        }
    }

    /** Pozos garantizados cerca del spawn (elípticos, orientados al azar; el de respiraderos con casillas enteras). */
    static final class PozosSpawn extends EtapaBase{
        PozosSpawn(){
            super("pozos-spawn", -700);
        }

        @Override
        void celda(CeldaBase ce){
            final int s = ce.s, x = ce.x, y = ce.y;
            final double dx0 = ce.dx0, dy0 = ce.dy0;
            final boolean nueva = ce.nueva;
            int pzPiso = -1;
            boolean pzLibre = false;
            float[] poz = ce.par.pozosSpawn ? ce.sp.poz : new float[0];   // pozos del spawn: solo si el mundo los tiene
            for(int k = 0; k < poz.length; k += STRIDE_POZ){
                float ddx = (float)(dx0 - poz[k + 1]), ddy = (float)(dy0 - poz[k + 2]), r = poz[k + 3];
                if(ddx * ddx + ddy * ddy > r * r * 3.4f) continue;
                if((int)poz[k] == 3){
                    // pozo de respiraderos: 3 bloques de 3x3 EXACTOS sobre casillas enteras
                    int bx = Math.round(poz[k + 1]), by = Math.round(poz[k + 2]);
                    boolean ev = false;
                    for(int[] o : (nueva ? ventsPozo(s, k, r) : VENTS_POZO)){
                        if(Math.abs(x - (bx + o[0])) <= 1 && Math.abs(y - (by + o[1])) <= 1){ ev = true; break; }
                    }
                    if(ev){ pzPiso = P_VRHYO; pzLibre = true; break; }
                }
                float rot = poz[k + 4], razon = poz[k + 5];
                float c = (float)Math.cos(rot), sn = (float)Math.sin(rot);
                float ea = ddx * c + ddy * sn, eb = (-ddx * sn + ddy * c) / razon;
                float d = (float)Math.sqrt(ea * ea + eb * eb) / r + (fbm(sem(s, 8800 + k), 6f, 2, x, y) - 0.5f) * 0.55f;
                if(d >= 1.25f) continue;
                pzLibre = true;            // halo despejado de muros
                if(d >= 1f) continue;
                switch((int)poz[k]){
                    case 0: pzPiso = d < 0.55f ? P_WATER : (d < 0.8f ? P_SANDW : P_SAND); break;
                    case 1: pzPiso = d < 0.62f ? P_TAR : P_DARKSAND; break;
                    case 2: pzPiso = d < 0.30f ? P_MAGMA : (d < 0.62f ? P_HOT : P_BASALT); break;
                    case 3: pzPiso = P_RCRATER; break;   // los 3 respiraderos se colocan arriba, con casillas enteras
                    case 4: pzPiso = d < 0.6f ? P_ARKYCITE : P_ARKYIC; break;
                    default: pzPiso = d < 0.55f ? P_SLAG : P_RCRATER;
                }
            }
            boolean enPozo = pzPiso >= 0;
            ce.enPozo = enPozo;
            ce.pzLibre = pzLibre;
            if(enPozo) ce.p = pzPiso;
        }
    }

    // ================================================================== cráteres

    /** Cráteres de impacto (Serpulo), cráteres con respiradero (Erekir) y respiraderos de las generaciones anteriores. */
    static final class Crateres extends EtapaBase{
        Crateres(){
            super("crateres", -600);
        }

        @Override
        void celda(CeldaBase ce){
            final int s = ce.s, x = ce.x, y = ce.y;
            final float dist = ce.dist;
            final boolean nueva = ce.nueva, ere = ce.ere;

            // respiradero de cráter (generaciones anteriores): el bloque 3x3 entero toma el mismo piso (decidido con su centro)
            int ventP = -1;
            if(!nueva){
                long vc = ventCentro(s, x, y);
                if(vc != SIN_VENT) ventP = ventPisoCentro(s, (int)(vc >> 32), (int)vc, ce.sp);
            }
            ce.ventP = ventP;

            int p = ce.p, mena = ce.mena;
            boolean enCrater = false;
            if(ventP >= 0){
                p = ventP;                  // bloque 3x3 de respiradero completo, sin muros, menas ni rocas encima
                enCrater = true;
            }else if(ce.enPozo || ce.pzLibre){
                enCrater = true;
            }else if(!ere){
                int c = craterBioma(p) ? crater(s, x, y, dist) : 0;
                if(c == 2){
                    if(seco(p)) p = (p == P_GRASS || p == P_DIRT || p == P_MOSS || p == P_SPORE || p == P_MUD) ? P_DIRT : P_BASALT;   // borde de impacto
                    enCrater = true;
                    if(rnd(x, y, sem(s, 5101)) < 0.18f) mena = O_CHATARRA;      // borde: chatarra de impacto
                }else if(c >= 1){
                    if(!profundo(p)) p = P_CRATERS;
                    enCrater = true;
                    int pct = ((c - 1) % 5000) / 10;
                    if(c >= 5000 && pct < 28 && dist > 150f) mena = O_TITANIO; // cráteres grandes: núcleo de titanio
                    else if(pct > 55 && rnd(x, y, sem(s, 5102)) < 0.10f) mena = O_CHATARRA;
                }
            }else{
                int c = nueva ? 0 : craterVent(s, x, y, dist);
                if(c == 2){
                    if(seco(p)) p = cratereDe(p);   // el centro 3x3 sin respiradero válido queda como suelo de cráter
                    enCrater = true;
                }else if(c == 1){
                    if(seco(p)) p = cratereDe(p);
                    enCrater = true;
                }
            }
            ce.p = p;
            ce.mena = mena;
            ce.enCrater = enCrater;
        }
    }

    // ================================================================== vetas, muros, menas

    /** Depósitos de menas: dónde hay una veta y de qué tipo; las vetas iniciales dejan el piso en seco y las demás no aparecen bajo el agua. */
    static final class Vetas extends EtapaBase{
        Vetas(){
            super("vetas", -500);
        }

        @Override
        void celda(CeldaBase ce){
            final float[] aux = ce.aux;
            int p = ce.p;
            boolean liq = liquido(p);
            int dep = (ce.enPozo || ce.pzLibre || ce.ventP >= 0 || ce.enCrater && ce.mena != 0) ? -1 : enDeposito(ce.s, ce.x, ce.y, ce.sp, aux, ce.ere, ce.dist);
            boolean inicial = dep >= 0 && (aux[4] == 1f || aux[4] == 3f);
            if(liq){
                // las vetas INICIALES siempre quedan en seco; las demás no aparecen bajo el agua
                if(inicial){ p = ce.ere ? P_REGO : P_SAND; liq = false; }
                else dep = -1;
            }
            ce.p = p;
            ce.dep = dep;
            ce.inicial = inicial;
            ce.tipoDep = dep < 0 ? -1 : dep / 1000;
            ce.radN = dep < 0 ? 0 : dep % 1000;
            ce.depPared = dep >= 0 && aux[4] >= 2f;   // menas de pared (Erekir): la zona es roca con mena dentro
        }
    }

    /** Macizos, pasillos hacia lo esencial y los muros que imponen las vetas (los iniciales nunca quedan tapados; los de pared son roca). */
    static final class Muros extends EtapaBase{
        Muros(){
            super("muros", -400);
        }

        @Override
        void celda(CeldaBase ce){
            final boolean liq = liquido(ce.p);
            if(!liq && !ce.spawn && !ce.enCrater){
                if(hayMuro(ce.s, ce.x, ce.y, ce.wx, ce.wy, ce.dist, ce.ere, ce.sp)){
                    ce.muro = 1;
                    if(ce.dist < 130f && ce.sp.cam.length > 0 && camino(ce.s, ce.sp, ce.x, ce.y)) ce.muro = 0;   // pasillos hacia lo esencial
                }
            }

            // los depósitos iniciales de suelo nunca quedan tapados por muros
            if(ce.inicial && !ce.depPared && ce.tipoDep != D_GRAFITO && ce.radN < 100) ce.muro = 0;
            // depósito de pared: la zona entera (mena + borde) es roca
            if(ce.depPared && !liq && !ce.spawn) ce.muro = 1;
        }
    }

    /** Veta de grafito, arenal, menas del suelo y de pared (solo si un taladro puede llegar a ellas). */
    static final class Menas extends EtapaBase{
        Menas(){
            super("menas", -300);
        }

        @Override
        void celda(CeldaBase ce){
            final int s = ce.s, x = ce.x, y = ce.y, tipoDep = ce.tipoDep, radN = ce.radN;
            final boolean liq = liquido(ce.p), spawn = ce.spawn, depPared = ce.depPared;
            final float[] aux = ce.aux;
            int p = ce.p, mena = ce.mena, muro = ce.muro;

            // veta de grafito: forma variable (maciza, rota, doble, en tramos, dispersa o con costras)
            if(tipoDep == D_GRAFITO && !liq && !spawn){
                if(radN < 85 && fbm(sem(s, 9410), 12f, 2, x, y) > 0.35f) p = P_CARBON;
                muro = muroGrafito((int)aux[2], aux[0], aux[1], s, x, y) ? 2 : 0;
            }
            // arenal: garantiza arena cerca del spawn aunque el bioma no la tenga
            if(tipoDep == D_ARENA && !liq && radN < 92){
                p = fbm(sem(s, 9420), 9f, 2, x, y) > 0.4f ? P_SAND : P_DARKSAND;
                if(!spawn) muro = 0;
            }

            // menas
            if(tipoDep >= 0 && tipoDep != D_GRAFITO && tipoDep != D_ARENA && !liq){
                boolean enMuro = muro != 0;
                switch(tipoDep){
                    case D_COBRE: if(!enMuro) mena = O_COBRE; break;
                    case D_PLOMO: if(!enMuro) mena = O_PLOMO; break;
                    case D_CHATARRA: if(!enMuro) mena = O_CHATARRA; break;
                    case D_CARBON: if(!enMuro) mena = O_CARBON; break;
                    case D_TITANIO: if(!enMuro) mena = O_TITANIO; break;
                    case D_TORIO: if(!enMuro) mena = O_TORIO; break;
                    case D_BERILIO: mena = depPared ? (radN < 100 ? O_BERILIO_MURO : 0) : (enMuro ? O_BERILIO_MURO : O_BERILIO); break;
                    case D_TUNGSTENO: mena = depPared ? (radN < 100 ? O_TUNGSTENO_MURO : 0) : (enMuro ? O_TUNGSTENO_MURO : O_TUNGSTENO); break;
                    case D_TORIO_E: mena = depPared ? (radN < 100 ? O_TORIO_MURO : 0) : (enMuro ? O_TORIO_MURO : O_TORIO_CRISTAL); break;
                    default: break;
                }
            }

            // menas de pared: solo si un taladro puede llegar a ellas
            if(mena == O_BERILIO_MURO || mena == O_TUNGSTENO_MURO || mena == O_TORIO_MURO || muro == 2){
                if(!expuesto(s, x, y, ce.sp)){
                    if(muro == 2) muro = 1;        // grafito enterrado: roca normal
                    if(mena >= O_TORIO_MURO) mena = 0;
                }
            }
            ce.p = p;
            ce.mena = mena;
            ce.muro = muro;
        }
    }

    // ================================================================== decoración

    /** Rocas sueltas y cristales sobre suelo libre (nunca en el spawn, pozos, menas, muros ni agua). */
    static final class Decoracion extends EtapaBase{
        Decoracion(){
            super("decoracion", -200);
        }

        /** Con presupuesto corto la decoración sale más espaciada y repartida (retícula), no cortada a la mitad del chunk. */
        @Override
        public ReglaDegradacion reglaDegradacion(){
            return ReglaDegradacion.REDUCCION_DE_RESOLUCION;
        }

        @Override
        void celda(CeldaBase ce){
            final boolean liq = liquido(ce.p);
            if(ce.muro == 0 && ce.mena == 0 && !liq && !ce.spawn && !ce.enPozo && !ce.pzLibre && ce.ventP < 0){
                final int p = ce.p, s = ce.s, x = ce.x, y = ce.y;
                if((p == P_CRYST || p == P_CRYSTF) && rnd(x, y, sem(s, 5201)) < 0.012f){
                    ce.prop = p == P_CRYSTF ? 3 : 2;
                }else if(rnd(x, y, sem(s, 5202)) < 0.011f){
                    ce.prop = 1;
                }
            }
        }
    }
}
