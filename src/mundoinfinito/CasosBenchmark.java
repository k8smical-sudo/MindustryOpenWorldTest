package mundoinfinito;

import arc.func.Prov;
import mindustry.type.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CASOS DEL BANCO DE PRUEBAS (ver {@link Bancos}). Para añadir un benchmark: añade un caso en el área que corresponda. Cada caso devuelve true solo
 * si su resultado es correcto, así que un benchmark nunca mide una operación que está mal hecha.
 *
 * Áreas: desduplicación (M7), terreno (corpus), azar, planetas (P1), tiempo y calendario, ajustes, modpack, versión y árbol tecnológico (P4-P5).
 * Lo que vive en el motor de MX-Test (anillo de casillas, niebla, pathfinder) se mide en tests/openworld de MX-Test, no aquí.
 */
final class CasosBenchmark{
    private CasosBenchmark(){}

    static List<Bancos.Caso> todos(){
        ApiPlaneta.iniciar();
        final List<Bancos.Caso> out = new ArrayList<Bancos.Caso>();
        desduplicacion(out);
        terreno(out);
        azar(out);
        planetas(out);
        tiempo(out);
        ajustes(out);
        modpack(out);
        version(out);
        arbol(out);
        return out;
    }

    // ================================================================== desduplicación (M7)

    private static void desduplicacion(List<Bancos.Caso> out){
        // Trivial: el coste de pasar por la desduplicación frente a llamar directamente. Es la medida del sobrecoste.
        final Prov<Long> trivial = () -> 42L;
        out.add(new Bancos.Caso("desduplicacion", "directo-trivial", 2000000, () -> trivial.get().longValue() == 42L, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "acierto-trivial", 2000000,
            () -> ApiDesduplicacion.calcular("bench-dedup:trivial", trivial, 7L).longValue() == 42L, "ApiDesduplicacion"));

        // Pesado: un cálculo real de ~4096 pasos. Aquí la desduplicación debe ganar.
        final Prov<Long> pesado = () -> {
            long h = 1469598103934665603L;
            for(int i = 0; i < 4096; i++){
                h ^= i;
                h *= 1099511628211L;
            }
            return h;
        };
        final long refPesado = pesado.get().longValue();
        out.add(new Bancos.Caso("desduplicacion", "directo-pesado", 20000, () -> pesado.get().longValue() == refPesado, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "acierto-pesado", 20000,
            () -> ApiDesduplicacion.calcular("bench-dedup:pesado", pesado, 4096L).longValue() == refPesado, "ApiDesduplicacion"));

        // Entradas que cambian: 3000 claves distintas, la caché se llena y se reutiliza.
        final long[] k = new long[1];
        out.add(new Bancos.Caso("desduplicacion", "entradas-variadas", 20000,
            () -> ApiDesduplicacion.calcular("bench-dedup:variadas", pesado, k[0]++ % 3000L).longValue() == refPesado, "ApiDesduplicacion"));
    }

    // ================================================================== terreno (corpus)

    private static void terreno(List<Bancos.Caso> out){
        // Los 435 chunks del corpus. Una iteración = un barrido completo; el resultado debe coincidir con la referencia directa.
        final List<int[]> es = Corpus.entradas();
        final long[] ref = Corpus.calcularDirecto(es);
        out.add(new Bancos.Caso("terreno", "directo-chunks", 1, () -> Arrays.equals(Corpus.calcularDirecto(es), ref), "GeneradorTerreno"));
        out.add(new Bancos.Caso("terreno", "nucleo-chunks", 1, () -> {
            Corpus.olvidarCaches();
            return Arrays.equals(Corpus.calcularNucleo(es), ref);
        }, "GeneradorTerreno"));
    }

    // ================================================================== azar determinista (ApiAzar)

    private static void azar(List<Bancos.Caso> out){
        final float refU = ApiAzar.unidad(7, 0, "bench", 3, 4);
        final int refE = ApiAzar.entero(7, 0, "bench", 3, 4, 10);
        out.add(new Bancos.Caso("azar", "unidad", 200000, () -> {
            final float v = ApiAzar.unidad(7, 0, "bench", 3, 4);
            return v == refU && v >= 0f && v < 1f;
        }, "ApiAzar"));
        out.add(new Bancos.Caso("azar", "entero", 200000, () -> {
            final int v = ApiAzar.entero(7, 0, "bench", 3, 4, 10);
            return v == refE && v >= 0 && v < 10;
        }, "ApiAzar"));
    }

