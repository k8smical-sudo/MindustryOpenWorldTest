package mundoinfinito;

import arc.func.Prov;
import arc.struct.Seq;
import arc.util.serialization.Jval;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Fase 4: y el perfil de mundo (valores por defecto, mundos antiguos, recorte fuera de rango, persistencia y que el perfil por defecto no cambia el terreno).
 * No cubre los hooks de la API (fenómenos, mods): necesitan el contenido del juego y entran en fases posteriores.
 *
 * Uso:   java -cp build/classes/java/main:libs/MindustryX-Desktop.jar mundoinfinito.Corpus MODO [archivo]
 *   congelar archivo    escribe los hashes (se hace UNA vez por versión de generación y se guarda en el repo)
 *   verificar archivo   compara con lo congelado; sale con error si algo cambió
 *   comprobar           reproducibilidad (dos pasadas en frío) y equivalencia de hilos (1, 2 y 4 hilos contra la ruta directa)
 *   base archivo        línea base de coste por chunk (ms) por dimensión y por semilla
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
        System.setProperty(Hilos.PROP_HILOS, String.valueOf(n));
        Hilos.configurar();
        final Seq<Prov<Long>> tareas = new Seq<Prov<Long>>(es.size());
        for(final int[] e : es) tareas.add(() -> Long.valueOf(hashChunkNucleo(e[0], e[1], e[2], e[3])));   // por el núcleo: el camino real
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
            && x.estirarHumedadSerpulo == y.estirarHumedadSerpulo;
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
        nuevo.sellarGeneracion();
        final PerfilMundo nuevoLeido = PerfilMundo.desde(archivoDe(nuevo));
        fallos += chequear("un mundo nuevo guarda sus parámetros de generación y no cambian al guardar y leer",
            nuevo.tiene(ParametrosGeneracion.ID_ALTURA_SERPULO) && nuevo.congelado() && iguales(nuevo.parametros(), def) && iguales(nuevoLeido.parametros(), def));

        // el terreno con el perfil por defecto (vacío, sellado o leído) es idéntico a la ruta directa
        final ParametrosGeneracion fuerte = new ParametrosGeneracion(3.6f, def.estirarTemperaturaSerpulo, def.estirarHumedadSerpulo);
        int malosDefecto = 0, serpuloCambia = 0, erekirCambia = 0;
        for(int i = 0; i < es.size(); i++){
            final int[] e = es.get(i);
            if(hashChunkNucleoCon(nuevoLeido.parametros(), e[0], e[1], e[2], e[3]) != directo[i]) malosDefecto++;
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

    public static void main(String[] args){
        int rc = 2;
        try{
            final String modo = args.length > 0 ? args[0] : "comprobar";
            if(modo.equals("congelar") && args.length > 1) rc = congelar(args[1]);
            else if(modo.equals("verificar") && args.length > 1) rc = verificar(args[1]);
            else if(modo.equals("comprobar")) rc = comprobar();
            else if(modo.equals("base") && args.length > 1) rc = base(args[1]);
            else System.out.println("Uso: Corpus congelar|verificar|base ARCHIVO   o   Corpus comprobar");
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);   // los hilos del pool son daemon, pero así el código de salida es siempre el de la comprobación
    }
}
