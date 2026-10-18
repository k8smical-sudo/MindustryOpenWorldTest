package mundoinfinito;

import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.meta.Attribute;
import mundoinfinito.MundoInfinitoMod.Muestreo;

/**
 * REVISIÓN DE LA GENERACIÓN (Parte A del plan), implementada como generadores del propio mundo base a través de la MISMA API que usan los
 * mods: el base se valida con sus propias reglas.
 *
 *  - {@link Respiraderos} (Erekir): sustituye a los respiraderos en rejilla de {@code Muestreo.ventCentro}. Disco de Poisson con distancia
 *    mínima real, comprobación de zona libre, mínimo garantizado por macrocelda, tipo de respiradero según el suelo y mezcla del suelo
 *    alrededor, como {@code ErekirPlanetGenerator}.
 *  - {@link Volcanico} (Serpulo): roca caliente y magma como parte del bioma (antes solo existían dentro del pozo geotérmico del spawn),
 *    con la regla de la campaña: magma donde las cuatro casillas vecinas son roca caliente o magma.
 *
 * Solo actúan en mundos de generación {@link MundoInfinitoMod#GEN_REVISADA} o posterior: los mundos anteriores conservan su terreno exacto.
 *
 * VALORES SIN CALIBRAR: las constantes marcadas con (calibrar) salen de leer la campaña, no de medir mundos generados. Se ajustan con las
 * métricas de la sección C del plan.
 */
final class FenomenosBase{
    private FenomenosBase(){}

    static void registrar(){
        Extensiones.registrarFenomeno(new Respiraderos());
        Extensiones.registrarTerreno(new Volcanico());
    }

    // ================================================================== perfil por semilla (variables por mundo)
    /** Probabilidad de que una celda de 96 casillas tenga un respiradero suelto. [0,30, 0,55] según semilla. */
    static float densidad(ContextoFenomeno c){
        return 0.30f + 0.25f * c.azar(1, 1, 0x7001);
    }

    /** Fracción mínima de terreno transitable alrededor de un respiradero (juicio de balance). [0,50, 0,70] según semilla. */
    static float umbralAbierto(ContextoFenomeno c){
        return 0.50f + 0.20f * c.azar(2, 2, 0x7003);
    }

    /** Umbral del ruido que define las regiones volcánicas de Serpulo: más alto = menos terreno volcánico. Varía por semilla. */
    static float umbralVolcanico(ContextoFenomeno c){
        return 0.60f + 0.08f * c.azar(2, 2, 0x7002);
    }

    /** Bandas de distancia al spawn donde debe haber al menos un respiradero de arranque: cerca, medio y lejos. Cada límite varía ±8 por semilla. */
    static final float[][] BANDAS = {{40f, 72f}, {105f, 165f}, {215f, 330f}};

    static float limite(ContextoFenomeno c, int banda, int lado){
        return BANDAS[banda][lado] + (c.azar(banda, 3 + lado, 0x7004) - 0.5f) * 16f;
    }

    // ================================================================== respiraderos de Erekir
    static final class Respiraderos implements GeneradorFenomeno{
        @Override public String id(){ return "mundo-infinito:respiraderos-erekir"; }
        @Override public int prioridad(){ return -100; }
        @Override public boolean aplicaA(int dimension){ return Extensiones.generacionNueva() && dimension == 1; }
        @Override public int presupuestoMs(){ return 60; }

        @Override public int celda(){ return 96; }
        @Override public int distanciaMinima(){ return 48; }     // (calibrar) la campaña no fija una
        @Override public int radio(){ return 20; }
        @Override public int intentos(){ return 3; }
        @Override public int macro(){ return 384; }
        @Override public int minimoPorMacro(){ return 3; }       // (calibrar)

        // ------------------------------------------------------------------ arranque: un respiradero cerca, otro medio y otro lejos
        /** Arranque de un mundo: cada entrada son 4 enteros {celda i, celda j, x virtual, y virtual}. Depende solo de la semilla. */
        private static final class Arranque{
            final int semilla, dim;
            final int[] datos;   // k * 4 enteros por respiradero de arranque

            Arranque(int semilla, int dim, int[] datos){
                this.semilla = semilla;
                this.dim = dim;
                this.datos = datos;
            }
        }

        private static volatile Arranque cache;

        private static int candX(ContextoFenomeno c, int i, int j, int intento){
            return i * 96 + 8 + (int)(c.azar(i, j, 0x7102 + 2 * intento) * 80f);
        }

        private static int candY(ContextoFenomeno c, int i, int j, int intento){
            return j * 96 + 8 + (int)(c.azar(i, j, 0x7103 + 2 * intento) * 80f);
        }

