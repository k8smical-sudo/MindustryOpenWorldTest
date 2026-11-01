package mundoinfinito;

import java.util.ArrayList;
import java.util.List;

/**
 * CATÁLOGO DE CÁLCULOS DESDUPLICADOS (P8). Es el único sitio donde se declara un cálculo del mod que pasa por la desduplicación.
 *
 * Cada entrada dice:
 *  - id: "mundo-infinito:nombre" (un contribuyente, único).
 *  - version: súbela cuando cambie el código del cálculo. Así su caché vieja deja de valer y no se sirve un resultado antiguo.
 *  - tope: cuántos resultados guarda como máximo (cada resultado puede pesar mucho: un chunk, una malla).
 *  - politica: AUTO (por defecto: el propio sistema decide por medición), SIEMPRE o NUNCA (ver Desduplicacion).
 *
 * Para añadir un cálculo: añade una entrada aquí, envuelve el cálculo con ApiDesduplicacion.calcular(id, ...) en el sitio de llamada, y añade un
 * caso en CasosBenchmark con cubre = id. Si falta el caso, el banco falla. Para quitar un cálculo: quita la entrada y su caso; la caché de un id
 * que ya no está en el catálogo se libera al olvidarlo.
 *
 * Ningún cálculo está conectado todavía. Ver la nota de P8 sobre el perfil necesario para elegir los puntos de conexión.
 */
final class Calculos{
    private Calculos(){}

    /** Un cálculo declarado. */
    static final class Entrada{
        final String id;
        final int version;
        final int tope;
        final int politica;
        final String descripcion;

        Entrada(String id, int version, int tope, int politica, String descripcion){
            this.id = id;
            this.version = version;
            this.tope = tope;
            this.politica = politica;
            this.descripcion = descripcion;
        }
    }

    /** Lista oficial de cálculos desduplicados. Vacía a propósito hasta que haya un punto de conexión medido. */
    static List<Entrada> catalogo(){
        final List<Entrada> l = new ArrayList<Entrada>();
        return l;
    }

    /** Entrada del catálogo para un id, o null si el cálculo no está declarado. */
    static Entrada buscar(String id){
        for(Entrada e : catalogo()){
            if(e.id.equals(id)) return e;
        }
        return null;
    }
}
