package mundoinfinito;

import java.util.List;

/**
 * PRUEBAS DE GALAXIA, CONTENIDO EN CAMPAÑA, MIGRACIÓN A MUNDOS Y RESPIRADEROS POR FICHA (plan v9). Sin el juego. Salida 0 si todo pasa; 1 si algo no
 * pasa; 3 si hay un error.
 */
final class PruebasGalaxia{
    private static int fallos;

    private PruebasGalaxia(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaGrupos();
            pruebaMaximoYReglas();
            pruebaPolitica();
            pruebaGuardado();
            pruebaAsignacionPorGrupo();
            pruebaContenido();
            pruebaMigracion();
            pruebaFicha();
            System.out.println(fallos == 0 ? "[Galaxia] OK: todas las pruebas pasan." : "[Galaxia] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }finally{
            ApiGalaxia.reiniciar();
            ApiContenido.reiniciar();
        }
        System.exit(rc);
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Galaxia] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }

    private static void pruebaGrupos(){
        ApiGalaxia.reiniciar();
        fallos += chequear("siempre existe el grupo inicial, en el origen", ApiGalaxia.grupos().size() == 1 && ApiGalaxia.buscar("grupo-1") != null
            && ApiGalaxia.buscar("grupo-1").x == 0.0 && ApiGalaxia.buscar("grupo-1").y == 0.0);
        final ApiGalaxia.Resultado r = ApiGalaxia.crearGrupo("  Cinturón   de | Orión ");
        fallos += chequear("crear un grupo limpia el nombre (espacios compactados, sin '|') y le da un id nuevo", r.ok && r.grupo.nombre().equals("Cinturón de Orión") && r.grupo.id.equals("grupo-2"));
        fallos += chequear("el grupo nuevo no se solapa con el inicial", Math.hypot(r.grupo.x, r.grupo.y) >= r.grupo.radio + ApiGalaxia.buscar("grupo-1").radio + Calibracion.GALAXIA_MARGEN - 1e-6);
        fallos += chequear("un nombre vacío no crea grupo", !ApiGalaxia.crearGrupo("   ").ok && !ApiGalaxia.crearGrupo(null).ok && ApiGalaxia.grupos().size() == 2);
        fallos += chequear("renombrar cambia el nombre y no el id", ApiGalaxia.renombrarGrupo("grupo-2", "Mi hogar") && ApiGalaxia.nombreDe("grupo-2").equals("Mi hogar") && ApiGalaxia.buscar("grupo-2") != null);
        fallos += chequear("renombrar con nombre vacío o grupo inexistente falla", !ApiGalaxia.renombrarGrupo("grupo-2", " ") && !ApiGalaxia.renombrarGrupo("nada", "X"));
        fallos += chequear("el nombre se corta a " + ApiGalaxia.NOMBRE_MAX + " caracteres", ApiGalaxia.limpiarNombre("a".repeat(100)).length() == ApiGalaxia.NOMBRE_MAX);
        fallos += chequear("el grupo inicial también se puede renombrar", ApiGalaxia.renombrarGrupo("grupo-1", "Casa") && ApiGalaxia.nombreDe("grupo-1").equals("Casa"));
    }

    private static void pruebaMaximoYReglas(){
        ApiGalaxia.reiniciar();
        fallos += chequear("el máximo por defecto sale de Calibracion", ApiGalaxia.maximoGrupos() == Calibracion.GALAXIA_MAX_GRUPOS);
        fallos += chequear("un máximo no válido se rechaza", !ApiGalaxia.fijarMaximoGrupos(0) && !ApiGalaxia.fijarMaximoGrupos(ApiGalaxia.MAXIMO_ABSOLUTO + 1));
        ApiGalaxia.fijarMaximoGrupos(3);
        ApiGalaxia.crearGrupo("A");
        ApiGalaxia.crearGrupo("B");
        final ApiGalaxia.Resultado extra = ApiGalaxia.crearGrupo("C");
        fallos += chequear("con el máximo en 3, el cuarto grupo no se crea y dice por qué", !extra.ok && extra.mensaje.contains("máximo") && ApiGalaxia.grupos().size() == 3);
        ApiGalaxia.fijarMaximoGrupos(30);
        fallos += chequear("subir el máximo permite crear más", ApiGalaxia.crearGrupo("C").ok);
        fallos += chequear("la distancia y el radio no válidos se rechazan", !ApiGalaxia.fijarDistanciaEntreGrupos(-1) && !ApiGalaxia.fijarDistanciaEntreGrupos(Double.NaN)
            && !ApiGalaxia.fijarRadioGrupo(0));
        boolean noSolapan = true;
        final List<ApiGalaxia.Grupo> gs = ApiGalaxia.grupos();
        for(int i = 0; i < gs.size(); i++) for(int j = i + 1; j < gs.size(); j++){
            noSolapan &= Math.hypot(gs.get(i).x - gs.get(j).x, gs.get(i).y - gs.get(j).y) >= gs.get(i).radio + gs.get(j).radio - 1e-6;
        }
        fallos += chequear("ningún par de grupos se solapa", noSolapan);
        ApiGalaxia.reiniciar();
        ApiGalaxia.fijarDistanciaEntreGrupos(1000.0);
        final ApiGalaxia.Grupo lejos = ApiGalaxia.crearGrupo("Lejos").grupo;
        fallos += chequear("una distancia mayor aleja el grupo nuevo", Math.hypot(lejos.x, lejos.y) >= 1000.0 - 1e-6);
    }

    private static void pruebaPolitica(){
        ApiGalaxia.reiniciar();
        fallos += chequear("una política válida se registra; un id sin formato o repetido, no",
            ApiGalaxia.proponerPolitica("addon:linea", 0, (ex, i, r, d) -> new double[]{i * 500.0, 0.0})
                && !ApiGalaxia.proponerPolitica("addon:linea", 0, (ex, i, r, d) -> null) && !ApiGalaxia.proponerPolitica("sinformato", 0, (ex, i, r, d) -> null));
        final ApiGalaxia.Grupo g = ApiGalaxia.crearGrupo("En línea").grupo;
        fallos += chequear("la política decide la posición del grupo nuevo", g.x == 500.0 && g.y == 0.0);
        ApiGalaxia.quitarPolitica("addon:linea");
        ApiGalaxia.proponerPolitica("addon:solapa", 0, (ex, i, r, d) -> new double[]{0.0, 0.0});
        final ApiGalaxia.Grupo h = ApiGalaxia.crearGrupo("Sin solape").grupo;
        fallos += chequear("una posición que se solapa se rechaza y decide la espiral por defecto", h != null && Math.hypot(h.x, h.y) > 0.0);
        ApiGalaxia.quitarPolitica("addon:solapa");
        ApiGalaxia.proponerPolitica("addon:rompe", 0, (ex, i, r, d) -> { throw new RuntimeException("boom"); });
        fallos += chequear("una política que falla se desactiva y no impide crear el grupo", ApiGalaxia.crearGrupo("Tras fallo").ok && ApiGalaxia.crearGrupo("Otro").ok);
    }

    private static void pruebaGuardado(){
        ApiGalaxia.reiniciar();
        ApiGalaxia.crearGrupo("Uno");
        ApiGalaxia.renombrarGrupo("grupo-1", "Casa");
        final String texto = ApiGalaxia.aTexto();
        ApiGalaxia.desdeTexto(texto);
        fallos += chequear("la galaxia se guarda y se lee con ida y vuelta idéntica", ApiGalaxia.aTexto().equals(texto) && ApiGalaxia.nombreDe("grupo-1").equals("Casa") && ApiGalaxia.grupos().size() == 2);
        ApiGalaxia.desdeTexto(null);
        fallos += chequear("sin archivo queda solo el grupo inicial", ApiGalaxia.grupos().size() == 1);
        ApiGalaxia.desdeTexto("grupo-1|Casa|0.0|0.0|60.0\nmal formada\ngrupo-7|Lejos|1.0|2.0|60.0\ngrupo-7|Repetido|3.0|4.0|60.0\ngrupo-8||0|0|60\n");
        fallos += chequear("las líneas mal formadas, repetidas o sin nombre se descartan y el resto se conserva", ApiGalaxia.grupos().size() == 2 && ApiGalaxia.buscar("grupo-7") != null);
        fallos += chequear("el siguiente grupo no repite un id ya usado", ApiGalaxia.crearGrupo("Nuevo").grupo.id.equals("grupo-8"));
    }

    private static void pruebaAsignacionPorGrupo(){
        fallos += chequear("el grupo inicial conserva los ids de siempre",
            AsignacionSistemas.idSistema("grupo-1", "serpulo").equals(AsignacionSistemas.idSistema("serpulo")) && AsignacionSistemas.claveOrbita("grupo-1", "serpulo").equals("serpulo"));
        fallos += chequear("otro grupo tiene su propio sistema y su propia órbita",
            !AsignacionSistemas.idSistema("grupo-2", "serpulo").equals(AsignacionSistemas.idSistema("serpulo")) && AsignacionSistemas.claveOrbita("grupo-2", "serpulo").equals("grupo-2/serpulo"));
        final EstadoSistemas global = new EstadoSistemas();
        AsignacionSistemas.asignar(global, "m1", "serpulo", 1, 20.0, ApiFisica.DIAMETRO_PLANETA, "grupo-1", 0.0, 0.0);
        AsignacionSistemas.asignar(global, "m2", "serpulo", 1, 20.0, ApiFisica.DIAMETRO_PLANETA, "grupo-2", 900.0, -300.0);
        final EstadoSistemas.SistemaSolar s2 = AsignacionSistemas.sistemaDe(global, "m2");
        fallos += chequear("un mundo de otro grupo cuelga de un sistema colocado en la posición de su grupo", s2 != null && s2.p0x == 900.0 && s2.p0y == -300.0
            && AsignacionSistemas.sistemaDe(global, "m1") != s2);
        fallos += chequear("las órbitas de los grupos no comparten huecos (cada uno empieza en su primer slot)",
            global.buscarCuerpo("m1").slot == 0 && global.buscarCuerpo("m2").slot == 0);
    }

    private static void pruebaContenido(){
        ApiContenido.reiniciar();
        fallos += chequear("por defecto el contenido del mod no se ve en campaña", !ApiContenido.visibleEnCampana("portal-viaje-1") && !ApiContenido.visibleEnCampana("booster-alcance"));
        ApiContenido.mostrarEnCampana("portal-viaje-1", true);
        fallos += chequear("un dev puede permitir un contenido concreto, sin tocar los demás", ApiContenido.visibleEnCampana("portal-viaje-1") && !ApiContenido.visibleEnCampana("booster-alcance"));
        ApiContenido.mostrarTodoEnCampana(true);
        ApiContenido.mostrarEnCampana("booster-alcance", false);
        fallos += chequear("permitir todo deja la excepción: lo prohibido por nombre sigue prohibido", ApiContenido.visibleEnCampana("radar-planetario-1") && !ApiContenido.visibleEnCampana("booster-alcance"));
        fallos += chequear("un nombre vacío o nulo no se acepta", !ApiContenido.mostrarEnCampana(null, true) && !ApiContenido.mostrarEnCampana("  ", true));
        ApiContenido.reiniciar();
        fallos += chequear("reiniciar vuelve al valor por defecto", !ApiContenido.visibleEnCampana("portal-viaje-1"));
    }

    private static void pruebaMigracion(){
        fallos += chequear("las carpetas de dimensión se reconocen: d0, d1, d16; otras no",
            MigracionMundos.terrenoDeCarpeta("d0") == 0 && MigracionMundos.terrenoDeCarpeta("d1") == 1 && MigracionMundos.terrenoDeCarpeta("d16") == 16
                && MigracionMundos.terrenoDeCarpeta("d") == -1 && MigracionMundos.terrenoDeCarpeta("d01") == -1 && MigracionMundos.terrenoDeCarpeta("regiones") == -1
                && MigracionMundos.terrenoDeCarpeta("dx") == -1 && MigracionMundos.terrenoDeCarpeta(null) == -1);
        fallos += chequear("el id del mundo que sale de una dimensión es estable", MigracionMundos.idDeDimension("abc", 1).equals("abc-d1")
            && MigracionMundos.idDeDimension("abc", 1).equals(MigracionMundos.idDeDimension("abc", 1)));
    }

    private static void pruebaFicha(){
        fallos += chequear("la ficha pequeña baja la probabilidad de un respiradero, la media la deja igual y la grande la sube",
            RespiraderosFicha.factor("pequeno") < 1f && RespiraderosFicha.factor("medio") == 1f && RespiraderosFicha.factor("grande") > 1f);
        fallos += chequear("sin dato o con una categoría desconocida, el factor es 1", RespiraderosFicha.factor("") == 1f && RespiraderosFicha.factor(null) == 1f && RespiraderosFicha.factor("x") == 1f);
    }
}