        private static Arranque arranque(Respiraderos g, ContextoFenomeno c){
            Arranque a = cache;
            if(a == null || a.semilla != c.semilla || a.dim != c.dimension){
                a = new Arranque(c.semilla, c.dimension, calcularArranque(g, c));
                cache = a;
            }
            return a;
        }

        /** Elige, por banda, un respiradero válido, sin cercanos a otro de arranque (Chebyshev >= 3 celdas). Si una banda no tiene ninguno, se amplía. */
        private static int[] calcularArranque(Respiraderos g, ContextoFenomeno c){
            final int[] escogido = new int[4 * 4];   // hasta 4 entradas: 3 obligatorias + 1 opcional
            final boolean[] llena = new boolean[3];
            int n = 0;
            for(int pasada = 0; pasada < 2; pasada++){
                for(int b = 0; b < 3; b++){
                    if(llena[b]) continue;
                    final float lo = pasada == 0 ? limite(c, b, 0) : Math.max(40f, limite(c, b, 0) - 25f);
                    final float hi = pasada == 0 ? limite(c, b, 1) : limite(c, b, 1) + 40f;
                    final int[] buscado = buscarEnBanda(g, c, lo, hi, escogido, n, b);
                    if(buscado != null){
                        System.arraycopy(buscado, 0, escogido, n * 4, 4);
                        n++;
                        llena[b] = true;
                    }
                }
            }
            // cuarto respiradero opcional (la mitad de los mundos): en banda media o lejana
            if(n < 4 && c.azar(7, 7, 0x7320) < 0.5f){
                final int b = 1 + (c.azar(8, 8, 0x7321) < 0.5f ? 0 : 1);
                final int[] extra = buscarEnBanda(g, c, limite(c, b, 0), limite(c, b, 1), escogido, n, b);
                if(extra != null){
                    System.arraycopy(extra, 0, escogido, n * 4, 4);
                    n++;
                }
            }
            return java.util.Arrays.copyOf(escogido, n * 4);
        }

        /** Entre las celdas de la banda, la primera (en orden de semilla) que es válida y no está cerca de los ya escogidos. */
        private static int[] buscarEnBanda(Respiraderos g, ContextoFenomeno c, float lo, float hi, int[] e, int n, int b){
            final int rango = (int)Math.ceil(hi / 96f) + 1;
            final java.util.ArrayList<int[]> ok = new java.util.ArrayList<>();
            for(int j = -rango; j <= rango - 1; j++){
                for(int i = -rango; i <= rango - 1; i++){
                    final int x = candX(c, i, j, 0), y = candY(c, i, j, 0);
                    final double d = Math.hypot(x, y);
                    if(d < lo || d > hi) continue;
                    if(!g.valido(c, x, y)) continue;
                    boolean lejos = true;
                    for(int k = 0; k < n; k++){
                        if(Math.max(Math.abs(e[k * 4] - i), Math.abs(e[k * 4 + 1] - j)) < 3){ lejos = false; break; }
                    }
                    if(lejos) ok.add(new int[]{i, j, x, y});
                }
            }
            if(ok.isEmpty()) return null;
            final int pick = Math.min(ok.size() - 1, (int)(c.azar(b + 1, 9, 0x7310) * ok.size()));
            final int[] r = ok.get(pick);
            return r;
        }

        /** Índice de arranque de la celda (i, j), o -1. */
        private static int indice(Arranque a, int i, int j){
            for(int k = 0; k < a.datos.length; k += 4){
                if(a.datos[k] == i && a.datos[k + 1] == j) return k / 4;
            }
            return -1;
        }

