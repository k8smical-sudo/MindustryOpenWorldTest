package mundoinfinito;

import java.util.List;

/**
 * PRUEBAS DE RADARES (plan v7, entrega A2). Sin el juego: solo EstadoSistemas, ApiSistemas y ApiRadar.
 * Cubren: niveles y acotado de tiers, validación de niveles, alcance, orden por sistema y por cuerpo, tiempo de detección por
 * cuerpo, determinismo y posición de un cuerpo de órbita en el tiempo. Salida 0 si todo pasa; 1 si no; 3 si hay un error.
 */
final class PruebasRadar{
    private static int fallos;
    private static final double PI = Math.PI, EPS = 1e-6;
    private static final java.util.function.ToDoubleFunction<String> SIN_MOVIMIENTO = p -> 0.0;

    private PruebasRadar(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaNiveles();
            pruebaAlcance();
            pruebaOrdenSistemas();
            pruebaOrdenCuerposDentroDeSistema();
            pruebaDeterminismo();
            pruebaOrbitaEnMovimiento();
            System.out.println(fallos == 0 ? "[Radar] OK: todas las pruebas pasan." : "[Radar] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static int chequear(String nombre, boolean ok){
        System.out.println((ok ? "[Radar] ok: " : "[Radar] FALLO: ") + nombre);
        return ok ? 0 : 1;
    }

    private static boolean cerca(double a, double b){ return Math.abs(a - b) < EPS; }

    private static void pruebaNiveles(){
        fallos += chequear("tiers fuera de rango se acotan: 0 -> 1, 7 -> 3",
            ApiRadar.nivel(0).tier == 1 && ApiRadar.nivel(7).tier == 3);
        fallos += chequear("valores por defecto: el tier 2 barre 240 ticks por cuerpo con alcance 120",
            ApiRadar.nivel(2).ticksPorCuerpo == 240L && cerca(ApiRadar.nivel(2).alcance, 120));
        boolean rechazo = false;
        try{
            new ApiRadar.Nivel(1, -1, 10);
        }catch(IllegalArgumentException ex){
            rechazo = true;
        }
        fallos += chequear("un nivel con alcance negativo se rechaza", rechazo);
    }

    private static void pruebaAlcance(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.crearSolitario(e, "dentro", "m-dentro", "erekir", 30, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "fuera", "m-fuera", "erekir", 500, 0, 0, 0, 0, 2);
        final List<ApiRadar.Hallazgo> h = ApiRadar.escanear(e, 0, 0, ApiRadar.nivel(1), 0, SIN_MOVIMIENTO);
        fallos += chequear("el tier 1 (alcance 40) detecta el cuerpo a 30 y no el de 500",
            h.size() == 1 && "m-dentro".equals(h.get(0).cuerpoId));
    }

    private static void pruebaOrdenSistemas(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.crearSolitario(e, "lejos", "m-lejos", "erekir", 30, 0, 0, 0, 0, 2);
        ApiSistemas.crearSolitario(e, "cerca", "m-cerca", "erekir", 5, 0, 0, 0, 0, 2);
        final List<ApiRadar.Hallazgo> h = ApiRadar.escanear(e, 0, 0, ApiRadar.nivel(2), 1000, SIN_MOVIMIENTO);
        fallos += chequear("sistemas de cercano a lejano: primero m-cerca (5), después m-lejos (30)",
            h.size() == 2 && "m-cerca".equals(h.get(0).cuerpoId) && "m-lejos".equals(h.get(1).cuerpoId));
        fallos += chequear("tiempo de detección: el k-ésimo aparece en tiempo + (k+1)·ticks (1240 y 1480)",
            h.get(0).tickDeteccion == 1000 + 240 && h.get(1).tickDeteccion == 1000 + 2 * 240);
    }

    private static void pruebaOrdenCuerposDentroDeSistema(){
        final EstadoSistemas e = new EstadoSistemas();
        // Sistema en el origen con dos cuerpos de órbita de radio 30: uno en ángulo 0 (x = 30) y otro en π (x = -30).
        ApiSistemas.anadirMundoEnOrbita(e, "s", "b1", "serpulo", 30, 2);
        ApiSistemas.anadirMundoEnOrbita(e, "s", "b2", "serpulo", 30, 2);
        final List<ApiRadar.Hallazgo> h = ApiRadar.escanear(e, 10, 0, ApiRadar.nivel(2), 0, SIN_MOVIMIENTO);
        fallos += chequear("dentro de un sistema, de cercano a lejano: b1 (dist 20) antes que b2 (dist 40)",
            h.size() == 2 && "b1".equals(h.get(0).cuerpoId) && "b2".equals(h.get(1).cuerpoId)
                && cerca(h.get(0).distancia, 20) && cerca(h.get(1).distancia, 40));
        fallos += chequear("el orden de la secuencia es 0 y 1", h.get(0).orden == 0 && h.get(1).orden == 1);
    }

    private static void pruebaDeterminismo(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e, "s", "b1", "serpulo", 30, 2);
        ApiSistemas.anadirMundoEnOrbita(e, "s", "b2", "serpulo", 30, 2);
        ApiSistemas.crearSolitario(e, "x", "m-x", "erekir", 12, 3, 0, 0, 0, 2);
        final java.util.function.ToDoubleFunction<String> periodo = p -> 100.0;
        final List<ApiRadar.Hallazgo> a = ApiRadar.escanear(e, 0, 0, ApiRadar.nivel(3), 777, periodo);
        final List<ApiRadar.Hallazgo> b = ApiRadar.escanear(e, 0, 0, ApiRadar.nivel(3), 777, periodo);
        boolean igual = a.size() == b.size();
        for(int i = 0; igual && i < a.size(); i++){
            igual = a.get(i).cuerpoId.equals(b.get(i).cuerpoId) && a.get(i).tickDeteccion == b.get(i).tickDeteccion
                && a.get(i).distancia == b.get(i).distancia;
        }
        fallos += chequear("el mismo barrido con los mismos datos da el mismo resultado", igual && a.size() == 3);
    }

    private static void pruebaOrbitaEnMovimiento(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e, "s", "b1", "serpulo", 30, 2);
        final EstadoSistemas.Cuerpo c = e.buscarCuerpo("b1");
        final EstadoSistemas.SistemaSolar s = e.buscar("s");
        // Periodo 100 ticks; a los 25 ticks el ángulo ha avanzado π/2: el cuerpo está en (0, 30).
        final double[] pos = ApiRadar.posicionCuerpo(s, c, 25, periodo -> 100.0);
        fallos += chequear("un cuerpo de órbita avanza con su periodo: a un cuarto de vuelta está en (0, radio)",
            cerca(pos[0], 0) && cerca(pos[1], 30));
        fallos += chequear("sin periodo válido, el cuerpo queda en su ángulo de creación (x = 30)",
            cerca(ApiRadar.posicionCuerpo(s, c, 25, periodo -> 0.0)[0], 30));
    }
}
