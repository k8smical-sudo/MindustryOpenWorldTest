package mundoinfinito;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * PRUEBAS DE LUZ SOLAR (plan v5, P1 a P3). Corren en CI sin el juego: usan un planeta y una sombra de prueba con fase controlada.
 * El planeta está en -2 (fase 0,5). La sombra, de radio 0,3 y órbita 1, está en -1 con fase 0,5 (eclipse) y en (0, 1) con fase 0,25 (sin eclipse).
 * Los planetas del juego (Serpulo y Erekir) quedan en el lado positivo del eje y no tapan al planeta de prueba.
 * Salida 0 si todo pasa; 1 si algo no pasa.
 */
final class PruebasLuz{
    private static final String PLANETA = "prueba:planeta", SOMBRA = "prueba:sombra";
    /** Fase de la sombra: se cambia entre pruebas para interponerla o sacarla. */
    private static final double[] FASE_SOMBRA = {0.25};
    private static int fallos;

    private PruebasLuz(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            preparar();
            pruebaLuzBase();
            pruebaEclipse();
            pruebaOrdenCarga();
            pruebaAislamiento();
            pruebaTope();
            pruebaIdsRepetidos();
            System.out.println(fallos == 0 ? "[Luz] OK: todas las pruebas pasan." : "[Luz] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static void preparar(){
        final boolean a = ApiLuz.registrarCuerpo(PLANETA, 2f, 0.12f, new ApiLuz.Fase(){
            @Override public double fase(){ return 0.5; }
        });
        final boolean b = ApiLuz.registrarCuerpo(SOMBRA, 1f, 0.3f, new ApiLuz.Fase(){
            @Override public double fase(){ return FASE_SOMBRA[0]; }
        });
        fallos += chequear("los cuerpos de prueba se registran", a && b);
    }

    /** P1: sin cuerpo interpuesto, el factor es 1. */
    private static void pruebaLuzBase(){
        FASE_SOMBRA[0] = 0.25;
        final float f = ApiLuz.factorPlaneta(PLANETA);
        fallos += chequear("P1: sin cuerpo interpuesto el factor es 1 (medido " + f + ")", cerca(f, 1f, 1e-4f));
    }

    /** P2: con la sombra entre la estrella y el planeta el factor baja, y vuelve a 1 al salir. */
    private static void pruebaEclipse(){
        FASE_SOMBRA[0] = 0.5;
        final float tapado = ApiLuz.factorPlaneta(PLANETA);
        fallos += chequear("P2: con la sombra entre la estrella y el planeta el factor baja a unos 0,125 (medido " + tapado + ")", cerca(tapado, 0.125f, 0.01f));
        FASE_SOMBRA[0] = 0.25;
        final float libre = ApiLuz.factorPlaneta(PLANETA);
        fallos += chequear("P2: al salir de la línea el factor vuelve a 1 (medido " + libre + ")", cerca(libre, 1f, 1e-4f));
    }

    /** P3: el mismo conjunto de modificadores da exactamente el mismo factor, con cualquier orden de registro. */
    private static void pruebaOrdenCarga(){
        FASE_SOMBRA[0] = 0.25;
        mod("prueba", "prueba:a", 0, 0.7f);
        mod("prueba", "prueba:b", 0, 0.9f);
        mod("otra", "prueba:c", 0, 0.3f);
        final float ida = ApiLuz.factorPlaneta(PLANETA);
        quitar("prueba:a", "prueba:b", "prueba:c");

        mod("otra", "prueba:c", 0, 0.3f);
        mod("prueba", "prueba:b", 0, 0.9f);
        mod("prueba", "prueba:a", 0, 0.7f);
        final float vuelta = ApiLuz.factorPlaneta(PLANETA);
        quitar("prueba:a", "prueba:b", "prueba:c");

        fallos += chequear("P3: el orden de registro no cambia el factor (" + ida + " frente a " + vuelta + ")", ida == vuelta && cerca(ida, 0.7f * 0.9f * 0.3f, 1e-4f));
    }

    /** P3: un modificador que lanza una excepción se desactiva con un solo aviso, y el resto sigue funcionando. */
    private static void pruebaAislamiento(){
        FASE_SOMBRA[0] = 0.25;
        final AtomicInteger llamadas = new AtomicInteger();
        ApiLuz.registrarModificador("prueba", "prueba:roto", 0, (p, f) -> {
            llamadas.incrementAndGet();
            throw new IllegalStateException("fallo de prueba");
        });
        mod("prueba", "prueba:bien", 1, 0.5f);
        final float r1 = ApiLuz.factorPlaneta(PLANETA);
        final float r2 = ApiLuz.factorPlaneta(PLANETA);
        final float r3 = ApiLuz.factorPlaneta(PLANETA);
        quitar("prueba:roto", "prueba:bien");
        fallos += chequear("P3: el modificador que falla se llama una sola vez y el resto sigue (llamadas " + llamadas.get() + ", factor " + r1 + ")",
            llamadas.get() == 1 && cerca(r1, 0.5f, 1e-4f) && cerca(r2, 0.5f, 1e-4f) && cerca(r3, 0.5f, 1e-4f));
    }

    /** El factor se limita a LuzSolar.FACTOR_MAX. */
    private static void pruebaTope(){
        FASE_SOMBRA[0] = 0.25;
        mod("prueba", "prueba:grande", 0, 10f);
        final float f = ApiLuz.factorPlaneta(PLANETA);
        quitar("prueba:grande");
        fallos += chequear("el factor se limita a " + LuzSolar.FACTOR_MAX + " (medido " + f + ")", cerca(f, LuzSolar.FACTOR_MAX, 1e-4f));
    }

    /** Un id repetido o no válido se rechaza. */
    private static void pruebaIdsRepetidos(){
        final boolean primero = mod("prueba", "prueba:unico", 0, 1f);
        final boolean repetido = mod("prueba", "prueba:unico", 0, 1f);
        final boolean invalido = mod("prueba", "sin-dos-puntos", 0, 1f);
        quitar("prueba:unico");
        fallos += chequear("los ids de modificador repetidos o sin formato se rechazan", primero && !repetido && !invalido);
    }

    private static boolean mod(String fuente, String id, int prioridad, final float valor){
        return ApiLuz.registrarModificador(fuente, id, prioridad, (p, f) -> valor);
    }

    private static void quitar(String... ids){
        for(String id : ids) ApiLuz.quitarModificador(id);
    }

    private static boolean cerca(float a, float b, float tolerancia){
        return Math.abs(a - b) <= tolerancia;
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Luz] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