        /** ¿Está (i, j) pegada a un respiradero de arranque? Ahí no se pone ningún otro (así el de arranque siempre se acepta). */
        private static boolean suprimida(Arranque a, int i, int j){
            for(int k = 0; k < a.datos.length; k += 4){
                final int di = Math.abs(a.datos[k] - i), dj = Math.abs(a.datos[k + 1] - j);
                if(di <= 1 && dj <= 1 && !(di == 0 && dj == 0)) return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ motor de fenómenos
        @Override
        public boolean proponer(ContextoFenomeno c, int i, int j, int intento, int[] out){
            final Arranque a = arranque(this, c);
            final int k = indice(a, i, j);
            if(k >= 0){
                if(intento != 0) return false;
                out[0] = a.datos[k * 4 + 2];
                out[1] = a.datos[k * 4 + 3];
                return true;
            }
            if(suprimida(a, i, j)) return false;
            // los intentos extra (garantía mínima por macrocelda) ignoran la probabilidad de la celda
            if(intento < intentos() && c.azar(i, j, 0x7100) >= densidad(c)) return false;
            out[0] = candX(c, i, j, intento);
            out[1] = candY(c, i, j, intento);
            return true;
        }

        /** Suelos secos de Erekir donde la campaña pone respiraderos (no cristal, no líquidos, no arkycita ni escoria). */
        static boolean suelo(int p){
            switch(p){
                case Muestreo.P_RHYO: case Muestreo.P_ROUGH: case Muestreo.P_RCRATER: case Muestreo.P_BERYL: case Muestreo.P_ARKYIC:
                case Muestreo.P_YELLOW: case Muestreo.P_YPLATES: case Muestreo.P_REGO: case Muestreo.P_RED: case Muestreo.P_DENSERED:
                case Muestreo.P_REDICE: case Muestreo.P_CARBON: case Muestreo.P_FERRIC: case Muestreo.P_FCRATER:
                    return true;
                default:
                    return false;
            }
        }

        private static final int[][] ANILLO = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}};

        /**
         * JUICIO DE BALANCE de una posición: fracción de terreno transitable (sin muro ni líquido) en una rejilla 7x7 de paso 2 alrededor.
         * Un respiradero encerrado por muros o metido en un lago no sirve de nada en el juego; se descarta por debajo de umbralAbierto.
         */
        static float abierto(ContextoFenomeno c, int x, int y){
            int buenas = 0, total = 0;
            for(int dy = -6; dy <= 6; dy += 2){
                for(int dx = -6; dx <= 6; dx += 2){
                    final int[] o = c.muestra(x + dx, y + dy);
                    total++;
                    if(o[2] == 0 && !Muestreo.liquido(o[0])) buenas++;
                }
            }
            return buenas / (float)total;
        }

        @Override
        public boolean valido(ContextoFenomeno c, int x, int y){
            if(c.distanciaSpawn(x, y) < Math.max(40f, c.radioClaroSpawn() * 1.8f)) return false;   // nunca en el claro inicial
            if(c.cercaDePozoSpawn(x, y, 8f)) return false;                                         // ni encima de los pozos del spawn
            if(!c.esTipoErekir(x, y)) return false;
            for(int dy = -1; dy <= 1; dy++){                                                        // bloque 3x3 limpio
                for(int dx = -1; dx <= 1; dx++){
                    final int[] o = c.muestra(x + dx, y + dy);
                    if(o[2] != 0 || Muestreo.liquido(o[0]) || !suelo(o[0])) return false;
                }
            }
            for(int[] d : ANILLO){                                                                  // acceso: sin muros ni líquidos a 3 casillas
                final int[] o = c.muestra(x + d[0], y + d[1]);
                if(o[2] != 0 || Muestreo.liquido(o[0])) return false;
            }
            return abierto(c, x, y) >= umbralAbierto(c);                                            // juicio de balance
        }

        @Override
        public void aplicar(ContextoTerreno ctx, ContextoFenomeno c, int x, int y){
            // el tipo de respiradero y de suelo sale del suelo del centro, como en la campaña
            final int code = c.codigoPiso(x, y);
            Block floor = Blocks.rhyolite, second = Blocks.rhyoliteCrater, vent = Blocks.rhyoliteVent;
            int xDir = 1;
            if(code == Muestreo.P_BERYL || code == Muestreo.P_ARKYIC){
                floor = second = Blocks.arkyicStone;
                vent = Blocks.arkyicVent;
            }else if(code == Muestreo.P_YELLOW || code == Muestreo.P_YPLATES || code == Muestreo.P_REGO){
                floor = Blocks.yellowStone;
                second = Blocks.yellowStonePlates;
                vent = Blocks.yellowStoneVent;
            }else if(code == Muestreo.P_RED || code == Muestreo.P_DENSERED){
                floor = Blocks.denseRedStone;
                second = Blocks.redStone;
                vent = Blocks.redStoneVent;
                xDir = -1;
            }else if(code == Muestreo.P_CARBON){
                floor = second = Blocks.carbonStone;
                vent = Blocks.carbonVent;
            }

            for(int dy = -1; dy <= 1; dy++){                       // 1) respiradero 3x3 limpio
                for(int dx = -1; dx <= 1; dx++){
                    ctx.ponerPiso(x + dx, y + dy, vent);
                    ctx.ponerBloque(x + dx, y + dy, Blocks.air);
                    ctx.ponerMena(x + dx, y + dy, Blocks.air);
                }
            }

            // 2) mezcla del suelo alrededor (círculo sesgado, radio 6..14 por respiradero)
            final int crad = 6 + (int)(c.azar(x, y, 0x7110) * 9f), crad2 = crad * crad;
            for(int cx = -crad; cx <= crad; cx++){
                for(int cy = -crad; cy <= crad; cy++){
                    final int rx = x + cx, ry = y + cy;
                    if(!ctx.contiene(rx, ry)) continue;
                    final float rcy = cy + cx * 0.9f;
                    final float borde = crad2 - c.ruido(0x7111, 8f, 2, rx, ry + rx * 2f * xDir) * crad2 * 1.1f;
                    if(cx * cx + rcy * rcy > borde) continue;
                    final Block actual = ctx.piso(rx, ry);
                    if(!(actual instanceof Floor)) continue;
                    final Floor f = (Floor)actual;
                    if(f.attributes.get(Attribute.steam) != 0f || f.isLiquid || actual == Blocks.roughRhyolite || actual == Blocks.arkyciteFloor || actual == Blocks.slag) continue;
                    final Block nuevo = c.azar(rx, ry, 0x7112) < 0.08f ? second : floor;
                    ctx.ponerPiso(rx, ry, nuevo);
                    final Block muro = ctx.bloque(rx, ry);
                    if(muro != null && muro != Blocks.air && muro.isStatic()) ctx.ponerBloque(rx, ry, ((Floor)nuevo).wall);
                }
            }
        }
    }

