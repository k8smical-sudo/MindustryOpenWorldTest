package mundoinfinito;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * COMPATIBILIDAD DE LA API PÚBLICA (plan v5, sección 10). Herramienta de CI, no forma parte de la API.
 *
 * C1 (generar): inventario ordenado de las clases públicas: constructores, campos y métodos públicos, con tipos. No incluye nombres de
 *     parámetros (Java no los conserva sin -parameters y no cuentan como incompatibilidad). Dos ejecuciones dan el mismo texto.
 * C2 (comparar): compara con la línea base versionada en corpus/api-base.txt. Quitar una línea (método, campo, clase o firma cambiada)
 *     falla, salvo que la versión mayor de la API (ApiVersion.PUBLICA_MAYOR) haya subido. Añadir líneas es compatible y solo se informa.
 *     Sin línea base, no comprueba nada y avisa cómo crearla.
 * C3 (regenerar): sustituye la línea base por el inventario actual solo si la versión mayor ha subido.
 *
 * Uso: InventarioApi generar SALIDA | comparar BASE ACTUAL | regenerar BASE ACTUAL.   Códigos: 0 correcto, 1 incompatibilidad, 2 uso, 3 error.
 */
final class InventarioApi{
    static final String PREFIJO = "# api-inventario mayor=";

    private InventarioApi(){}

