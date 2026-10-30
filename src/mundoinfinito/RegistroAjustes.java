package mundoinfinito;

import arc.util.Log;
import mundoinfinito.MundoInfinitoMod.Muestreo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REGISTRO DE AJUSTES (fase 4): las definiciones ({@link AjusteMundo}) de todas las reglas y parámetros, y el registro de recortes por valor
 * fuera de rango. Los ajustes de ámbito GENERACION se registran antes de abrir el primer mundo (después de eso se rechazan); los de ámbito
 * JUEGO, en cualquier momento (no afectan al terreno ya generado).
 */
final class RegistroAjustes{
    static final int MAX_RECORTES = 256;

    /** Un valor que se recortó: qué ajuste, qué se pidió y qué se aplicó. */
    static final class Recorte{
        final String id;
        final double pedido, aplicado;

        Recorte(String id, double pedido, double aplicado){
            this.id = id;
            this.pedido = pedido;
            this.aplicado = aplicado;
        }
    }

    private static final String BASE = "mundo-infinito:";
    private static final ConcurrentHashMap<String, AjusteMundo> porId = new ConcurrentHashMap<>();
    private static volatile boolean cerradoGeneracion;
    private static final ConcurrentLinkedQueue<Recorte> recortes = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger guardados = new AtomicInteger();
    private static final AtomicLong totalRecortes = new AtomicLong();
    private static final ConcurrentHashMap<String, Boolean> avisos = new ConcurrentHashMap<>();

