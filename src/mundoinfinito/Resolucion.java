package mundoinfinito;

import arc.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RESOLUCIÓN DE PARÁMETROS (fase 5): de varias propuestas de valor sobre un mismo ajuste a UN valor, con la política que declaró el ajuste.
 *
 * Garantías:
 *  - El resultado no depende del orden en que llegaron las propuestas: se ordenan en el orden canónico ({@link Orden#CANONICO}) antes de
 *    sumar o elegir (la suma de decimales no es asociativa: el orden fijo es lo que la hace reproducible).
 *  - Un valor fuera de rango nunca se impone: cada propuesta se recorta al rango del ajuste (y se anota en {@link RegistroAjustes}).
 *  - Con varios contribuyentes y SIN política, el ajuste no se resuelve: se reporta y queda su valor por defecto.
 *  - Una propuesta con peso no válido (menor o igual que 0, NaN, infinito) se ignora con un aviso.
 */
final class Resolucion{
    enum Origen{
        /** Ninguna propuesta: vale el valor por defecto. */
        DEFECTO,
        /** Una sola propuesta: no hace falta política. */
        UNICA,
        MEDIA,
        PRIORIDAD,
        ELECCION,
        /** Varios contribuyentes y ninguna política: no se resuelve, vale el valor por defecto. */
        SIN_POLITICA
    }

    static final class Resultado{
        final String parametro;
        final double valor;
        final Origen origen;
        /** Propuestas válidas que entraron en la resolución. */
        final int contribuyentes;
        /** Id de la propuesta elegida (solo prioridad absoluta y elección ponderada), o null. */
        final String elegida;

        Resultado(String parametro, double valor, Origen origen, int contribuyentes, String elegida){
            this.parametro = parametro;
            this.valor = valor;
            this.origen = origen;
            this.contribuyentes = contribuyentes;
            this.elegida = elegida;
        }

        /** ¿Se produjo un valor a partir de propuestas? (false = se usa el valor por defecto) */
        boolean resuelto(){
            return origen != Origen.DEFECTO && origen != Origen.SIN_POLITICA;
        }
    }

    private static final AtomicLong sinPolitica = new AtomicLong();
    private static final ConcurrentHashMap<String, Boolean> avisos = new ConcurrentHashMap<>();

    private Resolucion(){}

    /** Parámetros con varios contribuyentes y sin política que se han encontrado (para el diagnóstico). */
    static long conflictosSinPolitica(){
        return sinPolitica.get();
    }

    /**
     * @param a el ajuste (con su política)
     * @param propuestas propuestas sobre ESE ajuste, en cualquier orden
     * @param semilla semilla del mundo (solo la usa la elección ponderada)
     */
    static Resultado resolver(AjusteMundo a, List<? extends ContribucionParametro> propuestas, int semilla){
        // 1) orden canónico y filtro de pesos no válidos
        final List<ContribucionParametro> v = new ArrayList<ContribucionParametro>();
        for(ContribucionParametro c : propuestas){
            if(c == null || !a.id.equals(c.parametro())) continue;
            final double w = c.peso();
            if(Double.isNaN(w) || Double.isInfinite(w) || w <= 0.0){
                aviso("peso:" + c.id(), "La propuesta \"" + c.id() + "\" para \"" + a.id + "\" tiene un peso no válido (" + w + ") y se ignora.");
                continue;
            }
            v.add(c);
        }
        Collections.sort(v, Orden.CANONICO);
        final int n = v.size();

        if(n == 0) return new Resultado(a.id, a.defecto, Origen.DEFECTO, 0, null);
        if(n == 1) return new Resultado(a.id, recortar(a, v.get(0)), Origen.UNICA, 1, v.get(0).id());

        // 2) varios contribuyentes: la política es obligatoria
        final AjusteMundo.Politica pol = a.politica;
        if(pol == null){
            sinPolitica.incrementAndGet();
            aviso("sinpol:" + a.id, "El ajuste \"" + a.id + "\" tiene " + n + " contribuyentes (" + ids(v) + ") y ninguna política de resolución: no se resuelve y se usa su valor por defecto.");
            return new Resultado(a.id, a.defecto, Origen.SIN_POLITICA, n, null);
        }

        switch(pol){
            case PRIORIDAD_ABSOLUTA:{
                final ContribucionParametro g = v.get(0);   // la primera en orden canónico
                return new Resultado(a.id, recortar(a, g), Origen.PRIORIDAD, n, g.id());
            }
            case ELECCION_PONDERADA:{
                double total = 0.0;
                for(ContribucionParametro c : v) total += c.peso();
                final double u = azar(semilla, a.id) * total;
                double acum = 0.0;
                ContribucionParametro g = v.get(n - 1);   // respaldo por redondeo
                for(ContribucionParametro c : v){
                    acum += c.peso();
                    if(u < acum){
                        g = c;
                        break;
                    }
                }
                return new Resultado(a.id, recortar(a, g), Origen.ELECCION, n, g.id());
            }
            default:{
                // MEDIA_PONDERADA: cada valor recortado al rango, sumados en orden canónico
                double suma = 0.0, pesos = 0.0;
                for(ContribucionParametro c : v){
                    final double w = c.peso();
                    suma += w * recortar(a, c);
                    pesos += w;
                }
                return new Resultado(a.id, a.validar(suma / pesos), Origen.MEDIA, n, null);
            }
        }
    }

    /** El valor de la propuesta recortado al rango del ajuste; si hubo que recortar, se anota. */
    private static double recortar(AjusteMundo a, ContribucionParametro c){
        final double x = c.valor(), ok = a.validar(x);
        if(a.fueraDeRango(x)) RegistroAjustes.anotarRecorte(a.id + " (propuesta de " + c.id() + ")", x, ok);
        return ok;
    }

    /**
     * Número en [0,1) que depende SOLO de (semilla, id del ajuste): FNV-1a del id mezclado con la semilla y rematado con el finalizador de
     * SplitMix64. No usa ningún generador con estado ni el reloj.
     */
    static double azar(int semilla, String id){
        long h = 0xcbf29ce484222325L ^ ((long)semilla * 0x9E3779B97F4A7C15L);
        for(int i = 0; i < id.length(); i++) h = (h ^ id.charAt(i)) * 0x100000001b3L;
        h += 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * (1.0 / 9007199254740992.0);   // 53 bits -> [0,1)
    }

    private static String ids(List<ContribucionParametro> v){
        final StringBuilder sb = new StringBuilder();
        for(ContribucionParametro c : v){
            if(sb.length() > 0) sb.append(", ");
            sb.append(c.id());
        }
        return sb.toString();
    }

    private static void aviso(String clave, String texto){
        if(avisos.putIfAbsent(clave, Boolean.TRUE) == null) Log.warn("[MundoInfinito] " + texto);
    }
}
