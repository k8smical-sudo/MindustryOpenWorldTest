package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MOTOR DE FENÓMENOS: elige dónde van los fenómenos de un {@link GeneradorFenomeno} con muestreo de disco de Poisson por celdas.
 *
 * Es una función pura de (semilla, dimensión, generador): cualquier chunk, desde cualquier hilo, obtiene exactamente los mismos puntos, sin
 * ver los chunks vecinos ni guardar nada. Las cachés de aquí solo evitan recalcular.
 *
 *  1. Cada celda (lado g.celda()) tiene como mucho UN candidato: el primero de sus intentos que sea válido (reintentos acotados).
 *  2. Un candidato se acepta si ningún candidato vecino a menos de g.distanciaMinima() tiene mejor rango (hash determinista). Así el resultado
 *     no depende del orden de evaluación y NINGÚN par aceptado queda más cerca que la distancia mínima.
 *  3. Mínimo garantizado: si una macrocelda queda con menos de g.minimoPorMacro() puntos, se añaden candidatos de rechazo o intentos extra
 *     que respeten la distancia mínima con todo lo aceptado alrededor y queden a >= distanciaMinima/2 del borde de la macrocelda (así dos
 *     macrocélulas vecinas nunca añaden dos puntos demasiado cercanos entre sí).
 */
final class Fenomenos{
    private Fenomenos(){}

    private static final int[] NINGUNO = new int[0];
    private static final int INTENTOS_EXTRA = 4;
    private static final int MAX_CANDIDATOS = 80_000, MAX_MACROS = 20_000;

    private static final class Mapas{
        final ConcurrentHashMap<Long, int[]> cand = new ConcurrentHashMap<>();    // celda -> {x, y, rango} o NINGUNO
        final ConcurrentHashMap<Long, Boolean> acc = new ConcurrentHashMap<>();   // celda -> ¿su candidato se acepta?
        final ConcurrentHashMap<Long, int[]> macro = new ConcurrentHashMap<>();   // macrocelda -> {x0, y0, x1, y1, ...}
    }

    private static final class Estado{
        final int semilla, dim;
        final ConcurrentHashMap<String, Mapas> porGenerador = new ConcurrentHashMap<>();

        Estado(int semilla, int dim){
            this.semilla = semilla;
            this.dim = dim;
        }
    }

    private static volatile Estado estado;

    static void reiniciar(){
        estado = null;
    }

    private static Mapas mapas(String id, int semilla, int dim){
        Estado e = estado;
        if(e == null || e.semilla != semilla || e.dim != dim){
            e = new Estado(semilla, dim);
            estado = e;
        }
        return e.porGenerador.computeIfAbsent(id, k -> new Mapas());
    }

    private static long clave(int i, int j){
        return ((long)i << 32) ^ (j & 0xffffffffL);
    }

    private static int rango(ContextoFenomeno c, int i, int j){
        return Muestreo.hash(i, j, Muestreo.sem(c.semillaMod, 0x51ED));
    }

    /** ¿El candidato n (celda in, jn) tiene mejor rango que c (celda ic, jc)? Orden total: rango, luego celda. */
    private static boolean gana(int rn, int in, int jn, int rc, int ic, int jc){
        if(rn != rc) return rn < rc;
        if(jn != jc) return jn < jc;
        return in < ic;
    }

    private static int tamMacro(GeneradorFenomeno g){
        return g.macro() > 0 ? Math.max(g.macro(), g.celda()) : g.celda() * 8;
    }

    // ------------------------------------------------------------------ candidatos y aceptación
    private static int[] candidato(GeneradorFenomeno g, ContextoFenomeno c, Mapas m, int i, int j){
        final long k = clave(i, j);
        final int[] hecho = m.cand.get(k);
        if(hecho != null) return hecho;
        final int[] out = new int[2];
        int[] res = NINGUNO;
        for(int a = 0; a < g.intentos(); a++){
            if(!g.proponer(c, i, j, a, out)) continue;
            if(!g.valido(c, out[0], out[1])) continue;
            res = new int[]{out[0], out[1], rango(c, i, j)};
            break;
        }
        if(m.cand.size() > MAX_CANDIDATOS) m.cand.clear();
        m.cand.put(k, res);
        return res;
    }

