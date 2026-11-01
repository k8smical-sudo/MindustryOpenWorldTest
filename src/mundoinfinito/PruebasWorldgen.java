package mundoinfinito;

/**
 * PRUEBAS DE LA API DE WORLDGEN (plan v5, P4). Corren en CI sin el juego: lugares construidos a mano y fuentes de prueba.
 * Salida 0 si todo pasa; 1 si algo no pasa.
 */
final class PruebasWorldgen{
    private static int fallos;

    private PruebasWorldgen(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            ApiWorldgen.iniciar();
            pruebaBiomaDeterminista();
            pruebaValorFijo();
            pruebaMultiplicadoresOrden();
            pruebaAislamiento();
            pruebaPrioridadBioma();
            pruebaIdsRepetidos();
            pruebaAzar();
            System.out.println(fallos == 0 ? "[Worldgen] OK: todas las pruebas pasan." : "[Worldgen] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    /** Una fuente de prueba: multiplica o fija solo para su fenómeno, y puede lanzar una excepción al multiplicar. */
    private static final class FuentePrueba implements ApiWorldgen.Fuente{
        final String id, fenomeno;
        final int prio, biomaFijo;
        final float mult, fijo;
        final boolean lanza;
        int llamadas;

        FuentePrueba(String id, int prio, String fenomeno, float mult, float fijo, int biomaFijo, boolean lanza){
            this.id = id;
            this.prio = prio;
            this.fenomeno = fenomeno;
            this.mult = mult;
            this.fijo = fijo;
            this.biomaFijo = biomaFijo;
            this.lanza = lanza;
        }

        @Override public String id(){ return id; }
        @Override public int prioridad(){ return prio; }
        @Override public int bioma(ApiWorldgen.Lugar l){ return biomaFijo; }

        @Override
        public float multiplicador(String f, int b, ApiWorldgen.Lugar l){
            if(!fenomeno.equals(f)) return Float.NaN;
            llamadas++;
            if(lanza) throw new IllegalStateException("fallo de prueba");
            return mult;
        }

        @Override
        public float valorFijo(String f, int b, ApiWorldgen.Lugar l){
            return fenomeno.equals(f) ? fijo : Float.NaN;
        }
    }

    private static ApiWorldgen.Lugar lugar(int semilla, int dimension, int x, int y, int codigoPiso){
        return new ApiWorldgen.Lugar(semilla, dimension, x, y, codigoPiso, () -> 0, () -> "prueba");
    }

    /** El bioma de una coordenada es el mismo en cada consulta; en Serpulo no hay bioma de Erekir. */
    private static void pruebaBiomaDeterminista(){
        final ApiWorldgen.Lugar arkyic = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_ARKYIC);
        final ApiWorldgen.Lugar rojo = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_RED);
        final ApiWorldgen.Lugar serpulo = lugar(1, 0, 3, -2, MundoInfinitoMod.Muestreo.P_RED);
        final boolean ok = ApiWorldgen.bioma(arkyic) == ApiWorldgen.BIOMA_ARKYIC && ApiWorldgen.bioma(arkyic) == ApiWorldgen.BIOMA_ARKYIC
            && ApiWorldgen.bioma(rojo) == ApiWorldgen.BIOMA_ROJO && ApiWorldgen.bioma(serpulo) == ApiWorldgen.BIOMA_NINGUNO;
        fallos += chequear("el bioma de una coordenada es el mismo en cada consulta; en Serpulo no hay bioma de Erekir", ok);
    }

