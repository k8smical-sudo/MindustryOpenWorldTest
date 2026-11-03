package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * API DE VIAJES (plan v8, entrega B; respuesta 3 de la sección 10). Portales por tiers que enlazan dos cuerpos, y viajes entre ellos
 * dentro de un sistema o entre sistemas. Sin Arc salvo el log de avisos: se prueba en CI sin el juego.
 *
 * COSTE Y TIEMPO (respuesta 3): el coste de un viaje es proporcional a su distancia y al tier de la unidad que viaja:
 *     coste = distancia · tierUnidad · COSTE_POR_UNIDAD
 * El teletransporte tarda proporcional a la distancia y al tier de la unidad, y se acelera con el tier del portal (igual que subir el tier
 * de una fábrica):
 *     ticks = distancia · TICKS_POR_UNIDAD · tierUnidad / tierPortal
 *
 * REQUISITOS: cada tier de portal pide materiales ("item:cantidad"). Un viaje falla si el inventario no los cubre.
 *
 * CONSECUENCIAS: por defecto, ninguna. Un addon puede registrar consecuencias (interferencias, daños) con {@link #proponerConsecuencia}.
 * Una consecuencia que falla se desactiva con aviso.
 *
 * Valores por defecto declarados (pendientes de calibrar en F17): alcance 60, 300 y 1000; ticks para establecer 300, 900 y 2400;
 * materiales de los tiers 1 a 3 (ver {@link #portal}).
 */
public final class ApiViaje{
    public static final int TIER_MINIMO = 1, TIER_MAXIMO = 3;
    /** Ticks de teletransporte por unidad de distancia, con tier de unidad 1 y tier de portal 1. Default declarado. */
    public static final double TICKS_POR_UNIDAD = Calibracion.VIAJE_TICKS_POR_UNIDAD;
    /** Coste por unidad de distancia, con tier de unidad 1. Default declarado. */
    public static final double COSTE_POR_UNIDAD = Calibracion.VIAJE_COSTE_POR_UNIDAD;

    private ApiViaje(){}

    /** Un portal: tier, alcance del enlace, ticks para establecerlo y materiales que pide para funcionar ("item:cantidad"). */
    public static final class Portal{
        public final int tier;
        public final double alcance;
        public final long ticksEstablecer;
        private final String[] requisitos;

        public Portal(int tier, double alcance, long ticksEstablecer, String... requisitos){
            if(!(alcance > 0.0) || !Double.isFinite(alcance)) throw new IllegalArgumentException("alcance no válido: " + alcance);
            if(ticksEstablecer < 0L) throw new IllegalArgumentException("ticks para establecer no válidos: " + ticksEstablecer);
            this.tier = tier;
            this.alcance = alcance;
            this.ticksEstablecer = ticksEstablecer;
            this.requisitos = requisitos == null ? new String[0] : requisitos.clone();
        }

        public String[] requisitos(){ return requisitos.clone(); }
    }

    /** Acota un tier al rango válido. */
    static int acotarTier(int t){ return Math.max(TIER_MINIMO, Math.min(TIER_MAXIMO, t)); }

    /**
     * Portal por defecto de un tier. Materiales declarados (nombres internos de Mindustry): tier 1, cobre y plomo; tier 2, silicio, titanio
     * y plomo; tier 3, aleación de sobrecarga, tejido de fase y silicio.
     */
    public static Portal portal(int tier){
        final int i = Calibracion.indice(tier);
        return new Portal(i + 1, Calibracion.PORTAL_ALCANCE[i], Calibracion.PORTAL_TICKS_ESTABLECER[i], Calibracion.PORTAL_REQUISITOS[i]);
    }

    /** Materiales que faltan para un portal de ese tier, con la cantidad que falta ("item cantidad"). Vacío si está completo. */
    public static List<String> faltan(int tier, Map<String, Integer> inventario){
        final List<String> out = new ArrayList<String>();
        for(String req : portal(tier).requisitos()){
            final int sep = req.lastIndexOf(':');
            final String item = req.substring(0, sep);
            final int cantidad = Integer.parseInt(req.substring(sep + 1));
            final Integer tiene = inventario == null ? null : inventario.get(item);
            final int disponible = tiene == null ? 0 : tiene;
            if(disponible < cantidad) out.add(item + " " + (cantidad - disponible));
        }
        return out;
    }

    /** Enlace entre dos cuerpos creado por un portal de un tier. */
    public static final class Enlace{
        public final String id, cuerpoA, cuerpoB;
        public final int tier;

        Enlace(String id, String cuerpoA, String cuerpoB, int tier){
            this.id = id;
            this.cuerpoA = cuerpoA;
            this.cuerpoB = cuerpoB;
            this.tier = tier;
        }
    }

    /** Resultado de establecer un enlace o de un viaje. Si no es ok, {@link #mensaje} explica el motivo. */
    public static final class Resultado{
        public final boolean ok;
        public final String mensaje;
        public final long ticks;
        public final double coste;
        public final double distancia;
        public final Enlace enlace;
        /** Efectos de las consecuencias registradas (vacío por defecto). */
        public final List<String> consecuencias;

        Resultado(boolean ok, String mensaje, long ticks, double coste, double distancia, Enlace enlace, List<String> consecuencias){
            this.ok = ok;
            this.mensaje = mensaje;
            this.ticks = ticks;
            this.coste = coste;
            this.distancia = distancia;
            this.enlace = enlace;
            this.consecuencias = Collections.unmodifiableList(consecuencias);
        }
    }

    private static Resultado fallo(String mensaje){
        return new Resultado(false, mensaje, 0L, 0.0, 0.0, null, new ArrayList<String>());
    }

    /** Enlaces existentes. Un enlace une dos cuerpos en cualquier sentido. */
    public static final class Enlaces{
        private final ArrayList<Enlace> lista = new ArrayList<Enlace>();

        public List<Enlace> todos(){ return Collections.unmodifiableList(lista); }

        public Enlace buscar(String id){
            for(Enlace e : lista) if(e.id.equals(id)) return e;
            return null;
        }

        /** Enlace entre dos cuerpos, en cualquier orden, o null. */
        public Enlace entre(String a, String b){
            for(Enlace e : lista){
                if((e.cuerpoA.equals(a) && e.cuerpoB.equals(b)) || (e.cuerpoA.equals(b) && e.cuerpoB.equals(a))) return e;
            }
            return null;
        }

        public boolean quitar(String id){
            for(int i = 0; i < lista.size(); i++){
                if(lista.get(i).id.equals(id)){
                    lista.remove(i);
                    return true;
                }
            }
            return false;
        }

        /**
         * Texto para guardar los enlaces con el mundo: una línea por enlace, "id|cuerpoA|cuerpoB|tier", en orden. Los caracteres '%', '|' y salto
         * de línea de los ids se codifican. {@link #desdeTexto} lo lee de vuelta con el mismo orden y los mismos valores (ida y vuelta idéntica).
         */
        public String aTexto(){
            final StringBuilder sb = new StringBuilder();
            for(Enlace e : lista){
                sb.append(codificar(e.id)).append('|').append(codificar(e.cuerpoA)).append('|')
                    .append(codificar(e.cuerpoB)).append('|').append(e.tier).append('\n');
            }
            return sb.toString();
        }

        /**
         * Lee enlaces guardados con {@link #aTexto}. Texto nulo o vacío: sin enlaces (mundos antiguos). Una línea mal formada, un id repetido, un
         * enlace de un cuerpo consigo mismo o un par ya enlazado se descartan con aviso, y el resto se conserva; nunca lanza excepciones.
         */
        public static Enlaces desdeTexto(String texto){
            final Enlaces out = new Enlaces();
            if(texto == null || texto.isEmpty()) return out;
            for(String linea : texto.split("\n")){
                if(linea.isEmpty()) continue;
                final String[] c = linea.split("\\|", -1);
                int tier = 0;
                if(c.length == 4){
                    try{
                        tier = Integer.parseInt(c[3].trim());
                    }catch(NumberFormatException ex){
                        tier = 0;
                    }
                }
                if(c.length != 4 || tier < TIER_MINIMO || tier > TIER_MAXIMO){
                    Log.warn("[MundoInfinito] Enlace guardado descartado (línea mal formada): @", linea);
                    continue;
                }
                final String id = decodificar(c[0]), a = decodificar(c[1]), b = decodificar(c[2]);
                if(id.isEmpty() || a.isEmpty() || b.isEmpty() || a.equals(b) || out.buscar(id) != null || out.entre(a, b) != null){
                    Log.warn("[MundoInfinito] Enlace guardado descartado (datos no válidos o repetidos): @", linea);
                    continue;
                }
                out.lista.add(new Enlace(id, a, b, tier));
            }
            return out;
        }

        private static String codificar(String t){
            return t.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A");
        }

        private static String decodificar(String t){
            return t.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%");
        }
    }

    /** Posición de un cuerpo en el tiempo t (la de su sistema más su órbita), o null si no existe. */
    static double[] posicionDe(EstadoSistemas est, String cuerpo, double t, ToDoubleFunction<String> periodo){
        for(EstadoSistemas.SistemaSolar s : est.sistemas()){
            for(EstadoSistemas.Cuerpo c : s.cuerpos()){
                if(c.id.equals(cuerpo)) return ApiRadar.posicionCuerpo(s, c, t, periodo);
            }
        }
        return null;
    }

    /** Distancia entre dos cuerpos en el tiempo t. NaN si alguno no existe. */
    public static double distancia(EstadoSistemas est, String a, String b, double t, ToDoubleFunction<String> periodo){
        final double[] pa = posicionDe(est, a, t, periodo), pb = posicionDe(est, b, t, periodo);
        if(pa == null || pb == null) return Double.NaN;
        return ApiFisica.distancia(pa[0], pa[1], pb[0], pb[1]);
    }

    /**
     * Establece un enlace entre dos cuerpos con un portal de un tier. Falla si los cuerpos no existen, son el mismo, ya están enlazados, o
     * están fuera del alcance del portal. Lleva el coste de establecerlo en ticks ({@code ticksEstablecer}).
     */
    public static Resultado establecer(EstadoSistemas est, Enlaces enlaces, String id, String a, String b, Portal portal, double t,
                                       ToDoubleFunction<String> periodo){
        if(id == null || a == null || b == null || portal == null || enlaces == null) return fallo("datos no válidos");
        if(a.equals(b)) return fallo("un portal no puede enlazar un cuerpo consigo mismo");
        if(enlaces.buscar(id) != null) return fallo("ya existe un enlace con el id " + id);
        if(enlaces.entre(a, b) != null) return fallo("ya hay un enlace entre " + a + " y " + b);
        final double d = distancia(est, a, b, t, periodo);
        if(Double.isNaN(d)) return fallo("cuerpo no encontrado");
        if(d > portal.alcance){
            return fallo("fuera de alcance del portal tier " + portal.tier + ": distancia " + d + ", alcance " + portal.alcance);
        }
        final Enlace e = new Enlace(id, a, b, portal.tier);
        enlaces.lista.add(e);
        return new Resultado(true, "enlace establecido", portal.ticksEstablecer, 0.0, d, e, new ArrayList<String>());
    }

    /**
     * Viaje entre dos cuerpos enlazados. Falla si no hay enlace, si falta algún cuerpo o material del portal, o si el tier de la unidad no
     * es válido. Coste y ticks según la fórmula de la cabecera. Al completar el viaje se aplican las consecuencias registradas.
     */
    public static Resultado viajar(EstadoSistemas est, Enlaces enlaces, String origen, String destino, int tierUnidad,
                                   Map<String, Integer> inventario, double t, ToDoubleFunction<String> periodo){
        if(enlaces == null || origen == null || destino == null) return fallo("datos no válidos");
        final Enlace e = enlaces.entre(origen, destino);
        if(e == null) return fallo("no hay enlace entre " + origen + " y " + destino);
        final double d = distancia(est, origen, destino, t, periodo);
        if(Double.isNaN(d)) return fallo("cuerpo no encontrado");
        final List<String> faltan = faltan(e.tier, inventario);
        if(!faltan.isEmpty()) return fallo("faltan materiales para el portal tier " + e.tier + ": " + faltan);
        final int u = acotarTier(tierUnidad);
        final long ticks = Math.max(1L, (long)Math.ceil(d * TICKS_POR_UNIDAD * u / e.tier));
        final double coste = d * u * COSTE_POR_UNIDAD;
        final Resultado base = new Resultado(true, "viaje listo", ticks, coste, d, e, new ArrayList<String>());
        return new Resultado(true, "viaje listo", ticks, coste, d, e, aplicarConsecuencias(base));
    }

    // ================================================================== boosters y caos (plan v9, P7; ApiViaje 1.1)

    /** Boosters de viaje: se consumen del inventario del núcleo (quien llama decide qué boosters están instalados). */
    public static final String BOOSTER_ALCANCE = "booster:alcance", BOOSTER_VELOCIDAD = "booster:velocidad", BOOSTER_ESTABILIDAD = "booster:estabilidad";
    /** Factores de los boosters: alcance +25 %, velocidad −20 % de tiempo, estabilidad a la mitad el caos. (ver {@link Calibracion}) */
    public static final double FACTOR_ALCANCE = Calibracion.BOOSTER_ALCANCE, FACTOR_VELOCIDAD = Calibracion.BOOSTER_VELOCIDAD, FACTOR_ESTABILIDAD = Calibracion.BOOSTER_ESTABILIDAD;
    /** Caos: probabilidad base 2 % × tier de unidad × (distancia ÷ alcance), con tope 25 %. Coste extra 10 %, retraso ×1,5. (ver {@link Calibracion}) */
    public static final double CAOS_BASE = Calibracion.CAOS_BASE, CAOS_TOPE = Calibracion.CAOS_TOPE, CAOS_COSTE_EXTRA = Calibracion.CAOS_COSTE_EXTRA, CAOS_RETRASO = Calibracion.CAOS_RETRASO;
    /** Ajuste de mundo que activa el caos. Apagado por defecto. Lo registra {@link #iniciarAjustes}. */
    public static final String CAOS_AJUSTE = "mundo-infinito:viaje-caos";

    /** Registra el ajuste del caos. Lo llama el mod al cargar, antes de abrir el primer mundo. */
    static void iniciarAjustes(){
        ApiAjustes.registrarBooleano(CAOS_AJUSTE, "Caos en viajes", "Incidentes aleatorios en los viajes entre mundos. Se elige al crear el mundo.", false, null);
    }

    /** ¿Está activo el caos en el mundo abierto? Falso sin mundo o si el ajuste no existe. */
    public static boolean caosActivo(){
        final double v = ApiAjustes.valor(CAOS_AJUSTE);
        return !Double.isNaN(v) && v != 0.0;
    }

    /** Nombres de los ítems del juego que hacen de boosters. Cada viaje consume una unidad de cada booster usado. */
    public static final String ITEM_BOOSTER_ALCANCE = "booster-alcance", ITEM_BOOSTER_VELOCIDAD = "booster-velocidad",
        ITEM_BOOSTER_ESTABILIDAD = "booster-estabilidad";

    /** Ids de booster de un ítem, o null si el ítem no es un booster. */
    public static String boosterDeItem(String item){
        if(ITEM_BOOSTER_ALCANCE.equals(item)) return BOOSTER_ALCANCE;
        if(ITEM_BOOSTER_VELOCIDAD.equals(item)) return BOOSTER_VELOCIDAD;
        if(ITEM_BOOSTER_ESTABILIDAD.equals(item)) return BOOSTER_ESTABILIDAD;
        return null;
    }

    /** Ítem que hace de un booster, o null si el id no es de un booster. */
    public static String itemDeBooster(String booster){
        if(BOOSTER_ALCANCE.equals(booster)) return ITEM_BOOSTER_ALCANCE;
        if(BOOSTER_VELOCIDAD.equals(booster)) return ITEM_BOOSTER_VELOCIDAD;
        if(BOOSTER_ESTABILIDAD.equals(booster)) return ITEM_BOOSTER_ESTABILIDAD;
        return null;
    }

    /**
     * Boosters que se pueden usar con un inventario: los que tienen al menos una unidad. Orden estable (alcance, velocidad, estabilidad).
     * Inventario nulo: ninguno. Quien llama decide si usa todos o solo algunos antes de pasarlos a {@link #viajarConCaos}.
     */
    public static Set<String> boostersDisponibles(Map<String, Integer> inventario){
        final Set<String> out = new java.util.LinkedHashSet<String>();
        if(inventario == null) return out;
        for(String b : new String[]{BOOSTER_ALCANCE, BOOSTER_VELOCIDAD, BOOSTER_ESTABILIDAD}){
            final Integer n = inventario.get(itemDeBooster(b));
            if(n != null && n > 0) out.add(b);
        }
        return out;
    }

    /** Ítems y cantidades que cuesta usar unos boosters: una unidad de cada uno. Ids que no son de booster se ignoran. */
    public static Map<String, Integer> costeBoosters(Set<String> boosters){
        final Map<String, Integer> out = new java.util.LinkedHashMap<String, Integer>();
        if(boosters == null) return out;
        for(String b : boosters){
            final String item = itemDeBooster(b);
            if(item != null) out.put(item, 1);
        }
        return out;
    }

    /** Portal con el alcance que da el booster de alcance (si está instalado). Los demás datos del portal no cambian. */
    public static Portal portalEfectivo(Portal p, Set<String> boosters){
        final double alcance = has(boosters, BOOSTER_ALCANCE) ? p.alcance * FACTOR_ALCANCE : p.alcance;
        return new Portal(p.tier, alcance, p.ticksEstablecer, p.requisitos());
    }

    /** Ticks con el booster de velocidad (si está instalado). Nunca menos de 1. */
    public static long ticksEfectivos(long ticks, Set<String> boosters){
        if(!has(boosters, BOOSTER_VELOCIDAD)) return ticks;
        return Math.max(1L, (long)Math.ceil(ticks * FACTOR_VELOCIDAD));
    }

    /**
     * Probabilidad de incidente de un viaje: 2 % × tier de unidad × (distancia ÷ alcance), con tope 25 %. El booster de estabilidad la divide
     * entre dos. Distancia o alcance no válidos: 0.
     */
    public static double probabilidadCaos(double distancia, double alcance, int tierUnidad, Set<String> boosters){
        if(!(distancia >= 0.0) || !Double.isFinite(distancia) || !(alcance > 0.0) || !Double.isFinite(alcance)) return 0.0;
        double p = CAOS_BASE * acotarTier(tierUnidad) * (distancia / alcance);
        if(has(boosters, BOOSTER_ESTABILIDAD)) p *= FACTOR_ESTABILIDAD;
        return Math.min(CAOS_TOPE, p);
    }

    /** Incidente de un viaje. Sin incidente: {@link #incidente} es false y los demás campos son neutros. */
    public static final class Caos{
        public final boolean incidente;
        /** "retraso", "coste" o "desvio" (el desvío lo aplica quien llama: elige otro destino detectado). Null sin incidente. */
        public final String efecto;
        public final long ticksExtra;
        public final double costeExtra;

        Caos(boolean incidente, String efecto, long ticksExtra, double costeExtra){
            this.incidente = incidente;
            this.efecto = efecto;
            this.ticksExtra = ticksExtra;
            this.costeExtra = costeExtra;
        }
    }

    /**
     * Decide un incidente a partir de dos tiradas en [0, 1): la primera decide si hay incidente (tirada menor que la probabilidad); la segunda
     * elige el efecto: el tercio bajo retrasa, el medio encarece y el alto desvía.
     */
    public static Caos evaluarCaos(double probabilidad, double tirada, double tiradaEfecto, long ticks, double coste){
        if(!(tirada < probabilidad)) return new Caos(false, null, 0L, 0.0);
        if(tiradaEfecto < 1.0 / 3.0){
            return new Caos(true, "retraso", (long)Math.ceil(ticks * (CAOS_RETRASO - 1.0)), 0.0);
        }
        if(tiradaEfecto < 2.0 / 3.0){
            return new Caos(true, "coste", 0L, coste * CAOS_COSTE_EXTRA);
        }
        return new Caos(true, "desvio", 0L, 0.0);
    }

    /** Resultado de un viaje con boosters y caos. {@link #caos} es null si no hubo incidente o el caos está apagado. */
    public static final class Viaje{
        public final Resultado resultado;
        public final String caos;

        Viaje(Resultado resultado, String caos){
            this.resultado = resultado;
            this.caos = caos;
        }
    }

    /**
     * Viaje con boosters y caos (ApiViaje 1.1). Sin caos activo, solo aplica el booster de velocidad: el resultado es igual al de
     * {@link #viajar} salvo por los ticks. Con el caos activo, las tiradas salen de {@link ApiAzar} con el enlace y el tiempo, así que el mismo viaje
     * en el mismo instante da siempre el mismo resultado.
     */
    public static Viaje viajarConCaos(EstadoSistemas est, Enlaces enlaces, String origen, String destino, int tierUnidad,
                                      Map<String, Integer> inventario, double t, ToDoubleFunction<String> periodo,
                                      Set<String> boosters, int semilla, int dimension){
        final Resultado base = viajar(est, enlaces, origen, destino, tierUnidad, inventario, t, periodo);
        if(!base.ok) return new Viaje(base, null);
        final long ticks = ticksEfectivos(base.ticks, boosters);
        if(!caosActivo()){
            return new Viaje(new Resultado(true, "viaje listo", ticks, base.coste, base.distancia, base.enlace, new ArrayList<String>(base.consecuencias)), null);
        }
        final Portal portal = portal(base.enlace.tier);
        final double prob = probabilidadCaos(base.distancia, portal.alcance, tierUnidad, boosters);
        final String clave = "viaje/caos/" + origen + "/" + destino;
        final int bloque = (int)Math.floor(t);
        final double tirada = ApiAzar.unidad(semilla, dimension, clave, bloque, 0);
        final double tiradaEfecto = ApiAzar.unidad(semilla, dimension, clave + "/efecto", bloque, 0);
        final Caos caos = evaluarCaos(prob, tirada, tiradaEfecto, ticks, base.coste);
        final Resultado r = new Resultado(true, caos.incidente ? "viaje listo con incidente: " + caos.efecto : "viaje listo",
            ticks + caos.ticksExtra, base.coste + caos.costeExtra, base.distancia, base.enlace, new ArrayList<String>(base.consecuencias));
        return new Viaje(r, caos.efecto);
    }

    private static boolean has(Set<String> boosters, String id){
        return boosters != null && boosters.contains(id);
    }

    // ================================================================== unidades y desvíos (ApiViaje 1.2)

    /**
     * Tier de una unidad según su vida máxima: menos de {@link Calibracion#UNIDAD_VIDA_TIER2} es tier 1; menos de
     * {@link Calibracion#UNIDAD_VIDA_TIER3}, tier 2; el resto, tier 3. Vida no válida: tier 1. Estimación propia: el juego no da tiers comparables
     * entre Serpulo y Erekir.
     */
    public static int tierUnidadPorVida(double vida){
        if(!(vida > 0.0) || !Double.isFinite(vida)) return TIER_MINIMO;
        if(vida < Calibracion.UNIDAD_VIDA_TIER2) return 1;
        return vida < Calibracion.UNIDAD_VIDA_TIER3 ? 2 : 3;
    }

    /**
     * Destino de un desvío por caos: otro cuerpo enlazado con el origen distinto del destino pedido, elegido con {@link ApiAzar}
     * (mismo mundo, mismo origen y mismo instante dan siempre el mismo destino). Null si no hay otro cuerpo enlazado.
     */
    public static String elegirDesvio(Enlaces enlaces, String origen, String destino, int semilla, int dimension, int bloque){
        if(enlaces == null || origen == null) return null;
        final java.util.TreeSet<String> candidatos = new java.util.TreeSet<String>();
        for(Enlace e : enlaces.todos()){
            final String otro = e.cuerpoA.equals(origen) ? e.cuerpoB : (e.cuerpoB.equals(origen) ? e.cuerpoA : null);
            if(otro != null && !otro.equals(destino)) candidatos.add(otro);
        }
        if(candidatos.isEmpty()) return null;
        final int k = ApiAzar.entero(semilla, dimension, "viaje/desvio/" + origen, bloque, 0, candidatos.size());
        return new ArrayList<String>(candidatos).get(k);
    }

    // ================================================================== consecuencias (API lista para addons; por defecto, ninguna)

    /** Efecto de un viaje completado (interferencias, daños, etc.). Devuelve el texto del efecto, o null si no aplica. */
    @FunctionalInterface
    public interface Consecuencia{
        String aplicar(Resultado viaje);
    }

    private static final class Registrada{
        final String id;
        final Consecuencia consecuencia;
        boolean activa = true;

        Registrada(String id, Consecuencia consecuencia){
            this.id = id;
            this.consecuencia = consecuencia;
        }
    }

    private static final ArrayList<Registrada> consecuencias = new ArrayList<Registrada>();

    /** Registra una consecuencia de viajes. Devuelve false si el id es nulo, la consecuencia es nula o el id ya existe. */
    public static boolean proponerConsecuencia(String id, Consecuencia consecuencia){
        if(id == null || consecuencia == null) return false;
        for(Registrada r : consecuencias) if(r.id.equals(id)) return false;
        consecuencias.add(new Registrada(id, consecuencia));
        return true;
    }

    /** Solo para pruebas. */
    static void reiniciarConsecuencias(){ consecuencias.clear(); }

    private static List<String> aplicarConsecuencias(Resultado viaje){
        final List<String> out = new ArrayList<String>();
        for(Registrada r : new ArrayList<Registrada>(consecuencias)){
            if(!r.activa) continue;
            try{
                final String efecto = r.consecuencia.aplicar(viaje);
                if(efecto != null) out.add(efecto);
            }catch(RuntimeException e){
                r.activa = false;
                Log.err("[MundoInfinito] La consecuencia de viaje '@' falla y se desactiva: @", r.id, e.toString());
            }
        }
        return out;
    }
}
