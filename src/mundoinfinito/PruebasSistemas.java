package mundoinfinito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PRUEBAS DE SISTEMAS (plan v6, entrega A: F2 a F5). Sin el juego: solo EstadoSistemas, FichaFisica, ApiFisica y ApiSistemas.
 *
 * Cubren los criterios de CI: F2 (migración de mundos sin sección y round trip idéntico), F3 (fichas repetibles; una inválida cae a la
 * estimada), F4 (segundo mundo en el lado opuesto, tercero en el mayor hueco, capacidad agotada con su código, solitario que ocupa
 * una posición) y F5 (posición lineal y repetible; sin velocidad no se mueve). Además: una política que falla se aísla, y una que
 * devuelve un ángulo inválido se rechaza. Salida 0 si todo pasa; 1 si no; 3 si hay un error.
 */
final class PruebasSistemas{
    private static int fallos;
    private static final double PI = Math.PI, EPS = 1e-6;

    private PruebasSistemas(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaCapacidad();
            pruebaPoliticaDefecto();
            pruebaDosMundosOpuestos();
            pruebaTercerMundoEnHuecoMayor();
            pruebaInvariantesOrbita();
            pruebaCapacidadAgotada();
            pruebaSolitario();
            pruebaMovimiento();
            pruebaFichas();
            pruebaMigracion();
            pruebaRoundTrip();
            pruebaHexadecimal();
            pruebaInicio();
            pruebaAsignacion();
            pruebaAsignacionMigracion();
            pruebaAsignacionSolitario();
            pruebaPoliticaFallida();
            pruebaConsecuenciaOrbita();
            ApiSistemas.reiniciarPoliticas();
            System.out.println(fallos == 0 ? "[Sist] OK: todas las pruebas pasan." : "[Sist] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static int chequear(String nombre, boolean ok){
        System.out.println((ok ? "[Sist] ok: " : "[Sist] FALLO: ") + nombre);
        return ok ? 0 : 1;
    }

    private static boolean cerca(double a, double b){ return Math.abs(a - b) < EPS; }

    /** Separación angular mínima entre todos los pares de una lista de ángulos. Menos de dos ángulos: 2π. */
    private static double separacionEntreTodos(double[] a){
        double min = 2 * PI;
        for(int i = 0; i < a.length; i++){
            for(int j = i + 1; j < a.length; j++){
                final double d = Math.abs(ApiSistemas.normalizar(a[i] - a[j]));
                min = Math.min(min, Math.min(d, 2 * PI - d));
            }
        }
        return min;
    }

    // ================================================================== F4: capacidad y política

    private static void pruebaCapacidad(){
        fallos += chequear("capacidad floor(2πr/(d+m)): r=100, d=10 -> 62", ApiSistemas.capacidad(100, 10, 0) == 62);
        fallos += chequear("capacidad: r=20, d=10 -> 12", ApiSistemas.capacidad(20, 10, 0) == 12);
        fallos += chequear("capacidad 0 con datos no válidos",
            ApiSistemas.capacidad(0, 10, 0) == 0 && ApiSistemas.capacidad(10, Double.NaN, 0) == 0 && ApiSistemas.capacidad(10, 10, -10) == 0);
    }

    private static void pruebaPoliticaDefecto(){
        fallos += chequear("política: sin ocupados, el primer slot está en 0",
            cerca(ApiSistemas.politicaPorDefecto(new double[]{}, 100, 10, 0), 0));
        fallos += chequear("política: con un ocupado en 0, el opuesto (π)",
            cerca(ApiSistemas.politicaPorDefecto(new double[]{0}, 100, 10, 0), PI));
        fallos += chequear("política: con {0, π}, el centro del primer hueco (π/2)",
            cerca(ApiSistemas.politicaPorDefecto(new double[]{0, PI}, 100, 10, 0), PI / 2));
        fallos += chequear("política: con {0, π/2, π}, el hueco mayor está entre π y 2π (3π/2)",
            cerca(ApiSistemas.politicaPorDefecto(new double[]{0, PI / 2, PI}, 100, 10, 0), 3 * PI / 2));
        final double[] lleno = new double[12];
        for(int i = 0; i < lleno.length; i++) lleno[i] = i * 2 * PI / 12;
        fallos += chequear("política: 12 slots equiespaciados a radio 20 no dejan hueco con dos separaciones -> NaN",
            Double.isNaN(ApiSistemas.politicaPorDefecto(lleno, 20, 10, 0)));
    }

    private static void pruebaDosMundosOpuestos(){
        final EstadoSistemas e = new EstadoSistemas();
        final ApiSistemas.Resultado r1 = ApiSistemas.anadirMundoEnOrbita(e, "sol-1", "mundo-1", "serpulo", 100, 10);
        final ApiSistemas.Resultado r2 = ApiSistemas.anadirMundoEnOrbita(e, "sol-2", "mundo-2", "serpulo", 100, 10);
        final double[] ang = e.angulosOrbita("serpulo");
        fallos += chequear("dos mundos del mismo tipo comparten órbita: el primero en 0 y el segundo en π",
            r1.ok && r2.ok && ang.length == 2 && cerca(ang[0], 0) && cerca(ang[1], PI));
        fallos += chequear("el segundo mundo usa el radio de la órbita que fijó el primero",
            cerca(e.buscarCuerpo("mundo-2").radioOrbita, 100));
    }

    private static void pruebaTercerMundoEnHuecoMayor(){
        final EstadoSistemas e = new EstadoSistemas();
        boolean ok = true;
        ok &= ApiSistemas.anadirMundoEnOrbita(e, "sol-1", "mundo-1", "serpulo", 100, 10).ok;
        ok &= ApiSistemas.anadirMundoEnOrbita(e, "sol-2", "mundo-2", "serpulo", 100, 10).ok;
        ok &= ApiSistemas.anadirMundoEnOrbita(e, "sol-3", "mundo-3", "serpulo", 100, 10).ok;
        ok &= ApiSistemas.anadirMundoEnOrbita(e, "sol-4", "mundo-4", "serpulo", 100, 10).ok;
        final double[] ang = e.angulosOrbita("serpulo");
        fallos += chequear("el tercero queda en π/2 y el cuarto en 3π/2 (centro del mayor hueco)",
            ok && ang.length == 4 && cerca(ang[1], PI / 2) && cerca(ang[3], 3 * PI / 2));
        final ApiSistemas.Resultado repetido = ApiSistemas.anadirMundoEnOrbita(e, "sol-x", "mundo-1", "serpulo", 100, 10);
        fallos += chequear("un id de mundo repetido se rechaza con ID_REPETIDO",
            !repetido.ok && repetido.motivo == ApiSistemas.Motivo.ID_REPETIDO);
    }

    /** Con cualquier cantidad de intentos, los cuerpos de una órbita nunca quedan más cerca que la separación mínima ni superan la capacidad. */
    private static void pruebaInvariantesOrbita(){
        boolean ok = true;
        final double[][] configs = {{20, 10}, {100, 10}, {50, 4}};
        for(double[] c : configs){
            final EstadoSistemas e = new EstadoSistemas();
            final int cap = ApiSistemas.capacidad(c[0], c[1], ApiSistemas.MARGEN_ORBITA);
            for(int i = 0; i < 200; i++){
                ApiSistemas.anadirMundoEnOrbita(e, "inv-" + i, "inv-m" + i, "serpulo", c[0], c[1]);
            }
            final double[] ang = e.angulosOrbita("serpulo");
            final double minSep = (c[1] + ApiSistemas.MARGEN_ORBITA) / c[0];
            ok &= ang.length <= cap;
            ok &= ang.length == 0 || separacionEntreTodos(ang) >= minSep - EPS;
        }
        fallos += chequear("invariante: nunca se superan la capacidad ni la separación mínima entre cuerpos de una órbita", ok);
    }

    private static void pruebaCapacidadAgotada(){
        // Política de prueba: slots equiespaciados. Siempre cumple la separación, así que solo la capacidad puede detener la creación.
        ApiSistemas.reiniciarPoliticas();
        ApiSistemas.proponerPolitica("prueba-equiespaciada", 100,
            (ocupados, radio, diametro, margen) -> ocupados.length * 2 * PI / ApiSistemas.capacidad(radio, diametro, margen));
        final EstadoSistemas e = new EstadoSistemas();
        int aceptados = 0;
        ApiSistemas.Resultado ultimo = null;
        for(int i = 0; i < 30; i++){
            final ApiSistemas.Resultado r = ApiSistemas.anadirMundoEnOrbita(e, "cap-" + i, "cap-m" + i, "serpulo", 20, 10);
            if(r.ok) aceptados++;
            else{
                ultimo = r;
                break;
            }
        }
        final int cap = ApiSistemas.capacidad(20, 10, ApiSistemas.MARGEN_ORBITA);
        fallos += chequear("capacidad agotada: se aceptan exactamente " + cap + " mundos y el siguiente se rechaza con CAPACIDAD_AGOTADA",
            aceptados == cap && ultimo != null && ultimo.motivo == ApiSistemas.Motivo.CAPACIDAD_AGOTADA);
        fallos += chequear("el rechazo por capacidad lleva el mensaje de crear otro sistema solar",
            ultimo != null && ultimo.mensaje.contains("crea otro sistema solar"));
        ApiSistemas.reiniciarPoliticas();
    }

    // ================================================================== F4: planetas solitarios

    private static void pruebaSolitario(){
        final EstadoSistemas e = new EstadoSistemas();
        final ApiSistemas.Resultado r = ApiSistemas.crearSolitario(e, "solo-1", "mundo-solo", "erekir", 50, -20, 0, 0, 0, 10);
        boolean ok = r.ok && e.sistemas().size() == 1 && r.sistema.estrella == null && r.sistema.cuerpos().size() == 1;
        ok &= e.angulosOrbita("erekir").length == 0;
        ok &= e.buscarCuerpo("mundo-solo").slot == -1;
        fallos += chequear("un planeta solitario es un sistema de un cuerpo, sin estrella, y no ocupa órbita compartida", ok);

        final ApiSistemas.Resultado enOrbita = ApiSistemas.anadirMundoEnOrbita(e, "sol-e", "mundo-e", "erekir", 100, 10);
        fallos += chequear("una órbita compartida posterior empieza en 0 sin que el solitario la ocupe",
            enOrbita.ok && cerca(e.buscarCuerpo("mundo-e").angulo, 0));

        final ApiSistemas.Resultado mezcla = ApiSistemas.anadirMundoEnOrbita(e, "solo-1", "mundo-x", "erekir", 100, 10);
        fallos += chequear("no se añade un mundo en órbita a un sistema solitario",
            !mezcla.ok && mezcla.motivo == ApiSistemas.Motivo.DATOS_INVALIDOS);
    }

    // ================================================================== F5: movimiento

    private static void pruebaMovimiento(){
        ApiSistemas.reiniciarDeriva();
        final EstadoSistemas.SistemaSolar s = new EstadoSistemas.SistemaSolar("mov", null, 1, 2, 3, -1, 5);
        final double[] sinDeriva = ApiSistemas.posicion(s, 7);
        fallos += chequear("por defecto no hay deriva: el sistema queda en p0 aunque tenga velocidad",
            !ApiSistemas.derivaActiva() && cerca(sinDeriva[0], 1) && cerca(sinDeriva[1], 2));

        ApiSistemas.activarDeriva("prueba");
        final double[] a = ApiSistemas.posicion(s, 5);
        final double[] b = ApiSistemas.posicion(s, 7);
        final double[] c = ApiSistemas.posicion(s, 7);
        fallos += chequear("con deriva activa: p0 + v·(t − t0), en t0 es p0 y en t0+2 es (7, 0), repetible",
            cerca(a[0], 1) && cerca(a[1], 2) && cerca(b[0], 7) && cerca(b[1], 0) && b[0] == c[0] && b[1] == c[1]);
        ApiSistemas.desactivarDeriva("prueba");
        fallos += chequear("al quitar la última activación la deriva vuelve a apagarse", !ApiSistemas.derivaActiva());

        final EstadoSistemas.SistemaSolar quieto = new EstadoSistemas.SistemaSolar("quieto", null, 4, 4, 0, 0, 0);
        ApiSistemas.activarDeriva("prueba");
        final double[] q = ApiSistemas.posicion(quieto, 1e6);
        fallos += chequear("con deriva activa, un sistema sin velocidad no se mueve", cerca(q[0], 4) && cerca(q[1], 4));
        fallos += chequear("un tiempo no finito devuelve la posición de referencia", ApiSistemas.posicion(s, Double.NaN)[0] == 1);
        ApiSistemas.reiniciarDeriva();

        final EstadoSistemas.Cuerpo cuerpo = new EstadoSistemas.Cuerpo("o", ApiFisica.TIPO_PLANETA, "serpulo", "o", "serpulo", 0, 30, 0.5,
            ApiFisica.estimadaPlaneta("serpulo", 2f));
        fallos += chequear("órbita simulada: el ángulo avanza 2π·t/periodo sobre el ángulo inicial (0.5 + π/2 a un cuarto de vuelta)",
            cerca(ApiSistemas.anguloEn(cuerpo, 25, 100), 0.5 + PI / 2));
        fallos += chequear("órbita simulada: sin periodo válido, el ángulo queda fijo", cerca(ApiSistemas.anguloEn(cuerpo, 25, 0), 0.5));
        fallos += chequear("rotación simulada: media vuelta en la mitad del periodo", cerca(ApiSistemas.rotacionEn(50, 100), PI));
    }

    // ================================================================== F3: fichas

    private static void pruebaFichas(){
        final FichaFisica sinFicha = ApiFisica.validar(null, ApiFisica.TIPO_PLANETA);
        fallos += chequear("ficha ausente cae a la estimada del tipo (planeta = su diámetro de referencia)",
            sinFicha.diametro == ApiFisica.DIAMETRO_PLANETA && FichaFisica.ORIGEN_ESTIMADA.equals(sinFicha.origen));

        final FichaFisica mala = new FichaFisica(Float.NaN, 5f, 5f, FichaFisica.ORIGEN_DECLARADA, null);
        final FichaFisica cae = ApiFisica.validar(mala, ApiFisica.TIPO_PLANETA);
        fallos += chequear("ficha con diámetro NaN cae a la estimada", ApiFisica.valida(cae) && cae.diametro == ApiFisica.DIAMETRO_PLANETA);

        final FichaFisica distNegativa = new FichaFisica(10f, 10f, 10f, FichaFisica.ORIGEN_DECLARADA, new double[]{-1, 0, 0});
        fallos += chequear("ficha con una distancia negativa no es válida", !ApiFisica.valida(distNegativa));

        final FichaFisica buena = new FichaFisica(20f, 20f, 18f, FichaFisica.ORIGEN_DECLARADA, new double[]{30, 4, 0});
        fallos += chequear("una ficha válida se conserva tal cual", ApiFisica.validar(buena, ApiFisica.TIPO_PLANETA) == buena);
        fallos += chequear("distancias: la de estrella es 30 y la de sistemas 0",
            cerca(buena.distancia(FichaFisica.DIST_ESTRELLA), 30) && cerca(buena.distancia(FichaFisica.DIST_SISTEMAS), 0));

        fallos += chequear("categorías (umbrales 1.5 y 4): 0.5 pequeño, 2 medio, 8 grande",
            ApiFisica.PEQUENO.equals(ApiFisica.categoria(0.5f)) && ApiFisica.MEDIO.equals(ApiFisica.categoria(2f))
                && ApiFisica.GRANDE.equals(ApiFisica.categoria(8f)) && "".equals(ApiFisica.categoria(Float.NaN)));
        fallos += chequear("estimación de Mindustry: planeta de diámetro 2, sol de diámetro 8",
            ApiFisica.DIAMETRO_PLANETA == 2f && ApiFisica.DIAMETRO_ESTRELLA == 8f);
        fallos += chequear("órbitas estimadas (campaign de Mindustry): Erekir 17 y Serpulo 57,24; otro planeta sin dato (0)",
            cerca(ApiFisica.estimadaPlaneta("erekir", 2f).distancia(FichaFisica.DIST_ESTRELLA), 17)
                && cerca(ApiFisica.estimadaPlaneta("serpulo", 2f).distancia(FichaFisica.DIST_ESTRELLA), 57.24)
                && cerca(ApiFisica.estimadaPlaneta("otro", 2f).distancia(FichaFisica.DIST_ESTRELLA), 0));

        final FichaFisica f1 = ApiFisica.estimada(ApiFisica.TIPO_PLANETA, 12f), f2 = ApiFisica.estimada(ApiFisica.TIPO_PLANETA, 12f);
        fallos += chequear("la misma entrada da la misma ficha estimada (determinista)",
            f1.diametro == f2.diametro && f1.anchura == f2.altura && f1.tamano.equals(f2.tamano));
    }

    // ================================================================== F2: migración y round trip

    private static void pruebaMigracion(){
        fallos += chequear("un mundo sin sección de sistemas (null o vacía) se lee como estado vacío",
            EstadoSistemas.leer(null).sistemas().isEmpty() && EstadoSistemas.leer(new byte[0]).sistemas().isEmpty());
        fallos += chequear("datos dañados se leen como estado vacío", EstadoSistemas.leer(new byte[]{1, 2, 3}).sistemas().isEmpty());
        boolean rechazo = false;
        try{
            EstadoSistemas.leerEstricto(new byte[]{0, 0, 0, 0, 1, 0, 0, 0, 0});
        }catch(IOException ex){
            rechazo = true;
        }
        fallos += chequear("leerEstricto rechaza una cabecera que no es la nuestra", rechazo);
    }

    private static void pruebaHexadecimal(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e, "h1", "hm1", "serpulo", 100, 10);
        ApiSistemas.crearSolitario(e, "h2", "hm2", "erekir", 3, 4, 0, 0, 0, 2);
        final String hex = e.aHex();
        final EstadoSistemas leido = EstadoSistemas.leer(EstadoSistemas.desdeHex(hex));
        fallos += chequear("hexadecimal: el estado sobrevive a texto y vuelta con los mismos bytes",
            leido.sistemas().size() == 2 && java.util.Arrays.equals(e.serializar(), leido.serializar()));
        fallos += chequear("hexadecimal: texto vacío, impar o no hexadecimal no da bytes; el mundo queda vacío",
            EstadoSistemas.desdeHex("") == null && EstadoSistemas.desdeHex("abc") == null && EstadoSistemas.desdeHex("zz") == null
                && EstadoSistemas.leer(EstadoSistemas.desdeHex("zz")).sistemas().isEmpty());
    }

