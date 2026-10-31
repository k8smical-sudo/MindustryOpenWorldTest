package mundoinfinito;

import arc.util.Log;
import mindustry.Vars;
import mindustry.mod.Mods.LoadedMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * VALIDADOR DE ADDONS (robustez). Dos comprobaciones automáticas, al cargar el juego:
 *  1. Bordes: las decisiones de cada generador de fenómenos no dependen de la ventana de consulta. Lo que se decide sobre una región debe coincidir
 *     con lo que se decide sobre sus partes. Si no coincide, hay una decisión local (un corte en el borde de dos chunks).
 *  2. Azar y reloj: las clases de los addons que llaman a azar no determinista o al reloj del sistema se señalan en el registro, con su nombre.
 * Solo avisa: nunca desactiva ni bloquea. Es una heurística; no ve llamadas indirectas.
 */
final class ValidadorAddons{
    private static final String[] PROHIBIDOS = {"java/util/Random", "java/util/concurrent/ThreadLocalRandom", "nanoTime", "currentTimeMillis"};

    private ValidadorAddons(){}

    /** Qué llamadas sospechosas contiene una clase compilada. Vacía si no hay ninguna. */
    static List<String> sospechasClase(byte[] bytes){
        final String t = new String(bytes, StandardCharsets.ISO_8859_1);
        final List<String> out = new ArrayList<String>();
        for(String p : PROHIBIDOS) if(t.contains(p)) out.add(p);
        if(t.contains("java/lang/Math") && t.contains("random")) out.add("Math.random");
        return out;
    }

