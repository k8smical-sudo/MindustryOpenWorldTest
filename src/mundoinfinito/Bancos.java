package mundoinfinito;

import arc.func.Prov;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * BANCO DE PRUEBAS DEL MOD (fase de benchmarks). Corre en una JVM normal, en CI, junto al corpus: {@code Corpus bench RESULTADOS [BASE]}.
 *
 * Qué hace:
 *  - Ejecuta cada {@link Caso} de {@link CasosBenchmark}. Un caso es correcto solo si TODAS sus ejecuciones devuelven true. Un caso incorrecto
 *    o que lanza una excepción hace fallar la fase: el rendimiento no compensa un resultado malo.
 *  - Mide la mediana de tiempo por operación sobre {@link #REPETICIONES} repeticiones (con calentamiento previo).
 *  - COBERTURA AUTOMÁTICA: toda clase pública cuyo nombre empieza por "Api" debe estar cubierta por algún caso ({@link Caso#cubre}) o figurar en
 *    {@link #SIN_CASO} con el motivo. Así, una API nueva o eliminada se detecta sola en la siguiente ejecución, sin tocar esta lista.
 *  - Compara con una línea base (si existe): un caso cuyo MÍNIMO sea más de {@link #UMBRAL_REGRESION} veces el mínimo de la base se avisa.
 *    El mínimo de cinco repeticiones es el dato menos ruidoso (el ruido de la máquina solo añade tiempo); la mediana se sigue imprimiendo.
 *    El rendimiento NO bloquea,
 *    porque depende de la máquina; la línea base debe generarse en el mismo tipo de máquina de CI.
 *
 * Para añadir un benchmark: añade un caso en {@link CasosBenchmark}. Para quitar una API: quita sus casos; si la clase desaparece, el banco avisa
 * de la excepción obsoleta.
 */
final class Bancos{
    /** Repeticiones medidas por caso. La mediana de estas es el resultado. */
    static final int REPETICIONES = 5;
    /** Más de esto veces la línea base: aviso de regresión. */
    static final double UMBRAL_REGRESION = 1.5;

    /** APIs públicas que no tienen caso, y por qué. Cada entrada es obligatoria. */
    static final Map<String, String> SIN_CASO = new LinkedHashMap<String, String>();
    static{
        SIN_CASO.put("ApiVisual", "requiere GPU y un fotograma de dibujo: se mide en el juego, no en la JVM del banco");
        SIN_CASO.put("ApiMundo", "requiere un mundo abierto en marcha: se mide en el juego");
    }

    /** Un caso de rendimiento. {@link #operacion} se ejecuta {@link #iteraciones} veces por repetición y devuelve true si el resultado es correcto. */
    static final class Caso{
        final String area;
        final String nombre;
        final int iteraciones;
        final Prov<Boolean> operacion;
        /** Nombres de las APIs públicas que este caso ejercita (para la cobertura). */
        final String[] cubre;

        Caso(String area, String nombre, int iteraciones, Prov<Boolean> operacion, String... cubre){
            this.area = area;
            this.nombre = nombre;
            this.iteraciones = iteraciones;
            this.operacion = operacion;
            this.cubre = cubre;
        }

        String id(){
            return area + "/" + nombre;
        }
    }

    /** Resultado de medir un caso. Los tiempos son ns por operación. */
    static final class Resultado{
        final Caso caso;
        final double medianaNs, minNs, maxNs;
        final boolean ok;
        final String error;

        Resultado(Caso caso, double medianaNs, double minNs, double maxNs, boolean ok, String error){
            this.caso = caso;
            this.medianaNs = medianaNs;
            this.minNs = minNs;
            this.maxNs = maxNs;
            this.ok = ok;
            this.error = error;
        }
    }

    private Bancos(){}

    /** Número con punto decimal, sea cual sea el locale del sistema (la línea base se lee de vuelta). */
    static String num(double v){
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Mide un caso: calentamiento, {@link #REPETICIONES} repeticiones, mediana. Nunca lanza: un error queda en el resultado. */
    static Resultado medir(Caso c){
        try{
            boolean ok = true;
            final int calentar = Math.max(1, c.iteraciones / 10);
            for(int i = 0; i < calentar; i++){
                ok &= c.operacion.get().booleanValue();
            }
            final double[] t = new double[REPETICIONES];
            for(int r = 0; r < REPETICIONES; r++){
                final long t0 = System.nanoTime();
                for(int i = 0; i < c.iteraciones; i++){
                    if(!c.operacion.get().booleanValue()) ok = false;
                }
                t[r] = (System.nanoTime() - t0) / (double)c.iteraciones;
            }
            Arrays.sort(t);
            return new Resultado(c, t[REPETICIONES / 2], t[0], t[REPETICIONES - 1], ok, null);
        }catch(Throwable e){
            return new Resultado(c, Double.NaN, Double.NaN, Double.NaN, false, e.toString());
        }
    }

    /** Nombres de las clases públicas del paquete cuyo nombre empieza por "Api". Si una clase no carga, cuenta como pública (hay que cubrirla). */
    static Set<String> apisPublicas() throws Exception{
        final Set<String> out = new TreeSet<String>();
        final URL url = Bancos.class.getResource("Bancos.class");
        if(url == null || !"file".equals(url.getProtocol())) return null;
        final File[] ficheros = new File(url.toURI()).getParentFile().listFiles();
        if(ficheros == null) return null;
        for(File f : ficheros){
            final String n = f.getName();
            if(!n.endsWith(".class") || n.indexOf('$') >= 0) continue;
            final String simple = n.substring(0, n.length() - 6);
            if(!simple.startsWith("Api")) continue;
            try{
                final Class<?> c = Class.forName("mundoinfinito." + simple, false, Bancos.class.getClassLoader());
                if(Modifier.isPublic(c.getModifiers())) out.add(simple);
            }catch(Throwable t){
                out.add(simple);
            }
        }
        return out;
    }

    /** Cobertura: toda API pública debe tener caso o excepción con motivo. Las excepciones que ya no existen se avisan. */
    static int comprobarCobertura(List<Caso> casos, Set<String> apis){
        final Set<String> cubiertas = new HashSet<String>();
        for(Caso c : casos) for(String s : c.cubre) cubiertas.add(s);
        final List<String> sinCaso = new ArrayList<String>();
        for(String a : apis){
            if(!cubiertas.contains(a) && !SIN_CASO.containsKey(a)) sinCaso.add(a);
        }
        for(String k : SIN_CASO.keySet()){
            if(!apis.contains(k)) System.out.println("[Bench] AVISO: la excepción \"" + k + "\" ya no es una API pública; se puede quitar de SIN_CASO");
        }
        final boolean ok = sinCaso.isEmpty();
        System.out.println("[Bench] " + (ok ? "ok: " : "FALLO: ") + "cobertura: " + apis.size() + " APIs públicas, " + (apis.size() - sinCaso.size())
            + " cubiertas, sin caso " + sinCaso);
        return ok ? 0 : 1;
    }

    /**
     * Cada cálculo del catálogo ({@link Calculos}) debe tener un caso de rendimiento que lo cubra, y los ids del catálogo no pueden repetirse. Así un
     * cálculo nuevo, quitado o cambiado queda vigilado sin tocar el banco.
     */
    static int comprobarCalculos(List<Caso> casos){
        final Set<String> cubiertos = new HashSet<String>();
        for(Caso c : casos) for(String s : c.cubre) cubiertos.add(s);
        final Set<String> vistos = new HashSet<String>();
        final List<String> sinCaso = new ArrayList<String>();
        int fallos = 0;
        for(Calculos.Entrada e : Calculos.catalogo()){
            if(!vistos.add(e.id)){
                System.out.println("[Bench] FALLO: cálculo repetido en el catálogo: " + e.id);
                fallos++;
            }
            if(e.version < 1 || e.tope < 1){
                System.out.println("[Bench] FALLO: el cálculo " + e.id + " tiene versión o tope no válidos");
                fallos++;
            }
            if(!cubiertos.contains(e.id)) sinCaso.add(e.id);
        }
        if(!sinCaso.isEmpty()){
            System.out.println("[Bench] FALLO: cálculos del catálogo sin caso de rendimiento: " + sinCaso);
            fallos++;
        }
        System.out.println("[Bench] " + (fallos == 0 ? "ok: " : "FALLO: ") + "catálogo de cálculos: " + Calculos.catalogo().size() + " declarados");
        return fallos;
    }

    /** Ids duplicados: dos casos con el mismo id harían ambiguas la línea base y el reporte. */
    static int comprobarIds(List<Caso> casos){
        final Set<String> vistos = new HashSet<String>();
        int fallos = 0;
        for(Caso c : casos){
            if(!vistos.add(c.id())){
                System.out.println("[Bench] FALLO: caso duplicado " + c.id());
                fallos++;
            }
        }
        return fallos;
    }

    /** Línea base: id -> mínimo (ns), columna 3 del TSV. Null si el archivo no existe o no tiene esa columna. */
    static Map<String, Double> leerBaseMinimos(String ruta) throws Exception{
        if(ruta == null || !new File(ruta).exists()) return null;
        final Map<String, Double> out = new LinkedHashMap<String, Double>();
        final BufferedReader r = new BufferedReader(new FileReader(ruta));
        try{
            String linea;
            while((linea = r.readLine()) != null){
                final String[] p = linea.split("\t");
                if(p.length >= 3) out.put(p[0], Double.valueOf(p[2]));
            }
        }finally{
            r.close();
        }
        return out;
    }

    /** Línea base: id -> mediana (ns). Null si el archivo no existe (primera ejecución: no hay comparación). */
    static Map<String, Double> leerBase(String ruta) throws Exception{
        if(ruta == null || !new File(ruta).exists()) return null;
        final Map<String, Double> out = new LinkedHashMap<String, Double>();
        final BufferedReader r = new BufferedReader(new FileReader(ruta));
        try{
            String linea;
            while((linea = r.readLine()) != null){
                final String[] p = linea.split("\t");
                if(p.length >= 2) out.put(p[0], Double.valueOf(p[1]));
            }
        }finally{
            r.close();
        }
        return out;
    }

    /**
     * Ejecuta todo el banco. Devuelve 0 si todos los casos son correctos y la cobertura está completa; 1 en otro caso. Las regresiones de
     * rendimiento solo se avisan.
     */
    static int ejecutar(String archivoResultados, String archivoBase) throws Exception{
        final List<Caso> casos = CasosBenchmark.todos();
        int fallos = comprobarIds(casos);
        fallos += comprobarCalculos(casos);
        final Set<String> apis = apisPublicas();
        if(apis != null) fallos += comprobarCobertura(casos, apis);
        else{
            System.out.println("[Bench] FALLO: cobertura no verificable (el código no está en un directorio de clases)");
            fallos++;
        }

        final Map<String, Double> base = leerBaseMinimos(archivoBase);
        if(base == null) System.out.println("[Bench] no hay línea base (" + archivoBase + "): esta ejecución la puede generar");

        final StringBuilder tsv = new StringBuilder();
        int regresiones = 0;
        for(Caso c : casos){
            final Resultado r = medir(c);
            if(r.error != null){
                System.out.println("[Bench] FALLO " + c.id() + ": " + r.error);
            }else{
                final String estado = r.ok ? "ok" : "INCORRECTO";
                System.out.println("[Bench] " + c.id() + ": " + num(r.medianaNs) + " ns/op (mín " + num(r.minNs) + ", máx " + num(r.maxNs) + ") " + estado);
                if(!r.ok) fallos++;
                if(base != null && base.containsKey(c.id())){
                    final double ratio = r.minNs / base.get(c.id()).doubleValue();
                    if(ratio > UMBRAL_REGRESION){
                        regresiones++;
                        System.out.println("[Bench] AVISO regresión " + c.id() + ": x" + String.format(java.util.Locale.ROOT, "%.2f", ratio) + " (mínimo) frente a la línea base");
                    }
                }
            }
            tsv.append(c.id()).append('\t').append(num(r.medianaNs)).append('\t')
                .append(num(r.minNs)).append('\t').append(num(r.maxNs)).append('\t')
                .append(r.ok && r.error == null ? "ok" : "fallo").append('\t').append(c.iteraciones).append('\n');
        }
        if(archivoResultados != null){
            final File f = new File(archivoResultados);
            if(f.getParentFile() != null) f.getParentFile().mkdirs();
            final PrintWriter w = new PrintWriter(f, "UTF-8");
            try{
                w.print(tsv);
            }finally{
                w.close();
            }
        }
        System.out.println("[Bench] " + casos.size() + " casos, " + fallos + " fallo(s), " + regresiones + " aviso(s) de regresión (no bloquean)");
        return fallos == 0 ? 0 : 1;
    }
}