    /** Un valor fijo sustituye a la probabilidad base y a los multiplicadores de otras fuentes. */
    private static void pruebaValorFijo(){
        final ApiWorldgen.Lugar l = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_CARBON);
        ApiWorldgen.registrar(new FuentePrueba("pru:fija", -10, "pru:fen", 0.5f, 0.3f, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.registrar(new FuentePrueba("pru:mult", 0, "pru:fen", 0.5f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        final float p = ApiWorldgen.probabilidad("pru:fen", 0.8f, l);
        ApiWorldgen.quitar("pru:fija");
        ApiWorldgen.quitar("pru:mult");
        fallos += chequear("un valor fijo sustituye a la probabilidad base y a los multiplicadores (medido " + p + ")", cerca(p, 0.3f));
    }

    /** Tres multiplicadores dan el mismo resultado con cualquier orden de registro. */
    private static void pruebaMultiplicadoresOrden(){
        final ApiWorldgen.Lugar l = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_CARBON);
        ApiWorldgen.registrar(new FuentePrueba("pru:a", 0, "pru:mul", 0.7f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.registrar(new FuentePrueba("pru:b", 0, "pru:mul", 0.9f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.registrar(new FuentePrueba("pru:c", 0, "pru:mul", 0.3f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        final float ida = ApiWorldgen.probabilidad("pru:mul", 1f, l);
        quitar("pru:a", "pru:b", "pru:c");

        ApiWorldgen.registrar(new FuentePrueba("pru:c", 0, "pru:mul", 0.3f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.registrar(new FuentePrueba("pru:b", 0, "pru:mul", 0.9f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.registrar(new FuentePrueba("pru:a", 0, "pru:mul", 0.7f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        final float vuelta = ApiWorldgen.probabilidad("pru:mul", 1f, l);
        quitar("pru:a", "pru:b", "pru:c");

        fallos += chequear("el orden de registro no cambia el resultado (" + ida + " frente a " + vuelta + ")", ida == vuelta && cerca(ida, 0.189f));
    }

    /** Una fuente que lanza una excepción queda desactivada tras una llamada, y las demás siguen funcionando. */
    private static void pruebaAislamiento(){
        final FuentePrueba roto = new FuentePrueba("pru:roto", 0, "pru:ais", 1f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, true);
        final FuentePrueba bien = new FuentePrueba("pru:bien", 1, "pru:ais", 0.5f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false);
        ApiWorldgen.registrar(roto);
        ApiWorldgen.registrar(bien);
        final ApiWorldgen.Lugar l = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_CARBON);
        final float r1 = ApiWorldgen.probabilidad("pru:ais", 1f, l);
        final float r2 = ApiWorldgen.probabilidad("pru:ais", 1f, l);
        final float r3 = ApiWorldgen.probabilidad("pru:ais", 1f, l);
        quitar("pru:roto", "pru:bien");
        fallos += chequear("la fuente que falla se llama una sola vez y el resto sigue (llamadas " + roto.llamadas + ", medido " + r1 + ")",
            roto.llamadas == 1 && cerca(r1, 0.5f) && cerca(r2, 0.5f) && cerca(r3, 0.5f));
    }

    /** Una fuente de menor prioridad sustituye el bioma del juego; al quitarla, vuelve el del juego. */
    private static void pruebaPrioridadBioma(){
        final ApiWorldgen.Lugar l = lugar(1, 1, 3, -2, MundoInfinitoMod.Muestreo.P_ARKYIC);
        ApiWorldgen.registrar(new FuentePrueba("pru:bioma", -10, "pru:otro", 1f, Float.NaN, ApiWorldgen.BIOMA_ROJO, false));
        final int conAddon = ApiWorldgen.bioma(l);
        ApiWorldgen.quitar("pru:bioma");
        final int sinAddon = ApiWorldgen.bioma(l);
        fallos += chequear("la fuente de menor prioridad gana el bioma y al quitarla vuelve el del juego (" + conAddon + ", " + sinAddon + ")",
            conAddon == ApiWorldgen.BIOMA_ROJO && sinAddon == ApiWorldgen.BIOMA_ARKYIC);
    }

    /** Un id repetido o sin formato se rechaza. */
    private static void pruebaIdsRepetidos(){
        final boolean primero = ApiWorldgen.registrar(new FuentePrueba("pru:unica", 0, "x:y", 1f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        final boolean repetido = ApiWorldgen.registrar(new FuentePrueba("pru:unica", 0, "x:y", 1f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        final boolean invalido = ApiWorldgen.registrar(new FuentePrueba("sin-dos-puntos", 0, "x:y", 1f, Float.NaN, ApiWorldgen.BIOMA_NINGUNO, false));
        ApiWorldgen.quitar("pru:unica");
        fallos += chequear("los ids de fuente repetidos o sin formato se rechazan", primero && !repetido && !invalido);
    }

    /** El azar de worldgen es determinista por coordenada y canal, y cae en [0, 1). */
    private static void pruebaAzar(){
        final ApiWorldgen.Lugar l = lugar(7, 1, 10, -4, MundoInfinitoMod.Muestreo.P_CARBON);
        final float a = ApiWorldgen.azar(l, "pru:canal");
        final float b = ApiWorldgen.azar(l, "pru:canal");
        final float otro = ApiWorldgen.azar(l, "pru:otro");
        fallos += chequear("el azar es determinista por coordenada y canal, y cae en [0, 1)", a == b && a >= 0f && a < 1f && a != otro);
    }

    private static void quitar(String... ids){
        for(String id : ids) ApiWorldgen.quitar(id);
    }

    private static boolean cerca(float a, float b){
        return Math.abs(a - b) <= 1e-4f;
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Worldgen] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