    // ================================================================== planetas (P1, ApiPlaneta)

    private static void planetas(List<Bancos.Caso> out){
        final PlanetasMundo avance = new PlanetasMundo();
        final PerfilMundo perfil = new PerfilMundo();
        out.add(new Bancos.Caso("planetas", "avance-tick", 200000, () -> {
            avance.avanzar(1f, perfil);
            final double f = avance.fase(ApiPlaneta.SERPULO);
            return f >= 0.0 && f < 1.0;
        }, "ApiPlaneta"));
        // Criterio de P1: dos vueltas completas dan la misma posición.
        out.add(new Bancos.Caso("planetas", "dos-vueltas", 20000, () -> {
            final PlanetasMundo q = new PlanetasMundo();
            final float o = (float)ApiPlaneta.orbitaEn(perfil, ApiPlaneta.SERPULO);
            q.avanzar(o, perfil);
            q.avanzar(o, perfil);
            final double f = q.fase(ApiPlaneta.SERPULO);
            return f < 1e-6 || f > 1.0 - 1e-6;
        }, "ApiPlaneta"));
        out.add(new Bancos.Caso("planetas", "listar", 20000, () -> {
            boolean serpulo = false;
            for(ApiPlaneta.Ficha f : ApiPlaneta.listar()){
                if(ApiPlaneta.SERPULO.equals(f.tag) && "Serpulo".equals(f.nombre)) serpulo = true;
            }
            return serpulo;
        }, "ApiPlaneta"));
    }

    // ================================================================== tiempo y calendario (ApiTiempo)

    private static void tiempo(List<Bancos.Caso> out){
        // Un evento de cada uno de 100 programados por vuelta: todos deben dispararse exactamente una vez, en orden.
        final int[] disparos = new int[1];
        CalendarioMundo.registrarAccion("bench:evento", e -> disparos[0]++);
        out.add(new Bancos.Caso("tiempo", "calendario-eventos", 2000, () -> {
            final CalendarioMundo c = new CalendarioMundo();
            final int antes = disparos[0];
            for(int i = 1; i <= 100; i++) c.programar("bench:evento", i * 10f);
            c.avanzar(1000f);
            return disparos[0] - antes == 100 && c.pendientes() == 0;
        }, "ApiTiempo"));
        out.add(new Bancos.Caso("tiempo", "consulta-sin-mundo", 1000000, () ->
            ApiTiempo.dia() == 0 && "primavera".equals(ApiTiempo.temporada()) && ApiTiempo.tiempoMundo() == 0f, "ApiTiempo"));
    }

    // ================================================================== ajustes (ApiAjustes)

    private static void ajustes(List<Bancos.Caso> out){
        // Sin mundo abierto, la órbita de Serpulo es su valor por defecto.
        out.add(new Bancos.Caso("ajustes", "valor-por-defecto", 1000000,
            () -> ApiAjustes.valor("mundo-infinito:planeta-serpulo-orbita") == 39600.0, "ApiAjustes"));
    }

    // ================================================================== modpack (ApiModpack)

    private static void modpack(List<Bancos.Caso> out){
        // Un id desconocido se responde igual en cada consulta (determinismo). La semántica completa se prueba en Corpus comprobar.
        out.add(new Bancos.Caso("modpack", "consulta-determinista", 1000000,
            () -> ApiModpack.activo("bench-inexistente") == ApiModpack.activo("bench-inexistente"), "ApiModpack"));
    }

    // ================================================================== versión (ApiVersion)

    private static void version(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("version", "compatibilidad", 1000000,
            () -> ApiVersion.texto().startsWith("api ") && ApiVersion.compatible(1, 0, 1, 0) && !ApiVersion.compatible(2, 0, 1, 0), "ApiVersion"));
    }

    // ================================================================== árbol tecnológico (ApiArbol)

    private static void arbol(List<Bancos.Caso> out){
        // Añade un nodo nuevo y lo localiza. El árbol de Mindustry está vacío en la JVM del banco; el modelo de addons sí se ejercita.
        final int[] n = new int[1];
        out.add(new Bancos.Caso("arbol", "anadir-y-localizar", 2000, () -> {
            final int k = n[0]++;
            final String id = "bench-arbol:n" + k;
            return ApiArbol.anadir("bench-arbol", ApiPlaneta.SERPULO, id, null, (ItemStack[])null)
                && ApiArbol.nodo(ApiPlaneta.SERPULO + "/" + id) != null;
        }, "ApiArbol"));
    }
}