    private static int anillo(GeneradorFenomeno g){
        return (g.distanciaMinima() + g.celda() - 1) / g.celda();
    }

    private static boolean aceptado(GeneradorFenomeno g, ContextoFenomeno c, Mapas m, int i, int j, int[] cand){
        final long k = clave(i, j);
        final Boolean hecho = m.acc.get(k);
        if(hecho != null) return hecho;
        final int R = anillo(g);
        final long d2min = (long)g.distanciaMinima() * g.distanciaMinima();
        boolean ok = true;
        busqueda:
        for(int dj = -R; dj <= R; dj++){
            for(int di = -R; di <= R; di++){
                if(di == 0 && dj == 0) continue;
                final int[] n = candidato(g, c, m, i + di, j + dj);
                if(n.length == 0) continue;
                final long dx = n[0] - cand[0], dy = n[1] - cand[1];
                if(dx * dx + dy * dy >= d2min) continue;
                if(gana(n[2], i + di, j + dj, cand[2], i, j)){
                    ok = false;
                    break busqueda;
                }
            }
        }
        if(m.acc.size() > MAX_CANDIDATOS) m.acc.clear();
        m.acc.put(k, ok);
        return ok;
    }

    // ------------------------------------------------------------------ macrocelda (con mínimo garantizado)
    private static int[] macroPuntos(GeneradorFenomeno g, ContextoFenomeno c, Mapas m, int mi, int mj){
        final long k = clave(mi, mj);
        final int[] hecho = m.macro.get(k);
        if(hecho != null) return hecho;

        final int tam = tamMacro(g), celda = g.celda();
        final int mx0 = mi * tam, my0 = mj * tam, mx1 = mx0 + tam - 1, my1 = my0 + tam - 1;
        final ArrayList<int[]> puntos = new ArrayList<>();
        for(int j = Math.floorDiv(my0, celda); j <= Math.floorDiv(my1, celda); j++){
            for(int i = Math.floorDiv(mx0, celda); i <= Math.floorDiv(mx1, celda); i++){
                final int[] n = candidato(g, c, m, i, j);
                if(n.length == 0 || n[0] < mx0 || n[0] > mx1 || n[1] < my0 || n[1] > my1) continue;
                if(aceptado(g, c, m, i, j, n)) puntos.add(new int[]{n[0], n[1]});
            }
        }
        final int minimo = g.macro() > 0 ? g.minimoPorMacro() : 0;
        if(puntos.size() < minimo) completar(g, c, m, puntos, minimo, mx0, my0, mx1, my1);

        final int[] r = new int[puntos.size() * 2];
        for(int q = 0; q < puntos.size(); q++){
            r[2 * q] = puntos.get(q)[0];
            r[2 * q + 1] = puntos.get(q)[1];
        }
        if(m.macro.size() > MAX_MACROS) m.macro.clear();
        m.macro.put(k, r);
        return r;
    }

