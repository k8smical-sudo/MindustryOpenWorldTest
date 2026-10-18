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

    // ================================================================== perfil por semilla (rangos fijos)
    /** Probabilidad de que una celda de 96 casillas tenga un candidato de respiradero. Varía por semilla en [0,35, 0,60] (calibrar). */
    static float densidadRespiraderos(ContextoFenomeno c){
        return 0.35f + 0.25f * c.azar(1, 1, 0x7001);
    }

    /** Umbral del ruido que define las regiones volcánicas de Serpulo: más alto = menos terreno volcánico. Varía por semilla (calibrar). */
    static float umbralVolcanico(ContextoFenomeno c){
        return 0.60f + 0.08f * c.azar(2, 2, 0x7002);
    }

    // ================================================================== respiraderos de Erekir
    static final class Respiraderos implements GeneradorFenomeno{
        @Override public String id(){ return "mundo-infinito:respiraderos-erekir"; }
        @Override public int prioridad(){ return -100; }
        @Override public boolean aplicaA(int dimension){ return Extensiones.generacionNueva() && dimension == 1; }
        @Override public int presupuestoMs(){ return 40; }

        @Override public int celda(){ return 96; }
        @Override public int distanciaMinima(){ return 48; }     // (calibrar) la campaña no fija una; con 96 de celda hay ~1 por cada 15-26 mil casillas
        @Override public int radio(){ return 20; }               // el círculo de mezcla, sesgado en Y, llega a ~19 casillas
        @Override public int intentos(){ return 3; }
        @Override public int macro(){ return 384; }
        @Override public int minimoPorMacro(){ return 3; }       // (calibrar) la campaña garantiza 6-9 por mapa

        @Override
        public boolean proponer(ContextoFenomeno c, int i, int j, int intento, int[] out){
            // los intentos extra (de la garantía mínima) ignoran la probabilidad de la celda: es justo lo que se pide cuando faltan
            if(intento < intentos() && c.azar(i, j, 0x7100) >= densidadRespiraderos(c)) return false;
            out[0] = i * 96 + 8 + (int)(c.azar(i, j, 0x7102 + 2 * intento) * 80f);
            out[1] = j * 96 + 8 + (int)(c.azar(i, j, 0x7103 + 2 * intento) * 80f);
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

        @Override
        public boolean valido(ContextoFenomeno c, int x, int y){
            if(c.distanciaSpawn(x, y) < Math.max(40f, c.radioClaroSpawn() * 1.8f)) return false;   // nunca en el claro inicial
            if(c.cercaDePozoSpawn(x, y, 8f)) return false;                                         // ni encima de los pozos del spawn
            if(!c.esTipoErekir(x, y)) return false;
            // el bloque 3x3: suelo permitido, sin muros ni líquidos (la campaña comprueba "zona libre de sólidos y de otros respiraderos")
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    final int[] o = c.muestra(x + dx, y + dy);
                    if(o[2] != 0 || Muestreo.liquido(o[0]) || !suelo(o[0])) return false;
                }
            }
            // y espacio alrededor para acceder a él: cuatro sondas a 3 casillas
            for(int[] d : ANILLO){
                final int[] o = c.muestra(x + d[0], y + d[1]);
                if(o[2] != 0 || Muestreo.liquido(o[0])) return false;
            }
            return true;
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

            // 1) el respiradero 3x3, sin muros, menas ni rocas encima
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    ctx.ponerPiso(x + dx, y + dy, vent);
                    ctx.ponerBloque(x + dx, y + dy, Blocks.air);
                    ctx.ponerMena(x + dx, y + dy, Blocks.air);
                }
            }

            // 2) "círculo" de mezcla: el suelo de alrededor cambia al del respiradero, así no queda un parche aislado
            final int crad = 6 + (int)(c.azar(x, y, 0x7110) * 9f), crad2 = crad * crad;   // 6..14, como la campaña
            for(int cx = -crad; cx <= crad; cx++){
                for(int cy = -crad; cy <= crad; cy++){
                    final int rx = x + cx, ry = y + cy;
                    if(!ctx.contiene(rx, ry)) continue;
                    final float rcy = cy + cx * 0.9f;   // círculo sesgado en Y
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