    public static void main(String[] args){
        int rc = 2;
        try{
            final String modo = args.length > 0 ? args[0] : "";
            if(modo.equals("generar") && args.length > 1) rc = generar(args[1]);
            else if(modo.equals("comparar") && args.length > 2) rc = comparar(args[1], args[2]);
            else if(modo.equals("regenerar") && args.length > 2) rc = regenerar(args[1], args[2]);
            else System.out.println("Uso: InventarioApi generar SALIDA | comparar BASE ACTUAL | regenerar BASE ACTUAL");
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    /** C1: inventario de la superficie pública, con la versión mayor en la primera línea. Las clases se cargan sin inicializarlas. */
    static List<String> inventario() throws Exception{
        final URL url = InventarioApi.class.getResource("InventarioApi.class");
        if(url == null || !"file".equals(url.getProtocol())) throw new IllegalStateException("el código no está en un directorio de clases");
        final File dir = new File(url.toURI()).getParentFile();
        final String[] nombres = dir.list();
        if(nombres == null) throw new IllegalStateException("no se puede listar " + dir);
        Arrays.sort(nombres);
        final List<String> cuerpo = new ArrayList<String>();
        for(String n : nombres){
            if(!n.endsWith(".class") || n.indexOf('$') >= 0) continue;
            final Class<?> c = Class.forName("mundoinfinito." + n.substring(0, n.length() - 6), false, InventarioApi.class.getClassLoader());
            if(Modifier.isPublic(c.getModifiers())) volcar(c, cuerpo);
        }
        Collections.sort(cuerpo);
        final List<String> out = new ArrayList<String>(cuerpo.size() + 1);
        out.add(PREFIJO + ApiVersion.PUBLICA_MAYOR);
        out.addAll(cuerpo);
        return out;
    }

    /** Cada línea se entiende sola (lleva el nombre de la clase), así que el orden y los anidados no se mezclan. */
    private static void volcar(Class<?> c, List<String> out){
        out.add("clase " + c.getName() + " [" + Modifier.toString(c.getModifiers()) + "]");
        for(Constructor<?> k : c.getDeclaredConstructors()){
            if(Modifier.isPublic(k.getModifiers())) out.add("constructor " + c.getName() + "(" + tipos(k.getParameterTypes()) + ")");
        }
        for(Field f : c.getDeclaredFields()){
            if(Modifier.isPublic(f.getModifiers())) out.add("campo " + c.getName() + "." + f.getName() + " : " + f.getType().getTypeName() + " [" + Modifier.toString(f.getModifiers()) + "]");
        }
        for(Method m : c.getDeclaredMethods()){
            if(Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge()){
                out.add("metodo " + c.getName() + "." + m.getName() + "(" + tipos(m.getParameterTypes()) + ") : " + m.getReturnType().getTypeName()
                    + " [" + Modifier.toString(m.getModifiers()) + "]");
            }
        }
        for(Class<?> n : c.getDeclaredClasses()){
            if(Modifier.isPublic(n.getModifiers())) volcar(n, out);
        }
    }

    private static String tipos(Class<?>[] ts){
        final StringBuilder sb = new StringBuilder();
        for(int i = 0; i < ts.length; i++){
            if(i > 0) sb.append(',');
            sb.append(ts[i].getTypeName());
        }
        return sb.toString();
    }

    static int generar(String salida) throws Exception{
        final List<String> out = inventario();
        final Path p = Paths.get(salida);
        if(p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, out, StandardCharsets.UTF_8);
        System.out.println("[API] inventario: " + (out.size() - 1) + " líneas, versión mayor " + ApiVersion.PUBLICA_MAYOR + " -> " + salida);
        return 0;
    }

    static int comparar(String base, String actual) throws IOException{
        final List<String> nueva = Files.readAllLines(Paths.get(actual), StandardCharsets.UTF_8);
        final int mayorNueva = mayorDe(nueva);
        final Path pb = Paths.get(base);
        if(!Files.exists(pb)){
            System.out.println("[API] Sin línea base (" + base + "): no hay comparación. Para activar C2, copia " + actual + " a " + base + " y haz commit.");
            return 0;
        }
        final List<String> vieja = Files.readAllLines(pb, StandardCharsets.UTF_8);
        final int mayorVieja = mayorDe(vieja);
        final Set<String> sv = new TreeSet<String>(cuerpoDe(vieja));
        final Set<String> sn = new TreeSet<String>(cuerpoDe(nueva));
        final List<String> quitadas = new ArrayList<String>();
        final List<String> anadidas = new ArrayList<String>();
        for(String s : sv) if(!sn.contains(s)) quitadas.add(s);
        for(String s : sn) if(!sv.contains(s)) anadidas.add(s);
        System.out.println("[API] versión mayor base " + mayorVieja + ", actual " + mayorNueva + ": " + quitadas.size() + " quitadas o con firma cambiada, "
            + anadidas.size() + " añadidas.");
        for(String s : quitadas) System.out.println("[API] quitada o cambiada: " + s);
        for(String s : anadidas) System.out.println("[API] añadida (compatible): " + s);

        if(mayorNueva < mayorVieja){
            System.out.println("[API] FALLO: la versión mayor ha bajado de " + mayorVieja + " a " + mayorNueva + ".");
            return 1;
        }
        if(quitadas.isEmpty()){
            System.out.println("[API] OK: ninguna pieza pública se ha quitado ni cambiado de firma.");
            return 0;
        }
        if(mayorNueva > mayorVieja){
            System.out.println("[API] OK: hay cambios incompatibles, permitidos porque la versión mayor subió de " + mayorVieja + " a " + mayorNueva
                + ". Regenera la línea base con regenerar (C3).");
            return 0;
        }
        System.out.println("[API] FALLO: se quitan o cambian piezas públicas sin subir la versión mayor (ApiVersion.PUBLICA_MAYOR). Restaura la firma o sube el mayor.");
        return 1;
    }

    /** C3: la línea base solo cambia si la versión mayor ha subido (o si no existe todavía). */
    static int regenerar(String base, String actual) throws IOException{
        final List<String> nueva = Files.readAllLines(Paths.get(actual), StandardCharsets.UTF_8);
        final int mayorNueva = mayorDe(nueva);
        final Path pb = Paths.get(base);
        if(Files.exists(pb)){
            final int mayorVieja = mayorDe(Files.readAllLines(pb, StandardCharsets.UTF_8));
            if(mayorNueva <= mayorVieja){
                System.out.println("[API] No se regenera: la versión mayor sigue en " + mayorVieja + ". La base solo cambia al subir el mayor.");
                return 1;
            }
        }
        Files.write(pb, nueva, StandardCharsets.UTF_8);
        System.out.println("[API] Línea base escrita en " + base + " (versión mayor " + mayorNueva + ").");
        return 0;
    }

    private static List<String> cuerpoDe(List<String> lineas){
        final List<String> out = new ArrayList<String>();
        for(String l : lineas) if(!l.isEmpty() && !l.startsWith("#")) out.add(l);
        return out;
    }

    private static int mayorDe(List<String> lineas){
        for(String l : lineas){
            if(l.startsWith(PREFIJO)) return Integer.parseInt(l.substring(PREFIJO.length()).trim());
        }
        throw new IllegalArgumentException("el inventario no tiene la cabecera de versión (" + PREFIJO + "N)");
    }
}