    static{
        registrar(AjusteMundo.decimal(ParametrosGeneracion.ID_ALTURA_SERPULO, "Relieve de Serpulo",
            "Cuánto se estira el ruido de relieve hacia [0,1]. Más alto = montañas y mares más marcados. 1 = sin estirar.",
            AjusteMundo.Ambito.GENERACION, Muestreo.K_ALTURA, 1.0, 4.0).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.decimal(ParametrosGeneracion.ID_TEMPERATURA_SERPULO, "Temperatura de Serpulo",
            "Cuánto se estira el ruido de temperatura hacia [0,1]. Más alto = zonas heladas y abrasadoras más extensas. 1 = sin estirar.",
            AjusteMundo.Ambito.GENERACION, Muestreo.K_TEMP_S, 1.0, 4.0).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.decimal(ParametrosGeneracion.ID_HUMEDAD_SERPULO, "Humedad de Serpulo",
            "Cuánto se estira el ruido de humedad hacia [0,1]. Más alto = desiertos y pantanos más extensos. 1 = sin estirar.",
            AjusteMundo.Ambito.GENERACION, Muestreo.K_HUM_S, 1.0, 4.0).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.entero(ParametrosGeneracion.ID_FENOMENOS_DISTANCIA, "Distancia mínima entre fenómenos",
            "Distancia mínima, en casillas, entre dos fenómenos de cualquier mod (respiraderos, pozos, cráteres...). 0 = cada generador solo respeta su propia distancia.",
            AjusteMundo.Ambito.GENERACION, 0, 0, 512).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.entero(ParametrosGeneracion.ID_PRESUPUESTO_TERRENO, "Presupuesto de generación del terreno (%)",
            "Qué porcentaje del presupuesto de cada contribución de terreno se le permite gastar por chunk. 100 = todo (sin degradar). Menos = generación más barata y simple, siempre la misma para ese mundo.",
            AjusteMundo.Ambito.GENERACION, 100, 1, 100).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.entero(ParametrosGeneracion.ID_RESPIRADEROS_SEPARACION, "Separación entre respiraderos (casillas)",
            "Distancia mínima entre dos respiraderos de Erekir. Más alto = respiraderos más esparcidos. Los mundos anteriores conservan 48.",
            AjusteMundo.Ambito.GENERACION, 120, 48, 400).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.decimal(ParametrosGeneracion.ID_RESPIRADEROS_MEZCLA, "Respiraderos que alteran el suelo (0 a 1)",
            "Fracción de respiraderos que cambian el suelo de su alrededor. 0 = ninguno (aparecen sobre el terreno natural), 1 = todos.",
            AjusteMundo.Ambito.GENERACION, 0.5, 0.0, 1.0).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.entero(ParametrosGeneracion.ID_RESPIRADEROS_RADIO_MIN, "Radio mínimo de la zona alterada",
            "Radio mínimo, en casillas, de la zona que cambia alrededor de un respiradero.",
            AjusteMundo.Ambito.GENERACION, 4, 1, 14).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.entero(ParametrosGeneracion.ID_RESPIRADEROS_RADIO_MAX, "Radio máximo de la zona alterada",
            "Radio máximo, en casillas, de la zona que cambia alrededor de un respiradero. Si queda por debajo del mínimo se usa el mínimo.",
            AjusteMundo.Ambito.GENERACION, 12, 1, 14).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
        registrar(AjusteMundo.bool(ParametrosGeneracion.ID_POZOS_SPAWN, "Pozos del spawn",
            "Lista de pozos, respiraderos y depósitos agrupados alrededor del spawn. Desactivado en mundos nuevos: los pozos se reparten como el resto de fenómenos. Los mundos anteriores los conservan.",
            AjusteMundo.Ambito.GENERACION, false, null).conPolitica(AjusteMundo.Politica.MEDIA_PONDERADA));
    }

    private RegistroAjustes(){}

    // ================================================================== definiciones

    /** @return false si el id no es válido, ya existe, o es de generación y ya se abrió un mundo. */
    static boolean registrar(AjusteMundo a){
        if(a == null) return false;
        final String id = a.id;
        if(id == null || id.indexOf(':') <= 0 || id.indexOf(':') == id.length() - 1 || id.indexOf(' ') >= 0){
            Log.err("[MundoInfinito] Ajuste rechazado: el id debe tener el formato \"idmod:nombre\" y no tener espacios (recibido: @)", id);
            return false;
        }
        if(a.ambito == AjusteMundo.Ambito.GENERACION && cerradoGeneracion){
            Log.err("[MundoInfinito] Ajuste de generación \"@\" rechazado: ya se abrió un mundo (hay que registrarlo al cargar el mod).", id);
            return false;
        }
        if(porId.putIfAbsent(id, a) != null){
            Log.err("[MundoInfinito] Ajuste duplicado: @", id);
            return false;
        }
        return true;
    }

    static AjusteMundo get(String id){
        return id == null ? null : porId.get(id);
    }

    /** Los ajustes de un ámbito: primero los del mod base, después los demás; dentro de cada grupo, por id (orden estable). */
    static List<AjusteMundo> todos(AjusteMundo.Ambito ambito){
        final List<AjusteMundo> l = new ArrayList<AjusteMundo>();
        for(AjusteMundo a : porId.values()) if(a.ambito == ambito) l.add(a);
        Collections.sort(l, new Comparator<AjusteMundo>(){
            @Override
            public int compare(AjusteMundo x, AjusteMundo y){
                final boolean bx = x.id.startsWith(BASE), by = y.id.startsWith(BASE);
                return bx != by ? (bx ? -1 : 1) : x.id.compareTo(y.id);
            }
        });
        return l;
    }

    /** HILO PRINCIPAL, al abrir un mundo: desde aquí no se aceptan más ajustes de generación. */
    static void cerrarGeneracion(){
        cerradoGeneracion = true;
    }

    // ================================================================== recortes

    /** Anota un valor fuera de rango. Avisa en el log una vez por ajuste; guarda como máximo {@link #MAX_RECORTES}. */
    static void anotarRecorte(String id, double pedido, double aplicado){
        totalRecortes.incrementAndGet();
        if(guardados.incrementAndGet() <= MAX_RECORTES) recortes.add(new Recorte(id, pedido, aplicado));
        else guardados.decrementAndGet();
        if(avisos.putIfAbsent("recorte:" + id, Boolean.TRUE) == null){
            Log.warn("[MundoInfinito] Ajuste \"@\": el valor @ está fuera de rango y se recorta a @.", id, pedido, aplicado);
        }
    }

    static long totalRecortes(){
        return totalRecortes.get();
    }

    static List<Recorte> recortes(){
        return new ArrayList<Recorte>(recortes);
    }
}
