package mundoinfinito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * PRUEBAS DE COMPATIBILIDAD DE LA API (plan v5, sección 10: C2 y C3). Sin el juego: usan inventarios escritos a mano en archivos temporales.
 * Cubren los casos del criterio: quitar falla sin subir el mayor; añadir pasa; subir el mayor permite quitar; cambiar una firma cuenta como quitar;
 * bajar el mayor falla; y C3 solo regenera la línea base al subir el mayor.
 * Salida 0 si todo pasa; 1 si algo no pasa.
 */
final class PruebasApi{
    private static int fallos;

    private PruebasApi(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            final Path dir = Files.createTempDirectory("pruebas-api");
            final Path base = dir.resolve("base.txt"), actual = dir.resolve("actual.txt");
            final String a = "metodo mundoinfinito.ApiPrueba.uno(java.lang.String) : boolean [public static]";
            final String b = "metodo mundoinfinito.ApiPrueba.dos(int) : void [public static]";
            final String c = "clase mundoinfinito.ApiPrueba [public final]";
            final String d = "metodo mundoinfinito.ApiPrueba.tres() : int [public static]";

            escribir(base, 1, c, a, b);

            // C2: mismo inventario -> pasa.
            escribir(actual, 1, c, a, b);
            fallos += chequear("C2: el mismo inventario pasa", InventarioApi.comparar(base.toString(), actual.toString()) == 0);

            // C2: añadir un método -> pasa (compatible, solo se informa).
            escribir(actual, 1, c, a, b, d);
            fallos += chequear("C2: añadir un método pasa", InventarioApi.comparar(base.toString(), actual.toString()) == 0);

            // C2: quitar un método sin subir el mayor -> falla.
            escribir(actual, 1, c, a);
            fallos += chequear("C2: quitar un método sin subir el mayor falla", InventarioApi.comparar(base.toString(), actual.toString()) == 1);

            // C2: cambiar una firma sin subir el mayor -> falla (cuenta como quitar).
            escribir(actual, 1, c, a, "metodo mundoinfinito.ApiPrueba.dos(long) : void [public static]");
            fallos += chequear("C2: cambiar una firma sin subir el mayor falla", InventarioApi.comparar(base.toString(), actual.toString()) == 1);

            // C2: quitar con el mayor subido -> pasa (cambio incompatible permitido).
            escribir(actual, 2, c, a);
            fallos += chequear("C2: quitar con la versión mayor subida pasa", InventarioApi.comparar(base.toString(), actual.toString()) == 0);

            // C2: bajar el mayor -> falla.
            escribir(base, 2, c, a, b);
            escribir(actual, 1, c, a, b);
            fallos += chequear("C2: bajar la versión mayor falla", InventarioApi.comparar(base.toString(), actual.toString()) == 1);

            // C3: regenerar sin subir el mayor -> se niega y no toca la base.
            escribir(base, 1, c, a, b);
            escribir(actual, 1, c, a);
            final String antes = leer(base);
            final boolean negada = InventarioApi.regenerar(base.toString(), actual.toString()) == 1;
            fallos += chequear("C3: regenerar en un cambio menor se niega y no cambia la línea base",
                negada && antes.equals(leer(base)));

            // C3: regenerar con el mayor subido -> escribe la línea base nueva.
            escribir(actual, 2, c, a);
            fallos += chequear("C3: regenerar con el mayor subido escribe la línea base nueva",
                InventarioApi.regenerar(base.toString(), actual.toString()) == 0 && leer(base).equals(leer(actual)));

            System.out.println(fallos == 0 ? "[API] OK: todas las pruebas de compatibilidad pasan." : "[API] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static void escribir(Path p, int mayor, String... piezas) throws Exception{
        final List<String> lineas = new java.util.ArrayList<String>();
        lineas.add(InventarioApi.PREFIJO + mayor);
        lineas.addAll(Arrays.asList(piezas));
        Files.write(p, lineas, StandardCharsets.UTF_8);
    }

    private static String leer(Path p) throws Exception{
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[API-prueba] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
