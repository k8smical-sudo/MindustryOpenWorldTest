package mundoinfinito;

/**
 * PRUEBAS DE MEMORIA DE NIEBLA (plan v9). Sin el juego: políticas de persistencia, hooks de guardado/restauración, orden y aislamiento de fallos.
 * No se prueba aquí el enganche real con {@code Vars.fogControl} (eso es {@code NieblaMundo}, que necesita el motor): queda para P2 (verificación
 * en dispositivo). Salida 0 si todo pasa; 1 si algo no pasa; 3 si hay un error.
 */
final class PruebasNiebla{
    private static int fallos;

    private PruebasNiebla(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaValorPorDefecto();
            pruebaPolitica();
            pruebaOrdenYAislamiento();
            pruebaHooks();
            pruebaIds();
            System.out.println(fallos == 0 ? "[Niebla] OK: todas las pruebas pasan." : "[Niebla] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }finally{
            ApiNiebla.reiniciar();
        }
        System.exit(rc);
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Niebla] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }

    private static void pruebaValorPorDefecto(){
        ApiNiebla.reiniciar();
        fallos += chequear("sin ninguna política, gana el valor del ajuste de mundo (true)", ApiNiebla.persiste("m1", 0, true));
        fallos += chequear("sin ninguna política, gana el valor del ajuste de mundo (false)", !ApiNiebla.persiste("m1", 0, false));
    }

    private static void pruebaPolitica(){
        ApiNiebla.reiniciar();
        fallos += chequear("se registra una política válida", ApiNiebla.registrarPolitica("addon:politica", 0, (m, d) -> Boolean.FALSE));
        fallos += chequear("una política decide por encima del ajuste de mundo", !ApiNiebla.persiste("m1", 0, true));
        fallos += chequear("un id repetido no se registra dos veces", !ApiNiebla.registrarPolitica("addon:politica", 0, (m, d) -> Boolean.TRUE));
        fallos += chequear("se puede quitar una política", ApiNiebla.quitarPolitica("addon:politica"));
        fallos += chequear("tras quitarla, vuelve a ganar el ajuste de mundo", ApiNiebla.persiste("m1", 0, true));

        ApiNiebla.registrarPolitica("addon:indecisa", 0, (m, d) -> null);
        final boolean indecisaOk = ApiNiebla.persiste("m1", 0, true) && !ApiNiebla.persiste("m1", 0, false);
        ApiNiebla.quitarPolitica("addon:indecisa");
        fallos += chequear("una política que no opina (null) deja decidir al ajuste de mundo", indecisaOk);
    }

    private static void pruebaOrdenYAislamiento(){
        ApiNiebla.reiniciar();
        ApiNiebla.registrarPolitica("addon:segunda", 10, (m, d) -> Boolean.TRUE);
        ApiNiebla.registrarPolitica("addon:primera", 0, (m, d) -> Boolean.FALSE);
        fallos += chequear("gana la política de menor prioridad, no la de registro más reciente", !ApiNiebla.persiste("m1", 0, true));

        ApiNiebla.reiniciar();
        ApiNiebla.registrarPolitica("addon:rompe", 0, (m, d) -> { throw new RuntimeException("boom"); });
        ApiNiebla.registrarPolitica("addon:respaldo", 10, (m, d) -> Boolean.FALSE);
        fallos += chequear("una política que lanza una excepción se desactiva y se sigue preguntando a las demás", !ApiNiebla.persiste("m1", 0, true));
        fallos += chequear("una vez desactivada, no vuelve a decidir (sigue ganando el respaldo)", !ApiNiebla.persiste("m1", 0, true));
    }

    private static void pruebaHooks(){
        ApiNiebla.reiniciar();
        final long[] bits = new long[16];
        ApiNiebla.ejecutarAlGuardar("m1", 0, 0, 0, bits);
        ApiNiebla.ejecutarAlRestaurar("m1", 0, 0, 0, bits);
        fallos += chequear("sin hooks, ejecutar no cambia nada y no falla", true);

        final int[] contador = {0};
        final String[] mundoVisto = {null};
        final int[] dimVista = {-1}, cxVisto = {-1}, cyVisto = {-1};
        ApiNiebla.suscribirGuardado("addon:marca", 0, (m, d, cx, cy, b) -> {
            contador[0]++;
            mundoVisto[0] = m;
            dimVista[0] = d;
            cxVisto[0] = cx;
            cyVisto[0] = cy;
            b[0] = 0xFF;
        });
        final long[] b1 = new long[16];
        ApiNiebla.ejecutarAlGuardar("m2", 1, 3, 4, b1);
        fallos += chequear("un hook de guardado se llama con el mundo, la dimensión y el chunk correctos, y puede tocar los bits",
            contador[0] == 1 && "m2".equals(mundoVisto[0]) && dimVista[0] == 1 && cxVisto[0] == 3 && cyVisto[0] == 4 && b1[0] == 0xFF);
        ApiNiebla.quitarSuscripcion("addon:marca");

        final int[] g = {0}, r = {0};
        ApiNiebla.suscribir("addon:separado", 0, (m, d, cx, cy, b) -> g[0]++, (m, d, cx, cy, b) -> r[0]++);
        ApiNiebla.ejecutarAlGuardar("m1", 0, 0, 0, bits);
        final boolean soloGuardado = g[0] == 1 && r[0] == 0;
        ApiNiebla.ejecutarAlRestaurar("m1", 0, 0, 0, bits);
        final boolean soloRestaurar = g[0] == 1 && r[0] == 1;
        fallos += chequear("un hook de guardado no se llama al restaurar, y viceversa", soloGuardado && soloRestaurar);
        ApiNiebla.quitarSuscripcion("addon:separado");

        final int[] n = {0};
        ApiNiebla.suscribirGuardado("addon:rompe", 0, (m, d, cx, cy, b) -> { throw new RuntimeException("boom"); });
        ApiNiebla.suscribirGuardado("addon:bueno", 10, (m, d, cx, cy, b) -> n[0]++);
        ApiNiebla.ejecutarAlGuardar("m1", 0, 0, 0, bits);
        ApiNiebla.ejecutarAlGuardar("m1", 0, 0, 0, bits);
        fallos += chequear("un hook que falla se desactiva y no bloquea a los demás", n[0] == 2);
        ApiNiebla.quitarSuscripcion("addon:rompe");
        ApiNiebla.quitarSuscripcion("addon:bueno");
    }

    private static void pruebaIds(){
        ApiNiebla.reiniciar();
        fallos += chequear("un id sin ':' no se registra", !ApiNiebla.registrarPolitica("sindospuntos", 0, (m, d) -> null));
        fallos += chequear("un id con espacio no se registra", !ApiNiebla.registrarPolitica("addon: con espacio", 0, (m, d) -> null));
        fallos += chequear("un id nulo, o una política nula, no se registran",
            !ApiNiebla.registrarPolitica(null, 0, (m, d) -> null) && !ApiNiebla.registrarPolitica("addon:x", 0, null));
        fallos += chequear("suscribir sin ningún hook (los dos null) no se registra", !ApiNiebla.suscribir("addon:vacio", 0, null, null));
        ApiNiebla.reiniciar();
    }
}
