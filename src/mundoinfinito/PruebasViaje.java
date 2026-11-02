package mundoinfinito;

import java.util.Map;
import java.util.Set;

/**
 * PRUEBAS DE VIAJES (plan v8, entrega B). Sin el juego: solo EstadoSistemas, ApiRadar y ApiViaje.
 * Cubren: tiers de portal y sus materiales, enlaces dentro y fuera de alcance, viajes que exigen enlace y materiales, coste y tiempo
 * proporcionales a la distancia y al tier de la unidad, aceleración por tier del portal, y consecuencias (por defecto ninguna; un fallo
 * se aísla). Salida 0 si todo pasa; 1 si no; 3 si hay un error.
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
            pruebaRequisitos();
            pruebaCosteYTiempo();
            pruebaAceleracionPorPortal();
            pruebaConsecuencias();
            pruebaBoostersYCaos();
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

    /** Escenario: a en el origen y b a 50 unidades (ambos planetas solitarios). */
    private static EstadoSistemas escenario(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.crearSolitario(e, "a", "cuerpo-a", "erekir", 0, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "b", "cuerpo-b", "serpulo", 50, 0, 0, 0, 0, 2);
        return e;
    }

    private static void pruebaPortales(){
        fallos += chequear("tiers fuera de rango se acotan y el tier 2 tiene alcance 300",
            ApiViaje.portal(0).tier == 1 && ApiViaje.portal(9).tier == 3 && ApiViaje.portal(2).alcance == 300.0);
        fallos += chequear("materiales declarados: tier 1 pide 2 ítems y tier 3 pide 3",
            ApiViaje.portal(1).requisitos().length == 2 && ApiViaje.portal(3).requisitos().length == 3);
        boolean rechazo = false;
        try{
            new ApiViaje.Portal(1, 0.0, 10L);
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

    private static void pruebaRequisitos(){
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        ApiViaje.establecer(escenario(), enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        final java.util.List<String> faltanTodos = ApiViaje.faltan(1, null);
        fallos += chequear("sin inventario faltan todos los materiales del tier 1 (cobre 50 y plomo 30)",
            faltanTodos.size() == 2 && faltanTodos.contains("copper 50") && faltanTodos.contains("lead 30"));
        fallos += chequear("con el inventario completo no falta nada", ApiViaje.faltan(1, Map.of("copper", 50, "lead", 30)).isEmpty());
        final ApiViaje.Resultado sinMateriales = ApiViaje.viajar(escenario(), enlaces, "cuerpo-a", "cuerpo-b", 1, null, 0, SIN_MOVIMIENTO);
        fallos += chequear("un viaje sin materiales falla y dice qué falta", !sinMateriales.ok && sinMateriales.mensaje.contains("copper"));
    }

    private static void pruebaCosteYTiempo(){
        final EstadoSistemas e = escenario();
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        ApiViaje.establecer(e, enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        final Map<String, Integer> materiales = Map.of("copper", 50, "lead", 30);
        final ApiViaje.Resultado unidad1 = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO);
        // Tier de unidad 1, tier de portal 1: ticks = 50·2·1/1 = 100; coste = 50·1·1 = 50.
        fallos += chequear("viaje de unidad tier 1 a 50 unidades: 100 ticks y coste 50",
            unidad1.ok && unidad1.ticks == 100L && Math.abs(unidad1.coste - 50.0) < 1e-9);
        final ApiViaje.Resultado unidad3 = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 3, materiales, 0, SIN_MOVIMIENTO);
        // Tier de unidad 3: ticks = 50·2·3/1 = 300; coste = 50·3·1 = 150. Más tier de unidad, más tiempo y más coste.
        fallos += chequear("una unidad de tier 3 tarda 300 ticks y cuesta 150 (proporcional al tier)",
            unidad3.ok && unidad3.ticks == 300L && Math.abs(unidad3.coste - 150.0) < 1e-9);
        final ApiViaje.Resultado cero = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-a", 1, materiales, 0, SIN_MOVIMIENTO);
        fallos += chequear("un viaje a sí mismo no tiene enlace y falla", !cero.ok);
    }

    private static void pruebaAceleracionPorPortal(){
        final EstadoSistemas e = escenario();
        ApiSistemas.crearSolitario(e, "c", "cuerpo-c", "erekir", 1000, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "d", "cuerpo-d", "serpulo", 1050, 0, 0, 0, 0, 2);
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        // Portal tier 2 entre c y d (50 unidades): la misma unidad de tier 3 tarda la mitad que con portal tier 1.
        ApiViaje.establecer(e, enlaces, "e2", "cuerpo-c", "cuerpo-d", ApiViaje.portal(2), 0, SIN_MOVIMIENTO);
        final Map<String, Integer> materiales = Map.of("silicon", 40, "titanium", 20, "lead", 40);
        final ApiViaje.Resultado r = ApiViaje.viajar(e, enlaces, "cuerpo-c", "cuerpo-d", 3, materiales, 0, SIN_MOVIMIENTO);
        // ticks = 50·2·3/2 = 150; con portal tier 1 serían 300.
        fallos += chequear("un portal de tier 2 acelera el viaje de la unidad tier 3: 150 ticks en lugar de 300",
            r.ok && r.ticks == 150L);
    }

    /** P7: boosters (alcance, velocidad, estabilidad), probabilidad y decisión del caos, y viaje con caos apagado (por defecto). */
    private static void pruebaBoostersYCaos(){
        final Set<String> vacio = Set.of();
        final Set<String> alcance = Set.of(ApiViaje.BOOSTER_ALCANCE);
        fallos += chequear("el booster de alcance da 75 de alcance al portal tier 1 (60 · 1,25), y sin booster queda 60",
            Math.abs(ApiViaje.portalEfectivo(ApiViaje.portal(1), alcance).alcance - 75.0) < 1e-9
                && ApiViaje.portalEfectivo(ApiViaje.portal(1), vacio).alcance == 60.0);
        fallos += chequear("el booster de velocidad baja 100 ticks a 80; sin booster se quedan igual; nunca baja de 1",
            ApiViaje.ticksEfectivos(100L, Set.of(ApiViaje.BOOSTER_VELOCIDAD)) == 80L
                && ApiViaje.ticksEfectivos(100L, vacio) == 100L && ApiViaje.ticksEfectivos(1L, Set.of(ApiViaje.BOOSTER_VELOCIDAD)) == 1L);
        final double p1 = ApiViaje.probabilidadCaos(50, 300, 3, vacio);
        fallos += chequear("probabilidad del caos: 2 % · tier 3 · (50 ÷ 300) = 0,01",
            Math.abs(p1 - 0.01) < 1e-9);
        fallos += chequear("el booster de estabilidad divide la probabilidad entre dos",
            Math.abs(ApiViaje.probabilidadCaos(50, 300, 3, Set.of(ApiViaje.BOOSTER_ESTABILIDAD)) - 0.005) < 1e-9);
        fallos += chequear("la probabilidad se topa en 25 % y no es negativa ni sale de datos no válidos",
            ApiViaje.probabilidadCaos(1000, 1, 3, vacio) == ApiViaje.CAOS_TOPE
                && ApiViaje.probabilidadCaos(-5, 300, 3, vacio) == 0.0 && ApiViaje.probabilidadCaos(5, 0, 3, vacio) == 0.0);
        fallos += chequear("sin incidente la tirada no decide nada; con tirada baja hay incidente y el tercio de efecto decide cuál",
            !ApiViaje.evaluarCaos(0.01, 0.5, 0.0, 100L, 50.0).incidente
                && ApiViaje.evaluarCaos(0.01, 0.001, 0.1, 100L, 50.0).efecto.equals("retraso")
                && ApiViaje.evaluarCaos(0.01, 0.001, 0.5, 100L, 50.0).efecto.equals("coste")
                && ApiViaje.evaluarCaos(0.01, 0.001, 0.9, 100L, 50.0).efecto.equals("desvio"));
        final EstadoSistemas e = escenario();
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        ApiViaje.establecer(e, enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        final Map<String, Integer> materiales = Map.of("copper", 50, "lead", 30);
        final ApiViaje.Viaje sinCaos = ApiViaje.viajarConCaos(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO,
            Set.of(ApiViaje.BOOSTER_VELOCIDAD), 1, 0);
        fallos += chequear("con el caos apagado (por defecto) el viaje no tiene incidente y solo aplica el booster de velocidad: 100 → 80 ticks",
            sinCaos.resultado.ok && sinCaos.caos == null && sinCaos.resultado.ticks == 80L && Math.abs(sinCaos.resultado.coste - 50.0) < 1e-9);
    }

    private static void pruebaConsecuencias(){
        ApiViaje.reiniciarConsecuencias();
        final EstadoSistemas e = escenario();
        final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
        ApiViaje.establecer(e, enlaces, "e1", "cuerpo-a", "cuerpo-b", ApiViaje.portal(1), 0, SIN_MOVIMIENTO);
        final Map<String, Integer> materiales = Map.of("copper", 50, "lead", 30);
        final ApiViaje.Resultado sin = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO);
        fallos += chequear("por defecto no hay consecuencias", sin.ok && sin.consecuencias.isEmpty());

        ApiViaje.proponerConsecuencia("prueba-interferencia", v -> "señal reducida " + (int)v.distancia);
        final ApiViaje.Resultado con = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO);
        fallos += chequear("una consecuencia registrada aparece en el resultado del viaje",
            con.ok && con.consecuencias.size() == 1 && con.consecuencias.get(0).equals("señal reducida 50"));

        ApiViaje.reiniciarConsecuencias();
        ApiViaje.proponerConsecuencia("prueba-rota", v -> {
            throw new IllegalStateException("prueba");
        });
        final ApiViaje.Resultado r1 = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO);
        final ApiViaje.Resultado r2 = ApiViaje.viajar(e, enlaces, "cuerpo-a", "cuerpo-b", 1, materiales, 0, SIN_MOVIMIENTO);
        fallos += chequear("una consecuencia que falla se aísla: el viaje sigue y no añade efectos", r1.ok && r2.ok && r2.consecuencias.isEmpty());
        ApiViaje.reiniciarConsecuencias();
    }
}
