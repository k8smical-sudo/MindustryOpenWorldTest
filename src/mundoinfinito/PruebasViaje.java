package mundoinfinito;

/**
 * PRUEBAS DE VIAJES (plan v7, entrega B). Sin el juego: solo EstadoSistemas, ApiRadar y ApiViaje.
 * Cubren: portales por tier, enlace dentro y fuera de alcance, duplicados y cuerpos inexistentes, viaje que exige enlace, coste en ticks
 * y enlace en ambos sentidos. Salida 0 si todo pasa; 1 si no; 3 si hay un error.
 */
final class PruebasViaje{
    private static int fallos;
    private static final java.util.function.ToDoubleFunction<String> SIN_MOVIMIENTO = p -> 0.0;

    private PruebasViaje(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaPortales();
            pruebaEstablecer();
            pruebaViajar();
            System.out.println(fallos == 0 ? "[Viaje] OK: todas las pruebas pasan." : "[Viaje] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static int chequear(String nombre, boolean ok){
        System.out.println((ok ? "[Viaje] ok: " : "[Viaje] FALLO: ") + nombre);
        return ok ? 0 : 1;
    }

    private static void pruebaPortales(){
        fallos += chequear("tiers fuera de rango se acotan y el tier 2 tiene alcance 300",
            ApiViaje.portal(0).tier == 1 && ApiViaje.portal(9).tier == 3 && ApiViaje.portal(2).alcance == 300.0);
        boolean rechazo = false;
        try{
            new ApiViaje.Portal(1, 0.0, 10L, 1.0);
        }catch(IllegalArgumentException ex){
            rechazo = true;
        }
        fallos += chequear("un portal con alcance cero se rechaza", rechazo);
    }

    private static void pruebaEstablecer(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.crearSolitario(e, "a", "cuerpo-a", "erekir", 0, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "b", "cuerpo-b", "serpulo", 50, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "c", "cuerpo-c", "serpulo", 500, 0, 0, 0, 0, 2);
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        final ApiViaje.Resultado ok = ApiViaje.establecer(e, enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        fallos += chequear("un portal tier 1 (alcance 60) enlaza dos cuerpos a 50 unidades", ok.ok && enlaces.todos().size() == 1);
        final ApiViaje.Resultado lejos = ApiViaje.establecer(e, enlaces, "e2", "cuerpo-a", "cuerpo-c", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        fallos += chequear("un enlace a 500 unidades se rechaza con portal tier 1", !lejos.ok && enlaces.todos().size() == 1);
        final ApiViaje.Resultado repetido = ApiViaje.establecer(e, enlaces, "e3", "cuerpo-b", "cuerpo-a", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        fallos += chequear("no se duplica un enlace existente en sentido contrario", !repetido.ok);
        final ApiViaje.Resultado inexistente = ApiViaje.establecer(e, enlaces, "e4", "cuerpo-a", "no-existe", ApiViaje.portal(3), 0, SIN_MOVIMIENTO);
        fallos += chequear("un cuerpo inexistente se rechaza", !inexistente.ok);
    }

    private static void pruebaViajar(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.crearSolitario(e, "a", "cuerpo-a", "erekir", 0, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "b", "cuerpo-b", "serpulo", 50, 0, 0, 0, 0, 2);
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        final ApiViaje.Resultado sinEnlace = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 0, SIN_MOVIMIENTO);
        fallos += chequear("sin enlace no hay viaje", !sinEnlace.ok);
        ApiViaje.establecer(e, enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        final ApiViaje.Resultado ida = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 0, SIN_MOVIMIENTO);
        // Tier 1: 300 ticks base + 50 unidades · 2 ticks por unidad = 400.
        fallos += chequear("el viaje tier 1 a 50 unidades cuesta 300 + 50·2 = 400 ticks", ida.ok && ida.ticks == 400L);
        final ApiViaje.Resultado vuelta = ApiViaje.viajar(e, enlaces, "cuerpo-b", "cuerpo-a", 0, SIN_MOVIMIENTO);
        fallos += chequear("el enlace sirve en ambos sentidos y el coste es el mismo", vuelta.ok && vuelta.ticks == 400L);
    }
}
