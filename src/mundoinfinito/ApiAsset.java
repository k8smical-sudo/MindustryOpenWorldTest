package mundoinfinito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrato de un asset del terreno (mena, grafito, arena...): dónde puede aparecer, en qué orden, cuánto debe salir y qué
 * tolerancia tiene. Es API: un add-on registra sus assets con {@link #registrar} y el informe {@link #informe} los mide igual
 * que a los de Mindustry. Los objetivos de los assets de Mindustry son {@code null} hasta calibrarlos con la medición: el informe
 * muestra lo observado y no marca desbalanceo sin objetivo.
 */
public final class ApiAsset{
    /** Categorías: agrupan assets para el reparto y para los porcentajes. */
    public static final int CAT_MENA_SUELO = 0, CAT_MENA_PARED = 1, CAT_GRAFITO = 2, CAT_ARENA = 3;
    /** Dónde puede aparecer (máscara). */
    public static final int EN_SUELO = 1, EN_PARED = 2;

    /** Identificador único. Convención: {@code espacio:nombre}, p. ej. {@code miadd:cobalto}. */
    public final String id;
    public final int categoria;
    /** Código que produce en la capa de menas ({@code AporteTerreno.mena}); 0 si no produce mena (grafito, arena). */
    public final int codigo;
    /** Máscara {@link #EN_SUELO} | {@link #EN_PARED}. */
    public final int lugares;
    /** Porcentaje objetivo dentro de su categoría, o {@code null} si todavía no está calibrado. */
    public final Float objetivo;
    /** Puntos porcentuales de desvío admitidos antes de marcar desbalanceo. */
    public final float tolerancia;
    /** Orden en la cadena de dependencias (menor = antes). */
    public final int orden;

    public ApiAsset(String id, int categoria, int codigo, int lugares, Float objetivo, float tolerancia, int orden){
        if(id == null || id.isEmpty()) throw new IllegalArgumentException("el asset necesita un id");
        this.id = id;
        this.categoria = categoria;
        this.codigo = codigo;
        this.lugares = lugares;
        this.objetivo = objetivo;
        this.tolerancia = tolerancia;
        this.orden = orden;
    }

    private static final Map<String, ApiAsset> REGISTRO = new LinkedHashMap<String, ApiAsset>();

    /** Registra un asset. Un id repetido se rechaza: los add-ons no pueden pisar los assets de otro. */
    public static synchronized void registrar(ApiAsset a){
        if(a == null) throw new IllegalArgumentException("asset nulo");
        if(REGISTRO.containsKey(a.id)) throw new IllegalStateException("asset ya registrado: " + a.id);
        REGISTRO.put(a.id, a);
    }

    /** Todos los assets registrados, en orden de registro. */
    public static synchronized List<ApiAsset> todos(){
        return new ArrayList<ApiAsset>(REGISTRO.values());
    }

    static {
        final float tol = 2f;
        int o = 0;
        registrar(new ApiAsset("mindustry:cobre", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_COBRE, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:plomo", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_PLOMO, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:chatarra", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_CHATARRA, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:carbon", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_CARBON, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:titanio", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_TITANIO, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:torio", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_TORIO, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:berilio", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_BERILIO, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:tungsteno", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_TUNGSTENO, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:torio-cristal", CAT_MENA_SUELO, MundoInfinitoMod.Muestreo.O_TORIO_CRISTAL, EN_SUELO, null, tol, o++));
        registrar(new ApiAsset("mindustry:berilio-pared", CAT_MENA_PARED, MundoInfinitoMod.Muestreo.O_BERILIO_MURO, EN_PARED, null, tol, o++));
        registrar(new ApiAsset("mindustry:tungsteno-pared", CAT_MENA_PARED, MundoInfinitoMod.Muestreo.O_TUNGSTENO_MURO, EN_PARED, null, tol, o++));
        registrar(new ApiAsset("mindustry:torio-pared", CAT_MENA_PARED, MundoInfinitoMod.Muestreo.O_TORIO_MURO, EN_PARED, null, tol, o++));
        registrar(new ApiAsset("mindustry:grafito", CAT_GRAFITO, 0, EN_SUELO | EN_PARED, null, tol, o++));
        registrar(new ApiAsset("mindustry:arena", CAT_ARENA, 0, EN_SUELO, null, tol, o++));
    }

    /**
     * Mide el terreno base: para cada asset con código, casillas observadas y su porcentaje dentro de su categoría. Recorre
     * {@code radio} chunks alrededor del origen en la dimensión indicada. Solo lectura: no toca el mundo.
     */
    public static int informe(String[] args){
        final int semilla = args.length > 1 ? parseEntero(args[1], 12345) : 12345;
        final int radio = args.length > 2 ? Math.max(0, parseEntero(args[2], 4)) : 4;
        for(int dim : new int[]{0, 1}) imprimir(semilla, dim, radio);
        return 0;
    }

    private static int parseEntero(String s, int defecto){
        try{ return Integer.parseInt(s); }catch(NumberFormatException e){ return defecto; }
    }

    private static void imprimir(int semilla, int dimension, int radio){
        final int T = AporteTerreno.T;
        final AporteTerreno ap = new AporteTerreno();
        final EtapaBase[] etapas = PipelineBase.etapasBase();
        final ParametrosGeneracion par = Nucleo.parametros();
        final List<ApiAsset> assets = todos();
        final Map<Integer, ApiAsset> porCodigo = new HashMap<Integer, ApiAsset>();
        for(ApiAsset a : assets) if(a.codigo != 0) porCodigo.put(a.codigo, a);

        final Map<String, Long> cuenta = new HashMap<String, Long>();
        long menas = 0, sinRegistro = 0, casillas = 0;
        for(int cy = -radio; cy <= radio; cy++){
            for(int cx = -radio; cx <= radio; cx++){
                PipelineBase.ejecutar(new ContextoAporte(semilla, dimension, cx * T, cy * T, cx, cy, par), ap, etapas);
                casillas += AporteTerreno.N;
                for(int i = 0; i < AporteTerreno.N; i++){
                    final int code = ap.mena[i];
                    if(code == 0) continue;
                    menas++;
                    final ApiAsset a = porCodigo.get(code);
                    if(a == null){ sinRegistro++; continue; }
                    final Long previo = cuenta.get(a.id);
                    cuenta.put(a.id, previo == null ? 1L : previo + 1L);
                }
            }
        }

        System.out.println("[Assets] dimension=" + dimension + " semilla=" + semilla + " chunks=" + ((2 * radio + 1) * (2 * radio + 1))
            + " casillas=" + casillas + " menas=" + menas);
        final Map<Integer, Long> totalCategoria = new HashMap<Integer, Long>();
        for(ApiAsset a : assets){
            final Long v = cuenta.get(a.id);
            if(v != null){
                final Long t = totalCategoria.get(a.categoria);
                totalCategoria.put(a.categoria, t == null ? v : t + v);
            }
        }
        for(ApiAsset a : assets){
            final long n = cuenta.containsKey(a.id) ? cuenta.get(a.id) : 0L;
            final Long tc = totalCategoria.get(a.categoria);
            final double pct = (tc == null || tc == 0) ? 0 : 100.0 * n / tc;
            String estado;
            if(a.objetivo == null) estado = "sin objetivo";
            else{
                final double desvio = pct - a.objetivo;
                estado = Math.abs(desvio) <= a.tolerancia ? "OK" : "DESBALANCEADO (" + String.format("%+.1f", desvio) + " pp)";
            }
            System.out.println(String.format("[Assets]   %-24s cat=%d casillas=%8d  %%grupo=%6.2f  objetivo=%s  %s",
                a.id, a.categoria, n, pct, a.objetivo == null ? "-" : String.format("%.1f", a.objetivo), estado));
        }
        if(sinRegistro > 0) System.out.println("[Assets]   sin asset registrado (codigo no declarado): " + sinRegistro);
    }
}