    private static byte[] leerEntrada(ZipInputStream z) throws IOException{
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while((n = z.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Escanea las clases de un .jar. Devuelve una línea por cada clase sospechosa. */
    static List<String> escanearJar(InputStream in, String nombreMod) throws IOException{
        final List<String> out = new ArrayList<String>();
        try(ZipInputStream z = new ZipInputStream(in)){
            ZipEntry e;
            while((e = z.getNextEntry()) != null){
                if(!e.getName().endsWith(".class")) continue;
                for(String s : sospechasClase(leerEntrada(z)))
                    out.add(nombreMod + ": " + e.getName() + " usa " + s);
            }
        }
        return out;
    }

    /**
     * Bordes de un generador: la decisión sobre una región debe ser la unión de las decisiones sobre sus cuatro cuadrantes. Devuelve los fallos.
     * Vacía si no hay ninguna diferencia o si el generador no aplica a la dimensión.
     */
    static List<String> bordes(GeneradorFenomeno g, GeneradorFenomeno[] todos, int semilla, int dim, int distanciaGlobal){
        final List<String> out = new ArrayList<String>();
        final String nombre = g == null || g.id() == null ? "(sin id)" : g.id();
        try{
            if(!g.aplicaA(dim)) return out;
            final ContextoFenomeno c = new ContextoFenomeno(null, semilla, dim, g.id());
            final long[] todo = Fenomenos.puntosFinales(g, todos, c, -128, -128, 127, 127, distanciaGlobal);
            final Set<Long> ref = new HashSet<Long>();
            for(long p : todo) ref.add(p);
            final int[][] cuadrantes = {{-128, -1, -128, -1}, {0, 127, -128, -1}, {-128, -1, 0, 127}, {0, 127, 0, 127}};
            int diferencias = 0;
            String ejemplo = null;
            for(int[] q : cuadrantes){
                final long[] parte = Fenomenos.puntosFinales(g, todos, c, q[0], q[2], q[1], q[3], distanciaGlobal);
                final Set<Long> sub = new HashSet<Long>();
                for(long p : parte) sub.add(p);
                for(long p : ref){
                    final int x = (int)(p >> 32), y = (int)p;
                    if(x >= q[0] && x <= q[1] && y >= q[2] && y <= q[3] && !sub.contains(p)){
                        diferencias++;
                        if(ejemplo == null) ejemplo = "(" + x + ", " + y + ") solo en la región completa";
                    }
                }
                for(long p : sub) if(!ref.contains(p)){
                    diferencias++;
                    if(ejemplo == null) ejemplo = "(" + (int)(p >> 32) + ", " + (int)p + ") solo en la parte";
                }
            }
            if(diferencias > 0) out.add(nombre + ": " + diferencias + " puntos dependen de la ventana de consulta (ej. " + ejemplo + "); puede haber cortes en los bordes");
        }catch(Throwable t){
            out.add(nombre + ": no verificable en el borde (" + t + ")");
        }
        return out;
    }

    /**
     * HUELLA (solo en el juego, con los bloques cargados). Para cada ancla de un generador, se aplica el generador a las 9 piezas de chunk que la rodean
     * y se recogen todas las casillas que escribe. Si alguna cae fuera de {@code radio()} (medido desde el ancla), la huella excede lo declarado: es la
     * causa típica de un corte en el borde de dos chunks, porque el chunk vecino no consulta esa ancla con el margen suficiente.
     */
    static List<String> huella(GeneradorFenomeno g, int semilla, int dim){
        final List<String> out = new ArrayList<String>();
        final String nombre = g == null || g.id() == null ? "(sin id)" : g.id();
        try{
            if(!g.aplicaA(dim)) return out;
            final MundoInfinitoMod.Paleta pal = MundoInfinitoMod.Paleta.crear();
            final int T = ContextoTerreno.TAM, R = g.radio();
            final GeneradorFenomeno[] todos = {g};
            final long[] anclas = Fenomenos.puntosFinales(g, todos, new ContextoFenomeno(pal, semilla, dim, g.id()), -48, -48, 79, 79, 0);
            int fuera = 0, maxExceso = 0, probadas = 0;
            String ejemplo = null;
            for(long p : anclas){
                final int px = (int)(p >> 32), py = (int)p;
                probadas++;
                final int cvx = Math.floorDiv(px, T), cvy = Math.floorDiv(py, T);
                for(int dvx = -1; dvx <= 1; dvx++){
                    for(int dvy = -1; dvy <= 1; dvy++){
                        final int vcx = cvx + dvx, vcy = cvy + dvy;
                        final short[] piso = new short[T * T], mena = new short[T * T], bloque = new short[T * T];
                        final ContextoTerreno ctx = new ContextoTerreno(piso, mena, bloque, pal, semilla, dim, vcx, vcy, g.id());
                        g.aplicar(ctx, ctx.lectura, px, py);
                        for(int i = 0; i < T * T; i++){
                            if(piso[i] == 0 && mena[i] == 0 && bloque[i] == 0) continue;
                            final int x = vcx * T + i % T, y = vcy * T + i / T;
                            final int exceso = Math.max(Math.abs(x - px), Math.abs(y - py)) - R;
                            if(exceso > 0){
                                fuera++;
                                if(exceso > maxExceso) maxExceso = exceso;
                                if(ejemplo == null) ejemplo = "ancla (" + px + ", " + py + ") escribe (" + x + ", " + y + ")";
                            }
                        }
                    }
                }
            }
            if(fuera > 0) out.add(nombre + ": " + fuera + " casillas fuera del radio declarado " + R + " (máx. exceso " + maxExceso + "; ej. " + ejemplo + "); puede haber cortes en los bordes");
        }catch(Throwable t){
            out.add(nombre + ": huella no verificable (" + t + ")");
        }
        return out;
    }

    /** Al cargar el juego: escanea los addons Java y comprueba los bordes de los generadores registrados. Solo avisa. */
    static void alCargar(){
        try{
            if(Vars.mods != null){
                for(LoadedMod m : Vars.mods.list()){
                    if(m == null || !m.enabled() || !m.isJava() || OpenWorldAPI.ID_MOD.equals(m.name)) continue;
                    if(m.file == null || m.file.isDirectory()) continue;
                    for(String s : escanearJar(m.file.read(), m.name))
                        Log.warn("[MundoInfinito] Validación de addons (azar/reloj): @", s);
                }
            }
            final GeneradorFenomeno[] todos = Extensiones.fenomenosRegistrados();
            if(todos != null){
                for(GeneradorFenomeno g : todos){
                    for(String s : bordes(g, todos, 12345, 1, 0))
                        Log.warn("[MundoInfinito] Validación de addons (bordes): @", s);
                    for(String s : huella(g, 12345, 1))
                        Log.warn("[MundoInfinito] Validación de addons (huella): @", s);
                }
            }
        }catch(Throwable t){
            Log.warn("[MundoInfinito] La validación de addons no pudo completarse: @", t.toString());
        }
    }
}