    // ================================================================== roca caliente y magma de Serpulo
    static final class Volcanico implements GeneradorTerreno{
        @Override public String id(){ return "mundo-infinito:volcanico-serpulo"; }
        @Override public int prioridad(){ return -90; }
        @Override public boolean aplicaA(int dimension){ return Extensiones.generacionNueva() && dimension == 0; }
        @Override public int presupuestoMs(){ return 30; }

        /** Rocas secas sobre las que puede haber roca caliente (la campaña la pone entre basalto, arena oscura y rocas áridas). */
        private static boolean admite(Block b){
            return b == Blocks.basalt || b == Blocks.charr || b == Blocks.darksand || b == Blocks.stone || b == Blocks.dacite || b == Blocks.shale;
        }

        /** ¿Es (x, y) roca caliente? Región volcánica de baja frecuencia + vetas finas (|0,5 - ruido| <= 0,035, como la campaña) + suelo admitido. */
        private static boolean caliente(ContextoFenomeno c, ContextoTerreno ctx, int x, int y, float umbral, float rSpawn){
            if(c.ruido(0x7201, 260f, 3, x, y) <= umbral) return false;
            if(Math.abs(0.5f - c.ruido(0x7202, 80f, 4, x - 90, y)) > 0.035f) return false;
            if(c.distanciaSpawn(x, y) < rSpawn) return false;
            // dentro del chunk se lee su suelo actual; en el anillo de 1 casilla de fuera, el terreno base (misma función pura)
            final Block piso = ctx.contiene(x, y) ? ctx.piso(x, y) : c.pisoBase(x, y);
            return admite(piso);
        }

        @Override
        public void generar(ContextoTerreno ctx){
            final ContextoFenomeno c = ctx.lectura;
            final int T = ContextoTerreno.TAM, N = T + 2, X0 = ctx.x0 - 1, Y0 = ctx.y0 - 1;
            final float umbral = umbralVolcanico(c), rSpawn = Math.max(40f, c.radioClaroSpawn() * 1.8f);
            final boolean[] hot = new boolean[N * N];
            boolean alguna = false;
            for(int j = 0; j < N; j++){
                for(int i = 0; i < N; i++){
                    if(caliente(c, ctx, X0 + i, Y0 + j, umbral, rSpawn)){
                        hot[j * N + i] = true;
                        alguna = true;
                    }
                }
            }
            if(!alguna) return;
            for(int ly = 0; ly < T; ly++){
                for(int lx = 0; lx < T; lx++){
                    final int i = lx + 1, j = ly + 1;
                    if(!hot[j * N + i]) continue;
                    final int x = ctx.x0 + lx, y = ctx.y0 + ly;
                    final boolean magma = hot[j * N + i - 1] && hot[j * N + i + 1] && hot[(j - 1) * N + i] && hot[(j + 1) * N + i];
                    ctx.ponerPiso(x, y, magma ? Blocks.magmarock : Blocks.hotrock);
                    ctx.ponerMena(x, y, Blocks.air);   // la campaña quita la mena de la roca caliente
                    final Block b = ctx.bloque(x, y);
                    if(b != null && b != Blocks.air && !b.solid) ctx.ponerBloque(x, y, Blocks.air);   // sin rocas sueltas sobre el magma
                }
            }
        }
    }
}
