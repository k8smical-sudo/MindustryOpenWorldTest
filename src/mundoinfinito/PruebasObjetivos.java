package mundoinfinito;

/**
 * PRUEBAS DE OBJETIVOS DE SECTOR (plan v9, P11). Corren en CI sin el juego: lógica pura, sin núcleo ni gamerule.
 * Salida 0 si todo pasa; 1 si algo no pasa.
 */
final class PruebasObjetivos{
    private static int fallos;

    private PruebasObjetivos(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaRegistro();
            pruebaProgreso();
            pruebaTexto();
            pruebaGameruleApagada();
            System.out.println(fallos == 0 ? "[Objetivos] OK: todas las pruebas pasan." : "[Objetivos] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static void pruebaRegistro(){
        final boolean ok = ApiObjetivos.registrar("prueba:berilio", "Obtener berilio", "beryllium", 60);
        final boolean repetido = ApiObjetivos.registrar("prueba:berilio", "Otro", "copper", 5);
        final boolean idMalo = ApiObjetivos.registrar("sinDosPuntos", "Titulo", "copper", 5);
        final boolean tituloVacio = ApiObjetivos.registrar("prueba:vacio", "  ", "copper", 5);
        final boolean itemConEspacio = ApiObjetivos.registrar("prueba:espacio", "Titulo", "cobre rojo", 5);
        final boolean metaCero = ApiObjetivos.registrar("prueba:cero", "Titulo", "copper", 0);
        final boolean metaGrande = ApiObjetivos.registrar("prueba:grande", "Titulo", "copper", ApiObjetivos.META_MAX + 1);
        fallos += chequear("un objetivo válido se registra; repetido, id sin formato, título vacío, ítem con espacio o meta fuera de rango se rechazan",
            ok && !repetido && !idMalo && !tituloVacio && !itemConEspacio && !metaCero && !metaGrande);
        fallos += chequear("el objetivo registrado aparece en la lista con su meta", ApiObjetivos.objetivos().stream()
            .anyMatch(o -> o.id.equals("prueba:berilio") && o.meta == 60));
    }

    private static void pruebaProgreso(){
        fallos += chequear("el progreso se acota entre 0 y la meta",
            ApiObjetivos.progreso(58, 60) == 58 && ApiObjetivos.progreso(70, 60) == 60 && ApiObjetivos.progreso(-4, 60) == 0);
        fallos += chequear("completo solo cuando la cantidad alcanza la meta",
            !ApiObjetivos.completo(59, 60) && ApiObjetivos.completo(60, 60) && ApiObjetivos.completo(75, 60));
    }

    private static void pruebaTexto(){
        final ApiObjetivos.Objetivo o = ApiObjetivos.objetivos().stream().filter(x -> x.id.equals("prueba:berilio")).findFirst().orElse(null);
        fallos += chequear("el texto muestra título, progreso y meta, y marca completo al llegar",
            o != null && ApiObjetivos.texto(o, 58).equals("Obtener berilio 58/60")
                && ApiObjetivos.texto(o, 60).equals("Obtener berilio 60/60 (completo)"));
    }

    private static void pruebaGameruleApagada(){
        fallos += chequear("sin mundo abierto la barra no está activa", !ApiObjetivos.activa());
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Objetivos] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