    private static void pruebaRoundTrip(){
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e, "s1", "m1", "serpulo", 100, 10);
        ApiSistemas.anadirMundoEnOrbita(e, "s2", "m2", "serpulo", 100, 10);
        ApiSistemas.crearSolitario(e, "solo", "m3", "erekir", 1.5, -2.25, 0.5, 0.25, 3, 6);
        final byte[] b1 = e.serializar();
        final EstadoSistemas leido = EstadoSistemas.leer(b1);
        final byte[] b2 = leido.serializar();
        fallos += chequear("round trip: lo leído se vuelve a escribir con los mismos bytes",
            Arrays.equals(b1, b2) && leido.sistemas().size() == 3);
        fallos += chequear("round trip: los datos de cada cuerpo se conservan (ángulo, órbita, solitario sin órbita)",
            cerca(leido.buscarCuerpo("m2").angulo, PI) && "serpulo".equals(leido.buscarCuerpo("m2").orbita)
                && leido.buscarCuerpo("m3").orbita == null && leido.buscarCuerpo("m3").slot == -1);
        fallos += chequear("round trip de un estado vacío da bytes iguales y vacío",
            Arrays.equals(new EstadoSistemas().serializar(), EstadoSistemas.leer(new EstadoSistemas().serializar()).serializar())
                && EstadoSistemas.leer(new EstadoSistemas().serializar()).sistemas().isEmpty());
    }

    // ================================================================== P1: asignación de sistemas a los mundos

    private static AsignacionSistemas.Entrada antiguo(String id, String planeta, long creado){
        return new AsignacionSistemas.Entrada(id, planeta, 1000 + (int)creado, creado, null);
    }

    private static byte[] bytes(AsignacionSistemas.Entrada e){ return e.estado.serializar(); }

    private static void pruebaAsignacion(){
        ApiSistemas.reiniciarPoliticas();
        ApiSistemas.reiniciarConsecuenciasOrbita();
        final List<AsignacionSistemas.Entrada> mundos = new ArrayList<AsignacionSistemas.Entrada>();
        final EstadoSistemas g1 = AsignacionSistemas.unir(mundos);
        final EstadoSistemas e1 = AsignacionSistemas.asignar(g1, "w1", "serpulo", 11, 31, ApiFisica.DIAMETRO_PLANETA);
        fallos += chequear("P1: un mundo nuevo guarda exactamente un cuerpo, de su planeta, en su órbita",
            e1 != null && e1.sistemas().size() == 1 && e1.sistemas().get(0).cuerpos().size() == 1
                && "w1".equals(e1.sistemas().get(0).cuerpos().get(0).id) && "serpulo".equals(e1.buscarCuerpo("w1").orbita)
                && AsignacionSistemas.idSistema("serpulo").equals(e1.sistemas().get(0).id) && cerca(e1.buscarCuerpo("w1").angulo, 0));
        mundos.add(new AsignacionSistemas.Entrada("w1", "serpulo", 11, 1, e1));

        final EstadoSistemas g2 = AsignacionSistemas.unir(mundos);
        final EstadoSistemas e2 = AsignacionSistemas.asignar(g2, "w2", "serpulo", 12, 31, ApiFisica.DIAMETRO_PLANETA);
        fallos += chequear("P1: el segundo mundo del mismo planeta va al lado opuesto de la misma órbita",
            e2 != null && cerca(e2.buscarCuerpo("w2").angulo, PI) && e2.buscarCuerpo("w2").slot == 1 && g2.angulosOrbita("serpulo").length == 2);
        mundos.add(new AsignacionSistemas.Entrada("w2", "serpulo", 12, 2, e2));

        final EstadoSistemas g3 = AsignacionSistemas.unir(mundos);
        final EstadoSistemas e3 = AsignacionSistemas.asignar(g3, "w3", "erekir", 13, 17, ApiFisica.DIAMETRO_PLANETA);
        fallos += chequear("P1: un planeta de otro tipo tiene su propia órbita, con su radio, y empieza en 0",
            e3 != null && "erekir".equals(e3.buscarCuerpo("w3").orbita) && cerca(e3.buscarCuerpo("w3").radioOrbita, 17) && cerca(e3.buscarCuerpo("w3").angulo, 0));
        mundos.add(new AsignacionSistemas.Entrada("w3", "erekir", 13, 3, e3));

        final EstadoSistemas unido = AsignacionSistemas.unir(mundos);
        final EstadoSistemas otraVez = AsignacionSistemas.unir(mundos);
        fallos += chequear("P1: unir los cuerpos guardados da dos sistemas (uno por planeta) y es repetible",
            unido.sistemas().size() == 2 && unido.buscar("sistema-serpulo").cuerpos().size() == 2 && Arrays.equals(unido.serializar(), otraVez.serializar()));

        final EstadoSistemas leido = EstadoSistemas.leer(EstadoSistemas.desdeHex(e2.aHex()));
        fallos += chequear("P1: el cuerpo guardado sobrevive a texto y vuelta, y su segunda lectura es idéntica",
            Arrays.equals(e2.serializar(), leido.serializar()) && Arrays.equals(leido.serializar(), EstadoSistemas.leer(leido.serializar()).serializar()));

        // un cuerpo ajeno dentro de la meta de un mundo no cuenta como propio: no se une y el mundo se migra
        final AsignacionSistemas.Entrada ajeno = new AsignacionSistemas.Entrada("w9", "serpulo", 19, 9, e1);
        fallos += chequear("P1: un cuerpo de otro mundo dentro de la meta no se une ni cuenta como cuerpo propio",
            AsignacionSistemas.cuerpoPropio(ajeno.estado, "w9") == null && AsignacionSistemas.unir(List.of(ajeno)).sistemas().isEmpty());
    }

    private static void pruebaAsignacionMigracion(){
        ApiSistemas.reiniciarPoliticas();
        final java.util.function.ToDoubleFunction<String> radio = p -> "erekir".equals(p) ? 17.0 : 31.0;

        final List<AsignacionSistemas.Entrada> a = new ArrayList<AsignacionSistemas.Entrada>();
        a.add(antiguo("c", "serpulo", 30));
        a.add(antiguo("a", "serpulo", 10));
        a.add(antiguo("b", "serpulo", 20));
        a.add(antiguo("e", "erekir", 5));
        final List<AsignacionSistemas.Entrada> cambiadas = AsignacionSistemas.migrar(a, radio);
        fallos += chequear("P1: migrar da un cuerpo a cada mundo antiguo y los devuelve para guardarlos", cambiadas.size() == 4);
        final EstadoSistemas g = AsignacionSistemas.unir(a);
        fallos += chequear("P1: se migra en orden de creación: el más antiguo en 0, el siguiente en π",
            cerca(g.buscarCuerpo("a").angulo, 0) && cerca(g.buscarCuerpo("b").angulo, PI) && g.buscarCuerpo("c").slot == 2
                && cerca(g.buscarCuerpo("e").angulo, 0));

        // el orden en que llegan en la lista no cambia el resultado
        final List<AsignacionSistemas.Entrada> b = new ArrayList<AsignacionSistemas.Entrada>();
        b.add(antiguo("e", "erekir", 5));
        b.add(antiguo("b", "serpulo", 20));
        b.add(antiguo("c", "serpulo", 30));
        b.add(antiguo("a", "serpulo", 10));
        AsignacionSistemas.migrar(b, radio);
        final EstadoSistemas gb = AsignacionSistemas.unir(b);
        boolean mismoOrden = true;
        for(String id : new String[]{"a", "b", "c", "e"}){
            mismoOrden &= cerca(g.buscarCuerpo(id).angulo, gb.buscarCuerpo(id).angulo) && g.buscarCuerpo(id).slot == gb.buscarCuerpo(id).slot;
        }
        fallos += chequear("P1: el resultado de migrar no depende del orden de la lista", mismoOrden);

        // segunda lectura: nada que migrar y nada cambia
        final byte[][] antes = new byte[a.size()][];
        for(int i = 0; i < a.size(); i++) antes[i] = bytes(a.get(i));
        final List<AsignacionSistemas.Entrada> segunda = AsignacionSistemas.migrar(a, radio);
        boolean igual = true;
        for(int i = 0; i < a.size(); i++) igual &= Arrays.equals(antes[i], bytes(a.get(i)));
        fallos += chequear("P1: migrar por segunda vez no cambia nada (idempotente)", segunda.isEmpty() && igual);

        // un mundo guardado conserva su sitio aunque sea más reciente que el antiguo que se migra
        final List<AsignacionSistemas.Entrada> mezcla = new ArrayList<AsignacionSistemas.Entrada>();
        final EstadoSistemas base = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(base, "sistema-serpulo", "nuevo", "serpulo", 31, ApiFisica.DIAMETRO_PLANETA);
        final EstadoSistemas soloNuevo = AsignacionSistemas.unir(List.of(new AsignacionSistemas.Entrada("nuevo", "serpulo", 1, 99, base)));
        final AsignacionSistemas.Entrada enNuevo = new AsignacionSistemas.Entrada("nuevo", "serpulo", 1, 99, soloNuevo);
        final byte[] antesNuevo = bytes(enNuevo);
        mezcla.add(enNuevo);
        mezcla.add(antiguo("viejo", "serpulo", 1));
        AsignacionSistemas.migrar(mezcla, radio);
        fallos += chequear("P1: un mundo ya guardado no se mueve, y el antiguo toma el hueco que queda",
            Arrays.equals(antesNuevo, bytes(enNuevo)) && cerca(AsignacionSistemas.unir(mezcla).buscarCuerpo("viejo").angulo, PI));
    }

    private static void pruebaAsignacionSolitario(){
        ApiSistemas.reiniciarPoliticas();
        // órbita pequeña: r=3, diámetro 2 + margen 2 => capacidad 4. Al llenarse, los siguientes mundos son solitarios.
        final java.util.function.ToDoubleFunction<String> radio = p -> 3.0;
        final List<AsignacionSistemas.Entrada> a = new ArrayList<AsignacionSistemas.Entrada>();
        for(int i = 0; i < 12; i++) a.add(antiguo("m" + (100 + i), "serpulo", i));
        AsignacionSistemas.migrar(a, radio);
        final EstadoSistemas g = AsignacionSistemas.unir(a);
        int enOrbita = 0, solitarios = 0;
        boolean todosConCuerpo = true, lejos = true;
        for(AsignacionSistemas.Entrada e : a){
            final EstadoSistemas.Cuerpo c = g.buscarCuerpo(e.idMundo);
            if(c == null){ todosConCuerpo = false; continue; }
            if(c.orbita != null) enOrbita++;
            else{
                solitarios++;
                final EstadoSistemas.SistemaSolar s = AsignacionSistemas.sistemaDe(g, e.idMundo);
                lejos &= Math.hypot(s.p0x, s.p0y) >= 3.0 * AsignacionSistemas.SOLITARIO_MIN - 1e-9 && c.slot == -1;
            }
        }
        fallos += chequear("P1: con la órbita llena, cada mundo sigue teniendo cuerpo; los que no caben son solitarios fuera de la órbita",
            todosConCuerpo && enOrbita >= 1 && enOrbita <= ApiSistemas.capacidad(3.0, ApiFisica.DIAMETRO_PLANETA, ApiSistemas.MARGEN_ORBITA)
                && solitarios == 12 - enOrbita && solitarios >= 1 && lejos);
        fallos += chequear("P1: los cuerpos en órbita respetan la separación mínima entre todos",
            separacionEntreTodos(g.angulosOrbita("serpulo")) * 3.0 >= ApiFisica.DIAMETRO_PLANETA + ApiSistemas.MARGEN_ORBITA - 1e-6 || enOrbita < 2);

        final List<AsignacionSistemas.Entrada> b = new ArrayList<AsignacionSistemas.Entrada>();
        for(int i = 0; i < 12; i++) b.add(antiguo("m" + (100 + i), "serpulo", i));
        AsignacionSistemas.migrar(b, radio);
        fallos += chequear("P1: la posición de los solitarios es determinista (misma semilla, mismos bytes)",
            Arrays.equals(AsignacionSistemas.unir(a).serializar(), AsignacionSistemas.unir(b).serializar()));
    }

    // ================================================================== F4: políticas que fallan

    private static void pruebaConsecuenciaOrbita(){
        ApiSistemas.reiniciarConsecuenciasOrbita();
        final int[] vistos = {0};
        ApiSistemas.proponerConsecuenciaOrbita("prueba-orbita", (orbita, cuerpos, radio, diametro) -> vistos[0] = cuerpos);
        final EstadoSistemas e = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e, "o1", "om1", "serpulo", 100, 10);
        ApiSistemas.anadirMundoEnOrbita(e, "o2", "om2", "serpulo", 100, 10);
        fallos += chequear("una consecuencia de órbita recibe el número de cuerpos tras cada alta", vistos[0] == 2);
        ApiSistemas.reiniciarConsecuenciasOrbita();
    }

    private static void pruebaInicio(){
        ApiInicioMundo.reiniciar();
        final float a = ApiInicioMundo.inicio(7), b = ApiInicioMundo.inicio(7), c = ApiInicioMundo.inicio(8);
        fallos += chequear("hora inicial aleatoria pero determinista por semilla, dentro de un día",
            a == b && a >= 0f && a < CicloDiaNoche.DIA_TICKS);
        fallos += chequear("semillas distintas empiezan a horas distintas", a != c);
        ApiInicioMundo.proponer("prueba-fija", 100, s -> 1234f);
        fallos += chequear("un inicio propuesto con prioridad manda", ApiInicioMundo.inicio(7) == 1234f);
        ApiInicioMundo.reiniciar();
        ApiInicioMundo.proponer("prueba-rota", 100, s -> {
            throw new IllegalStateException("prueba");
        });
        fallos += chequear("un inicio que falla se desactiva y vuelve el de defecto",
            ApiInicioMundo.inicio(7) == a && ApiInicioMundo.inicio(7) == a);
        ApiInicioMundo.reiniciar();
    }

    private static void pruebaPoliticaFallida(){
        ApiSistemas.reiniciarPoliticas();
        ApiSistemas.proponerPolitica("prueba-fallo", 1000, (o, r, d, m) -> {
            throw new IllegalStateException("prueba");
        });
        final EstadoSistemas e = new EstadoSistemas();
        final ApiSistemas.Resultado r1 = ApiSistemas.anadirMundoEnOrbita(e, "f1", "mf1", "serpulo", 100, 10);
        final ApiSistemas.Resultado r2 = ApiSistemas.anadirMundoEnOrbita(e, "f2", "mf2", "serpulo", 100, 10);
        fallos += chequear("una política que lanza excepción se desactiva y la de defecto coloca los mundos en 0 y π",
            r1.ok && r2.ok && cerca(e.buscarCuerpo("mf1").angulo, 0) && cerca(e.buscarCuerpo("mf2").angulo, PI));

        ApiSistemas.reiniciarPoliticas();
        ApiSistemas.proponerPolitica("prueba-invalida", 1000, (o, r, d, m) -> 0.0);
        final EstadoSistemas e2 = new EstadoSistemas();
        ApiSistemas.anadirMundoEnOrbita(e2, "g1", "mg1", "serpulo", 100, 10);
        final ApiSistemas.Resultado malo = ApiSistemas.anadirMundoEnOrbita(e2, "g2", "mg2", "serpulo", 100, 10);
        fallos += chequear("una política que pone un cuerpo encima de otro se rechaza con SIN_HUECO",
            !malo.ok && malo.motivo == ApiSistemas.Motivo.SIN_HUECO);
        ApiSistemas.reiniciarPoliticas();
    }
}
