package mundoinfinito;

import mundoinfinito.MundoInfinitoMod.Muestreo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * REPARTO DE ÁREAS (fase 6): quién es el dueño de una macrocélula cuando varias contribuciones de terreno se la disputan.
 *
 * Una macrocélula son 2x2 chunks (64x64 casillas). Las macrocélulas se agrupan en bloques de {@link #BLOQUE}x{@link #BLOQUE}; en cada bloque
 * cada candidato recibe una CUOTA de macrocélulas proporcional a su peso (redondeada al azar con un hash determinista, así que el reparto
 * esperado es exacto y nadie se queda sin nada por tener poco peso), y qué macrocélulas concretas le tocan las decide un hash de sus coordenadas.
 * Resultado: la proporción real sigue a los pesos de cerca (en una región alineada de 10x10 macrocélulas con pesos 1 y 3, el candidato ligero tiene
 * entre 24 y 28, siempre), y a la vez el reparto parece aleatorio.
 *
 * Es una función pura de (semilla, dimensión, macrocélula, candidatos): no depende del orden de registro ni de carga, ni del hilo, ni del reloj.
 * Solo aritmética entera y de decimales básica (sin funciones trascendentes), idéntica en cualquier dispositivo.
 */
final class Reparto{
    /** Lado de la macrocélula en chunks (64 casillas). */
    static final int MACRO_CHUNKS = 2;
    /** Lado del bloque de cuotas, en macrocélulas. */
    static final int BLOQUE = 5;
    private static final int CELDAS = BLOQUE * BLOQUE;

    private Reparto(){}

    /** Macrocélula que contiene un chunk (coordenada de chunk -> coordenada de macrocélula). */
    static int macro(int chunk){
        return Math.floorDiv(chunk, MACRO_CHUNKS);
    }

    /**
     * Dueño de la macrocélula (mcx, mcy) entre los candidatos dados, en cualquier orden. Se ordenan por {@link Orden#CANONICO}.
     * Un candidato con peso menor o igual que 0, NaN o infinito queda fuera (no se elige nunca). Sin candidatos válidos: null.
     */
    static <T extends Contribucion> T dueno(int semilla, int dim, int mcx, int mcy, List<T> candidatos){
        final List<T> v = new ArrayList<T>();
        for(T c : candidatos){
            final double w = c.peso();
            if(!(w > 0.0) || Double.isInfinite(w)) continue;
            v.add(c);
        }
        Collections.sort(v, Orden.CANONICO);
        final int n = v.size();
        if(n == 0) return null;
        if(n == 1) return v.get(0);
        final double[] pesos = new double[n];
        for(int i = 0; i < n; i++) pesos[i] = v.get(i).peso();
        return v.get(indiceDueno(semilla, dim, mcx, mcy, pesos));
    }

    private static double unidad(int h){
        return ((h & 0xffffffffL) + 0.5) / 4294967296.0;   // (0,1)
    }

    /** Índice (en el orden de {@code pesos}, que ya es el canónico) del dueño de la macrocélula. */
    static int indiceDueno(int semilla, int dim, int mcx, int mcy, double[] pesos){
        final int n = pesos.length;
        final int bx = Math.floorDiv(mcx, BLOQUE), by = Math.floorDiv(mcy, BLOQUE);
        final int sb = Muestreo.sem(Muestreo.sem(semilla, dim), 0x4EA0);

        // 1) cuotas del bloque: parte entera de lo que le toca a cada uno...
        double total = 0.0;
        for(double w : pesos) total += w;
        final int[] cuota = new int[n];
        final double[] resto = new double[n];
        int suma = 0, mayor = 0;
        for(int i = 0; i < n; i++){
            final double exacto = CELDAS * pesos[i] / total;
            cuota[i] = (int)Math.floor(exacto);
            resto[i] = exacto - cuota[i];
            suma += cuota[i];
            if(pesos[i] > pesos[mayor]) mayor = i;
        }
        // ...y las macrocélulas que sobran, una a una, entre los que tienen resto, con probabilidad proporcional al resto
        int sobran = CELDAS - suma, ronda = 0;
        while(sobran > 0){
            double restos = 0.0;
            for(int i = 0; i < n; i++) restos += resto[i];
            int elegido = mayor;
            if(restos > 0.0){
                final double u = unidad(Muestreo.hash(bx, by, Muestreo.sem(sb, 0x100 + ronda))) * restos;
                double acum = 0.0;
                for(int i = 0; i < n; i++){
                    if(resto[i] <= 0.0) continue;
                    elegido = i;
                    acum += resto[i];
                    if(u < acum) break;
                }
            }
            cuota[elegido]++;
            resto[elegido] = 0.0;
            sobran--;
            ronda++;
        }
        while(sobran < 0){   // solo por error de redondeo: quitar al que más tiene
            int may = 0;
            for(int i = 1; i < n; i++) if(cuota[i] > cuota[may]) may = i;
            cuota[may]--;
            sobran++;
        }

        // 2) posición de esta macrocélula en el orden por hash de su bloque
        final int semRango = Muestreo.sem(sb, 0x77);
        final int mi = Math.floorMod(mcx, BLOQUE) + BLOQUE * Math.floorMod(mcy, BLOQUE);
        final int miRango = Muestreo.hash(mcx, mcy, semRango);
        int pos = 0;
        for(int k = 0; k < CELDAS; k++){
            if(k == mi) continue;
            final int cx = bx * BLOQUE + k % BLOQUE, cy = by * BLOQUE + k / BLOQUE;
            final int r = Muestreo.hash(cx, cy, semRango);
            if(r < miRango || (r == miRango && k < mi)) pos++;
        }

        // 3) las primeras cuota[0] posiciones son del primer candidato, las siguientes del segundo...
        int acum = 0;
        for(int i = 0; i < n; i++){
            acum += cuota[i];
            if(pos < acum) return i;
        }
        return n - 1;
    }
}
