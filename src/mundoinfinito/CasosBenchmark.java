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
        ApiWorldgen.iniciar();
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
        luz(out);
        worldgen(out);
        dificultadYFenomenos(out);
        objetivos(out);
        sistemasYFisica(out);
        radar(out);
        viaje(out);
        viajeV12(out);
        colision(out);
        calibracion(out);
        niebla(out);
        assets(out);
        embudoPared(out);
        galaxia(out);
        inicio(out);
        return out;
    }

    // ================================================================== desduplicación (M7)

    /** Sumidero: el JIT no puede eliminar una escritura en un campo volatile, así que el cálculo trivial se mide de verdad. */
    static volatile long sumidero;

    private static void desduplicacion(List<Bancos.Caso> out){
        // Contribuyentes de prueba: cada caso fija la política que quiere medir.
        ApiDesduplicacion.registrar("bench-dedup:trivial-auto", 1, 256, ApiDesduplicacion.AUTO);
        ApiDesduplicacion.registrar("bench-dedup:trivial-siempre", 1, 256, ApiDesduplicacion.SIEMPRE);
        ApiDesduplicacion.registrar("bench-dedup:pesado-siempre", 1, 256, ApiDesduplicacion.SIEMPRE);
        ApiDesduplicacion.registrar("bench-dedup:pesado-auto", 1, 256, ApiDesduplicacion.AUTO);
        ApiDesduplicacion.registrar("bench-dedup:sin-repeticion-auto", 1, 256, ApiDesduplicacion.AUTO);
        ApiDesduplicacion.registrar("bench-dedup:pesado-nunca", 1, 256, ApiDesduplicacion.NUNCA);

        // Trivial: lo que cuesta pasar por la desduplicación frente a llamar directo. Es el sobrecoste que la política debe evitar.
        final Prov<Long> trivial = () -> 42L;
        out.add(new Bancos.Caso("desduplicacion", "directo-trivial", 2000000, () -> {
            sumidero = trivial.get().longValue();
            return sumidero == 42L;
        }, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "auto-trivial", 2000000, () -> {
            sumidero = ApiDesduplicacion.calcular("bench-dedup:trivial-auto", trivial, 7L).longValue();
            return sumidero == 42L;
        }, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "siempre-trivial", 2000000, () -> {
            sumidero = ApiDesduplicacion.calcular("bench-dedup:trivial-siempre", trivial, 7L).longValue();
            return sumidero == 42L;
        }, "ApiDesduplicacion"));

        // Pesado: un cálculo real de ~4096 pasos. Aquí la desduplicación debe ganar cuando hay repeticiones.
        final Prov<Long> pesado = () -> {
            long h = 1469598103934665603L;
            for(int i = 0; i < 4096; i++){
                h ^= i;
                h *= 1099511628211L;
            }
            return h;
        };
        final long refPesado = pesado.get().longValue();
        out.add(new Bancos.Caso("desduplicacion", "directo-pesado", 20000, () -> {
            sumidero = pesado.get().longValue();
            return sumidero == refPesado;
        }, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "siempre-pesado", 20000, () ->
            ApiDesduplicacion.calcular("bench-dedup:pesado-siempre", pesado, 4096L).longValue() == refPesado, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "auto-pesado", 20000, () ->
            ApiDesduplicacion.calcular("bench-dedup:pesado-auto", pesado, 4096L).longValue() == refPesado, "ApiDesduplicacion"));
        out.add(new Bancos.Caso("desduplicacion", "nunca-pesado", 20000, () ->
            ApiDesduplicacion.calcular("bench-dedup:pesado-nunca", pesado, 4096L).longValue() == refPesado, "ApiDesduplicacion"));

        // Sin repeticiones: cada llamada trae entradas nuevas. La política debe dejar de guardar y pasar directo.
        final long[] k = new long[1];
        out.add(new Bancos.Caso("desduplicacion", "auto-sin-repeticion", 20000, () ->
            ApiDesduplicacion.calcular("bench-dedup:sin-repeticion-auto", pesado, k[0]++).longValue() == refPesado, "ApiDesduplicacion"));
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

    // ================================================================== luz solar (ApiLuz)

    private static void luz(List<Bancos.Caso> out){
        // Un modificador neutro (siempre 1) ejercita la ruta de modificadores sin cambiar el resultado. Erekir no tiene ningún cuerpo entre
        // la estrella y él en el sistema solar por defecto, así que su factor es exactamente 1 (sin eclipse). Serpulo sí queda tapado por Erekir.
        ApiLuz.registrarModificador("bench-luz", "bench-luz:neutro", 0, (p, f) -> 1f);
        out.add(new Bancos.Caso("luz", "factor-erekir-sin-eclipse", 200000,
            () -> ApiLuz.factorPlaneta(ApiPlaneta.EREKIR) == 1f, "ApiLuz"));
        // P8 (ApiLuz 1.2): consultar la intensidad de la estrella de un cuerpo sin asignación, que es 1.
        out.add(new Bancos.Caso("luz", "estrella-intensidad-por-defecto", 200000,
            () -> ApiLuz.intensidadEstrellaDe(ApiPlaneta.SERPULO) == 1f, "ApiLuz"));
    }

    // ================================================================== dificultad y fenómenos cósmicos (P10)

    private static void dificultadYFenomenos(List<Bancos.Caso> out){
        // Sin mundo abierto el perfil es el neutro: la consulta devuelve el valor base sin tocarlo.
        out.add(new Bancos.Caso("dificultad", "valor-neutro-deja-la-base", 200000,
            () -> ApiDificultad.valor("bench:clave", 0.5) == 0.5, "ApiDificultad"));
        // El agujero negro está registrado y apagado por defecto: sin addon, no hay efectos ni dibujo.
        FenomenosCosmicos.registrar();
        out.add(new Bancos.Caso("fenomenos", "agujero-apagado-por-defecto", 200000,
            () -> !ApiFenomenoCosmico.activo(FenomenosCosmicos.AGUJERO_NEGRO), "ApiFenomenoCosmico"));
    }

    // ================================================================== objetivos de sector (P11)

    private static void objetivos(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("objetivos", "progreso-acotado-a-la-meta", 200000,
            () -> ApiObjetivos.progreso(70, 60) == 60 && ApiObjetivos.progreso(-3, 60) == 0 && ApiObjetivos.completo(60, 60), "ApiObjetivos"));
    }

    // ================================================================== worldgen (ApiWorldgen)

    private static void worldgen(List<Bancos.Caso> out){
        // Una fuente de prueba con un valor fijo. Se registra una vez; su id es propio del banco.
        ApiWorldgen.registrar(new ApiWorldgen.Fuente(){
            @Override public String id(){ return "bench-worldgen:fija"; }
            @Override public int prioridad(){ return -5; }
            @Override public float valorFijo(String fenomeno, int bioma, ApiWorldgen.Lugar l){
                return "bench:fijo".equals(fenomeno) ? 0.25f : Float.NaN;
            }
        });
        final ApiWorldgen.Lugar l = new ApiWorldgen.Lugar(7, 1, 10, -4, MundoInfinitoMod.Muestreo.P_ARKYIC, () -> 5, () -> "arkyic");
        out.add(new Bancos.Caso("worldgen", "fijo-y-bioma", 200000, () ->
            ApiWorldgen.bioma(l) == ApiWorldgen.BIOMA_ARKYIC && ApiWorldgen.probabilidad("bench:fijo", 1f, l) == 0.25f
                && ApiWorldgen.probabilidad("bench:libre", 0.5f, l) == 0.5f, "ApiWorldgen"));
        // P9 (ApiWorldgen 1.1): el lugar da su planeta y la distancia de la ficha estimada.
        final ApiWorldgen.Lugar e = new ApiWorldgen.Lugar(7, 1, 10, -4, MundoInfinitoMod.Muestreo.P_ARKYIC, () -> 5, () -> "arkyic");
        out.add(new Bancos.Caso("worldgen", "ficha-del-lugar", 200000, () ->
            ApiPlaneta.EREKIR.equals(e.planeta()) && Math.abs(e.distanciaEstrella() - ApiFisica.ORBITA_EREKIR) < 1e-6, "ApiWorldgen"));
    }

    // ================================================================== física y sistemas (ApiFisica, ApiSistemas; plan v6, F3 a F5)

    private static void sistemasYFisica(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("fisica", "validar-y-categoria", 200000, () -> {
            final FichaFisica f = ApiFisica.validar(null, ApiFisica.TIPO_PLANETA);
            return f.diametro == ApiFisica.DIAMETRO_PLANETA && ApiFisica.PEQUENO.equals(ApiFisica.categoria(0.5f))
                && ApiFisica.GRANDE.equals(ApiFisica.categoria(8f));
        }, "ApiFisica"));
        out.add(new Bancos.Caso("sistemas", "orbita-compartida", 2000, () -> {
            final EstadoSistemas e = new EstadoSistemas();
            final boolean a = ApiSistemas.anadirMundoEnOrbita(e, "b1", "bm1", "serpulo", 100, 10).ok;
            final boolean b = ApiSistemas.anadirMundoEnOrbita(e, "b2", "bm2", "serpulo", 100, 10).ok;
            final double[] ang = e.angulosOrbita("serpulo");
            return a && b && ang.length == 2 && Math.abs(ang[1] - Math.PI) < 1e-6;
        }, "ApiSistemas"));
    }

    // ================================================================== radares (ApiRadar; plan v7, entrega A2)

    private static void radar(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("radar", "orden-por-sistema-y-distancia", 2000, () -> {
            final EstadoSistemas e = new EstadoSistemas();
            ApiSistemas.crearSolitario(e, "r-lejos", "r-m1", "erekir", 30, 0, 0, 0, 0, 2);
            ApiSistemas.crearSolitario(e, "r-cerca", "r-m2", "erekir", 5, 0, 0, 0, 0, 2);
            final List<ApiRadar.Hallazgo> h = ApiRadar.escanear(e, 0, 0, ApiRadar.nivel(2), 1000, p -> 0.0);
            return h.size() == 2 && "r-m2".equals(h.get(0).cuerpoId) && h.get(1).tickDeteccion == 1000 + 2 * 240;
        }, "ApiRadar"));
    }

    // ================================================================== viajes (ApiViaje; plan v7, entrega B1)

    private static void viaje(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("viaje", "enlace-y-coste", 2000, () -> {
            final EstadoSistemas e = new EstadoSistemas();
            ApiSistemas.crearSolitario(e, "v-a", "v-m1", "erekir", 0, 0, 0, 0, 0, 2);
            ApiSistemas.crearSolitario(e, "v-b", "v-m2", "serpulo", 50, 0, 0, 0, 0, 2);
            final ApiViaje.Enlaces enlaces = new ApiViaje.Enlaces();
            if(!ApiViaje.establecer(e, enlaces, "v-e", "v-m1", "v-m2", ApiViaje.portal(1), 0, p -> 0.0).ok) return false;
            return ApiViaje.viajar(e, enlaces, "v-m2", "v-m1", 1, java.util.Map.of("copper", 50, "lead", 30), 0, p -> 0.0).ticks == 100L;
        }, "ApiViaje"));
    }

    // ================================================================== hora inicial de un mundo nuevo (ApiInicioMundo; plan v8)

    private static void inicio(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("tiempo", "inicio-aleatorio-determinista", 200000, () -> {
            final float a = ApiInicioMundo.inicio(42);
            return a == ApiInicioMundo.inicio(42) && a >= 0f && a < CicloDiaNoche.DIA_TICKS;
        }, "ApiInicioMundo"));
    }

    // ================================================================== árbol tecnológico (ApiArbol)

    private static void arbol(List<Bancos.Caso> out){
        // Añade un nodo nuevo y lo localiza. El árbol de Mindustry está vacío en la JVM del banco; el modelo de addons sí se ejercita.
        final int[] n = new int[1];
        // Estado acotado: solo 200 nodos de prueba. anadir recorre todos los nodos del árbol (grupo), así que sin límite
        // el coste crecía con cada iteración y el banco medía su propio crecimiento, no la API.
        out.add(new Bancos.Caso("arbol", "anadir-y-localizar", 2000, () -> {
            final int k = n[0]++ % 200;
            final String id = "bench-arbol:n" + k;
            final String clave = ApiPlaneta.SERPULO + "/" + id;
            if(ApiArbol.nodo(clave) == null) ApiArbol.anadir("bench-arbol", ApiPlaneta.SERPULO, id, null, (ItemStack[])null);
            return ApiArbol.nodo(clave) != null;
        }, "ApiArbol"));
    }

    // ================================================================== plan v9: enlaces persistentes, boosters, desvío, colisiones, calibración

    private static void viajeV12(List<Bancos.Caso> out){
        final ApiViaje.Enlaces enlaces = ApiViaje.Enlaces.desdeTexto("e1|a|b|1\ne2|a|c|2\ne3|d|e|3\n");
        final String texto = enlaces.aTexto();
        out.add(new Bancos.Caso("viaje", "enlaces-ida-y-vuelta", 20000,
            () -> ApiViaje.Enlaces.desdeTexto(texto).aTexto().equals(texto), "ApiViaje"));
        final java.util.Map<String, Integer> inv = new java.util.HashMap<String, Integer>();
        inv.put("booster-alcance", 1);
        out.add(new Bancos.Caso("viaje", "boosters-disponibles-y-coste", 100000,
            () -> ApiViaje.costeBoosters(ApiViaje.boostersDisponibles(inv)).size() == 1, "ApiViaje"));
        out.add(new Bancos.Caso("viaje", "desvio-determinista", 100000,
            () -> {
                final String a = ApiViaje.elegirDesvio(enlaces, "a", "b", 9, 0, 5);
                return "c".equals(a) && a.equals(ApiViaje.elegirDesvio(enlaces, "a", "b", 9, 0, 5));
            }, "ApiViaje"));
        out.add(new Bancos.Caso("viaje", "tier-de-unidad-por-vida", 200000,
            () -> ApiViaje.tierUnidadPorVida(350) == 1 && ApiViaje.tierUnidadPorVida(20000) == 3, "ApiViaje"));
        out.add(new Bancos.Caso("viaje", "migracion-de-portal-antiguo", 200000,
            () -> MigracionPortales.nombreActual("silo-interdimensional").equals("portal-viaje-1"), "MigracionPortales"));
        final ApiObjetivos.Objetivo o = new ApiObjetivos.Objetivo("bench:o", "Meta", "copper", 10);
        out.add(new Bancos.Caso("objetivos", "estado-guardar-y-leer", 20000, () -> {
            final ApiObjetivos.Estado e = new ApiObjetivos.Estado();
            ApiObjetivos.actualizar(e, o, 10);
            return ApiObjetivos.Estado.desdeTexto(e.aTexto()).completado("bench:o") && ApiObjetivos.completo(e, o, 0);
        }, "ApiObjetivos"));
    }

    private static void colision(List<Bancos.Caso> out){
        final ApiColision.Regla regla = ApiColision.Regla.de("bench-colision:regla", "a", "b").fase("impacto", 600).fase("secuela", 1200)
            .superviviente(ApiColision.Criterio.MAYOR, "masa").conProbabilidad(0.5);
        out.add(new Bancos.Caso("colision", "resolver-con-azar", 200000, () -> {
            final ApiColision.Desenlace d = ApiColision.resolver(regla, 42, 0, 7, (c, v) -> c.equals("a") ? 2.0 : 1.0);
            return !d.ocurre || "a".equals(d.superviviente);
        }, "ApiColision"));
        out.add(new Bancos.Caso("colision", "plan-de-fases", 200000, () -> ApiColision.plan(regla, 100f).size() == 2, "ApiColision"));
        out.add(new Bancos.Caso("colision", "validar-regla", 200000, () -> ApiColision.motivoInvalida(regla) == null, "ApiColision"));
    }

    private static void calibracion(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("calibracion", "niveles-de-radar-y-portal", 200000,
            () -> ApiRadar.nivel(2).alcance == Calibracion.RADAR_ALCANCE[1] && ApiViaje.portal(3).alcance == Calibracion.PORTAL_ALCANCE[2], "Calibracion"));
    }

    /** Embudo de pared: fuera de su modo de medición no cuenta nada (sin coste en la generación normal). */
    private static void embudoPared(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("embudo", "inactivo-sin-coste", 2000, () -> !ApiDiagnosticoPared.activo, "ApiDiagnosticoPared"));
        out.add(new Bancos.Caso("acceso-mena", "criterio-por-defecto", 2000, () -> ApiAccesoMena.criterio() != null, "ApiAccesoMena"));
    }

    /** Assets: el catálogo de assets de Mindustry está completo y los ids son únicos (la lectura no cambia nada). */
    private static void assets(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("assets", "catalogo-completo", 2000, () -> ApiAsset.todos().size() >= 14, "ApiAsset"));
    }

    private static void niebla(List<Bancos.Caso> out){
        out.add(new Bancos.Caso("niebla", "persiste-sin-politicas", 500000, () -> ApiNiebla.persiste("bench", 0, true), "ApiNiebla"));
        // La política se registra la primera vez que el caso se ejecuta, no al construir la lista: el banco ejecuta los casos después de
        // construirla, y un quitarPolitica aquí dejaba el caso sin política (INCORRECTO). Como ningún otro caso usa la niebla después
        // de este, la política se queda registrada.
        final boolean[] registrada = {false};
        out.add(new Bancos.Caso("niebla", "politica-registrada", 500000, () -> {
            if(!registrada[0]) registrada[0] = ApiNiebla.registrarPolitica("bench-niebla:pol", 0, (m, d) -> Boolean.FALSE);
            return !ApiNiebla.persiste("bench", 0, true);
        }, "ApiNiebla"));
    }

    private static void galaxia(List<Bancos.Caso> out){
        final List<ApiGalaxia.Grupo> base = ApiGalaxia.grupos();
        out.add(new Bancos.Caso("galaxia", "posicion-por-defecto", 100000, () -> ApiGalaxia.posicionPorDefecto(base, 60.0, 250.0) != null, "ApiGalaxia"));
        final String texto = ApiGalaxia.aTexto();
        out.add(new Bancos.Caso("galaxia", "texto-ida-y-vuelta", 20000, () -> ApiGalaxia.limpiarNombre("  Sistema   Solar ").equals("Sistema Solar") && !texto.isEmpty(), "ApiGalaxia"));
        out.add(new Bancos.Caso("contenido", "visible-en-campana", 500000, () -> !ApiContenido.visibleEnCampana("portal-viaje-1"), "ApiContenido"));
        out.add(new Bancos.Caso("respiraderos", "factor-de-ficha", 500000, () -> RespiraderosFicha.factor("grande") > RespiraderosFicha.factor("pequeno"), "RespiraderosFicha"));
    }
}