    private static void completar(GeneradorFenomeno g, ContextoFenomeno c, Mapas m, ArrayList<int[]> puntos, int minimo, int mx0, int my0, int mx1, int my1){
        final int celda = g.celda(), dm = g.distanciaMinima(), borde = dm / 2, R = anillo(g);
        final long d2min = (long)dm * dm;
        final ArrayList<int[]> extra = new ArrayList<>();   // {x, y, rango}
        final int[] out = new int[2];
        for(int j = Math.floorDiv(my0, celda); j <= Math.floorDiv(my1, celda); j++){
            for(int i = Math.floorDiv(mx0, celda); i <= Math.floorDiv(mx1, celda); i++){
                final int[] n = candidato(g, c, m, i, j);
                if(n.length > 0){
                    extra.add(new int[]{n[0], n[1], n[2]});   // candidato válido que perdió contra un vecino: puede servir
                    continue;
                }
                // la celda no tenía ningún intento válido: reintentos extra, acotados
                for(int a = g.intentos(); a < g.intentos() + INTENTOS_EXTRA; a++){
                    if(!g.proponer(c, i, j, a, out) || !g.valido(c, out[0], out[1])) continue;
                    extra.add(new int[]{out[0], out[1], Muestreo.hash(i, j, Muestreo.sem(c.semillaMod, 0x51EE + a))});
                    break;
                }
            }
        }
        extra.sort((p, q) -> p[2] != q[2] ? Integer.compare(p[2], q[2]) : (p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1])));

        for(int[] e : extra){
            if(puntos.size() >= minimo) return;
            if(e[0] < mx0 + borde || e[0] > mx1 - borde || e[1] < my0 + borde || e[1] > my1 - borde) continue;   // cerca del borde: lo podría chocar otra macrocelda
            boolean ok = true;
            for(int[] p : puntos){
                final long dx = p[0] - e[0], dy = p[1] - e[1];
                if(dx * dx + dy * dy < d2min){ ok = false; break; }
            }
            if(!ok) continue;
            // y con todo lo aceptado alrededor (también en macrocélulas vecinas)
            final int ci = Math.floorDiv(e[0], celda), cj = Math.floorDiv(e[1], celda);
            vecinos:
            for(int dj = -R; dj <= R; dj++){
                for(int di = -R; di <= R; di++){
                    final int[] n = candidato(g, c, m, ci + di, cj + dj);
                    if(n.length == 0 || (n[0] == e[0] && n[1] == e[1])) continue;
                    final long dx = n[0] - e[0], dy = n[1] - e[1];
                    if(dx * dx + dy * dy >= d2min) continue;
                    if(aceptado(g, c, m, ci + di, cj + dj, n)){
                        ok = false;
                        break vecinos;
                    }
                }
            }
            if(ok) puntos.add(new int[]{e[0], e[1]});
        }
    }

    // ------------------------------------------------------------------ API interna
    /** Centros aceptados dentro del rectángulo virtual [x0, x1] x [y0, y1], ordenados por (x, y), empaquetados (x en los 32 bits altos). */
    static long[] puntos(GeneradorFenomeno g, ContextoFenomeno c, int x0, int y0, int x1, int y1){
        final Mapas m = mapas(g.id(), c.semilla, c.dimension);
        final int tam = tamMacro(g);
        long[] r = new long[16];
        int n = 0;
        for(int mj = Math.floorDiv(y0, tam); mj <= Math.floorDiv(y1, tam); mj++){
            for(int mi = Math.floorDiv(x0, tam); mi <= Math.floorDiv(x1, tam); mi++){
                final int[] p = macroPuntos(g, c, m, mi, mj);
                for(int q = 0; q < p.length; q += 2){
                    if(p[q] < x0 || p[q] > x1 || p[q + 1] < y0 || p[q + 1] > y1) continue;
                    if(n == r.length) r = Arrays.copyOf(r, n * 2);
                    r[n++] = ((long)p[q] << 32) | (p[q + 1] & 0xffffffffL);
                }
            }
        }
        r = Arrays.copyOf(r, n);
        Arrays.sort(r);
        return r;
    }

    /** Aplica al chunk de {@code ctx} todos los fenómenos aceptados cuyo alcance lo toca. */
    static void aplicar(GeneradorFenomeno g, ContextoTerreno ctx){
        final ContextoFenomeno c = ctx.lectura;
        final int r = g.radio(), T = ContextoTerreno.TAM;
        final long[] pts = puntos(g, c, ctx.x0 - r, ctx.y0 - r, ctx.x0 + T - 1 + r, ctx.y0 + T - 1 + r);
        for(long p : pts) g.aplicar(ctx, c, (int)(p >> 32), (int)p);
    }
}
