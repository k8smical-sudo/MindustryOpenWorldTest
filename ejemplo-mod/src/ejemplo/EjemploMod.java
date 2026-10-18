package ejemplo;

import arc.util.Log;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.mod.Mod;
import mindustry.world.Block;
import mundoinfinito.*;

/** Usa las cinco extensiones de la API v2 con el mínimo de código. */
public class EjemploMod extends Mod{
    static final Team EQUIPO = Team.malis;

    public EjemploMod(){
        // 1) TERRENO: una veta de nieve en Serpulo, pura (solo ctx.ruido / ctx.azar)
        OpenWorldAPI.registrarTerreno(new GeneradorTerreno(){
            public String id(){ return "ejemplo:veta-nieve"; }
            public boolean aplicaA(int dim){ return dim == 0; }
            public void generar(ContextoTerreno ctx){
                for(int ly = 0; ly < ContextoTerreno.TAM; ly++){
                    for(int lx = 0; lx < ContextoTerreno.TAM; lx++){
                        int x = ctx.x0 + lx, y = ctx.y0 + ly;
                        if(Math.abs(0.5f - ctx.ruido(1, 60f, 3, x, y)) < 0.02f && !ctx.esMuro(x, y)) ctx.ponerPiso(x, y, Blocks.snow);
                    }
                }
            }
        });

        // 2) FENÓMENO: cráteres separados >= 70 casillas, un candidato por celda de 120
        OpenWorldAPI.registrarFenomeno(new GeneradorFenomeno(){
            public String id(){ return "ejemplo:crateres"; }
            public int celda(){ return 120; }
            public int distanciaMinima(){ return 70; }
            public int radio(){ return 5; }
            public boolean proponer(ContextoFenomeno c, int i, int j, int intento, int[] out){
                if(c.azar(i, j, 1) > 0.5f) return false;
                out[0] = i * 120 + 10 + (int)(c.azar(i, j, 2 + intento) * 100f);
                out[1] = j * 120 + 10 + (int)(c.azar(i, j, 3 + intento) * 100f);
                return true;
            }
            public boolean valido(ContextoFenomeno c, int x, int y){
                return c.distanciaSpawn(x, y) > 60 && !c.muroBase(x, y) && !c.liquidoBase(x, y);
            }
            public void aplicar(ContextoTerreno ctx, ContextoFenomeno c, int x, int y){
                for(int dy = -5; dy <= 5; dy++) for(int dx = -5; dx <= 5; dx++){
                    if(dx * dx + dy * dy <= 25) ctx.ponerPiso(x + dx, y + dy, Blocks.craters);
                }
            }
        });

        // 3) ESTRUCTURA: una torreta abandonada con una unidad, colocada UNA vez por ancla
        OpenWorldAPI.registrarEstructura(new GeneradorEstructura(){
            public String id(){ return "ejemplo:puesto"; }
            public int celda(){ return 160; }
            public int radio(){ return 4; }
            public boolean ancla(ContextoFenomeno c, int i, int j, int[] out){
                if(c.azar(i, j, 1) > 0.4f) return false;
                out[0] = i * 160 + 20 + (int)(c.azar(i, j, 2) * 120f);
                out[1] = j * 160 + 20 + (int)(c.azar(i, j, 3) * 120f);
                return c.distanciaSpawn(out[0], out[1]) > 80;
            }
            public void colocar(ContextoColocacion ctx){
                if(!ctx.libre(ctx.anclaX, ctx.anclaY)) return;
                ctx.poner(ctx.anclaX, ctx.anclaY, Blocks.duo, EQUIPO, 0);
                ctx.unidad(UnitTypes.dagger, EQUIPO, ctx.anclaX + 2, ctx.anclaY);
            }
        });

        // 4) ENTIDADES: las unidades de ese equipo duermen fuera de vista; el equipo siempre se guarda
        OpenWorldAPI.registrarEquipo(EQUIPO);
        OpenWorldAPI.registrarPolitica(new PoliticaSueno(){
            public String id(){ return "ejemplo:sueno"; }
            public boolean duerme(mindustry.gen.Unit u){ return u.team == EQUIPO; }
        });

        // 5) MUNDO: llano de arena con un único muro cada cierto tramo; se abre desde el portal
        OpenWorldAPI.registrarMundo(MundoRegistrado.nuevo("ejemplo:llanura", "Llanura de ejemplo", ctx -> {
            for(int ly = 0; ly < ContextoTerreno.TAM; ly++){
                for(int lx = 0; lx < ContextoTerreno.TAM; lx++){
                    int x = ctx.x0 + lx, y = ctx.y0 + ly;
                    ctx.ponerPiso(x, y, Blocks.sand);
                    if(ctx.azar(x, y, 1) < 0.01f) ctx.ponerBloque(x, y, Blocks.sandBoulder);
                }
            }
        }));
        Log.info("[ejemplo] extensiones registradas");
    }
}
