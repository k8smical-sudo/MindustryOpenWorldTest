package mundoinfinito;

import arc.func.Boolp;
import arc.func.Cons;
import arc.func.Prov;
import arc.graphics.gl.Shader;
import arc.struct.Seq;
import arc.util.serialization.Jval;
import mindustry.world.meta.Env;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CORPUS DE REGRESIÓN DEL TERRENO BASE (plan definitivo, fase 0).
 *
 * Hashea el terreno base de un conjunto fijo de semillas, dimensiones y coordenadas de chunk. Solo usa {@code Muestreo}, que es
 * puro (no necesita el juego cargado), así que corre en una JVM normal, en CI, con el classpath de la mod compilada + el jar del motor.
 * La ruta directa replica el bucle original de {@code ChunkData.generar}; la ruta del núcleo es la que usa el juego ahora ({@link Nucleo#terreno}).
 * "congelar" usa la ruta directa (referencia independiente del núcleo); "verificar" y la equivalencia de hilos usan la del núcleo.
 * Fase 3: "comprobar" además prueba las etapas por separado (suelo solo contra Muestreo.pisoBase, sin decoración, sin cada etapa).
 * API pública: y los puntos de shader del juego, las versiones, las fachadas ApiVisual y ApiAjustes y la superficie pública (solo lo previsto es público).
 * Fase 10: y los efectos con pool, el color ambiental con política y las mallas preparadas fuera del hilo de render.
 * Fase 9: y la capa visual (orden de capas, shaders, uniformes, fallos aislados, presupuestos, calidad, medición, separación de la simulación).
 * Fase 7: y los presupuestos (las tres reglas de degradación, misma salida degradada siempre, mundo reabierto, nada interrumpe la generación).
 * Fase 6: y el reparto de áreas (cuota de peso por macrocélula, distancia mínima global entre fuentes, conflictos ponderados, procedencia).
 * Fase 5: y las políticas de resolución (media ponderada, prioridad absoluta, elección ponderada con hash, fuera de rango, sin política).
 * Fase 4: y el perfil de mundo (valores por defecto, mundos antiguos, recorte fuera de rango, persistencia y que el perfil por defecto no cambia el terreno).
 * No cubre los hooks de la API (fenómenos, mods): necesitan el contenido del juego y entran en fases posteriores.
 *
 * Uso:   java -cp build/classes/java/main:libs/MindustryX-Desktop.jar mundoinfinito.Corpus MODO [archivo]
 *   congelar archivo    escribe los hashes (se hace UNA vez por versión de generación y se guarda en el repo)
 *   verificar archivo   compara con lo congelado; sale con error si algo cambió
 *   comprobar           reproducibilidad (dos pasadas en frío) y equivalencia de hilos (1, 2 y 4 hilos contra la ruta directa)
 *   base archivo        línea base de coste por chunk (ms) por dimensión y por semilla
 *   escala archivo      escalado de la generación con 1, 2, 4... hilos (fase 8): tiempo, chunks por segundo y aceleración
 * Regla del plan: si un cambio altera el resultado a propósito, se sube GEN_ACTUAL y se vuelve a congelar; si no, el corpus no se toca.
 */
public final class Corpus{
    static final int VERSION = 1;
    static final int[] SEMILLAS = {1, 12345, 987654321, -20260101, 1592653589};
    /** -1 = terreno mezclado (mundos antiguos), 0 = Serpulo, 1 = Erekir. */
    static final int[] DIMS = {0, 1, -1};
    /** Coordenadas de chunk: spawn (3x3), cerca, medio, lejos (hasta ~990 chunks, el mundo llega a ±1000), con signos mezclados. */
    static final int[][] COORDS = {
        {-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {0, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1},
        {5, 5}, {-5, 5}, {5, -5}, {-5, -5}, {20, 0}, {-20, 0}, {0, 20}, {0, -20},
        {64, 64}, {-64, -64}, {100, -100}, {-100, 100}, {250, 40}, {-300, -300}, {500, -17}, {-731, 263},
        {990, 990}, {-990, 990}, {990, -990}, {-990, -990}
    };

    private Corpus(){}

    // ================================================================== el cálculo (puro)

    /** Hash de un chunk: mismo recorrido y mismas llamadas que ChunkData.generar con ox = oy = 0. */
    static long hashChunk(int semilla, int dim, int cx, int cy){
        final int T = MundoInfinitoMod.TAM_CHUNK;
        final int sd = MundoInfinitoMod.Muestreo.semDim(semilla, dim);
        final MundoInfinitoMod.Muestreo.Spawn sp = MundoInfinitoMod.Muestreo.spawn(sd, dim);
        final float[] aux = new float[14];
        final int[] out = new int[4];
        aux[11] = 0f;   // caché de estructura de la celda: vacía al empezar el chunk (igual que generar)
        long h = 0xcbf29ce484222325L;
        for(int ly = 0; ly < T; ly++){
            for(int lx = 0; lx < T; lx++){
                MundoInfinitoMod.Muestreo.muestrear(sd, cx * T + lx, cy * T + ly, sp, aux, out);
                for(int k = 0; k < 4; k++){
                    h = (h ^ out[k]) * 0x100000001b3L;
                    h ^= h >>> 31;
                }
            }
        }
        return h;
    }

    /** Hash de un aporte de terreno: las cuatro capas, casilla a casilla, con la misma mezcla que hashChunk. */
    static long hashAporte(AporteTerreno ap){
        long h = 0xcbf29ce484222325L;
        for(int i = 0; i < AporteTerreno.N; i++){
            h = (h ^ ap.piso[i]) * 0x100000001b3L;
            h ^= h >>> 31;
            h = (h ^ ap.mena[i]) * 0x100000001b3L;
            h ^= h >>> 31;
            h = (h ^ ap.muro[i]) * 0x100000001b3L;
            h ^= h >>> 31;
            h = (h ^ ap.prop[i]) * 0x100000001b3L;
            h ^= h >>> 31;
        }
        return h;
    }

    /** Mismo hash, pero con el terreno base servido por el núcleo (el camino real de ChunkData.generar). Debe dar lo mismo que hashChunk. */
    static long hashChunkNucleo(int semilla, int dim, int cx, int cy){
        final int T = MundoInfinitoMod.TAM_CHUNK;
        return hashAporte(Nucleo.terreno(semilla, dim, cx * T, cy * T, cx, cy));
    }

    /** Lo mismo, con los parámetros de generación indicados (el perfil de un mundo). */
    static long hashChunkNucleoCon(ParametrosGeneracion par, int semilla, int dim, int cx, int cy){
        final int T = MundoInfinitoMod.TAM_CHUNK;
        return hashAporte(Nucleo.terreno(par, semilla, dim, cx * T, cy * T, cx, cy));
    }

    /** Lo mismo, pero evaluando solo las etapas indicadas (en orden canónico). */
    static long hashChunkConEtapas(int semilla, int dim, int cx, int cy, Set<String> ids){
        final int T = MundoInfinitoMod.TAM_CHUNK;
        return hashAporte(Nucleo.terrenoSolo(ids, semilla, dim, cx * T, cy * T, cx, cy));
    }

    static final String PREFIJO = "mundo-infinito:";

    static Set<String> ids(String... nombres){
        final Set<String> s = new HashSet<String>();
        for(String n : nombres) s.add(PREFIJO + n);
        return s;
    }

    static Set<String> todasMenos(String... nombres){
        final Set<String> s = new HashSet<String>();
        for(ContribucionTerreno c : Nucleo.contribucionesTerreno()) s.add(c.id());
        for(String n : nombres) s.remove(PREFIJO + n);
        return s;
    }

    /** Todas las entradas en un orden fijo (semilla, dimensión, coordenada). */
    static List<int[]> entradas(){
        final List<int[]> l = new ArrayList<int[]>();
        for(int s : SEMILLAS) for(int d : DIMS) for(int[] c : COORDS) l.add(new int[]{s, d, c[0], c[1]});
        return l;
    }

    static long[] calcularNucleo(List<int[]> es){
        final long[] r = new long[es.size()];
        for(int i = 0; i < r.length; i++){
            int[] e = es.get(i);
            r[i] = hashChunkNucleo(e[0], e[1], e[2], e[3]);
        }
        return r;
    }

    static long[] calcularConEtapas(List<int[]> es, Set<String> ids){
        final long[] r = new long[es.size()];
        for(int i = 0; i < r.length; i++){
            int[] e = es.get(i);
            r[i] = hashChunkConEtapas(e[0], e[1], e[2], e[3], ids);
        }
        return r;
    }

    static long[] calcularDirecto(List<int[]> es){
        final long[] r = new long[es.size()];
        for(int i = 0; i < r.length; i++){
            int[] e = es.get(i);
            r[i] = hashChunk(e[0], e[1], e[2], e[3]);
        }
        return r;
    }

    /** Lo mismo, repartido por el servicio de hilos con n hilos. Los resultados vuelven por posición. */
    static long[] calcularConHilos(List<int[]> es, int n){
        return calcularConHilos(es, n, Nucleo.parametros());
    }

    static long[] calcularConHilos(List<int[]> es, int n, final ParametrosGeneracion par){
        System.setProperty(Hilos.PROP_HILOS, String.valueOf(n));
        Hilos.configurar();
        final Seq<Prov<Long>> tareas = new Seq<Prov<Long>>(es.size());
        for(final int[] e : es) tareas.add(() -> Long.valueOf(hashChunkNucleoCon(par, e[0], e[1], e[2], e[3])));   // por el núcleo: el camino real
        final Seq<Long> res = Hilos.ordenado(Hilos.Carril.SIMULACION, "corpus", tareas);
        final long[] r = new long[es.size()];
        for(int i = 0; i < r.length; i++){
            Long v = res.get(i);
            r[i] = v == null ? Long.MIN_VALUE : v.longValue();   // una tarea fallida deja un valor imposible y la comprobación falla
        }
        return r;
    }

    static void olvidarCaches(){
        MundoInfinitoMod.Muestreo.cacheSpawn = null;   // pasada "en frío": nada heredado de la anterior
    }

    static String hex(long h){
        return String.format("%016x", h);
    }

    static long total(long[] hs){
        long t = 0xcbf29ce484222325L;
        for(long h : hs){
            t = (t ^ h) * 0x100000001b3L;
            t ^= t >>> 29;
        }
        return t;
    }

    // ================================================================== archivos

    static void escribir(String archivo, List<int[]> es, long[] hs) throws Exception{
        final PrintWriter w = new PrintWriter(new FileWriter(archivo));
        try{
            w.println("# corpus mundo-infinito v" + VERSION + " gen=" + MundoInfinitoMod.GEN_ACTUAL);
            w.println("# semilla dim cx cy hash");
            for(int i = 0; i < hs.length; i++){
                int[] e = es.get(i);
                w.println(e[0] + " " + e[1] + " " + e[2] + " " + e[3] + " " + hex(hs[i]));
            }
            w.println("# total " + hex(total(hs)));
        }finally{
            w.close();
        }
    }

    /** clave "semilla dim cx cy" -> hash en hexadecimal; la clave especial "#gen" guarda la generación del archivo. */
    static Map<String, String> leer(String archivo) throws Exception{
        final Map<String, String> m = new HashMap<String, String>();
        final BufferedReader r = new BufferedReader(new FileReader(archivo));
        try{
            String linea;
            while((linea = r.readLine()) != null){
                linea = linea.trim();
                if(linea.isEmpty()) continue;
                if(linea.startsWith("#")){
                    int i = linea.indexOf("gen=");
                    if(i >= 0) m.put("#gen", linea.substring(i + 4).trim());
                    continue;
                }
                String[] p = linea.split("\\s+");
                if(p.length == 5) m.put(p[0] + " " + p[1] + " " + p[2] + " " + p[3], p[4]);
            }
        }finally{
            r.close();
        }
        return m;
    }

    // ================================================================== modos

    static int congelar(String archivo) throws Exception{
        final List<int[]> es = entradas();
        olvidarCaches();
        final long[] hs = calcularDirecto(es);
        escribir(archivo, es, hs);
        System.out.println("[Corpus] congelado: " + hs.length + " chunks, gen=" + MundoInfinitoMod.GEN_ACTUAL + ", total=" + hex(total(hs)) + " -> " + archivo);
        return 0;
    }

    static int verificar(String archivo) throws Exception{
        final Map<String, String> esperado = leer(archivo);
        final String genArchivo = esperado.remove("#gen");
        if(genArchivo != null && !genArchivo.equals(String.valueOf(MundoInfinitoMod.GEN_ACTUAL))){
            System.out.println("[Corpus] FALLO: el archivo es de la generación " + genArchivo + " y el código es la " + MundoInfinitoMod.GEN_ACTUAL
                + ". Vuelve a congelar el corpus con la generación nueva.");
            return 1;
        }
        final List<int[]> es = entradas();
        olvidarCaches();
        final long[] hs = calcularNucleo(es);   // el terreno servido por el núcleo debe dar lo congelado
        int distintos = 0, faltan = 0;
        for(int i = 0; i < hs.length; i++){
            int[] e = es.get(i);
            String k = e[0] + " " + e[1] + " " + e[2] + " " + e[3];
            String x = esperado.remove(k);
            if(x == null){
                faltan++;
                if(faltan <= 10) System.out.println("[Corpus] no está en el archivo: " + k);
            }else if(!x.equals(hex(hs[i]))){
                distintos++;
                if(distintos <= 20) System.out.println("[Corpus] DISTINTO " + k + ": esperado " + x + ", obtenido " + hex(hs[i]));
            }
        }
        final int sobran = esperado.size();
        if(distintos == 0 && faltan == 0 && sobran == 0){
            System.out.println("[Corpus] OK: " + hs.length + " chunks idénticos a lo congelado (gen=" + MundoInfinitoMod.GEN_ACTUAL + ").");
            return 0;
        }
        System.out.println("[Corpus] FALLO: distintos=" + distintos + ", sin esperado=" + faltan + ", sobrantes en el archivo=" + sobran
            + ". Si el cambio es intencionado, sube GEN_ACTUAL y vuelve a congelar; si no, el cambio rompe el mundo.");
        return 1;
    }

    /** Etapas de suelo solas: el piso debe ser exactamente pisoBase de Muestreo (referencia independiente) y las demás capas quedan vacías. */
    static int comprobarSuelo(List<int[]> es){
        final Set<String> suelo = ids("suelo-serpulo", "suelo-erekir", "volcanico-serpulo", "brea-agua-serpulo");
        final int T = MundoInfinitoMod.TAM_CHUNK;
        int malas = 0;
        for(int[] e : es){
            final int sd = MundoInfinitoMod.Muestreo.semDim(e[0], e[1]);
            final MundoInfinitoMod.Muestreo.Spawn sp = MundoInfinitoMod.Muestreo.spawn(sd, e[1]);
            final AporteTerreno ap = Nucleo.terrenoSolo(suelo, e[0], e[1], e[2] * T, e[3] * T, e[2], e[3]);
            for(int ly = 0; ly < T; ly++){
                for(int lx = 0; lx < T; lx++){
                    final int i = ly * T + lx, x = e[2] * T + lx, y = e[3] * T + ly;
                    final double dx0 = x, dy0 = y;
                    final float dist = (float)Math.sqrt(dx0 * dx0 + dy0 * dy0);
                    final double wx = x + (MundoInfinitoMod.Muestreo.fbm(MundoInfinitoMod.Muestreo.sem(sd, 50), 90f, 3, x, y) - 0.5) * 70.0;
                    final double wy = y + (MundoInfinitoMod.Muestreo.fbm(MundoInfinitoMod.Muestreo.sem(sd, 51), 90f, 3, x + 500.0, y + 500.0) - 0.5) * 70.0;
                    final boolean ere = MundoInfinitoMod.Muestreo.esE(sd, x, y, sp);
                    final int p = MundoInfinitoMod.Muestreo.pisoBase(sd, x, y, dist, wx, wy, ere, sp);
                    if(ap.piso[i] != p || ap.mena[i] != 0 || ap.muro[i] != 0 || ap.prop[i] != 0){
                        malas++;
                        if(malas <= 5) System.out.println("[Corpus]   suelo distinto en " + e[0] + " " + e[1] + " chunk " + e[2] + "," + e[3] + " casilla " + lx + "," + ly + ": etapas " + ap.piso[i] + ", Muestreo.pisoBase " + p);
                    }
                }
            }
        }
        if(malas == 0) System.out.println("[Corpus] etapas de suelo solas: el piso coincide con Muestreo.pisoBase y no tocan mena, muro ni decoración (" + es.size() + " chunks).");
        else System.out.println("[Corpus] FALLO: las etapas de suelo solas difieren de Muestreo.pisoBase en " + malas + " casillas.");
        return malas == 0 ? 0 : 1;
    }

    /** Quitar la decoración solo debe quitar la decoración: piso, mena y muro quedan idénticos al resultado completo. */
    static int comprobarSinDecoracion(List<int[]> es){
        final Set<String> sin = todasMenos("decoracion");
        final int T = MundoInfinitoMod.TAM_CHUNK;
        int malas = 0;
        for(int[] e : es){
            final AporteTerreno a = Nucleo.terreno(e[0], e[1], e[2] * T, e[3] * T, e[2], e[3]);
            final int[] p = a.piso.clone(), m = a.mena.clone(), w = a.muro.clone();
            final AporteTerreno b = Nucleo.terrenoSolo(sin, e[0], e[1], e[2] * T, e[3] * T, e[2], e[3]);
            for(int i = 0; i < AporteTerreno.N; i++){
                if(b.piso[i] != p[i] || b.mena[i] != m[i] || b.muro[i] != w[i] || b.prop[i] != 0){
                    malas++;
                    if(malas <= 5) System.out.println("[Corpus]   sin decoración difiere en " + e[0] + " " + e[1] + " chunk " + e[2] + "," + e[3] + " casilla " + i);
                }
            }
        }
        if(malas == 0) System.out.println("[Corpus] sin la decoración: piso, mena y muro idénticos al completo y sin decoración (" + es.size() + " chunks).");
        else System.out.println("[Corpus] FALLO: quitar la decoración cambió otras capas en " + malas + " casillas.");
        return malas == 0 ? 0 : 1;
    }

    /** Cada etapa quitada por turnos: el resultado debe ser reproducible (dos pasadas en frío iguales). Informa del hash de cada una. */
    static int comprobarSinCadaEtapa(List<int[]> es, long[] completo){
        int fallos = 0;
        for(ContribucionTerreno c : Nucleo.contribucionesTerreno()){
            final String nombre = c.id().substring(PREFIJO.length());
            final Set<String> sin = todasMenos(nombre);
            olvidarCaches();
            final long[] r1 = calcularConEtapas(es, sin);
            olvidarCaches();
            final long[] r2 = calcularConEtapas(es, sin);
            if(!Arrays.equals(r1, r2)){
                fallos++;
                System.out.println("[Corpus] FALLO: sin la etapa '" + nombre + "' el resultado no es reproducible.");
            }else{
                System.out.println("[Corpus] sin '" + nombre + "': reproducible, total=" + hex(total(r1)) + (Arrays.equals(r1, completo) ? " (igual que completo: no actúa en el corpus)" : ""));
            }
        }
        return fallos;
    }

    // ================================================================== fase 4: ajustes y perfil

    private static int chequear(String texto, boolean ok){
        System.out.println("[Corpus] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }

    private static boolean iguales(ParametrosGeneracion x, ParametrosGeneracion y){
        return x.estirarAlturaSerpulo == y.estirarAlturaSerpulo && x.estirarTemperaturaSerpulo == y.estirarTemperaturaSerpulo
            && x.estirarHumedadSerpulo == y.estirarHumedadSerpulo && x.distanciaMinimaFenomenos == y.distanciaMinimaFenomenos
            && x.presupuestoTerrenoPct == y.presupuestoTerrenoPct;
    }

    private static Jval archivoDe(PerfilMundo p){
        final Jval raiz = Jval.newObject();
        raiz.put("ajustes", p.aJson());
        raiz.put("gamerules", p.aJsonReglas());
        return raiz;
    }

    /** Perfil de mundo: valores por defecto, mundos antiguos, validación y recorte, persistencia, y que un perfil por defecto no cambia el terreno. */
    static int comprobarAjustes(List<int[]> es, long[] directo){
        int fallos = 0;
        final ParametrosGeneracion def = ParametrosGeneracion.DEFECTO;

        fallos += chequear("un perfil vacío da los parámetros por defecto", iguales(new PerfilMundo().parametros(), def));

        final PerfilMundo antiguo = PerfilMundo.desde(Jval.read("{\"nombre\":\"viejo\",\"semilla\":5}"));
        fallos += chequear("un mundo antiguo (sin ajustes ni gamerules) se abre con los valores por defecto", iguales(antiguo.parametros(), def) && antiguo.congelado());
        fallos += chequear("el perfil abierto está congelado: no se cambian los parámetros de generación", !antiguo.fijar(ParametrosGeneracion.ID_ALTURA_SERPULO, 2.0));

        final long antes = RegistroAjustes.totalRecortes();
        final PerfilMundo malo = new PerfilMundo();
        malo.fijar(ParametrosGeneracion.ID_ALTURA_SERPULO, 99.0);
        malo.fijar(ParametrosGeneracion.ID_TEMPERATURA_SERPULO, -5.0);
        malo.fijar(ParametrosGeneracion.ID_HUMEDAD_SERPULO, Double.NaN);
        final ParametrosGeneracion pm = malo.parametros();
        fallos += chequear("fuera de rango se recorta (99 -> 4, -5 -> 1, NaN -> por defecto) y queda registrado (3 recortes)",
            pm.estirarAlturaSerpulo == 4.0f && pm.estirarTemperaturaSerpulo == 1.0f && pm.estirarHumedadSerpulo == def.estirarHumedadSerpulo
                && RegistroAjustes.totalRecortes() - antes == 3L);

        final PerfilMundo viejo = PerfilMundo.desde(Jval.read("{\"gamerules\":{\"mod:regla\":true,\"mod:otra\":false},\"ajustes\":{\"otro:num\":7.5}}"));
        fallos += chequear("el formato antiguo de gamerules se lee y un ajuste desconocido se conserva",
            viejo.bool("mod:regla", false) && !viejo.bool("mod:otra", true) && viejo.tiene("otro:num"));
        final PerfilMundo ida = PerfilMundo.desde(archivoDe(viejo));
        final Jval reglas = ida.aJsonReglas(), ajustes = ida.aJson();
        fallos += chequear("guardar y volver a leer conserva reglas booleanas y valores desconocidos",
            ida.bool("mod:regla", false) && reglas.has("mod:regla") && reglas.get("mod:regla").isBoolean() && reglas.get("mod:regla").asBool()
                && ajustes.has("otro:num") && ajustes.get("otro:num").asDouble() == 7.5);

        final PerfilMundo nuevo = new PerfilMundo();
        nuevo.sellarGeneracion(12345);
        final PerfilMundo nuevoLeido = PerfilMundo.desde(archivoDe(nuevo));
        fallos += chequear("un mundo nuevo guarda sus parámetros de generación y no cambian al guardar y leer",
            nuevo.tiene(ParametrosGeneracion.ID_ALTURA_SERPULO) && nuevo.congelado() && iguales(nuevo.parametros(), def) && iguales(nuevoLeido.parametros(), def));

        // el terreno con el perfil por defecto (vacío, sellado o leído) es idéntico a la ruta directa
        final ParametrosGeneracion fuerte = new ParametrosGeneracion(3.6f, def.estirarTemperaturaSerpulo, def.estirarHumedadSerpulo);
        int malosDefecto = 0, serpuloCambia = 0, erekirCambia = 0;
        for(int i = 0; i < es.size(); i++){
            final int[] e = es.get(i);
            if(hashChunkNucleoCon(ParametrosGeneracion.DEFECTO, e[0], e[1], e[2], e[3]) != directo[i]) malosDefecto++;   // referencia: el perfil por defecto de la mod (con pozos)
            if(hashChunkNucleoCon(fuerte, e[0], e[1], e[2], e[3]) != directo[i]){
                if(e[1] == 1) erekirCambia++;
                else serpuloCambia++;
            }
        }
        fallos += chequear("un mundo nuevo con el perfil por defecto da exactamente el terreno del corpus (" + es.size() + " chunks)", malosDefecto == 0);
        fallos += chequear("el parámetro actúa: con relieve 3.6 cambia el terreno de Serpulo (" + serpuloCambia + " chunks) y no el de Erekir (" + erekirCambia + ")",
            serpuloCambia > 0 && erekirCambia == 0);
        return fallos;
    }

    // ================================================================== fase 5: contribuciones múltiples y políticas

    private static <T> List<List<T>> permutaciones(List<T> l){
        final List<List<T>> r = new ArrayList<List<T>>();
        if(l.size() <= 1){
            r.add(new ArrayList<T>(l));
            return r;
        }
        for(int i = 0; i < l.size(); i++){
            final List<T> resto = new ArrayList<T>(l);
            final T x = resto.remove(i);
            for(List<T> p : permutaciones(resto)){
                p.add(0, x);
                r.add(p);
            }
        }
        return r;
    }

    private static PropuestaParametro prop(String fuente, String nombre, String parametro, double valor, double peso, int prioridad){
        return PropuestaParametro.de(fuente, fuente + ":" + nombre, parametro, valor, peso, prioridad);
    }

    /** Resuelve con todas las permutaciones de entrada: todas deben dar el mismo valor, bit a bit, y el mismo origen y elegida. */
    private static Resolucion.Resultado resolverEnTodosLosOrdenes(AjusteMundo a, List<PropuestaParametro> ps, int semilla, boolean[] identico){
        Resolucion.Resultado ref = null;
        identico[0] = true;
        for(List<PropuestaParametro> p : permutaciones(ps)){
            final Resolucion.Resultado r = Resolucion.resolver(a, p, semilla);
            if(ref == null) ref = r;
            else if(Double.doubleToLongBits(r.valor) != Double.doubleToLongBits(ref.valor) || r.origen != ref.origen
                || (r.elegida == null ? ref.elegida != null : !r.elegida.equals(ref.elegida))) identico[0] = false;
        }
        return ref;
    }

    /** Políticas de resolución: media ponderada, prioridad absoluta, elección ponderada con hash, valores fuera de rango y ajustes sin política. */
    static int comprobarResolucion(){
        int fallos = 0;
        final String P = "prueba:parametro";
        final AjusteMundo media = AjusteMundo.decimal(P, "p", "", AjusteMundo.Ambito.GENERACION, 2.0, 1.0, 4.0).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA);
        final boolean[] ident = new boolean[1];

        // pesos 1 y 3: (2*1 + 4*3) / 4 = 3.5, con cualquier orden de carga
        List<PropuestaParametro> dos = Arrays.asList(prop("aaa", "p1", P, 2.0, 1, 0), prop("bbb", "p2", P, 4.0, 3, 0));
        Resolucion.Resultado r = resolverEnTodosLosOrdenes(media, dos, 1, ident);
        fallos += chequear("media ponderada con pesos 1 y 3: 2.0 y 4.0 dan 3.5 con cualquier orden de carga", r.valor == 3.5 && r.origen == Resolucion.Origen.MEDIA && ident[0]);

        List<PropuestaParametro> tres = Arrays.asList(prop("aaa", "p1", P, 1.0, 1, 0), prop("bbb", "p2", P, 2.5, 3, 0), prop("ccc", "p3", P, 4.0, 2, 0));
        r = resolverEnTodosLosOrdenes(media, tres, 1, ident);
        fallos += chequear("media ponderada de tres fuentes (pesos 1, 3 y 2) = 2.75 con las 6 ordenaciones", r.valor == 2.75 && ident[0]);

        // la suma de decimales no es asociativa: el orden canónico hace que no cambie ni un bit
        List<PropuestaParametro> raros = Arrays.asList(prop("aaa", "p1", P, 1.1, 0.1, 0), prop("bbb", "p2", P, 2.2, 0.2, 0), prop("ccc", "p3", P, 3.3, 0.3, 0), prop("ddd", "p4", P, 1.7, 0.7, 0));
        r = resolverEnTodosLosOrdenes(media, raros, 1, ident);
        fallos += chequear("con pesos y valores decimales el resultado es idéntico bit a bit en las 24 ordenaciones", ident[0]);

        // un valor fuera de rango no se impone
        final long recortesAntes = RegistroAjustes.totalRecortes();
        List<PropuestaParametro> fuera = Arrays.asList(prop("aaa", "p1", P, 99.0, 1, 0), prop("bbb", "p2", P, 2.0, 3, 0));
        r = resolverEnTodosLosOrdenes(media, fuera, 1, ident);
        fallos += chequear("un valor fuera de rango no se impone: 99 (peso 1) y 2.0 (peso 3) dan 2.5, no 26.75, y queda anotado",
            r.valor == 2.5 && r.valor <= media.max && RegistroAjustes.totalRecortes() > recortesAntes && ident[0]);

        // varios contribuyentes sin política: se reporta y no se resuelve
        final AjusteMundo sinPol = AjusteMundo.decimal("prueba:sinpolitica", "s", "", AjusteMundo.Ambito.GENERACION, 2.0, 1.0, 4.0);
        final long conflictosAntes = Resolucion.conflictosSinPolitica();
        List<PropuestaParametro> dosSin = Arrays.asList(prop("aaa", "p1", "prueba:sinpolitica", 3.0, 1, 0), prop("bbb", "p2", "prueba:sinpolitica", 4.0, 1, 0));
        r = Resolucion.resolver(sinPol, dosSin, 1);
        fallos += chequear("varios contribuyentes y sin política: no se resuelve, queda el valor por defecto y se reporta",
            r.origen == Resolucion.Origen.SIN_POLITICA && !r.resuelto() && r.valor == sinPol.defecto && Resolucion.conflictosSinPolitica() == conflictosAntes + 1);
        r = Resolucion.resolver(sinPol, Arrays.asList(prop("aaa", "p1", "prueba:sinpolitica", 3.0, 1, 0)), 1);
        fallos += chequear("con un solo contribuyente no hace falta política", r.origen == Resolucion.Origen.UNICA && r.valor == 3.0);
        r = Resolucion.resolver(sinPol, new ArrayList<PropuestaParametro>(), 1);
        fallos += chequear("sin propuestas vale el valor por defecto", r.origen == Resolucion.Origen.DEFECTO && r.valor == sinPol.defecto);
        r = Resolucion.resolver(media, Arrays.asList(prop("aaa", "p1", P, 3.0, 0, 0), prop("bbb", "p2", P, 4.0, -2, 0), prop("ccc", "p3", P, 2.0, 1, 0)), 1);
        fallos += chequear("las propuestas con peso no válido (0, negativo) se ignoran", r.contribuyentes == 1 && r.valor == 2.0);

        // prioridad absoluta: la primera en orden canónico (prioridad, fuente, id)
        final AjusteMundo prio = AjusteMundo.decimal("prueba:prioridad", "p", "", AjusteMundo.Ambito.GENERACION, 2.0, 1.0, 4.0).conPolitica(AjusteMundo.Politica.PRIORIDAD_ABSOLUTA);
        List<PropuestaParametro> pp = Arrays.asList(prop("bbb", "x", "prueba:prioridad", 3.0, 1, 5), prop("zzz", "y", "prueba:prioridad", 1.5, 1, 1), prop("aaa", "z", "prueba:prioridad", 2.5, 1, 1));
        r = resolverEnTodosLosOrdenes(prio, pp, 1, ident);
        fallos += chequear("prioridad absoluta: gana la de menor prioridad y, en empate, la de fuente 'aaa', con las 6 ordenaciones",
            r.valor == 2.5 && "aaa:z".equals(r.elegida) && ident[0]);

        // elección ponderada con hash: determinista, e independiente del orden
        final AjusteMundo ele = AjusteMundo.decimal("prueba:eleccion", "e", "", AjusteMundo.Ambito.GENERACION, 2.0, 1.0, 4.0).conPolitica(AjusteMundo.Politica.ELECCION_PONDERADA);
        List<PropuestaParametro> ab = Arrays.asList(prop("aaa", "a", "prueba:eleccion", 1.0, 1, 0), prop("bbb", "b", "prueba:eleccion", 3.0, 3, 0));
        boolean determinista = true;
        int eligeB = 0;
        final int N = 10000;
        for(int sem = 0; sem < N; sem++){
            final Resolucion.Resultado x = Resolucion.resolver(ele, ab, sem);
            final Resolucion.Resultado y = Resolucion.resolver(ele, Arrays.asList(ab.get(1), ab.get(0)), sem);
            if(x.valor != y.valor || !x.elegida.equals(y.elegida)) determinista = false;
            if("bbb:b".equals(x.elegida)) eligeB++;
        }
        final double proporcion = eligeB / (double)N;
        fallos += chequear("elección ponderada: igual con cualquier orden de entrada y de la misma semilla", determinista);
        fallos += chequear("elección ponderada con pesos 1 y 3 en " + N + " semillas: la de peso 3 sale el " + Math.round(proporcion * 1000) / 10.0 + " % (esperado 75 %, tolerancia 3 puntos)", Math.abs(proporcion - 0.75) < 0.03);

        // integración con el perfil de un mundo nuevo
        final String ALTURA = ParametrosGeneracion.ID_ALTURA_SERPULO;
        final List<PropuestaParametro> alt = Arrays.asList(prop("aaa", "relieve", ALTURA, 2.0, 1, 0), prop("bbb", "relieve", ALTURA, 4.0, 3, 0));
        final PerfilMundo mundo = new PerfilMundo();
        mundo.sellarGeneracion(7, alt);
        fallos += chequear("un mundo nuevo guarda el resultado de resolver las propuestas (relieve 3.5) y deja el resto por defecto",
            mundo.parametros().estirarAlturaSerpulo == 3.5f && mundo.parametros().estirarTemperaturaSerpulo == ParametrosGeneracion.DEFECTO.estirarTemperaturaSerpulo && mundo.congelado());
        fallos += chequear("al abrir el mundo después no se vuelve a resolver: el valor guardado manda aunque ya no estén las propuestas",
            PerfilMundo.desde(archivoDe(mundo)).parametros().estirarAlturaSerpulo == 3.5f);
        final PerfilMundo explicito = new PerfilMundo();
        explicito.fijar(ALTURA, 1.5);
        explicito.sellarGeneracion(7, alt);
        fallos += chequear("un valor fijado expresamente por el creador manda sobre las propuestas de los mods", explicito.parametros().estirarAlturaSerpulo == 1.5f);

        // registro de propuestas en el núcleo: el orden de registro no cuenta
        final boolean a1 = Nucleo.registrarParametro(prop("ccc", "3", "prueba:zz", 1.0, 1, 0));
        final boolean a2 = Nucleo.registrarParametro(prop("aaa", "1", "prueba:zz", 1.0, 1, 0));
        final boolean a3 = Nucleo.registrarParametro(prop("bbb", "2", "prueba:zz", 1.0, 1, -1));
        final boolean dup = Nucleo.registrarParametro(prop("ccc", "3", "prueba:zz", 9.0, 1, 0));
        final StringBuilder orden = new StringBuilder();
        for(ContribucionParametro c : Nucleo.propuestasPara("prueba:zz")) orden.append(c.id()).append(' ');
        fallos += chequear("el registro de propuestas queda en orden canónico sea cual sea el orden de registro y rechaza ids repetidos",
            a1 && a2 && a3 && !dup && orden.toString().trim().equals("bbb:2 aaa:1 ccc:3"));
        return fallos;
    }

    // ================================================================== fase 6: reparto de áreas

    /** Candidato de terreno de prueba para el reparto. */
    private static final class CandPrueba implements Contribucion{
        private final String fuente, id;
        private final float peso;
        private final int prioridad;

        CandPrueba(String fuente, String nombre, float peso, int prioridad){
            this.fuente = fuente;
            this.id = fuente + ":" + nombre;
            this.peso = peso;
            this.prioridad = prioridad;
        }

        @Override public String id(){ return id; }
        @Override public String fuente(){ return fuente; }
        @Override public int version(){ return 1; }
        @Override public Tipo tipo(){ return Tipo.TERRENO; }
        @Override public float peso(){ return peso; }
        @Override public int prioridad(){ return prioridad; }
    }

    /** Generador de fenómenos de prueba: un candidato por celda de 100 casillas, siempre en el mismo desplazamiento (los de distintas fuentes chocan entre sí). */
    private static final class GenPrueba implements GeneradorFenomeno{
        private final String id;
        private final float peso;
        private final int ox, oy;

        GenPrueba(String id, float peso, int ox, int oy){
            this.id = id;
            this.peso = peso;
            this.ox = ox;
            this.oy = oy;
        }

        @Override public String id(){ return id; }
        @Override public float peso(){ return peso; }
        @Override public int celda(){ return 100; }
        @Override public int distanciaMinima(){ return 30; }
        @Override public int radio(){ return 0; }

        @Override
        public boolean proponer(ContextoFenomeno c, int celdaX, int celdaY, int intento, int[] out){
            out[0] = celdaX * 100 + ox;
            out[1] = celdaY * 100 + oy;
            return true;
        }

        @Override public boolean valido(ContextoFenomeno c, int x, int y){ return true; }
        @Override public void aplicar(ContextoTerreno ctx, ContextoFenomeno c, int x, int y){}
    }

    private static int contarDuenos(int semilla, int dim, int x0, int y0, int lado, List<CandPrueba> cands, String id){
        int n = 0;
        for(int j = 0; j < lado; j++){
            for(int i = 0; i < lado; i++){
                final CandPrueba d = Reparto.dueno(semilla, dim, x0 + i, y0 + j, cands);
                if(d != null && d.id().equals(id)) n++;
            }
        }
        return n;
    }

    private static long[] finales(GeneradorFenomeno g, GeneradorFenomeno[] todos, int semilla, int lado, int D){
        return Fenomenos.puntosFinales(g, todos, new ContextoFenomeno(null, semilla, 0, g.id()), 0, 0, lado - 1, lado - 1, D);
    }

    /** Reparto de terreno por cuota de peso, distancia mínima global entre fuentes, conflictos ponderados, orden de carga y procedencia. */
    static int comprobarAreas(){
        int fallos = 0;

        // ---- terreno disputado: pesos 1 y 3 sobre cien macrocélulas
        final CandPrueba ligera = new CandPrueba("aaa", "ligera", 1f, 0), pesada = new CandPrueba("bbb", "pesada", 3f, 0);
        final List<CandPrueba> par = Arrays.asList(ligera, pesada);
        final int[] semillas = {1, 12345, 987654321, -20260101};
        final int[][] origenes = {{0, 0}, {-5, -5}, {10, -15}};
        int peor = 0;
        for(int s : semillas){
            for(int d : DIMS){
                for(int[] o : origenes){
                    final int a = contarDuenos(s, d, o[0], o[1], 10, par, ligera.id());
                    peor = Math.max(peor, Math.abs(a - 25));
                }
            }
        }
        fallos += chequear("terreno disputado con pesos 1 y 3 sobre 100 macrocélulas: la de peso 1 se queda entre 25 +/- " + peor + " (límite del plan: menos de 5 puntos; "
            + (semillas.length * DIMS.length * origenes.length) + " regiones)", peor < 5);

        final CandPrueba a1 = new CandPrueba("aaa", "uno", 1f, 0), b1 = new CandPrueba("bbb", "dos", 1f, 0), c2 = new CandPrueba("ccc", "tres", 2f, 0);
        final List<CandPrueba> tres = Arrays.asList(a1, b1, c2);
        final int na = contarDuenos(12345, 0, 0, 0, 10, tres, a1.id()), nb = contarDuenos(12345, 0, 0, 0, 10, tres, b1.id()), nc = contarDuenos(12345, 0, 0, 0, 10, tres, c2.id());
        fallos += chequear("tres candidatos con pesos 1, 1 y 2 reparten 100 macrocélulas como " + na + ", " + nb + " y " + nc + " (esperado 25, 25 y 50, tolerancia 5)",
            na + nb + nc == 100 && Math.abs(na - 25) < 5 && Math.abs(nb - 25) < 5 && Math.abs(nc - 50) < 5);

        boolean mismoOrden = true;
        for(List<CandPrueba> p : permutaciones(tres)){
            for(int j = -10; j < 10 && mismoOrden; j++){
                for(int i = -10; i < 10; i++){
                    if(!Reparto.dueno(777, 0, i, j, p).id().equals(Reparto.dueno(777, 0, i, j, tres).id())){
                        mismoOrden = false;
                        break;
                    }
                }
            }
        }
        fallos += chequear("el dueño de cada macrocélula es el mismo con cualquier orden de carga de los candidatos (6 ordenaciones, 400 macrocélulas)", mismoOrden);

        final CandPrueba cero = new CandPrueba("aaa", "cero", 0f, 0), neg = new CandPrueba("bbb", "neg", -2f, 0), nan = new CandPrueba("ccc", "nan", Float.NaN, 0), ok = new CandPrueba("ddd", "ok", 1f, 0);
        boolean soloValido = true;
        for(int i = 0; i < 50; i++) if(Reparto.dueno(1, 0, i, i, Arrays.asList(cero, neg, nan, ok)) != ok) soloValido = false;
        fallos += chequear("un candidato con peso 0, negativo o NaN no se elige nunca, y sin candidatos válidos no hay dueño",
            soloValido && Reparto.dueno(1, 0, 0, 0, Arrays.asList(cero, neg, nan)) == null);

        final CandPrueba rara = new CandPrueba("aaa", "rara", 1f, 0), comun = new CandPrueba("bbb", "comun", 100f, 0);
        final int pocas = contarDuenos(5, 0, 0, 0, 50, Arrays.asList(rara, comun), rara.id());
        fallos += chequear("un candidato con muy poco peso (1 contra 100) sigue apareciendo: " + pocas + " de 2500 macrocélulas (esperado unas 25)", pocas >= 10 && pocas <= 40);

        int distintas = 0;
        for(int j = 0; j < 10; j++) for(int i = 0; i < 10; i++){
            if(!Reparto.dueno(1, 0, i, j, par).id().equals(Reparto.dueno(2, 0, i, j, par).id())) distintas++;
        }
        fallos += chequear("otra semilla da otro reparto (" + distintas + " de 100 macrocélulas cambian)", distintas > 10);
        fallos += chequear("con solo el terreno base registrado, el dueño de toda macrocélula es la tubería del terreno base",
            Nucleo.TUBERIA_BASE.equals(Nucleo.duenoTerreno(1, 0, 3, 4)) && Nucleo.TUBERIA_BASE.equals(Nucleo.duenoTerreno(-9, 1, -7, 12)));

        // ---- fenómenos: distancia mínima global entre fuentes
        Fenomenos.reiniciar();
        final GenPrueba ga = new GenPrueba("aaa:uno", 1f, 10, 10), gb = new GenPrueba("bbb:dos", 3f, 30, 10), gc = new GenPrueba("ccc:tres", 1f, 20, 40);
        final GeneradorFenomeno[] todos = {ga, gb, gc};
        final int LADO = 4000, D = 50, CELDAS = 40 * 40;

        int sinGlobal = 0;
        for(GeneradorFenomeno g : todos) sinGlobal += finales(g, todos, 11, LADO, 0).length;
        final long[][] fin = new long[3][];
        int total = 0;
        for(int i = 0; i < 3; i++){
            fin[i] = finales(todos[i], todos, 11, LADO, D);
            total += fin[i].length;
        }
        long minimo = Long.MAX_VALUE;
        final int[] px = new int[total], py = new int[total];
        int k = 0;
        for(long[] f : fin) for(long p : f){ px[k] = (int)(p >> 32); py[k] = (int)p; k++; }
        for(int i = 0; i < total; i++){
            for(int j = i + 1; j < total; j++){
                final long dx = px[i] - px[j], dy = py[i] - py[j];
                minimo = Math.min(minimo, dx * dx + dy * dy);
            }
        }
        fallos += chequear("sin distancia global los tres generadores se pisan: " + sinGlobal + " fenómenos en " + CELDAS + " celdas (la prueba tiene dientes)", sinGlobal == 3 * CELDAS);
        fallos += chequear("con distancia global " + D + ": " + total + " fenómenos, uno por celda, y el par más cercano de cualquier fuente está a " + (long)Math.sqrt(minimo) + " casillas (mínimo " + D + ")",
            total == CELDAS && minimo >= (long)D * D);

        final double fa = fin[0].length / (double)total, fb = fin[1].length / (double)total, fc = fin[2].length / (double)total;
        fallos += chequear("conflictos ponderados con pesos 1, 3 y 1: ganan " + Math.round(fa * 1000) / 10.0 + " %, " + Math.round(fb * 1000) / 10.0 + " % y " + Math.round(fc * 1000) / 10.0
            + " % (esperado 20, 60 y 20; tolerancia 5 puntos)", Math.abs(fa - 0.2) < 0.05 && Math.abs(fb - 0.6) < 0.05 && Math.abs(fc - 0.2) < 0.05);

        boolean igualOrden = true;
        for(List<GeneradorFenomeno> p : permutaciones(Arrays.asList(todos))){
            final GeneradorFenomeno[] arr = p.toArray(new GeneradorFenomeno[0]);
            for(int i = 0; i < 3; i++) if(!Arrays.equals(fin[i], finales(todos[i], arr, 11, LADO, D))) igualOrden = false;
        }
        fallos += chequear("el resultado es idéntico con cualquier orden de registro de los generadores (6 ordenaciones)", igualOrden);

        // ---- procedencia por macrocélula
        final int M = 16, ladoRegion = M * Procedencia.LADO;
        int esperado = 0;
        for(GeneradorFenomeno g : todos) esperado += Fenomenos.puntosFinales(g, todos, new ContextoFenomeno(null, 11, 0, g.id()), 0, 0, ladoRegion - 1, ladoRegion - 1, D).length;
        int contados = 0;
        boolean duenoBase = true;
        for(int mj = 0; mj < M; mj++){
            for(int mi = 0; mi < M; mi++){
                final Procedencia.Macro m = Procedencia.consultar(todos, 11, 0, mi, mj, null, D);
                contados += m.totalFenomenos();
                if(!Nucleo.TUBERIA_BASE.equals(m.terreno)) duenoBase = false;
            }
        }
        fallos += chequear("la procedencia por macrocélula suma " + contados + " fenómenos en " + (M * M) + " macrocélulas (directo: " + esperado + ") y dice quién es dueña del terreno", contados == esperado && duenoBase);

        // ---- parámetro de generación: distancia mínima global
        final String DIST = ParametrosGeneracion.ID_FENOMENOS_DISTANCIA;
        fallos += chequear("la distancia global de fenómenos vale 0 por defecto: sin ella todo funciona como antes", ParametrosGeneracion.DEFECTO.distanciaMinimaFenomenos == 0 && new PerfilMundo().parametros().distanciaMinimaFenomenos == 0);
        final PerfilMundo pd = new PerfilMundo();
        pd.fijar(DIST, 9999);
        final int alto = pd.parametros().distanciaMinimaFenomenos;
        final PerfilMundo pb = new PerfilMundo();
        pb.fijar(DIST, -3);
        fallos += chequear("la distancia global se recorta a su rango (9999 -> 512, -3 -> 0)", alto == 512 && pb.parametros().distanciaMinimaFenomenos == 0);
        final PerfilMundo pr = new PerfilMundo();
        pr.sellarGeneracion(7, Arrays.asList(prop("aaa", "d", DIST, 40.0, 1, 0), prop("bbb", "d", DIST, 80.0, 3, 0)));
        fallos += chequear("la distancia global se resuelve por media ponderada al crear el mundo (40 y 80 con pesos 1 y 3 = 70) y se guarda",
            pr.parametros().distanciaMinimaFenomenos == 70 && PerfilMundo.desde(archivoDe(pr)).parametros().distanciaMinimaFenomenos == 70);
        return fallos;
    }

    // ================================================================== fase 7: presupuestos y degradación

    /** Etapa de prueba: marca cada casilla que procesa, con el presupuesto y la regla de degradación que se le den. */
    private static final class EtapaMarca extends EtapaBase{
        private final int marca, unidades;
        private final ReglaDegradacion regla;

        EtapaMarca(String nombre, int marca, int unidades, ReglaDegradacion regla){
            super(nombre, 0);
            this.marca = marca;
            this.unidades = unidades;
            this.regla = regla;
        }

        @Override
        public PresupuestoTrabajo presupuesto(){
            return new PresupuestoTrabajo(unidades, 0, 1024, "casilla");
        }

        @Override
        public ReglaDegradacion reglaDegradacion(){
            return regla;
        }

        @Override
        void celda(CeldaBase ce){
            ce.prop = marca;
        }
    }

    /** Contribución de terreno de prueba que no es una etapa: escribe un piso fijo, gasta las unidades que se le digan, o falla. */
    private static final class OtraPrueba implements ContribucionTerreno{
        private final int unidades, piso;
        private final boolean falla;

        OtraPrueba(int unidades, int piso, boolean falla){
            this.unidades = unidades;
            this.piso = piso;
            this.falla = falla;
        }

        @Override public String id(){ return "prueba:otra"; }
        @Override public String fuente(){ return "prueba"; }
        @Override public int version(){ return 1; }

        @Override
        public int aportar(ContextoAporte ctx, AporteTerreno out){
            if(falla) throw new IllegalStateException("fallo de prueba");
            for(int i = 0; i < AporteTerreno.N; i++){
                out.piso[i] = piso;
                out.mena[i] = 0;
                out.muro[i] = 0;
                out.prop[i] = 0;
            }
            return unidades;
        }
    }

    /** Ejecuta una sola etapa de prueba en el chunk de origen (x0, y0) y devuelve el aporte (un buffer nuevo) con su informe. */
    private static AporteTerreno correrMarca(EtapaMarca e, int x0, int y0, int pct){
        final AporteTerreno o = new AporteTerreno();
        final int T = MundoInfinitoMod.TAM_CHUNK;
        final ParametrosGeneracion par = new ParametrosGeneracion(2.3f, 3.1f, 2.7f, 0, pct);
        PipelineBase.ejecutar(new ContextoAporte(1, 0, x0, y0, Math.floorDiv(x0, T), Math.floorDiv(y0, T), par), o, new EtapaBase[]{e});
        return o;
    }

    private static int marcados(AporteTerreno o, int marca){
        int n = 0;
        for(int i = 0; i < AporteTerreno.N; i++) if(o.prop[i] == marca) n++;
        return n;
    }

    private static String listaDegradaciones(){
        final List<String> l = new ArrayList<String>();
        for(Degradaciones.Entrada e : Degradaciones.copia()) l.add(e.contribuyente + "@" + e.vcx + "," + e.vcy + ":" + e.pedidas + "/" + e.permitidas + ":" + e.regla);
        java.util.Collections.sort(l);
        return l.toString();
    }

    /** Presupuestos y reglas de degradación: determinismo, reapertura del mundo, y que nada excedido interrumpe la generación. */
    static int comprobarPresupuestos(List<int[]> es, long[] directo){
        int fallos = 0;
        final int T = MundoInfinitoMod.TAM_CHUNK;

        // ---- las tres reglas, una a una, en chunks de coordenadas positivas y negativas
        final int[][] origenes = {{0, 0}, {-64, -96}, {320, 128}};
        boolean recorte = true, omision = true, reduccion = true, normal = true, escala = true;
        for(int[] o : origenes){
            // recorte canónico: las primeras 300 casillas, fila a fila
            AporteTerreno a = correrMarca(new EtapaMarca("r", 7, 300, ReglaDegradacion.RECORTE_CANONICO), o[0], o[1], 100);
            for(int i = 0; i < AporteTerreno.N; i++) if((a.prop[i] == 7) != (i < 300)) recorte = false;
            if(!a.informe.degradada[0] || a.informe.pedidas[0] != 1024 || a.informe.permitidas[0] != 300 || a.informe.usadas[0] != 300) recorte = false;

            // omisión: nada
            a = correrMarca(new EtapaMarca("o", 8, 300, ReglaDegradacion.OMISION), o[0], o[1], 100);
            if(marcados(a, 8) != 0 || !a.informe.degradada[0] || a.informe.usadas[0] != 0) omision = false;

            // reducción de resolución: retícula 2x2 (256 casillas caben en 300), anclada a las coordenadas virtuales
            a = correrMarca(new EtapaMarca("d", 9, 300, ReglaDegradacion.REDUCCION_DE_RESOLUCION), o[0], o[1], 100);
            int esperadas = 0;
            for(int ly = 0; ly < T; ly++) for(int lx = 0; lx < T; lx++){
                final boolean enReticula = Math.floorMod(o[0] + lx, 2) == 0 && Math.floorMod(o[1] + ly, 2) == 0;
                if(enReticula) esperadas++;
                if((a.prop[ly * T + lx] == 9) != enReticula) reduccion = false;
            }
            if(esperadas != 256 || a.informe.usadas[0] != 256 || a.informe.paso[0] != 2) reduccion = false;
            // presupuestos cada vez más bajos: paso 4 (64 <= 100), paso 16 (4 <= 10) y, sin presupuesto, omitida
            if(PipelineBase.pasoReduccion(o[0], o[1], 100) != 4 || PipelineBase.pasoReduccion(o[0], o[1], 10) != 16 || PipelineBase.pasoReduccion(o[0], o[1], 0) != 0) reduccion = false;

            // sin exceso: todo el trabajo, sin degradar
            a = correrMarca(new EtapaMarca("n", 7, 1024, ReglaDegradacion.RECORTE_CANONICO), o[0], o[1], 100);
            if(marcados(a, 7) != 1024 || a.informe.degradada[0]) normal = false;

            // el porcentaje del perfil escala el presupuesto: 50 % de 1024 = 512
            a = correrMarca(new EtapaMarca("p", 7, 1024, ReglaDegradacion.RECORTE_CANONICO), o[0], o[1], 50);
            if(marcados(a, 7) != 512 || a.informe.permitidas[0] != 512) escala = false;
        }
        fallos += chequear("recorte canónico: con presupuesto 300 solo se hacen las 300 primeras casillas, fila a fila (3 chunks, coordenadas positivas y negativas)", recorte);
        fallos += chequear("omisión: con presupuesto menor que lo pedido no se hace nada de esa etapa", omision);
        fallos += chequear("reducción de resolución: con presupuesto 300 se hace 1 casilla de cada 2x2 anclada a coordenadas virtuales (256), y 4x4 con 100, 16x16 con 10", reduccion);
        fallos += chequear("sin exceso no se degrada nada y se hace todo el trabajo (1024 casillas)", normal);
        fallos += chequear("el porcentaje de presupuesto del perfil escala lo permitido (50 % de 1024 = 512)", escala);

        // ---- el terreno de verdad con un presupuesto bajo: siempre la misma salida degradada
        final ParametrosGeneracion p25 = new ParametrosGeneracion(2.3f, 3.1f, 2.7f, 0, 25);
        Nucleo.alReiniciarMundo(p25);   // como abrir un mundo: contadores y registro de degradaciones a cero
        olvidarCaches();
        final long[] d1 = calcularConHilos(es, 1, p25);
        final String reg1 = listaDegradaciones();
        final long tot1 = Degradaciones.total();
        final List<String> sist = Nucleo.sistematicas();
        olvidarCaches();
        final long[] d2 = calcularConHilos(es, 1, p25);
        final long[] d4 = calcularConHilos(es, 4, p25);
        fallos += chequear("con el 25 % del presupuesto el terreno cambia (no es el completo) pero es SIEMPRE el mismo: dos pasadas en frío y 4 hilos dan lo mismo",
            !Arrays.equals(d1, directo) && Arrays.equals(d1, d2) && Arrays.equals(d1, d4));
        fallos += chequear("el registro de degradaciones anota cada etapa recortada (" + tot1 + " entradas en " + es.size() + " chunks; pide 1024, se le permiten 256)",
            tot1 >= 11L * es.size() && reg1.contains(":1024/256:"));
        fallos += chequear("el aviso de contribuyente sistemático salta para las " + sist.size() + " etapas que se degradan en todos los chunks (esperado 11)", sist.size() == 11);

        // ---- un mundo reabierto reproduce la misma degradación (el perfil guardado trae el porcentaje)
        final PerfilMundo guardado = new PerfilMundo();
        guardado.fijar(ParametrosGeneracion.ID_PRESUPUESTO_TERRENO, 25);
        guardado.sellarGeneracion(7, new ArrayList<PropuestaParametro>());
        final ParametrosGeneracion reabierto = PerfilMundo.desde(archivoDe(guardado)).parametros();
        Nucleo.alReiniciarMundo(reabierto);
        olvidarCaches();
        final long[] r1 = calcularConHilos(es, 1, reabierto);
        fallos += chequear("un mundo reabierto reproduce la misma degradación: mismo terreno y mismo registro (porcentaje guardado en el perfil: " + reabierto.presupuestoTerrenoPct + ")",
            reabierto.presupuestoTerrenoPct == 25 && Arrays.equals(d1, r1) && listaDegradaciones().equals(reg1));

        // ---- ningún presupuesto excedido interrumpe la generación
        final ParametrosGeneracion p1 = new ParametrosGeneracion(2.3f, 3.1f, 2.7f, 0, 1);
        Nucleo.alReiniciarMundo(p1);
        boolean sinError = true;
        try{
            calcularNucleoCon(es, p1);
        }catch(Throwable t){
            sinError = false;
            t.printStackTrace();
        }
        fallos += chequear("con el 1 % del presupuesto (10 casillas por etapa) los " + es.size() + " chunks se generan igual, sin ninguna excepción", sinError && Degradaciones.total() >= 11L * es.size());

        // ---- una contribución que no es etapa: excede o falla -> omisión y respaldo del terreno base
        Nucleo.alReiniciarMundo(null);
        final int vcx = 3, vcy = -2, x0 = vcx * T, y0 = vcy * T;
        final ParametrosGeneracion pd = ParametrosGeneracion.DEFECTO;
        final EtapaBase[] base = EtapasBase.todas();
        java.util.Arrays.sort(base, Orden.CANONICO);
        final AporteTerreno ref = new AporteTerreno();
        PipelineBase.ejecutar(new ContextoAporte(1, 0, x0, y0, vcx, vcy, pd), ref, base);
        final long hashBase = hashAporte(ref);

        final AporteTerreno bien = Nucleo.evaluarOtra(new OtraPrueba(1000, 3, false), base, new ContextoAporte(1, 0, x0, y0, vcx, vcy, pd), new AporteTerreno());
        boolean suyo = true;
        for(int i = 0; i < AporteTerreno.N; i++) if(bien.piso[i] != 3) suyo = false;
        fallos += chequear("una contribución dentro de su presupuesto (1000 de 1024) aporta lo suyo y no se degrada", suyo && Degradaciones.total() == 0);

        final AporteTerreno pasada = Nucleo.evaluarOtra(new OtraPrueba(5000, 3, false), base, new ContextoAporte(1, 0, x0, y0, vcx, vcy, pd), new AporteTerreno());
        fallos += chequear("una contribución que gasta 5000 unidades con 1024 permitidas se omite y el chunk lo genera el terreno base (idéntico al terreno base solo)",
            hashAporte(pasada) == hashBase && Degradaciones.total() == 1 && listaDegradaciones().contains("prueba:otra@3,-2:5000/1024:omision"));

        final AporteTerreno rota = Nucleo.evaluarOtra(new OtraPrueba(0, 3, true), base, new ContextoAporte(1, 0, x0, y0, vcx, vcy, pd), new AporteTerreno());
        fallos += chequear("una contribución que lanza una excepción no interrumpe la generación: su aporte se omite y lo genera el terreno base",
            hashAporte(rota) == hashBase && Degradaciones.total() == 2 && listaDegradaciones().contains("omision-por-fallo"));

        // ---- el parámetro del perfil
        final PerfilMundo pp = new PerfilMundo();
        pp.fijar(ParametrosGeneracion.ID_PRESUPUESTO_TERRENO, 0);
        final int bajo = pp.parametros().presupuestoTerrenoPct;
        final PerfilMundo pq = new PerfilMundo();
        pq.fijar(ParametrosGeneracion.ID_PRESUPUESTO_TERRENO, 500);
        fallos += chequear("el presupuesto de generación vale 100 por defecto (nada se degrada) y se recorta a su rango (0 -> 1, 500 -> 100)",
            ParametrosGeneracion.DEFECTO.presupuestoTerrenoPct == 100 && new PerfilMundo().parametros().presupuestoTerrenoPct == 100 && bajo == 1 && pq.parametros().presupuestoTerrenoPct == 100);

        // dejar el núcleo como al empezar: sin degradaciones y con el perfil por defecto
        Nucleo.alReiniciarMundo(null);
        return fallos;
    }

    private static void calcularNucleoCon(List<int[]> es, ParametrosGeneracion par){
        for(int[] e : es) hashChunkNucleoCon(par, e[0], e[1], e[2], e[3]);
    }

    // ================================================================== fase 9: capa visual

    private static final class SalidaPrueba implements VisualSalida{
        float z;

        @Override public void z(float z){ this.z = z; }
        @Override public void reiniciar(){}
        @Override public void enCapa(float z, Runnable r){
            this.z = z;
            r.run();
        }
    }

    private static final class ProgramaPrueba implements VisualPrograma{
        final boolean falla;
        int aplicado, liberado;

        ProgramaPrueba(boolean falla){
            this.falla = falla;
        }

        @Override
        public void aplicar(Runnable d){
            aplicado++;
            if(falla) throw new IllegalStateException("shader roto de prueba");
            d.run();
        }

        @Override public void liberar(){ liberado++; }
    }

    private static VisualDibujo dib(final SalidaPrueba sal, final List<String> log, String fuente, String nombre, VisualCapa capa, int prio, int coste, boolean esencial, String shader, Boolp activo){
        final String id = fuente + ":" + nombre;
        return VisualDibujo.de(fuente, id, capa, prio, coste, esencial, shader, activo, new Runnable(){
            @Override public void run(){ log.add(id + "@" + sal.z); }
        });
    }

    private static VisualDibujo dibQueFalla(String fuente, String nombre, VisualCapa capa, String shader){
        return VisualDibujo.de(fuente, fuente + ":" + nombre, capa, 0, 1, false, shader, null, new Runnable(){
            @Override public void run(){ throw new IllegalStateException("dibujo roto de prueba"); }
        });
    }

    private static VisualPasada pasada(final List<String> log, String fuente, String nombre, int prio, int pasadas, long bytes){
        final String id = fuente + ":" + nombre;
        return VisualPasada.de(fuente, id, prio, pasadas, bytes, new Runnable(){
            @Override public void run(){ log.add(id); }
        });
    }

    private static List<String> ids(List<String> log){
        final List<String> r = new ArrayList<String>();
        for(String e : log) r.add(e.indexOf('@') < 0 ? e : e.substring(0, e.indexOf('@')));
        return r;
    }

    private static VisualShaderDef shaderDe(String fuente, String nombre, int prio, String[] uniformes, final VisualPrograma p){
        return VisualShaderDef.de(fuente, fuente + ":" + nombre, prio, uniformes, new Prov<VisualPrograma>(){
            @Override public VisualPrograma get(){ return p; }
        });
    }

    private static boolean referenciaVisual(byte[] b){
        return new String(b, StandardCharsets.ISO_8859_1).contains("mundoinfinito/Visual");
    }

    private static byte[] leerClase(File f) throws Exception{
        final java.io.FileInputStream in = new java.io.FileInputStream(f);
        try{
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }finally{
            in.close();
        }
    }

    /** Capa visual: orden declarado, shaders, conflictos de uniformes, fallos aislados, presupuestos, calidad, medición y separación de la simulación. */
    static int comprobarVisual(List<int[]> es, long[] directo) throws Exception{
        int fallos = 0;

        // ---- orden declarado: (capa, prioridad, fuente, id), con cualquier orden de registro
        final String[][] defs = {
            {"zeta", "uno", "UNIDADES", "0"}, {"alfa", "dos", "UNIDADES", "0"}, {"alfa", "tres", "UNIDADES", "-5"}, {"mid", "suelo", "SUELO", "9"},
            {"mid", "ui", "INTERFAZ", "0"}, {"beta", "bloque", "BLOQUES", "3"}, {"beta", "sobre", "SOBRE_BLOQUES", "0"}
        };
        final List<String[]> lista = Arrays.asList(defs);
        final String esperadoOrden = "[mid:suelo, beta:bloque, beta:sobre, alfa:tres, alfa:dos, zeta:uno, mid:ui]";
        boolean ordenOk = true;
        int permus = 0;
        for(List<String[]> p : permutaciones(lista)){
            final VisualRegistro r = new VisualRegistro();
            final SalidaPrueba sal = new SalidaPrueba();
            final List<String> log = new ArrayList<String>();
            for(String[] d : p) r.registrarDibujo(dib(sal, log, d[0], d[1], VisualCapa.valueOf(d[2]), Integer.parseInt(d[3]), 1, false, null, null));
            if(!r.ordenDibujo().toString().equals(esperadoOrden)) ordenOk = false;
            permus++;
        }
        fallos += chequear("el orden de dibujo (capa, prioridad, fuente, id) es el mismo con las " + permus + " ordenaciones de registro", ordenOk && permus == 5040);

        final VisualRegistro rz = new VisualRegistro();
        final SalidaPrueba sz = new SalidaPrueba();
        final List<String> lz = new ArrayList<String>();
        for(int i = defs.length - 1; i >= 0; i--) rz.registrarDibujo(dib(sz, lz, defs[i][0], defs[i][1], VisualCapa.valueOf(defs[i][2]), Integer.parseInt(defs[i][3]), 1, false, null, null));
        rz.configurar(VisualCalidad.ALTA, false);
        rz.dibujarFotograma(sz, 16f);
        boolean zOk = true;
        float anterior = -1f;
        for(String e : lz){
            final float z = Float.parseFloat(e.substring(e.indexOf('@') + 1));
            if(z < anterior) zOk = false;
            anterior = z;
        }
        fallos += chequear("al dibujar, las capas salen en el orden declarado y con la z de la capa (+ un desplazamiento mínimo dentro de ella): " + lz,
            ids(lz).toString().equals(esperadoOrden) && zOk && lz.get(3).endsWith("@60.0") && lz.get(4).endsWith("@60.001") && lz.get(5).endsWith("@60.002"));
        fallos += chequear("las capas siguen el orden real del motor (z creciente)",
            VisualCapa.SUELO.z < VisualCapa.BLOQUES.z && VisualCapa.BLOQUES.z < VisualCapa.SOBRE_BLOQUES.z && VisualCapa.SOBRE_BLOQUES.z < VisualCapa.UNIDADES.z
                && VisualCapa.UNIDADES.z < VisualCapa.BALAS.z && VisualCapa.BALAS.z < VisualCapa.SOBRE_UNIDADES.z && VisualCapa.SOBRE_UNIDADES.z < VisualCapa.INTERFAZ.z);

        // ---- un shader se registra, se aplica y se desactiva sin afectar a nada más
        final VisualRegistro rs = new VisualRegistro();
        final SalidaPrueba ss = new SalidaPrueba();
        final List<String> ls = new ArrayList<String>();
        final ProgramaPrueba azul = new ProgramaPrueba(false);
        rs.registrarShader(shaderDe("t", "azul", 0, new String[]{"t_fuerza"}, azul));
        rs.registrarDibujo(dib(ss, ls, "t", "con-shader", VisualCapa.BLOQUES, 0, 1, false, "t:azul", null));
        rs.registrarDibujo(dib(ss, ls, "t", "sin-shader", VisualCapa.BLOQUES, 1, 1, false, null, null));
        rs.configurar(VisualCalidad.ALTA, false);
        rs.dibujarFotograma(ss, 16f);
        final boolean aplicadoUna = azul.aplicado == 1 && ids(ls).toString().equals("[t:con-shader, t:sin-shader]");
        rs.desactivar("t:azul", "prueba");
        ls.clear();
        rs.dibujarFotograma(ss, 16f);
        final boolean desactivado = azul.aplicado == 1 && ids(ls).toString().equals("[t:con-shader, t:sin-shader]") && !rs.activo("t:azul");
        rs.reactivar("t:azul");
        ls.clear();
        rs.dibujarFotograma(ss, 16f);
        fallos += chequear("un shader se registra y se aplica (1 vez), se desactiva (los dibujos salen igual sin él) y se reactiva (2 aplicaciones), sin afectar a los demás dibujos",
            aplicadoUna && desactivado && azul.aplicado == 2 && rs.activo("t:azul") && ids(ls).toString().equals("[t:con-shader, t:sin-shader]"));

        // ---- dos shaders que escriben el mismo uniforme: aviso y resultado definido, sea cual sea el orden de carga
        boolean conflictoOk = true;
        for(int orden = 0; orden < 2; orden++){
            final VisualRegistro rc = new VisualRegistro();
            final VisualShaderDef a = shaderDe("aaa", "s1", 0, new String[]{"u_x"}, new ProgramaPrueba(false));
            final VisualShaderDef b = shaderDe("bbb", "s2", 0, new String[]{"u_x", "u_y"}, new ProgramaPrueba(false));
            if(orden == 0){
                rc.registrarShader(a);
                rc.registrarShader(b);
            }else{
                rc.registrarShader(b);
                rc.registrarShader(a);
            }
            if(!"aaa:s1".equals(rc.duenoDeUniforme("u_x")) || !rc.activo("aaa:s1") || rc.activo("bbb:s2") || rc.conflictosUniforme() != 1
                || !"conflicto de uniforme".equals(rc.motivoDesactivado("bbb:s2"))) conflictoOk = false;
        }
        fallos += chequear("dos shaders que escriben el mismo uniforme: aviso, gana el primero en orden canónico y el otro se desactiva, con cualquier orden de registro", conflictoOk);

        final VisualRegistro rc3 = new VisualRegistro();
        rc3.registrarShader(shaderDe("aaa", "s1", 0, new String[]{"u_x"}, new ProgramaPrueba(false)));
        rc3.registrarShader(shaderDe("bbb", "s2", 0, new String[]{"u_y"}, new ProgramaPrueba(false)));
        final boolean sinConflicto = rc3.conflictosUniforme() == 0 && rc3.activo("aaa:s1") && rc3.activo("bbb:s2");
        rc3.registrarShader(shaderDe("zzz", "s3", -1, new String[]{"u_x"}, new ProgramaPrueba(false)));
        fallos += chequear("uniformes distintos no chocan; si llega después uno de mayor prioridad que escribe uno ya usado, pasa a ser el dueño y el anterior se desactiva",
            sinConflicto && "zzz:s3".equals(rc3.duenoDeUniforme("u_x")) && !rc3.activo("aaa:s1") && rc3.activo("zzz:s3") && rc3.activo("bbb:s2") && rc3.conflictosUniforme() == 1);

        // ---- un shader que falla al crearse se desactiva y el dibujo sale igual
        final VisualRegistro rf = new VisualRegistro();
        final SalidaPrueba sf = new SalidaPrueba();
        final List<String> lf = new ArrayList<String>();
        final int[] creaciones = new int[1];
        rf.registrarShader(VisualShaderDef.de("t", "t:roto", 0, new String[0], new Prov<VisualPrograma>(){
            @Override public VisualPrograma get(){
                creaciones[0]++;
                throw new IllegalArgumentException("el shader no compila");
            }
        }));
        final ProgramaPrueba sano = new ProgramaPrueba(false);
        rf.registrarShader(shaderDe("t", "sano", 0, new String[]{"t_sano"}, sano));
        rf.registrarDibujo(dib(sf, lf, "t", "d-roto", VisualCapa.BLOQUES, 0, 1, false, "t:roto", null));
        rf.registrarDibujo(dib(sf, lf, "t", "d-sano", VisualCapa.BLOQUES, 1, 1, false, "t:sano", null));
        rf.configurar(VisualCalidad.ALTA, false);
        rf.dibujarFotograma(sf, 16f);
        rf.dibujarFotograma(sf, 16f);
        fallos += chequear("un shader que no compila se desactiva con aviso, su dibujo sale igual sin él, no se vuelve a intentar y el resto sigue (2 fotogramas)",
            creaciones[0] == 1 && !rf.activo("t:roto") && "fallo".equals(rf.motivoDesactivado("t:roto")) && rf.activo("t:sano") && sano.aplicado == 2
                && ids(lf).toString().equals("[t:d-roto, t:d-sano, t:d-roto, t:d-sano]"));

        // ---- un shader que falla al aplicarse
        final VisualRegistro ra = new VisualRegistro();
        final SalidaPrueba sa = new SalidaPrueba();
        final List<String> la = new ArrayList<String>();
        final ProgramaPrueba roto = new ProgramaPrueba(true);
        ra.registrarShader(shaderDe("t", "roto", 0, new String[0], roto));
        ra.registrarDibujo(dib(sa, la, "t", "d", VisualCapa.BLOQUES, 0, 1, false, "t:roto", null));
        ra.configurar(VisualCalidad.ALTA, false);
        ra.dibujarFotograma(sa, 16f);
        ra.dibujarFotograma(sa, 16f);
        fallos += chequear("un shader que lanza una excepción al aplicarse se desactiva (se libera y no se vuelve a usar) y el dibujo sale sin él en ese mismo fotograma",
            roto.aplicado == 1 && roto.liberado == 1 && !ra.activo("t:roto") && ids(la).toString().equals("[t:d, t:d]"));

        // ---- un dibujo que falla no se atribuye al shader, y no arrastra a los demás
        final VisualRegistro rd = new VisualRegistro();
        final SalidaPrueba sd = new SalidaPrueba();
        final List<String> ld = new ArrayList<String>();
        final ProgramaPrueba ok1 = new ProgramaPrueba(false);
        rd.registrarShader(shaderDe("t", "ok", 0, new String[0], ok1));
        rd.registrarDibujo(dibQueFalla("t", "malo-con-shader", VisualCapa.BLOQUES, "t:ok"));
        rd.registrarDibujo(dibQueFalla("t", "malo", VisualCapa.UNIDADES, null));
        rd.registrarDibujo(dib(sd, ld, "t", "bueno", VisualCapa.INTERFAZ, 0, 1, false, null, null));
        rd.registrarDibujo(dib(sd, ld, "t", "condicion-rota", VisualCapa.INTERFAZ, 1, 1, false, null, new Boolp(){
            @Override public boolean get(){ throw new IllegalStateException("condición rota"); }
        }));
        rd.configurar(VisualCalidad.ALTA, false);
        rd.dibujarFotograma(sd, 16f);
        rd.dibujarFotograma(sd, 16f);
        fallos += chequear("un dibujo (o su condición) que lanza una excepción se desactiva con aviso; el shader en el que iba sigue activo y los demás dibujos salen en los 2 fotogramas",
            !rd.activo("t:malo-con-shader") && !rd.activo("t:malo") && !rd.activo("t:condicion-rota") && rd.activo("t:ok") && ok1.aplicado == 1
                && ids(ld).toString().equals("[t:bueno, t:bueno]"));

        // ---- presupuesto de la calidad: se omite lo opcional de atrás hacia delante; lo esencial no se omite nunca
        boolean presOk = true;
        String ultimo = "";
        for(List<String[]> p : permutaciones(Arrays.asList(
            new String[]{"e1", "SUELO", "0", "30", "true"}, new String[]{"o1", "BLOQUES", "0", "5", "false"},
            new String[]{"o2", "BLOQUES", "1", "5", "false"}, new String[]{"o3", "UNIDADES", "0", "20", "false"}))){
            final VisualRegistro rp = new VisualRegistro();
            final SalidaPrueba sp = new SalidaPrueba();
            final List<String> lp = new ArrayList<String>();
            for(String[] d : p) rp.registrarDibujo(dib(sp, lp, "t", d[0], VisualCapa.valueOf(d[1]), Integer.parseInt(d[2]), Integer.parseInt(d[3]), Boolean.parseBoolean(d[4]), null, null));
            rp.configurar(VisualCalidad.BAJA, false);   // 40 unidades: 60 pedidas
            rp.dibujarFotograma(sp, 16f);
            ultimo = ids(lp).toString();
            if(!ultimo.equals("[t:e1, t:o1, t:o2]") || rp.ultimoCoste() != 40 || rp.ultimosOmitidos() != 1) presOk = false;
        }
        fallos += chequear("con calidad baja (40 unidades) y 60 pedidas se omite lo opcional empezando por el último en orden canónico (queda " + ultimo + "), con las 24 ordenaciones", presOk);

        final VisualRegistro rg = new VisualRegistro();
        final SalidaPrueba sg = new SalidaPrueba();
        final List<String> lg = new ArrayList<String>();
        rg.registrarDibujo(dib(sg, lg, "t", "grande-esencial", VisualCapa.BLOQUES, 0, 100, true, null, null));
        rg.registrarDibujo(dib(sg, lg, "t", "inactivo", VisualCapa.BLOQUES, 1, 500, false, null, new Boolp(){
            @Override public boolean get(){ return false; }
        }));
        rg.configurar(VisualCalidad.BAJA, false);
        rg.dibujarFotograma(sg, 16f);
        fallos += chequear("un dibujo esencial nunca se omite aunque exceda el presupuesto, y uno cuya condición es falsa no cuenta en el coste", ids(lg).toString().equals("[t:grande-esencial]") && rg.ultimoCoste() == 100);
        rg.configurar(VisualCalidad.ALTA, false);

        // ---- cadena de post-procesado con coste declarado
        boolean pasOk = true;
        for(List<String[]> p : permutaciones(Arrays.asList(
            new String[]{"p1", "0", "1", "4194304"}, new String[]{"p2", "1", "1", "4194304"}, new String[]{"p3", "2", "1", "1048576"}))){
            final VisualRegistro rq = new VisualRegistro();
            final List<String> lq = new ArrayList<String>();
            for(String[] d : p) rq.registrarPasada(pasada(lq, "t", d[0], Integer.parseInt(d[1]), Integer.parseInt(d[2]), Long.parseLong(d[3])));
            rq.configurar(VisualCalidad.MEDIA, false);   // 2 pasadas y 8 MB
            rq.ejecutarPasadas();
            if(!lq.toString().equals("[t:p1, t:p2]") || rq.ultimasPasadas() != 2 || rq.ultimasPasadasOmitidas() != 1 || rq.ultimosBytes() != 8L * 1024 * 1024) pasOk = false;
            lq.clear();
            rq.configurar(VisualCalidad.BAJA, false);
            rq.ejecutarPasadas();
            if(!lq.isEmpty() || rq.ultimasPasadasOmitidas() != 3) pasOk = false;
            lq.clear();
            rq.configurar(VisualCalidad.ALTA, false);
            rq.ejecutarPasadas();
            if(!lq.toString().equals("[t:p1, t:p2, t:p3]")) pasOk = false;
        }
        fallos += chequear("las pasadas de post-procesado salen en orden canónico y solo mientras quepan: media (2 pasadas, 8 MB) deja la tercera fuera, baja ninguna, alta todas (6 ordenaciones)", pasOk);

        final VisualRegistro rr = new VisualRegistro();
        final List<String> lr = new ArrayList<String>();
        rr.registrarPasada(VisualPasada.de("t", "t:mala", 0, 1, 1L, new Runnable(){
            @Override public void run(){ throw new IllegalStateException("pasada rota"); }
        }));
        rr.registrarPasada(pasada(lr, "t", "buena", 1, 1, 1L));
        rr.configurar(VisualCalidad.ALTA, false);
        rr.ejecutarPasadas();
        rr.ejecutarPasadas();
        fallos += chequear("una pasada que falla se desactiva con aviso y las demás siguen", !rr.activo("t:mala") && lr.toString().equals("[t:buena, t:buena]"));

        // ---- registros inválidos
        final VisualRegistro rv = new VisualRegistro();
        final boolean rechaza = !rv.registrarDibujo(VisualDibujo.de("t", "sin-formato", VisualCapa.BLOQUES, 0, 1, false, null, null, new Runnable(){ @Override public void run(){} }))
            && !rv.registrarShader(VisualShaderDef.de("t", "t:x", 0, null, null)) && !rv.registrarPasada(null);
        final VisualDibujo duplicable = dib(new SalidaPrueba(), new ArrayList<String>(), "t", "uno", VisualCapa.BLOQUES, 0, 1, false, null, null);
        fallos += chequear("se rechazan los registros sin formato de id, sin función o duplicados", rechaza && rv.registrarDibujo(duplicable) && !rv.registrarDibujo(duplicable));

        // ---- medición automática de fotogramas
        final VisualMonitor mon = new VisualMonitor();
        for(int i = 0; i < VisualMonitor.IGNORAR; i++) mon.muestra(500f);
        final boolean ignoraCarga = mon.niveles() == 0;
        for(int i = 0; i < VisualMonitor.VENTANA; i++) mon.muestra(40f);
        final VisualCalidad tras1 = mon.efectiva(VisualCalidad.ALTA);
        for(int i = 0; i < VisualMonitor.VENTANA; i++) mon.muestra(40f);
        final VisualCalidad tras2 = mon.efectiva(VisualCalidad.ALTA);
        for(int i = 0; i < VisualMonitor.VENTANA; i++) mon.muestra(40f);
        final boolean noPasaDeBaja = mon.niveles() == 2 && mon.efectiva(VisualCalidad.ALTA) == VisualCalidad.BAJA && mon.efectiva(VisualCalidad.MEDIA) == VisualCalidad.BAJA && mon.efectiva(VisualCalidad.BAJA) == VisualCalidad.BAJA;
        for(int i = 0; i < VisualMonitor.VENTANA * VisualMonitor.VENTANAS_PARA_SUBIR; i++) mon.muestra(10f);
        final int trasMejora1 = mon.niveles();
        for(int i = 0; i < VisualMonitor.VENTANA * VisualMonitor.VENTANAS_PARA_SUBIR; i++) mon.muestra(10f);
        final int trasMejora2 = mon.niveles();
        final boolean nuncaSobreElegida = mon.efectiva(VisualCalidad.MEDIA) == VisualCalidad.MEDIA && mon.efectiva(VisualCalidad.BAJA) == VisualCalidad.BAJA;
        for(int i = 0; i < VisualMonitor.VENTANA * 3; i++) mon.muestra(25f);   // entre los dos umbrales: no cambia nada
        final boolean intermedio = mon.niveles() == 0;
        final VisualMonitor tiron = new VisualMonitor();
        for(int i = 0; i < VisualMonitor.IGNORAR; i++) tiron.muestra(10f);
        tiron.muestra(5000f);
        for(int i = 1; i < VisualMonitor.VENTANA; i++) tiron.muestra(10f);
        fallos += chequear("la medición automática ignora la carga, baja un nivel por ventana lenta hasta baja, no sube por encima de lo elegido, recupera tras 5 ventanas rápidas y un tirón suelto no decide",
            ignoraCarga && tras1 == VisualCalidad.MEDIA && tras2 == VisualCalidad.BAJA && noPasaDeBaja && trasMejora1 == 1 && trasMejora2 == 0 && nuncaSobreElegida && intermedio && tiron.niveles() == 0);

        final VisualRegistro rm = new VisualRegistro();
        rm.configurar(VisualCalidad.ALTA, true);
        final SalidaPrueba sm = new SalidaPrueba();
        for(int i = 0; i < VisualMonitor.IGNORAR + VisualMonitor.VENTANA; i++) rm.dibujarFotograma(sm, 50f);
        final VisualCalidad conAuto = rm.calidad();
        rm.configurar(VisualCalidad.ALTA, false);
        fallos += chequear("con la medición automática activada el registro baja de alta a media con fotogramas lentos; desactivada, queda en la elegida", conAuto == VisualCalidad.MEDIA && rm.calidad() == VisualCalidad.ALTA && VisualCalidad.deIndice(-3) == VisualCalidad.BAJA && VisualCalidad.deIndice(9) == VisualCalidad.ALTA);

        // ---- la calidad visual no cambia el mundo
        boolean mismoMundo = true;
        for(VisualCalidad q : VisualCalidad.values()){
            VisualRegistro.global().configurar(q, false);
            olvidarCaches();
            if(!Arrays.equals(directo, calcularNucleo(es)) || VisualRegistro.global().calidad() != q) mismoMundo = false;
        }
        VisualRegistro.global().configurar(VisualCalidad.ALTA, true);
        fallos += chequear("cambiar la calidad visual (baja, media, alta) no cambia ni un chunk del corpus (" + es.size() + " chunks, 3 calidades)", mismoMundo);

        // ---- regresión de un dispositivo real: el entorno combinado no puede incluir el calor abrasador (mataba a las unidades de Serpulo)
        fallos += chequear("el entorno combinado de los mundos con construcción global no incluye el calor abrasador (Env.scorching), que mata a las unidades de Serpulo al aparecer; incluye los de ambos planetas",
            (MundoInfinitoMod.ENTORNO_COMBINADO & Env.scorching) == 0
                && (MundoInfinitoMod.ENTORNO_COMBINADO & (Env.terrestrial | Env.spores | Env.groundOil | Env.groundWater | Env.oxygen)) == (Env.terrestrial | Env.spores | Env.groundOil | Env.groundWater | Env.oxygen));

        // ---- la simulación y la generación no pueden referenciar la capa visual (se mira el código compilado)
        final java.util.Set<String> sim = new HashSet<String>(Arrays.asList("Nucleo", "PipelineBase", "CeldaBase", "EtapaBase", "EtapasBase", "Reparto", "Resolucion", "Fenomenos",
            "Procedencia", "Orden", "Contribucion", "ContribucionTerreno", "ContribucionParametro", "PropuestaParametro", "PresupuestoTrabajo", "Degradaciones", "PerfilMundo",
            "RegistroAjustes", "AjusteMundo", "ParametrosGeneracion", "ReglaDegradacion", "ContextoAporte", "AporteTerreno", "Extensiones", "Hilos", "ContextoFenomeno",
            "ContextoTerreno", "GeneradorFenomeno", "GeneradorTerreno", "Estructuras"));
        final URL url = Corpus.class.getResource("Corpus.class");
        if(url == null || !"file".equals(url.getProtocol())){
            fallos += chequear("separación simulación / capa visual: no verificable (el código no está en un directorio de clases)", true);
        }else{
            final File dir = new File(url.toURI()).getParentFile();
            final File[] clases = dir.listFiles();
            int mirados = 0;
            final List<String> infractoras = new ArrayList<String>();
            boolean detectorFunciona = false;
            if(clases != null){
                for(File f : clases){
                    final String n = f.getName();
                    if(!n.endsWith(".class")) continue;
                    final String base = n.substring(0, n.length() - 6);
                    final String top = base.indexOf('$') < 0 ? base : base.substring(0, base.indexOf('$'));
                    if(base.equals("VisualRegistro")) detectorFunciona = referenciaVisual(leerClase(f));
                    if(!sim.contains(top)) continue;
                    mirados++;
                    if(referenciaVisual(leerClase(f))) infractoras.add(base);
                }
            }
            fallos += chequear("la simulación y la generación no referencian la capa visual: " + mirados + " clases revisadas en el código compilado, " + infractoras.size() + " infractoras " + infractoras
                + " (el detector ve las referencias de VisualRegistro: " + detectorFunciona + ")", infractoras.isEmpty() && mirados >= 25 && detectorFunciona);
        }
        return fallos;
    }

    // ================================================================== fase 10: efectos, ambiente y mallas

    private static VisualEfectoDef efectoPrueba(final List<float[]> log, final SalidaPrueba sal, String fuente, String nombre, VisualCapa capa, int prio, float dur, int coste, boolean esencial){
        final String id = fuente + ":" + nombre;
        return VisualEfectoDef.de(fuente, id, capa, prio, dur, coste, esencial, new Cons<VisualEfectoInst>(){
            @Override public void get(VisualEfectoInst i){ log.add(new float[]{i.x, i.y, sal.z, i.fin(), i.seq}); }
        });
    }

    private static boolean emitirSimple(VisualEfectos ef, String id, float x){
        return ef.emitir(id, x, 0f, 0f, 1f, 1f, 1f, 1f, null);
    }

    private static int[] bits(float[] c){
        return new int[]{Float.floatToIntBits(c[0]), Float.floatToIntBits(c[1]), Float.floatToIntBits(c[2]), Float.floatToIntBits(c[3])};
    }

    /** Efectos con pool, color ambiental con política y mallas preparadas fuera del hilo de render. */
    static int comprobarVisual10() throws Exception{
        int fallos = 0;

        // ---- un efecto aparece en la posición y la capa declaradas y desaparece al terminar su duración
        final VisualEfectos ef = new VisualEfectos(512);
        final SalidaPrueba se = new SalidaPrueba();
        final List<float[]> le = new ArrayList<float[]>();
        fallos += chequear("se rechazan los efectos sin formato de id, sin duración positiva o duplicados",
            !ef.registrarEfecto(VisualEfectoDef.de("t", "sin-formato", VisualCapa.BLOQUES, 0, 10f, 1, false, new Cons<VisualEfectoInst>(){ @Override public void get(VisualEfectoInst i){} }))
                && !ef.registrarEfecto(VisualEfectoDef.de("t", "t:cero", VisualCapa.BLOQUES, 0, 0f, 1, false, new Cons<VisualEfectoInst>(){ @Override public void get(VisualEfectoInst i){} }))
                && ef.registrarEfecto(efectoPrueba(le, se, "t", "anillo", VisualCapa.SOBRE_BLOQUES, 0, 60f, 1, false))
                && !ef.registrarEfecto(efectoPrueba(le, se, "t", "anillo", VisualCapa.SOBRE_BLOQUES, 0, 60f, 1, false)));
        ef.configurar(VisualCalidad.ALTA);
        final boolean emitido = ef.emitir("t:anillo", 10f, 20f, 0f, 1f, 1f, 1f, 1f, null);
        for(int t = 1; t <= 59; t++) ef.dibujar(se, 1f);
        final boolean vivoA59 = ef.vivos() == 1 && le.size() == 59;
        ef.dibujar(se, 1f);   // tick 60: la duración se cumple
        final boolean terminado = ef.vivos() == 0 && ef.libres() == 512 && le.size() == 59;
        boolean posOk = true, creciente = true;
        final float zEsperada = VisualCapa.SOBRE_BLOQUES.z + VisualEfectos.Z_EFECTO;
        for(int i = 0; i < le.size(); i++){
            final float[] d = le.get(i);
            if(d[0] != 10f || d[1] != 20f || d[2] != zEsperada) posOk = false;
            if(d[3] != (i + 1) / 60f) creciente = false;
        }
        fallos += chequear("un efecto de 60 ticks aparece en (10, 20) y en la z de su capa (" + zEsperada + "), se dibuja 59 fotogramas con progreso creciente y al cumplirse la duración desaparece y su instancia vuelve al pool",
            emitido && vivoA59 && terminado && posOk && creciente);

        // ---- en cada capa, la suya; las capas salen en orden
        final VisualEfectos e2 = new VisualEfectos(512);
        final SalidaPrueba s2 = new SalidaPrueba();
        final List<float[]> l2 = new ArrayList<float[]>();
        e2.registrarEfecto(efectoPrueba(l2, s2, "t", "ui", VisualCapa.INTERFAZ, 0, 10f, 1, false));
        e2.registrarEfecto(efectoPrueba(l2, s2, "t", "suelo", VisualCapa.SUELO, 0, 10f, 1, false));
        e2.registrarEfecto(efectoPrueba(l2, s2, "t", "unidad", VisualCapa.UNIDADES, 0, 10f, 1, false));
        e2.configurar(VisualCalidad.ALTA);
        emitirSimple(e2, "t:ui", 1f);
        emitirSimple(e2, "t:suelo", 2f);
        emitirSimple(e2, "t:unidad", 3f);
        e2.dibujar(s2, 1f);
        fallos += chequear("los efectos de capas distintas salen en la z de su capa y por orden de capa (suelo, unidades, interfaz)",
            l2.size() == 3 && l2.get(0)[2] == VisualCapa.SUELO.z + VisualEfectos.Z_EFECTO && l2.get(1)[2] == VisualCapa.UNIDADES.z + VisualEfectos.Z_EFECTO
                && l2.get(2)[2] == VisualCapa.INTERFAZ.z + VisualEfectos.Z_EFECTO && l2.get(0)[0] == 2f && l2.get(1)[0] == 3f && l2.get(2)[0] == 1f);

        // ---- pool: tamaño fijo, sin asignaciones al emitir
        final VisualEfectos e3 = new VisualEfectos(64);
        final SalidaPrueba s3 = new SalidaPrueba();
        e3.registrarEfecto(efectoPrueba(new ArrayList<float[]>(), s3, "t", "p", VisualCapa.BLOQUES, 0, 5f, 1, false));
        e3.configurar(VisualCalidad.ALTA);
        int aceptadas3 = 0, maxVivos = 0;
        for(int i = 0; i < 20000; i++){
            if(emitirSimple(e3, "t:p", i)) aceptadas3++;
            maxVivos = Math.max(maxVivos, e3.vivos());
            if(i % 7 == 0) e3.dibujar(s3, 1f);
        }
        fallos += chequear("el pool es de tamaño fijo: tras 20000 emisiones hay como mucho " + maxVivos + " vivos de 64 y las instancias creadas siguen siendo " + e3.creadas() + " (" + aceptadas3 + " aceptadas)",
            maxVivos <= 64 && e3.creadas() == 64 && aceptadas3 > 0 && e3.vivos() + e3.libres() == 64);

        // ---- densidad por calidad (determinista) y los esenciales no se adelgazan
        final int[] esperadas = {500, 750, 1000};
        boolean densidadOk = true;
        String detalle = "";
        for(VisualCalidad q : VisualCalidad.values()){
            final VisualEfectos e4 = new VisualEfectos(512);
            final SalidaPrueba s4 = new SalidaPrueba();
            e4.registrarEfecto(efectoPrueba(new ArrayList<float[]>(), s4, "t", "opcional", VisualCapa.BLOQUES, 0, 10f, 1, false));
            e4.registrarEfecto(efectoPrueba(new ArrayList<float[]>(), s4, "t", "esencial", VisualCapa.BLOQUES, 0, 10f, 1, true));
            e4.configurar(q);
            int op = 0, es4 = 0;
            for(int i = 0; i < 1000; i++){
                if(emitirSimple(e4, "t:opcional", i)) op++;
                if(emitirSimple(e4, "t:esencial", i)) es4++;
                e4.dibujar(s4, 1000f);
            }
            detalle += q.nombre + "=" + op + " ";
            if(op != esperadas[q.ordinal()] || es4 != 1000) densidadOk = false;
        }
        fallos += chequear("la densidad de efectos sigue a la calidad sin azar: de 1000 emisiones opcionales entran " + detalle + "(esperado 500, 750 y 1000) y los esenciales entran todos", densidadOk);

        // ---- tope por calidad y desalojo por prioridad
        final VisualEfectos e5 = new VisualEfectos(512);
        final SalidaPrueba s5 = new SalidaPrueba();
        final List<float[]> l5 = new ArrayList<float[]>();
        e5.registrarEfecto(efectoPrueba(l5, s5, "t", "lento", VisualCapa.BLOQUES, 5, 1e6f, 1, true));
        e5.registrarEfecto(efectoPrueba(l5, s5, "t", "mejor", VisualCapa.BLOQUES, 1, 1e6f, 1, true));
        e5.registrarEfecto(efectoPrueba(l5, s5, "t", "peor", VisualCapa.BLOQUES, 9, 1e6f, 1, true));
        e5.configurar(VisualCalidad.BAJA);   // 32 efectos como máximo
        int entran = 0;
        for(int i = 0; i < 100; i++) if(emitirSimple(e5, "t:lento", i)) entran++;
        final boolean tope = e5.vivos() == 32 && entran == 32;
        final boolean mejorEntra = emitirSimple(e5, "t:mejor", 500f);
        final boolean peorNo = !emitirSimple(e5, "t:peor", 600f);
        final boolean igualNo = !emitirSimple(e5, "t:lento", 700f);
        e5.dibujar(s5, 0f);
        boolean esta1 = false, estaMejor = false;
        for(float[] d : l5){
            if(d[0] == 0f) esta1 = true;
            if(d[0] == 500f) estaMejor = true;
        }
        fallos += chequear("con calidad baja (32 efectos) no entran más; uno de mejor prioridad desaloja al peor y más antiguo, y uno peor o igual no entra",
            tope && mejorEntra && peorNo && igualNo && e5.vivos() == 32 && !esta1 && estaMejor && l5.size() == 32);

        // ---- un efecto que falla se desactiva sin afectar a los demás
        final VisualEfectos e6 = new VisualEfectos(512);
        final SalidaPrueba s6 = new SalidaPrueba();
        final List<float[]> l6 = new ArrayList<float[]>();
        e6.registrarEfecto(VisualEfectoDef.de("t", "t:malo", VisualCapa.BLOQUES, 0, 100f, 1, false, new Cons<VisualEfectoInst>(){
            @Override public void get(VisualEfectoInst i){ throw new IllegalStateException("efecto roto de prueba"); }
        }));
        e6.registrarEfecto(efectoPrueba(l6, s6, "t", "bueno", VisualCapa.BLOQUES, 1, 100f, 1, false));
        e6.configurar(VisualCalidad.ALTA);
        emitirSimple(e6, "t:malo", 1f);
        emitirSimple(e6, "t:bueno", 2f);
        emitirSimple(e6, "t:malo", 3f);
        e6.dibujar(s6, 1f);
        final boolean desactivado = !e6.activo("t:malo") && l6.size() == 1;
        e6.dibujar(s6, 1f);
        fallos += chequear("un efecto que lanza una excepción al dibujar se desactiva con aviso, sus instancias se liberan, ya no se puede emitir y el bueno sigue",
            desactivado && e6.vivos() == 1 && !emitirSimple(e6, "t:malo", 4f) && e6.activo("t:bueno") && l6.size() == 2);

        // ---- presupuesto de coste de dibujo
        final VisualEfectos e7 = new VisualEfectos(512);
        final SalidaPrueba s7 = new SalidaPrueba();
        final List<float[]> l7 = new ArrayList<float[]>();
        e7.registrarEfecto(efectoPrueba(l7, s7, "t", "caro", VisualCapa.BLOQUES, 0, 1e6f, 10, false));
        e7.configurar(VisualCalidad.ALTA);   // presupuesto 1024: caben 102 de coste 10
        for(int i = 0; i < 120; i++) emitirSimple(e7, "t:caro", i);
        e7.dibujar(s7, 0f);
        boolean primeros = true;
        for(float[] d : l7) if(d[0] > 101f) primeros = false;
        fallos += chequear("el coste declarado de los efectos vivos se limita al presupuesto de la calidad: de 120 se dibujan " + e7.ultimosDibujados() + " (los más antiguos) y se omiten " + e7.ultimosOmitidos(),
            e7.ultimosDibujados() == 102 && e7.ultimosOmitidos() == 18 && l7.size() == 102 && primeros && e7.vivos() == 120);

        final VisualEfectos e8 = new VisualEfectos(512);
        final SalidaPrueba s8 = new SalidaPrueba();
        final List<float[]> l8 = new ArrayList<float[]>();
        e8.registrarEfecto(efectoPrueba(l8, s8, "t", "grande", VisualCapa.BLOQUES, 0, 100f, 500, true));
        e8.configurar(VisualCalidad.BAJA);
        emitirSimple(e8, "t:grande", 1f);
        for(int i = 0; i < 10; i++) e8.dibujar(s8, 0f);   // en pausa (delta 0) no envejece
        fallos += chequear("un efecto esencial se dibuja aunque exceda el presupuesto, y con el juego en pausa (delta 0) se queda quieto: 10 fotogramas, mismo progreso",
            l8.size() == 10 && e8.vivos() == 1 && l8.get(0)[3] == 0f && l8.get(9)[3] == 0f);

        // ---- color ambiental: política declarada por el canal
        boolean media = true;
        for(int orden = 0; orden < 2; orden++){
            final VisualAmbiente amb = new VisualAmbiente(VisualAmbiente.Politica.MEDIA_PONDERADA);
            if(orden == 0){
                amb.registrar("a", "a:uno", 0, 1f);
                amb.registrar("b", "b:dos", 0, 3f);
            }else{
                amb.registrar("b", "b:dos", 0, 3f);
                amb.registrar("a", "a:uno", 0, 1f);
            }
            amb.fijar("a:uno", 1f, 0f, 0.5f, 0.25f);
            amb.fijar("b:dos", 0f, 1f, 0.5f, 0.75f);
            final float[] c = amb.combinar();
            if(c == null || c[0] != 0.25f || c[1] != 0.75f || c[2] != 0.5f || c[3] != 0.625f) media = false;
        }
        fallos += chequear("un cambio de iluminación de dos mods con política de media ponderada (pesos 1 y 3) da (0.25, 0.75, 0.5, 0.625) con cualquier orden de registro", media);

        final float[] pesosAmb = {0.7f, 1.3f, 0.1f, 2.9f};
        final float[][] coloresAmb = {{0.1f, 0.2f, 0.3f, 0.4f}, {0.9f, 0.8f, 0.7f, 0.6f}, {0.33f, 0.66f, 0.99f, 0.11f}, {0.5f, 0.25f, 0.125f, 0.0625f}};
        final String[] fuentesAmb = {"a", "b", "c", "d"};
        int[] primero = null;
        boolean identico = true;
        int ordenaciones = 0;
        for(List<Integer> p : permutaciones(Arrays.asList(0, 1, 2, 3))){
            final VisualAmbiente amb = new VisualAmbiente(VisualAmbiente.Politica.MEDIA_PONDERADA);
            for(int i : p) amb.registrar(fuentesAmb[i], fuentesAmb[i] + ":x", 0, pesosAmb[i]);
            for(int k = p.size() - 1; k >= 0; k--){
                final int i = p.get(k);
                amb.fijar(fuentesAmb[i] + ":x", coloresAmb[i][0], coloresAmb[i][1], coloresAmb[i][2], coloresAmb[i][3]);
            }
            final int[] b = bits(amb.combinar());
            if(primero == null) primero = b;
            else if(!Arrays.equals(primero, b)) identico = false;
            ordenaciones++;
        }
        fallos += chequear("con pesos y colores decimales el resultado de la media es idéntico bit a bit con las " + ordenaciones + " ordenaciones de registro y de fijado", identico && ordenaciones == 24);

        boolean prioridad = true;
        for(int orden = 0; orden < 2; orden++){
            final VisualAmbiente amb = new VisualAmbiente(VisualAmbiente.Politica.PRIORIDAD_ABSOLUTA);
            if(orden == 0){
                amb.registrar("z", "z:x", 5, 1f);
                amb.registrar("y", "y:w", 1, 1f);
            }else{
                amb.registrar("y", "y:w", 1, 1f);
                amb.registrar("z", "z:x", 5, 1f);
            }
            amb.fijar("z:x", 1f, 0f, 0f, 1f);
            amb.fijar("y:w", 0f, 0f, 1f, 0.5f);
            final float[] c = amb.combinar();
            if(c == null || c[0] != 0f || c[2] != 1f || c[3] != 0.5f) prioridad = false;
        }
        fallos += chequear("con política de prioridad absoluta manda la contribución de menor prioridad, sea cual sea el orden de registro", prioridad);

        final VisualAmbiente am4 = new VisualAmbiente(VisualAmbiente.Politica.MEDIA_PONDERADA);
        am4.registrar("x", "x:a", 0, 1f);
        am4.registrar("y", "y:nan", 0, 1f);
        am4.registrar("z", "z:cero", 0, 0f);
        am4.fijar("x:a", 2f, -1f, 0.5f, 0.5f);
        am4.fijar("y:nan", Float.NaN, 0f, 0f, 0f);
        am4.fijar("z:cero", 0f, 0f, 0f, 1f);
        final float[] c4 = am4.combinar();
        fallos += chequear("un color fuera de rango se recorta a [0, 1], y uno no numérico o con peso 0 se ignora: no se impone nada inválido",
            c4 != null && c4[0] == 1f && c4[1] == 0f && c4[2] == 0.5f && c4[3] == 0.5f);

        final VisualAmbiente am5 = new VisualAmbiente(VisualAmbiente.Politica.MEDIA_PONDERADA);
        am5.registrar("a", "a:uno", 0, 1f);
        final boolean sinColor = am5.combinar() == null;
        am5.fijar("a:uno", 0.1f, 0.2f, 0.3f, 0.4f);
        final boolean conColor = am5.combinar() != null && am5.activas() == 1;
        am5.quitar("a:uno");
        fallos += chequear("sin contribuciones activas no hay ningún cambio (null) y al quitarlas no queda nada residual", sinColor && conColor && am5.combinar() == null && am5.activas() == 0);

        // ---- mallas: se preparan en otro hilo y se suben solo en el hilo de render
        Hilos.cuadro();   // este hilo es el principal (el de render) para las pruebas
        final String hiloRender = Thread.currentThread().getName();
        final VisualMallas m1 = new VisualMallas(16);
        final List<String> hilosTrabajo = java.util.Collections.synchronizedList(new ArrayList<String>());
        final List<String> hilosSubida = new ArrayList<String>();
        final List<Integer> subidos = new ArrayList<Integer>();
        for(int i = 0; i < 6; i++){
            final int k = i;
            m1.preparar("malla" + i, new Prov<Integer>(){
                @Override public Integer get(){
                    hilosTrabajo.add(Thread.currentThread().getName());
                    return k;
                }
            }, new Cons<Integer>(){
                @Override public void get(Integer v){
                    hilosSubida.add(Thread.currentThread().getName());
                    subidos.add(v);
                }
            });
        }
        esperarMallas(m1);
        final boolean nadaAntes = subidos.isEmpty();
        final int subidas1 = m1.subirListos(10);
        boolean fondo = hilosTrabajo.size() == 6, render = hilosSubida.size() == 6;
        for(String h : hilosTrabajo) if(!h.startsWith("MundoInfinito-Pool-") || h.equals(hiloRender)) fondo = false;
        for(String h : hilosSubida) if(!h.equals(hiloRender)) render = false;
        fallos += chequear("una malla preparada en un hilo de fondo (" + (hilosTrabajo.isEmpty() ? "?" : hilosTrabajo.get(0)) + ") no se sube hasta que el hilo de render lo pide y entonces se sube en él, 6 de 6, en orden de petición",
            nadaAntes && subidas1 == 6 && fondo && render && subidos.toString().equals("[0, 1, 2, 3, 4, 5]") && m1.subidas() == 6 && m1.pendientes() == 0);

        final VisualMallas m2 = new VisualMallas(16);
        for(int i = 0; i < 10; i++){
            final int k = i;
            m2.preparar("lote" + i, new Prov<Integer>(){ @Override public Integer get(){ return k; } }, new Cons<Integer>(){ @Override public void get(Integer v){} });
        }
        esperarMallas(m2);
        final int a1 = m2.subirListos(VisualCalidad.ALTA.subidasMalla), a2 = m2.subirListos(VisualCalidad.ALTA.subidasMalla), a3 = m2.subirListos(VisualCalidad.ALTA.subidasMalla), a4 = m2.subirListos(VisualCalidad.ALTA.subidasMalla);
        fallos += chequear("la subida respeta el máximo por fotograma de la calidad (alta: " + VisualCalidad.ALTA.subidasMalla + "): 10 mallas se suben como " + a1 + ", " + a2 + ", " + a3 + " y " + a4, a1 == 4 && a2 == 4 && a3 == 2 && a4 == 0);

        final VisualMallas m3 = new VisualMallas(16);
        final int[] cuentaSubidas = new int[1];
        for(int i = 0; i < 3; i++){
            m3.preparar("fuera" + i, new Prov<Integer>(){ @Override public Integer get(){ return 1; } }, new Cons<Integer>(){ @Override public void get(Integer v){ cuentaSubidas[0]++; } });
        }
        esperarMallas(m3);
        final int[] desdeOtro = {-1};
        final Thread otro = new Thread(new Runnable(){ @Override public void run(){ desdeOtro[0] = m3.subirListos(10); } }, "hilo-que-no-es-el-de-render");
        otro.start();
        otro.join();
        final boolean nadaFuera = desdeOtro[0] == 0 && cuentaSubidas[0] == 0 && m3.pendientes() == 3;
        final int dentro = m3.subirListos(10);
        fallos += chequear("si se pide subir desde un hilo que no es el de render no se sube nada (ninguna llamada de GPU fuera de él); desde el de render se suben las 3", nadaFuera && dentro == 3 && cuentaSubidas[0] == 3);

        final VisualMallas m4 = new VisualMallas(16);
        final List<Integer> buenas = new ArrayList<Integer>();
        m4.preparar("falla-trabajo", new Prov<Integer>(){ @Override public Integer get(){ throw new IllegalStateException("trabajo roto de prueba"); } }, new Cons<Integer>(){ @Override public void get(Integer v){ buenas.add(-1); } });
        m4.preparar("sin-datos", new Prov<Integer>(){ @Override public Integer get(){ return null; } }, new Cons<Integer>(){ @Override public void get(Integer v){ buenas.add(-2); } });
        m4.preparar("buena-1", new Prov<Integer>(){ @Override public Integer get(){ return 1; } }, new Cons<Integer>(){ @Override public void get(Integer v){ buenas.add(v); } });
        m4.preparar("falla-subida", new Prov<Integer>(){ @Override public Integer get(){ return 2; } }, new Cons<Integer>(){ @Override public void get(Integer v){ throw new IllegalStateException("subida rota de prueba"); } });
        m4.preparar("buena-2", new Prov<Integer>(){ @Override public Integer get(){ return 3; } }, new Cons<Integer>(){ @Override public void get(Integer v){ buenas.add(v); } });
        esperarMallas(m4);
        final int tratadas = m4.subirListos(10);
        fallos += chequear("un trabajo que falla, uno sin datos y una subida que falla se descartan con aviso y las demás mallas se suben (5 tratadas, 2 subidas, 3 fallos)",
            tratadas == 5 && m4.subidas() == 2 && m4.fallos() == 3 && buenas.toString().equals("[1, 3]") && m4.pendientes() == 0);

        final VisualMallas m5 = new VisualMallas(2);
        final boolean[] r5 = new boolean[3];
        for(int i = 0; i < 3; i++) r5[i] = m5.preparar("cap" + i, new Prov<Integer>(){ @Override public Integer get(){ return 1; } }, new Cons<Integer>(){ @Override public void get(Integer v){} });
        esperarMallas(m5);
        m5.subirListos(10);
        fallos += chequear("la cola de mallas tiene un tope: con capacidad 2 la tercera petición se rechaza, y tras subir se vuelve a aceptar",
            r5[0] && r5[1] && !r5[2] && m5.preparar("cap-otra", new Prov<Integer>(){ @Override public Integer get(){ return 1; } }, new Cons<Integer>(){ @Override public void get(Integer v){} }));
        esperarMallas(m5);
        m5.subirListos(10);

        fallos += chequear("los presupuestos de efectos y de subida de mallas crecen con la calidad (efectos 32/128/512, subidas 1/2/4)",
            VisualCalidad.BAJA.efectosMax == 32 && VisualCalidad.MEDIA.efectosMax == 128 && VisualCalidad.ALTA.efectosMax == 512
                && VisualCalidad.BAJA.subidasMalla == 1 && VisualCalidad.MEDIA.subidasMalla == 2 && VisualCalidad.ALTA.subidasMalla == 4
                && VisualCalidad.BAJA.costeEfectos < VisualCalidad.MEDIA.costeEfectos && VisualCalidad.MEDIA.costeEfectos < VisualCalidad.ALTA.costeEfectos);
        return fallos;
    }

    /** Espera (hasta 5 s) a que los trabajos de preparación de las mallas terminen. */
    private static void esperarMallas(VisualMallas m) throws Exception{
        final long limite = System.nanoTime() + 5_000_000_000L;
        while(!m.todoListo() && System.nanoTime() < limite) Thread.sleep(2);
    }

    // ================================================================== API pública

    private static final class CapaPrueba implements VisualShaderPuntos.Capa{
        final Object original;
        Object actual;
        int cambios;

        CapaPrueba(Object original){
            this.original = original;
            this.actual = original;
        }

        @Override public Object original(){ return original; }
        @Override public void poner(Object shader){
            actual = shader;
            cambios++;
        }
    }

    private static Prov<Object> fab(final Object valor, final int[] llamadas){
        return new Prov<Object>(){
            @Override public Object get(){
                llamadas[0]++;
                return valor;
            }
        };
    }

    /** Qué shader acaba en la capa del agua con una calidad dada, y comprueba que se restaura el original. */
    private static Object ganadoraAgua(VisualShaderPuntos vp, VisualCalidad q){
        final CapaPrueba agua = new CapaPrueba("original");
        final EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa> m = new EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa>(VisualShaderPuntos.Punto.class);
        m.put(VisualShaderPuntos.Punto.AGUA, agua);
        vp.aplicar(m, q);
        final Object r = agua.actual;
        vp.restaurar(m);
        return agua.actual == agua.original ? r : "NO-RESTAURADO";
    }

    /** Puntos de sustitución de shaders del juego, versiones, fachadas públicas, ajustes de mods y superficie pública. */
    static int comprobarApi() throws Exception{
        int fallos = 0;
        final int[] ll = new int[1];

        // ---- puntos de shader del juego
        final CapaPrueba agua = new CapaPrueba("agua-original"), barro = new CapaPrueba("barro-original");
        final EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa> capas = new EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa>(VisualShaderPuntos.Punto.class);
        capas.put(VisualShaderPuntos.Punto.AGUA, agua);
        capas.put(VisualShaderPuntos.Punto.BARRO, barro);
        final VisualShaderPuntos vacio = new VisualShaderPuntos();
        vacio.aplicar(capas, VisualCalidad.ALTA);
        vacio.restaurar(capas);
        fallos += chequear("sin variantes ni límites registrados no se toca ninguna capa del juego ni se omite ninguna superficie",
            agua.cambios == 0 && barro.cambios == 0 && !vacio.omitirSuperficies(1e9f) && Float.isNaN(vacio.limiteZoom()));

        final VisualShaderPuntos v1 = new VisualShaderPuntos();
        v1.registrarVariante("t", "t:v1", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("v1", ll), null);
        v1.aplicar(capas, VisualCalidad.ALTA);
        final boolean puesta = "v1".equals(agua.actual) && barro.cambios == 0 && "barro-original".equals(barro.actual);
        v1.restaurar(capas);
        fallos += chequear("una variante se pone solo en su punto durante el dibujo y después se restaura el shader original del juego", puesta && "agua-original".equals(agua.actual) && barro.cambios == 0);

        boolean conflicto = true;
        for(List<String[]> p : permutaciones(Arrays.asList(new String[]{"aaa", "0"}, new String[]{"bbb", "0"}, new String[]{"zzz", "-1"}))){
            final VisualShaderPuntos vp = new VisualShaderPuntos();
            for(String[] d : p) vp.registrarVariante(d[0], d[0] + ":v", VisualShaderPuntos.Punto.AGUA, Integer.parseInt(d[1]), null, null, fab(d[0], ll), null);
            final Object g = ganadoraAgua(vp, VisualCalidad.ALTA);
            if(!"zzz".equals(g) || vp.conflictos() != 2) conflicto = false;
        }
        fallos += chequear("si varias variantes compiten por un punto gana la de menor prioridad (y, en empate, la primera por fuente) con las 6 ordenaciones de registro; las demás avisan", conflicto);

        boolean empate = true;
        for(int orden = 0; orden < 2; orden++){
            final VisualShaderPuntos vp = new VisualShaderPuntos();
            if(orden == 0){
                vp.registrarVariante("aaa", "aaa:v", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("A", ll), null);
                vp.registrarVariante("bbb", "bbb:v", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("B", ll), null);
            }else{
                vp.registrarVariante("bbb", "bbb:v", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("B", ll), null);
                vp.registrarVariante("aaa", "aaa:v", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("A", ll), null);
            }
            if(!"A".equals(ganadoraAgua(vp, VisualCalidad.ALTA)) || vp.conflictos() != 1) empate = false;
        }
        fallos += chequear("con la misma prioridad gana la fuente que va antes alfabéticamente, sea cual sea el orden de carga", empate);

        final VisualShaderPuntos calidades = new VisualShaderPuntos();
        calidades.registrarVariante("a", "a:barata", VisualShaderPuntos.Punto.AGUA, 0, VisualCalidad.BAJA, VisualCalidad.MEDIA, fab("barata", ll), null);
        calidades.registrarVariante("b", "b:bonita", VisualShaderPuntos.Punto.AGUA, 0, VisualCalidad.ALTA, VisualCalidad.ALTA, fab("bonita", ll), null);
        final boolean rangoMalo = !calidades.registrarVariante("c", "c:mala", VisualShaderPuntos.Punto.AGUA, 0, VisualCalidad.ALTA, VisualCalidad.BAJA, fab("x", ll), null);
        fallos += chequear("variantes de calidades distintas no compiten: baja y media usan la barata, alta la bonita; un rango de calidad al revés se rechaza",
            "barata".equals(ganadoraAgua(calidades, VisualCalidad.BAJA)) && "barata".equals(ganadoraAgua(calidades, VisualCalidad.MEDIA))
                && "bonita".equals(ganadoraAgua(calidades, VisualCalidad.ALTA)) && calidades.conflictos() == 0 && rangoMalo);

        final VisualShaderPuntos rota = new VisualShaderPuntos();
        final int[] intentos = new int[1];
        rota.registrarVariante("t", "t:rota", VisualShaderPuntos.Punto.AGUA, 0, null, null, new Prov<Object>(){
            @Override public Object get(){
                intentos[0]++;
                throw new IllegalStateException("shader que no compila");
            }
        }, null);
        rota.registrarVariante("t", "t:buena", VisualShaderPuntos.Punto.BARRO, 0, null, null, fab("barro-nuevo", ll), null);
        final CapaPrueba a2 = new CapaPrueba("agua-original"), b2 = new CapaPrueba("barro-original");
        final EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa> m2 = new EnumMap<VisualShaderPuntos.Punto, VisualShaderPuntos.Capa>(VisualShaderPuntos.Punto.class);
        m2.put(VisualShaderPuntos.Punto.AGUA, a2);
        m2.put(VisualShaderPuntos.Punto.BARRO, b2);
        rota.aplicar(m2, VisualCalidad.ALTA);
        rota.restaurar(m2);
        rota.aplicar(m2, VisualCalidad.ALTA);
        final boolean seguia = "barro-nuevo".equals(b2.actual) && "agua-original".equals(a2.actual);
        rota.restaurar(m2);
        fallos += chequear("una variante cuyo shader no se puede crear se descarta con aviso (se queda el original, no se reintenta) y las de otros puntos siguen", intentos[0] == 1 && seguia && a2.cambios == 0);

        final boolean[] on = {false};
        final VisualShaderPuntos cond = new VisualShaderPuntos();
        cond.registrarVariante("t", "t:cond", VisualShaderPuntos.Punto.AGUA, 0, null, null, fab("cond", ll), new Boolp(){
            @Override public boolean get(){ return on[0]; }
        });
        final Object apagada = ganadoraAgua(cond, VisualCalidad.ALTA);
        on[0] = true;
        final Object encendida = ganadoraAgua(cond, VisualCalidad.ALTA);
        fallos += chequear("la condición de una variante se evalúa en cada fotograma (apagada: shader original; encendida: la variante)", "original".equals(apagada) && "cond".equals(encendida));

        boolean zoomOk = true;
        for(int orden = 0; orden < 2; orden++){
            final VisualShaderPuntos z = new VisualShaderPuntos();
            if(orden == 0){
                z.registrarLimiteZoom("a", "a:z", 0, 1f, 600f, null);
                z.registrarLimiteZoom("b", "b:z", 0, 3f, 1000f, null);
            }else{
                z.registrarLimiteZoom("b", "b:z", 0, 3f, 1000f, null);
                z.registrarLimiteZoom("a", "a:z", 0, 1f, 600f, null);
            }
            if(z.limiteZoom() != 900f || z.omitirSuperficies(899f) || !z.omitirSuperficies(901f)) zoomOk = false;
        }
        final VisualShaderPuntos zc = new VisualShaderPuntos();
        final boolean[] zon = {false};
        zc.registrarLimiteZoom("a", "a:z", 0, 1f, 600f, new Boolp(){ @Override public boolean get(){ return zon[0]; } });
        final boolean sinActiva = Float.isNaN(zc.limiteZoom()) && !zc.omitirSuperficies(1e9f);
        zon[0] = true;
        final boolean rechazaMalo = !zc.registrarLimiteZoom("b", "b:z", 0, 0f, 600f, null) && !zc.registrarLimiteZoom("c", "c:z", 0, 1f, -5f, null) && !zc.registrarLimiteZoom("d", "sin-formato", 0, 1f, 600f, null);
        fallos += chequear("el límite de zoom combina varios mods por media ponderada (600 con peso 1 y 1000 con peso 3 = 900) con cualquier orden; solo cuentan los activos; se rechazan pesos o anchos inválidos",
            zoomOk && sinActiva && zc.limiteZoom() == 600f && rechazaMalo);

        // ---- versiones
        fallos += chequear("las versiones de la API son compatibles con el mismo mayor y un menor suficiente",
            ApiVersion.compatible(1, 0, 1, 0) && ApiVersion.compatible(1, 3, 1, 2) && !ApiVersion.compatible(1, 2, 1, 3) && !ApiVersion.compatible(2, 0, 1, 0) && !ApiVersion.compatible(1, 0, 2, 0)
                && ApiVersion.requiereVisual("api-prueba", ApiVersion.VISUAL_MAYOR, ApiVersion.VISUAL_MENOR) && ApiVersion.requiereAjustes("api-prueba", ApiVersion.AJUSTES_MAYOR, 0)
                && !ApiVersion.requiereVisual("api-prueba", ApiVersion.VISUAL_MAYOR + 1, 0) && !ApiVersion.requiereAjustes("api-prueba", ApiVersion.AJUSTES_MAYOR, ApiVersion.AJUSTES_MENOR + 1)
                && ApiVersion.texto().startsWith("api 1.0"));

        // ---- fachada visual: lo que se registra por la API queda en los registros del núcleo
        final Runnable nada = new Runnable(){ @Override public void run(){} };
        final boolean dib = ApiVisual.registrarDibujo("api-prueba", "api-prueba:d1", VisualCapa.BLOQUES, 0, 1, false, null, null, nada);
        final boolean dup = ApiVisual.registrarDibujo("api-prueba", "api-prueba:d1", VisualCapa.BLOQUES, 0, 1, false, null, null, nada);
        final boolean inv = ApiVisual.registrarDibujo("api-prueba", "sin-formato", VisualCapa.BLOQUES, 0, 1, false, null, null, nada);
        final boolean vivo = ApiVisual.activo("api-prueba:d1");
        ApiVisual.desactivar("api-prueba:d1", "prueba");
        fallos += chequear("la API visual registra dibujos en el registro del núcleo, rechaza repetidos e inválidos, y permite desactivar", dib && !dup && !inv && vivo && !ApiVisual.activo("api-prueba:d1") && ApiVisual.calidad() != null);

        final boolean efReg = ApiVisual.registrarEfecto("api-prueba", "api-prueba:e1", VisualCapa.SOBRE_BLOQUES, 0, 10f, 1, true, new Cons<VisualEfectoInst>(){
            @Override public void get(VisualEfectoInst i){ i.fin(); i.fout(); i.x(); i.y(); i.r(); i.g(); i.b(); i.a(); i.rot(); i.tiempo(); i.datos(); }
        });
        VisualEfectos.global().configurar(VisualCalidad.ALTA);
        final boolean emitido = ApiVisual.emitirEfecto("api-prueba:e1", 1f, 2f, 0f, 1f, 1f, 1f, 1f, null);
        final int vivosAntes = VisualEfectos.global().vivos();
        VisualEfectos.global().dibujar(new SalidaPrueba(), 1000f);
        fallos += chequear("la API de efectos registra y emite en el pool del núcleo, y el efecto se libera al terminar", efReg && emitido && vivosAntes == 1 && VisualEfectos.global().vivos() == 0 && !ApiVisual.emitirEfecto("api-prueba:no-existe", 0f, 0f, 0f, 1f, 1f, 1f, 1f, null));

        final boolean ambReg = ApiVisual.registrarAmbiente("api-prueba", "api-prueba:a1", 0, 1f);
        ApiVisual.fijarAmbiente("api-prueba:a1", 0.25f, 0.5f, 0.75f, 0.125f);
        final float[] ca = VisualAmbiente.global().combinar();
        final boolean ambOk = ca != null && ca[0] == 0.25f && ca[1] == 0.5f && ca[2] == 0.75f && ca[3] == 0.125f;
        ApiVisual.quitarAmbiente("api-prueba:a1");
        fallos += chequear("la API del color ambiental aporta un color al canal del juego y al quitarlo no queda nada", ambReg && ambOk && VisualAmbiente.global().combinar() == null);

        Hilos.cuadro();
        final int[] subidasApi = new int[1];
        final boolean malla = ApiVisual.prepararMalla("api-prueba:malla", new Prov<Integer>(){ @Override public Integer get(){ return 7; } }, new Cons<Integer>(){
            @Override public void get(Integer v){ subidasApi[0] += v; }
        });
        esperarMallas(VisualMallas.global());
        final int subidas = VisualMallas.global().subirListos(10);
        fallos += chequear("la API de mallas prepara en un hilo de fondo y sube en el hilo de render", malla && subidas == 1 && subidasApi[0] == 7);

        final VisualShaderPuntos puntosAntes = VisualShaderPuntos.global();
        final int variantesAntes = puntosAntes.variantes();
        final boolean varReg = ApiVisual.registrarVarianteShader("api-prueba", "api-prueba:variante", ApiVisual.PuntoShader.AGUA, 0, VisualCalidad.BAJA, VisualCalidad.ALTA, new Prov<Shader>(){
            @Override public Shader get(){ return null; }
        }, null);
        final boolean limReg = ApiVisual.registrarLimiteZoom("api-prueba", "api-prueba:limite", 0, 1f, 500f, null);
        fallos += chequear("la API de shaders del juego registra variantes y límites de zoom en los puntos del núcleo", varReg && limReg && puntosAntes.variantes() == variantesAntes + 1 && puntosAntes.limiteZoom() == 500f);

        // ---- fachada de ajustes de mods
        final boolean regA = ApiAjustes.registrarDecimal("api-prueba:param", "Parámetro de prueba", "Para comprobar la API", 2.0, 1.0, 4.0, ApiAjustes.Politica.MEDIA_PONDERADA);
        final boolean dupA = ApiAjustes.registrarDecimal("api-prueba:param", "otra vez", "", 2.0, 1.0, 4.0, ApiAjustes.Politica.MEDIA_PONDERADA);
        final boolean invA = ApiAjustes.registrarEntero("sin-formato", "x", "", 1, 0, 2, null);
        final boolean propA = ApiAjustes.proponer("a", "a:p", "api-prueba:param", 1.0, 1.0, 0) && ApiAjustes.proponer("b", "b:p", "api-prueba:param", 4.0, 3.0, 0);
        final PerfilMundo mundoApi = new PerfilMundo();
        mundoApi.sellarGeneracion(7);
        fallos += chequear("la API de ajustes registra un parámetro de un mod, recibe propuestas y al crear un mundo se resuelven con su política (1 y 4 con pesos 1 y 3 = 3.25); sin mundo vale su valor por defecto",
            regA && !dupA && !invA && propA && mundoApi.valor(RegistroAjustes.get("api-prueba:param")) == 3.25 && ApiAjustes.valor("api-prueba:param") == 2.0 && Double.isNaN(ApiAjustes.valor("api-prueba:no-existe"))
                && ApiAjustes.procedencia(0, 0) == null);

        // ---- superficie pública: solo lo previsto es público, y la API no filtra tipos internos
        final java.util.Set<String> previstas = new java.util.TreeSet<String>(Arrays.asList("AlmacenModulo", "ContextoColocacion", "ContextoFenomeno", "ContextoTerreno", "GameRule",
            "GeneradorEstructura", "GeneradorFenomeno", "GeneradorMundo", "GeneradorTerreno", "MundoInfinitoMod", "MundoRegistrado", "OpenWorldAPI", "PoliticaSueno", "SiloInterdimensional",
            "Corpus", "ApiVersion", "ApiVisual", "ApiAjustes", "VisualCapa", "VisualCalidad", "VisualPrograma", "VisualEfectoInst"));
        final java.util.Set<String> publicas = new java.util.TreeSet<String>();
        final URL url = Corpus.class.getResource("Corpus.class");
        if(url == null || !"file".equals(url.getProtocol())){
            fallos += chequear("superficie pública: no verificable (el código no está en un directorio de clases)", true);
        }else{
            final File[] clases = new File(url.toURI()).getParentFile().listFiles();
            int noCargadas = 0;
            if(clases != null){
                for(File f : clases){
                    final String n = f.getName();
                    if(!n.endsWith(".class") || n.indexOf('$') >= 0) continue;
                    final String simple = n.substring(0, n.length() - 6);
                    try{
                        final Class<?> c = Class.forName("mundoinfinito." + simple, false, Corpus.class.getClassLoader());
                        if(Modifier.isPublic(c.getModifiers())) publicas.add(simple);
                    }catch(Throwable t){
                        noCargadas++;
                    }
                }
            }
            final java.util.Set<String> sobran = new java.util.TreeSet<String>(publicas);
            sobran.removeAll(previstas);
            final java.util.Set<String> faltan = new java.util.TreeSet<String>(previstas);
            faltan.removeAll(publicas);
            fallos += chequear("superficie pública: solo son públicas las " + previstas.size() + " clases previstas (sobran " + sobran + ", faltan " + faltan + ", sin poder cargar " + noCargadas + ")", sobran.isEmpty() && faltan.isEmpty());
        }
        final List<String> filtran = new ArrayList<String>();
        for(Class<?> api : new Class<?>[]{ApiVisual.class, ApiAjustes.class, ApiVersion.class}){
            for(Method m : api.getDeclaredMethods()){
                if(!Modifier.isPublic(m.getModifiers())) continue;
                final List<Class<?>> tipos = new ArrayList<Class<?>>(Arrays.asList(m.getParameterTypes()));
                tipos.add(m.getReturnType());
                for(Class<?> t : tipos){
                    Class<?> base = t;
                    while(base.isArray()) base = base.getComponentType();
                    if(base.getName().startsWith("mundoinfinito.") && !Modifier.isPublic(base.getModifiers())) filtran.add(api.getSimpleName() + "." + m.getName() + " -> " + base.getSimpleName());
                }
            }
        }
        fallos += chequear("los métodos de la API pública solo usan tipos públicos (no filtran clases internas): " + filtran, filtran.isEmpty());
        return fallos;
    }

    /** Respiraderos: separación, mezcla y radio. Los mundos anteriores conservan los valores de entonces; los nuevos, los nuevos. */
    static int comprobarEspaciado(){
        int fallos = 0;
        final PerfilMundo viejo = new PerfilMundo();
        final ParametrosGeneracion pv = viejo.parametros();
        fallos += chequear("un mundo anterior a estos ajustes conserva sus respiraderos: separación 48, mezcla 100 % y radio 6 a 14",
            pv.separacionRespiraderos == 48 && pv.mezclaRespiraderos == 1f && pv.radioMezclaMin == 6 && pv.radioMezclaMax == 14);

        final PerfilMundo nuevo = new PerfilMundo();
        nuevo.sellarGeneracion(7);
        final ParametrosGeneracion pn = PerfilMundo.desde(archivoDe(nuevo)).parametros();
        fallos += chequear("un mundo nuevo guarda separación 120, mezcla 50 % y radio 4 a 12, y sobrevive a guardarlo y leerlo",
            pn.separacionRespiraderos == 120 && pn.mezclaRespiraderos == 0.5f && pn.radioMezclaMin == 4 && pn.radioMezclaMax == 12);

        final PerfilMundo fuera = new PerfilMundo();
        fuera.fijar(ParametrosGeneracion.ID_RESPIRADEROS_SEPARACION, 9999);
        fuera.fijar(ParametrosGeneracion.ID_RESPIRADEROS_MEZCLA, -1);
        fuera.fijar(ParametrosGeneracion.ID_RESPIRADEROS_RADIO_MIN, 20);
        fuera.fijar(ParametrosGeneracion.ID_RESPIRADEROS_RADIO_MAX, 2);
        final ParametrosGeneracion pf = fuera.parametros();
        fallos += chequear("valores fuera de rango se recortan (separación 400, mezcla 0) y un radio mínimo mayor que el máximo se corrige",
            pf.separacionRespiraderos == 400 && pf.mezclaRespiraderos == 0f && pf.radioMezclaMin <= pf.radioMezclaMax);

        Nucleo.alReiniciarMundo(pn);
        final int distNueva = new FenomenosBase.Respiraderos().distanciaMinima(), celdaNueva = new FenomenosBase.Respiraderos().celda();
        Nucleo.alReiniciarMundo(pv);
        final int distVieja = new FenomenosBase.Respiraderos().distanciaMinima(), celdaVieja = new FenomenosBase.Respiraderos().celda();
        fallos += chequear("el generador de respiraderos usa la separación del mundo: 120 en uno nuevo (retícula >= 120) y 48 con retícula 96 en uno anterior",
            distNueva == 120 && celdaNueva >= 120 && distVieja == 48 && celdaVieja == 96);
        final PerfilMundo sinPozos = new PerfilMundo();
        sinPozos.sellarGeneracion(7);
        final boolean nuevoSinPozos = !sinPozos.parametros().pozosSpawn && !PerfilMundo.desde(archivoDe(sinPozos)).parametros().pozosSpawn;
        final boolean antiguoConPozos = viejo.parametros().pozosSpawn;
        fallos += chequear("los mundos anteriores conservan sus pozos del spawn y los mundos nuevos no los generan (el ajuste se guarda y se lee)", nuevoSinPozos && antiguoConPozos);
        Nucleo.alReiniciarMundo(null);
        return fallos;
    }

    static int comprobar() throws Exception{
        final List<int[]> es = entradas();
        int fallos = 0;

        olvidarCaches();
        long t0 = System.nanoTime();
        final long[] a = calcularDirecto(es);
        final long msA = (System.nanoTime() - t0) / 1_000_000L;
        olvidarCaches();
        final long[] b = calcularDirecto(es);
        if(Arrays.equals(a, b)){
            System.out.println("[Corpus] reproducible: dos pasadas en frío dan lo mismo (total=" + hex(total(a)) + ", " + msA + " ms).");
        }else{
            fallos++;
            System.out.println("[Corpus] FALLO: dos pasadas seguidas dan resultados distintos (el terreno no es una función pura de la semilla).");
        }

        olvidarCaches();
        final long[] nu = calcularNucleo(es);
        if(Arrays.equals(a, nu)){
            System.out.println("[Corpus] el terreno servido por el núcleo es idéntico a la ruta directa (" + es.size() + " chunks).");
        }else{
            fallos++;
            System.out.println("[Corpus] FALLO: el terreno servido por el núcleo difiere de la ruta directa.");
        }

        // fase 3: independencia de las etapas
        olvidarCaches();
        fallos += comprobarSuelo(es);
        olvidarCaches();
        fallos += comprobarSinDecoracion(es);
        fallos += comprobarSinCadaEtapa(es, a);
        olvidarCaches();
        fallos += comprobarAjustes(es, a);
        fallos += comprobarResolucion();
        fallos += comprobarAreas();
        fallos += comprobarApi();
        fallos += comprobarEspaciado();   // antes de comprobarPresupuestos: ahí se abre un mundo y los ajustes de generación ya no se pueden registrar
        fallos += comprobarPresupuestos(es, a);
        fallos += comprobarVisual(es, a);
        fallos += comprobarVisual10();

        for(int n : new int[]{1, 2, 4}){
            olvidarCaches();
            t0 = System.nanoTime();
            final long[] c = calcularConHilos(es, n);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            if(Arrays.equals(a, c)){
                System.out.println("[Corpus] equivalencia con " + n + (n == 1 ? " hilo (ruta de un solo hilo)" : " hilos") + ": idéntico (" + ms + " ms). " + Hilos.resumen());
            }else{
                fallos++;
                int d = 0;
                for(int i = 0; i < a.length && d < 5; i++){
                    if(a[i] != c[i]){
                        d++;
                        int[] e = es.get(i);
                        System.out.println("[Corpus]   difiere en " + e[0] + " " + e[1] + " " + e[2] + " " + e[3] + ": directo " + hex(a[i]) + ", con " + n + " hilos " + hex(c[i]));
                    }
                }
                System.out.println("[Corpus] FALLO: con " + n + " hilos el resultado no es idéntico.");
            }
        }
        System.setProperty(Hilos.PROP_HILOS, "0");
        Hilos.configurar();
        System.out.println("[Corpus] " + Nucleo.resumen());
        System.out.println(fallos == 0 ? "[Corpus] comprobación completa: OK" : "[Corpus] comprobación completa: " + fallos + " fallo(s)");
        return fallos == 0 ? 0 : 1;
    }

    /** Línea base: coste de muestrear un chunk (ms), tras una pasada de calentamiento. Es el coste del terreno base, sin hooks de la API. */
    static int base(String archivo) throws Exception{
        final List<int[]> es = entradas();
        olvidarCaches();
        calcularDirecto(es);   // calentamiento (JIT) que no cuenta
        final int n = es.size();
        final long[] ns = new long[n];
        olvidarCaches();
        for(int i = 0; i < n; i++){
            int[] e = es.get(i);
            long t0 = System.nanoTime();
            hashChunk(e[0], e[1], e[2], e[3]);
            ns[i] = System.nanoTime() - t0;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("# linea base del terreno base, ms por chunk (JVM de CI; sirve para comparar entre versiones, no como valor absoluto del móvil)\n");
        sb.append("# nucleos=").append(Hilos.nucleos()).append(" gen=").append(MundoInfinitoMod.GEN_ACTUAL).append('\n');
        sb.append("# dim n media mediana p95 max\n");
        for(int d : DIMS) sb.append(fila("dim " + d, ns, es, d, Integer.MIN_VALUE));
        final long[] nsN = new long[n];
        olvidarCaches();
        for(int i = 0; i < n; i++){
            int[] e = es.get(i);
            long t0 = System.nanoTime();
            hashChunkNucleo(e[0], e[1], e[2], e[3]);
            nsN[i] = System.nanoTime() - t0;
        }
        sb.append("# por el núcleo (etapas): debe quedar cerca de la ruta directa\n");
        for(int d : DIMS) sb.append(fila("nucleo dim " + d, nsN, es, d, Integer.MIN_VALUE));
        sb.append("# por semilla\n");
        for(int s : SEMILLAS) for(int d : DIMS) sb.append(fila("semilla " + s + " dim " + d, ns, es, d, s));
        final String texto = sb.toString();
        System.out.print(texto);
        final PrintWriter w = new PrintWriter(new FileWriter(archivo));
        try{ w.print(texto); }finally{ w.close(); }
        return 0;
    }

    private static String fila(String nombre, long[] ns, List<int[]> es, int dim, int semilla){
        final List<Long> l = new ArrayList<Long>();
        for(int i = 0; i < ns.length; i++){
            int[] e = es.get(i);
            if(e[1] == dim && (semilla == Integer.MIN_VALUE || e[0] == semilla)) l.add(Long.valueOf(ns[i]));
        }
        final long[] v = new long[l.size()];
        long suma = 0L;
        for(int i = 0; i < v.length; i++){
            v[i] = l.get(i).longValue();
            suma += v[i];
        }
        Arrays.sort(v);
        final int c = v.length;
        if(c == 0) return nombre + " n=0\n";
        return nombre + " n=" + c + " media=" + ms(suma / c) + " mediana=" + ms(v[c / 2]) + " p95=" + ms(v[Math.min(c - 1, (int)(c * 0.95))]) + " max=" + ms(v[c - 1]) + "\n";
    }

    private static String ms(long ns){
        final long d = ns / 10_000L;   // centésimas de ms
        return (d / 100) + "." + (d % 100 < 10 ? "0" : "") + (d % 100);
    }

    /**
     * Escalado de la generación con hilos (fase 8): cuánto tarda el corpus entero (terreno base por el núcleo, un chunk por tarea, igual que reparten
     * los hilos de generación del juego) con 1, 2, 4 y, si hay más núcleos, todos hasta 8. Antes de medir se calienta (JIT) y de cada cifra se toma la
     * mejor de 3 pasadas. Exige que TODOS los resultados sean idénticos al de un hilo; la aceleración solo se informa, nunca hace fallar: depende del
     * equipo (núcleos compartidos o limitados). Si no hay aceleración, el veredicto lo dice y no se fuerza el paralelismo.
     */
    static int escala(String archivo) throws Exception{
        final List<int[]> es = entradas();
        final int nuc = Hilos.nucleos();
        final List<Integer> hs = new ArrayList<Integer>();
        hs.add(1);
        if(nuc >= 2) hs.add(2);
        if(nuc >= 4) hs.add(4);
        if(nuc > 4) hs.add(Math.min(nuc, Hilos.MAX_HILOS));
        final ParametrosGeneracion par = ParametrosGeneracion.DEFECTO;

        olvidarCaches();
        final long[] ref = calcularNucleo(es);
        calcularConHilos(es, 1, par);                       // calentamiento
        calcularConHilos(es, hs.get(hs.size() - 1), par);

        final StringBuilder sb = new StringBuilder();
        sb.append("# escalado de la generacion por chunk: terreno base por el nucleo, ").append(es.size()).append(" chunks, un chunk por tarea, mejor de 3 pasadas tras calentar\n");
        sb.append("# nucleos=").append(nuc).append(" gen=").append(MundoInfinitoMod.GEN_ACTUAL).append('\n');
        boolean identico = true;
        long ns1 = 0L;
        double mejorAcel = 1.0;
        for(int h : hs){
            long mejor = Long.MAX_VALUE;
            for(int r = 0; r < 3; r++){
                olvidarCaches();
                final long t0 = System.nanoTime();
                final long[] res = calcularConHilos(es, h, par);
                mejor = Math.min(mejor, System.nanoTime() - t0);
                if(!Arrays.equals(res, ref)) identico = false;
            }
            if(h == 1) ns1 = mejor;
            final double acel = ns1 / (double)mejor;
            if(h > 1) mejorAcel = Math.max(mejorAcel, acel);
            final double ms = mejor / 1e6, porSeg = es.size() * 1e9 / mejor;
            sb.append("hilos=").append(h).append(" ms=").append(Math.round(ms)).append(" chunks/s=").append(Math.round(porSeg)).append(" ms/chunk=").append(Math.round(ms / es.size() * 100) / 100.0)
                .append(" aceleracion=").append(Math.round(acel * 100) / 100.0).append('\n');
        }
        System.setProperty(Hilos.PROP_HILOS, "0");
        Hilos.configurar();
        sb.append("# resultados identicos con todos los hilos: ").append(identico ? "si" : "NO").append('\n');
        if(nuc < 2) sb.append("# veredicto: un solo nucleo, no hay nada que comparar\n");
        else if(mejorAcel >= 1.2) sb.append("# veredicto: cumple, con varios hilos la generacion por chunk es mas rapida que con uno (x").append(Math.round(mejorAcel * 100) / 100.0).append(")\n");
        else sb.append("# veredicto: no cumple en este equipo (x").append(Math.round(mejorAcel * 100) / 100.0)
            .append("): no se fuerza el paralelismo; ver docs/HILOS_REVISION.md (nucleos compartidos o limitados del equipo, coste de reparto frente al de un chunk)\n");
        final String texto = sb.toString();
        System.out.print(texto);
        final PrintWriter w = new PrintWriter(new FileWriter(archivo));
        try{ w.print(texto); }finally{ w.close(); }
        return identico ? 0 : 1;
    }

    public static void main(String[] args){
        int rc = 2;
        try{
            final String modo = args.length > 0 ? args[0] : "comprobar";
            if(modo.equals("congelar") && args.length > 1) rc = congelar(args[1]);
            else if(modo.equals("verificar") && args.length > 1) rc = verificar(args[1]);
            else if(modo.equals("comprobar")) rc = comprobar();
            else if(modo.equals("base") && args.length > 1) rc = base(args[1]);
            else if(modo.equals("escala") && args.length > 1) rc = escala(args[1]);
            else System.out.println("Uso: Corpus congelar|verificar|base|escala ARCHIVO   o   Corpus comprobar");
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);   // los hilos del pool son daemon, pero así el código de salida es siempre el de la comprobación
    }
}
