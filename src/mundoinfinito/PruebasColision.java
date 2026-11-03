package mundoinfinito;

import java.util.List;

/**
 * PRUEBAS DE COLISIONES (plan v9, P10). Sin el juego: reglas, validación, plan de fases, azar determinista y superviviente por variable.
 * Salida 0 si todo pasa; 1 si algo no pasa; 3 si hay un error.
 */
final class PruebasColision{
    private static int fallos;

    private PruebasColision(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaValidacion();
            pruebaPlan();
            pruebaDisparo();
            pruebaSuperviviente();
            pruebaRegistro();
            pruebaApagadaPorDefecto();
            System.out.println(fallos == 0 ? "[Colision] OK: todas las pruebas pasan." : "[Colision] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Colision] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }

    private static ApiColision.Regla base(){
        return ApiColision.Regla.de("prueba:choque", "a", "b").fase("aproximacion", 0).fase("impacto", 600).fase("secuela", 1200);
    }

    private static void pruebaValidacion(){
        fallos += chequear("una regla completa es válida", ApiColision.motivoInvalida(base()) == null);
        fallos += chequear("id sin formato, mismo cuerpo, cuerpo vacío o id con '/' se rechazan",
            ApiColision.motivoInvalida(ApiColision.Regla.de("sinDosPuntos", "a", "b")) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "a")) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", " ")) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x/y", "a", "b")) != null
                && ApiColision.motivoInvalida(null) != null);
        fallos += chequear("fases hacia atrás, repetidas, con espacio o con desplazamiento negativo se rechazan",
            ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").fase("uno", 10).fase("dos", 5)) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").fase("uno", 1).fase("uno", 2)) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").fase("u no", 1)) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").fase("uno", -1)) != null);
        fallos += chequear("MAYOR y MENOR piden variable; una probabilidad fuera de 0 a 1 se rechaza",
            ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").superviviente(ApiColision.Criterio.MAYOR)) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").conProbabilidad(1.5)) != null
                && ApiColision.motivoInvalida(ApiColision.Regla.de("p:x", "a", "b").conProbabilidad(Double.NaN)) != null);
    }

    private static void pruebaPlan(){
        final List<ApiColision.Programacion> plan = ApiColision.plan(base(), 1000f);
        fallos += chequear("el plan programa una fase por evento, desde el inicio más su desplazamiento, con id regla/fase",
            plan.size() == 3 && plan.get(0).evento.equals("prueba:choque/aproximacion") && plan.get(0).tiempo == 1000f
                && plan.get(1).tiempo == 1600f && plan.get(2).evento.equals("prueba:choque/secuela") && plan.get(2).tiempo == 2200f);
        fallos += chequear("un inicio no válido o una regla nula no programan nada",
            ApiColision.plan(base(), Float.NaN).isEmpty() && ApiColision.plan(base(), -1f).isEmpty() && ApiColision.plan(null, 0f).isEmpty());
    }

    private static void pruebaDisparo(){
        final ApiColision.Desenlace siempre = ApiColision.resolver(base().siempre(), 1, 0, 0, null);
        final ApiColision.Desenlace nunca = ApiColision.resolver(base().nunca(), 1, 0, 0, null);
        fallos += chequear("siempre ocurre y nunca no ocurre (sin destruidos)", siempre.ocurre && !nunca.ocurre && nunca.destruidos.isEmpty());
        final ApiColision.Regla azar = base().conProbabilidad(0.5);
        int ocurren = 0;
        boolean estable = true;
        for(int t = 0; t < 400; t++){
            final ApiColision.Desenlace x = ApiColision.resolver(azar, 42, 0, t, null), y = ApiColision.resolver(azar, 42, 0, t, null);
            estable &= x.ocurre == y.ocurre && x.tirada == y.tirada;
            if(x.ocurre) ocurren++;
        }
        fallos += chequear("con azar, el mismo mundo e instante dan siempre el mismo resultado", estable);
        fallos += chequear("con probabilidad 0,5 ocurre en torno a la mitad de los instantes (entre 35 % y 65 %)", ocurren > 140 && ocurren < 260);
        fallos += chequear("probabilidad 0 no ocurre nunca y 1 ocurre siempre",
            !ApiColision.resolver(base().conProbabilidad(0.0), 42, 0, 5, null).ocurre && ApiColision.resolver(base().conProbabilidad(1.0), 42, 0, 5, null).ocurre);
    }

    private static void pruebaSuperviviente(){
        final java.util.function.ToDoubleBiFunction<String, String> masa = (c, v) -> c.equals("a") ? 10.0 : (c.equals("b") ? 4.0 : Double.NaN);
        final ApiColision.Desenlace mayor = ApiColision.resolver(base().superviviente(ApiColision.Criterio.MAYOR, "masa"), 1, 0, 0, masa);
        final ApiColision.Desenlace menor = ApiColision.resolver(base().superviviente(ApiColision.Criterio.MENOR, "masa"), 1, 0, 0, masa);
        fallos += chequear("sobrevive el de mayor (o menor) valor de la variable y el otro se destruye",
            "a".equals(mayor.superviviente) && mayor.destruidos.equals(java.util.Arrays.asList("b")) && "b".equals(menor.superviviente) && menor.destruidos.equals(java.util.Arrays.asList("a")));
        final ApiColision.Desenlace ningunoA = ApiColision.resolver(base().superviviente(ApiColision.Criterio.NINGUNO), 1, 0, 0, masa);
        final ApiColision.Desenlace fijoB = ApiColision.resolver(base().superviviente(ApiColision.Criterio.B), 1, 0, 0, masa);
        fallos += chequear("sin criterio se destruyen los dos; con B fijo sobrevive B", ningunoA.superviviente == null && ningunoA.destruidos.size() == 2 && "b".equals(fijoB.superviviente));
        final ApiColision.Desenlace sinDato = ApiColision.resolver(base().superviviente(ApiColision.Criterio.MAYOR, "otra"), 1, 0, 0, (c, v) -> Double.NaN);
        final ApiColision.Desenlace sinFuncion = ApiColision.resolver(base().superviviente(ApiColision.Criterio.MAYOR, "masa"), 1, 0, 0, null);
        fallos += chequear("si falta el valor (NaN) o la función, no se inventa: no hay superviviente y se destruyen los dos",
            sinDato.superviviente == null && sinDato.destruidos.size() == 2 && sinFuncion.superviviente == null);
        final ApiColision.Desenlace empate = ApiColision.resolver(ApiColision.Regla.de("p:e", "zeta", "alfa").superviviente(ApiColision.Criterio.MAYOR, "masa"), 1, 0, 0, (c, v) -> 5.0);
        fallos += chequear("un empate lo decide el id menor, siempre", "alfa".equals(empate.superviviente));
    }

    private static void pruebaRegistro(){
        ApiColision.reiniciar();
        final boolean ok = ApiColision.registrar(base());
        final boolean repetida = ApiColision.registrar(base());
        final boolean mala = ApiColision.registrar(ApiColision.Regla.de("sinDosPuntos", "a", "b"));
        fallos += chequear("una regla válida se registra; repetida o inválida, no", ok && !repetida && !mala);
        fallos += chequear("se busca por id y por par de cuerpos en cualquier orden",
            ApiColision.regla("prueba:choque") != null && ApiColision.entre("b", "a") != null && ApiColision.entre("a", "c") == null);
        fallos += chequear("se puede quitar", ApiColision.quitar("prueba:choque") && ApiColision.reglas().isEmpty());
        ApiColision.reiniciar();
    }

    private static void pruebaApagadaPorDefecto(){
        fallos += chequear("sin mundo abierto las colisiones están apagadas y iniciar no programa nada", !ApiColision.activa() && ApiColision.iniciar("prueba:choque").isEmpty());
    }
}
