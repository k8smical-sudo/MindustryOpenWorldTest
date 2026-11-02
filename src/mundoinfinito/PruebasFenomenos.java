package mundoinfinito;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PRUEBAS DE DIFICULTAD Y FENÓMENOS CÓSMICOS (plan v9, P10). Corren en CI sin el juego: no abren mundo, así que el perfil es el neutro.
 * Salida 0 si todo pasa; 1 si algo no pasa.
 */
final class PruebasFenomenos{
    private static int fallos;

    private PruebasFenomenos(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            ApiDificultad.iniciar();
            pruebaRegistroPerfiles();
            pruebaValorConPerfil();
            pruebaFalloDePerfil();
            pruebaVariablesYAzar();
            pruebaRegistroFenomenos();
            pruebaAgujeroNegro();
            System.out.println(fallos == 0 ? "[Fenomenos] OK: todas las pruebas pasan." : "[Fenomenos] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    /** Perfil de prueba que multiplica por 2 la clave doble y no cambia el resto. */
    private static ApiDificultad.Perfil perfil(final int codigo, final String id){
        return new ApiDificultad.Perfil(){
            @Override public int codigo(){ return codigo; }
            @Override public String id(){ return id; }
            @Override public String nombre(){ return "Prueba"; }
            @Override public String descripcion(){ return "Prueba"; }
            @Override public double valor(String clave, double base, ApiDificultad.ContextoDificultad c){
                return clave.equals("doble") ? base * 2.0 : base;
            }
        };
    }

    private static void pruebaRegistroPerfiles(){
        final boolean nulo = ApiDificultad.registrarPerfil(null);
        final boolean neutro = ApiDificultad.registrarPerfil(perfil(0, "prueba:cero"));
        final boolean grande = ApiDificultad.registrarPerfil(perfil(10000, "prueba:grande"));
        final boolean idMalo = ApiDificultad.registrarPerfil(perfil(4001, "sinDosPuntos"));
        final boolean ok = ApiDificultad.registrarPerfil(perfil(4002, "prueba:ok"));
        final boolean repetido = ApiDificultad.registrarPerfil(perfil(4002, "prueba:otro"));
        final boolean idRepetido = ApiDificultad.registrarPerfil(perfil(4003, "prueba:ok"));
        fallos += chequear("un perfil nulo, con código 0 o fuera de rango, con id sin formato, o con código o id repetidos se rechaza",
            !nulo && !neutro && !grande && !idMalo && ok && !repetido && !idRepetido);
        fallos += chequear("el neutro siempre va primero en la lista de perfiles disponibles",
            ApiDificultad.disponibles().get(0).codigo() == ApiDificultad.CODIGO_NEUTRO);
    }

    private static void pruebaValorConPerfil(){
        final ApiDificultad.ContextoDificultad c = new ApiDificultad.ContextoDificultad(1, 0, 0f);
        final double sinPerfil = ApiDificultad.valorCon(null, "doble", 0.5, c);
        final double neutro = ApiDificultad.valorCon(ApiDificultad.actual(), "doble", 0.5, c);
        final double conPerfil = ApiDificultad.valorCon(perfil(4002, "prueba:ok"), "doble", 0.5, c);
        final double otraClave = ApiDificultad.valorCon(perfil(4002, "prueba:ok"), "otra", 0.5, c);
        fallos += chequear("sin perfil y con el neutro, la consulta devuelve el valor base",
            sinPerfil == 0.5 && neutro == 0.5);
        fallos += chequear("un perfil decide el valor de su clave y deja el resto como estaba",
            conPerfil == 1.0 && otraClave == 0.5);
    }

    private static void pruebaFalloDePerfil(){
        final ApiDificultad.ContextoDificultad c = new ApiDificultad.ContextoDificultad(1, 0, 0f);
        final ApiDificultad.Perfil roto = new ApiDificultad.Perfil(){
            @Override public int codigo(){ return 4004; }
            @Override public String id(){ return "prueba:roto"; }
            @Override public String nombre(){ return "Roto"; }
            @Override public String descripcion(){ return "Roto"; }
            @Override public double valor(String clave, double base, ApiDificultad.ContextoDificultad ctx){
                throw new IllegalStateException("fallo de prueba");
            }
        };
        final ApiDificultad.Perfil noFinito = new ApiDificultad.Perfil(){
            @Override public int codigo(){ return 4005; }
            @Override public String id(){ return "prueba:infinito"; }
            @Override public String nombre(){ return "Infinito"; }
            @Override public String descripcion(){ return "Infinito"; }
            @Override public double valor(String clave, double base, ApiDificultad.ContextoDificultad ctx){
                return Double.POSITIVE_INFINITY;
            }
        };
        final double a = ApiDificultad.valorCon(roto, "x", 0.25, c);
        final double b = ApiDificultad.valorCon(roto, "x", 0.25, c);
        final double n = ApiDificultad.valorCon(noFinito, "x", 0.25, c);
        fallos += chequear("un perfil que falla o da un valor no finito devuelve el valor base, y el que falla queda desactivado",
            a == 0.25 && b == 0.25 && n == 0.25);
    }

    private static void pruebaVariablesYAzar(){
        final Map<String, Double> defectos = new LinkedHashMap<String, Double>();
        defectos.put("v", 0.5);
        final ApiFenomenoCosmico.ContextoCosmico c = new ApiFenomenoCosmico.ContextoCosmico("prueba:f", 7, 0, 10f, defectos);
        fallos += chequear("una variable declarada da su valor por defecto sin perfil; una no declarada da NaN",
            c.variable("v") == 0.5 && Double.isNaN(c.variable("no-existe")));
        final float a1 = c.azar("canal", 3), a2 = c.azar("canal", 3), otro = c.azar("otro", 3);
        fallos += chequear("el azar es determinista (mismo canal y bloque, mismo valor), en [0, 1), y cambia con el canal",
            a1 == a2 && a1 >= 0f && a1 < 1f && otro >= 0f && otro < 1f && a1 != otro);
    }

    private static void pruebaRegistroFenomenos(){
        final ApiFenomenoCosmico.Fenomeno f = new ApiFenomenoCosmico.Fenomeno(){
            @Override public String id(){ return "prueba:pulsar"; }
            @Override public Map<String, Double> variables(){
                final Map<String, Double> v = new LinkedHashMap<String, Double>();
                v.put("fuerza", 1.0);
                return v;
            }
        };
        final boolean ok = ApiFenomenoCosmico.registrar(f);
        final boolean repetido = ApiFenomenoCosmico.registrar(f);
        final boolean idMalo = ApiFenomenoCosmico.registrar(new ApiFenomenoCosmico.Fenomeno(){
            @Override public String id(){ return "sinDosPuntos"; }
        });
        fallos += chequear("un fenómeno se registra una vez y rechaza repetidos e ids sin formato",
            ok && !repetido && !idMalo && ApiFenomenoCosmico.ids().contains("prueba:pulsar"));
        fallos += chequear("un fenómeno registrado está apagado por defecto (sin mundo abierto)",
            !ApiFenomenoCosmico.activo("prueba:pulsar"));
    }

    private static void pruebaAgujeroNegro(){
        FenomenosCosmicos.registrar();
        final boolean registrado = ApiFenomenoCosmico.ids().contains(FenomenosCosmicos.AGUJERO_NEGRO);
        final ApiFenomenoCosmico.Fenomeno agujero = new FenomenosCosmicos.AgujeroNegro();
        final Map<String, Double> v = agujero.variables();
        fallos += chequear("el agujero negro está registrado, declara sus variables y está apagado por defecto",
            registrado && v.containsKey("horizonte") && v.containsKey("probabilidadIncidente")
                && !ApiFenomenoCosmico.activo(FenomenosCosmicos.AGUJERO_NEGRO));
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Fenomenos] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
