package mundoinfinito;

import arc.Core;
import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mundoinfinito.MundoInfinitoMod.ChunkData;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Regiones;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * REUBICACIÓN ANTICIPADA DE LA VENTANA (sin tirones).
 *
 * El mundo es infinito pero Mindustry solo tiene una ventana finita de tiles. Cuando el jugador llega al borde, la ventana se
 * recoloca y TODO el terreno cambia de casilla local. Antes eso se hacía de golpe: leer del disco las regiones nuevas, copiar
 * ~100 000 tiles visibles y generar el terreno que faltara, todo en un fotograma = el congelamiento.
 *
 * Ahora el trabajo se hace ANTES, a escondidas y en trocitos:
 *
 *  1. PLANIFICAR (el jugador se acerca al borde): se fija el origen futuro de la ventana, desplazado hacia donde se mueve el
 *     jugador (más espacio por delante). Se piden en segundo plano las regiones de disco y el terreno de los chunks nuevos.
 *  2. ADELANTAR: los chunks del destino que quedan LEJOS de lo que se ve ahora (no se pueden ver todavía) se escriben ya con
 *     su terreno nuevo, unos pocos por fotograma. Esas casillas hoy no importan: están fuera de pantalla. Se sacan de
 *     'generados' para que nadie las trate como cargadas, y las unidades aliadas que haya ahí se duermen.
 *  3. COMPROMETER: al llegar al margen, el desplazamiento de entidades y cámara es instantáneo y casi todo el terreno visible
 *     ya está escrito. Solo queda lo poco que no se pudo adelantar.
 *
 * Si el jugador da media vuelta y se acerca a lo adelantado, se reubica en el acto (ya está casi hecho). Si no se puede, se
 * cancela y los chunks tocados se vuelven a cargar con el origen viejo (reparar).
 */
final class Ventana{
    static final int TAM = MundoInfinitoMod.TAM_CHUNK;
    static final int REG = MundoInfinitoMod.REGION_CHUNKS;
    /** Desde aquí (en chunks al borde) se empieza a preparar la reubicación si el jugador se acerca. */
    static final int MARGEN_PREP = MundoInfinitoMod.MARGEN_REBASE + 4;
    /** Cuánto se descentra la ventana nueva hacia donde va el jugador (fracción de la ventana). */
    static final float SESGO = 0.2f;

    static boolean hayPlan;
    static int nox, noy;                 // origen virtual (tiles) de la ventana futura
    static int dcx, dcy;                 // desplazamiento en chunks (nuevo - viejo)
    static int epocaPlan;
    static boolean[] staged, descartado; // por chunk local DESTINO: terreno nuevo ya escrito / no se puede adelantar
    static int nStaged;
    private static long[] orden = new long[1024];
    private static int nOrden, inicio, framesPeligro;
    /** Chunks tocados por un plan cancelado: hay que volver a cargarlos con el origen viejo. */
    static final IntSeq reparar = new IntSeq();

    private Ventana(){}

    static void reiniciar(){
        hayPlan = false;
        nStaged = 0;
        nOrden = 0;
        inicio = 0;
        framesPeligro = 0;
        if(staged != null){
            java.util.Arrays.fill(staged, false);
            java.util.Arrays.fill(descartado, false);
        }
        reparar.clear();
    }

    /** ¿Este chunk local tiene ya terreno de la ventana futura? Entonces el flujo normal NO debe tocarlo. */
    static boolean bloqueado(int k){
        return hayPlan && staged != null && k >= 0 && k < staged.length && staged[k];
    }

    static boolean enStaging(int cx, int cy){
        int n = Streamer.nch;
        return cx >= 0 && cy >= 0 && cx < n && cy < n && bloqueado(cy * n + cx);
    }

    // ================================================================ vigilancia (cada escaneo, ~0,5 s)
    /** @return true si se inició la reubicación (el escaneo normal se salta ese ciclo). */
    static boolean vigilar(int pcx, int pcy){
        if(Vars.player == null || Vars.player.dead() || Streamer.meta == null) return false;
        if(Mundos.viajando || Mundos.rebase != null) return false;
        final int n = Streamer.nch;
        final int margen = Math.min(Math.min(pcx, pcy), Math.min(n - 1 - pcx, n - 1 - pcy));
        if(margen <= MundoInfinitoMod.MARGEN_REBASE) return Mundos.rebasar();
        if(!Ajustes.anticipar()){
            if(hayPlan) cancelar();
            return false;
        }
        if(hayPlan){
            // el jugador se alejó del borde, o la ventana cambió por otro camino: se descarta
            if(margen >= MARGEN_PREP + 3 || epocaPlan != Streamer.epoca){ cancelar(); return false; }
            // el plan sirve mientras el jugador quede bien dentro de la ventana nueva
            int m = MundoInfinitoMod.MARGEN_REBASE + 2;
            int npcx = pcx - dcx, npcy = pcy - dcy;
            if(npcx < m || npcy < m || npcx >= n - m || npcy >= n - m) cancelar();
            return false;
        }
        if(margen > MARGEN_PREP) return false;
        if(Time.time - Mundos.ultimoRebase < 720f) return false;   // no se prepara justo después de reubicar
        if(Streamer.restaurando || Bases.ocupada()) return false;
        if(margen <= MundoInfinitoMod.MARGEN_REBASE + 2 || acercandose(pcx, pcy, n)) planificar();
        return false;
    }

    private static boolean acercandose(int pcx, int pcy, int n){
        Motor m = Streamer.motor;
        if(m.velocidad() <= Motor.UMBRAL_MOVIMIENTO) return false;
        return (pcx < MARGEN_PREP && m.vx < 0) || (pcx >= n - MARGEN_PREP && m.vx > 0)
            || (pcy < MARGEN_PREP && m.vy < 0) || (pcy >= n - MARGEN_PREP && m.vy > 0);
    }

    // ================================================================ origen futuro
    /** Origen virtual de la ventana nueva: el jugador queda descentrado hacia atrás, así tiene más mundo por delante. */
    static void calcularOrigen(double[] pos){
        final int tam = Streamer.meta.tam;
        float hx = 0f, hy = 0f;
        Motor m = Streamer.motor;
        if(Ajustes.anticipar() && m.velocidad() > Motor.UMBRAL_MOVIMIENTO){
            float len = (float)Math.hypot(m.vx, m.vy);
            hx = m.vx / len;
            hy = m.vy / len;
        }
        double objX = tam / 2.0 - hx * SESGO * tam, objY = tam / 2.0 - hy * SESGO * tam;   // posición local deseada del jugador
        nox = Math.floorDiv((int)Math.floor(pos[0] - objX), TAM) * TAM;
        noy = Math.floorDiv((int)Math.floor(pos[1] - objY), TAM) * TAM;
    }

    /** Antes de reubicar: deja fijado el origen (el del plan, o uno nuevo si no hubo tiempo de planificar). */
    static void fijarOrigen(){
        if(hayPlan && epocaPlan == Streamer.epoca) return;
        if(hayPlan) cancelar();
        calcularOrigen(Mundos.posicionVirtual());
    }

    static boolean alBorde(){
        int n = Streamer.nch;
        int pcx = Mathf.clamp((int)Streamer.jugadorX / TAM, 0, n - 1), pcy = Mathf.clamp((int)Streamer.jugadorY / TAM, 0, n - 1);
        return Math.min(Math.min(pcx, pcy), Math.min(n - 1 - pcx, n - 1 - pcy)) <= 2;
    }

    /**
     * ¿Están en memoria todas las regiones de disco de la ventana futura? Si no, pide su lectura (en el hilo de E/S).
     * Reubicar sin ellas obligaría a leer archivos en el hilo del juego: eso era el congelamiento.
     */
    static boolean regionesListas(){
        final int n = Streamer.nch;
        final int vcx0 = nox / TAM, vcy0 = noy / TAM;
        final int r0x = Math.floorDiv(vcx0, REG), r1x = Math.floorDiv(vcx0 + n - 1, REG);
        final int r0y = Math.floorDiv(vcy0, REG), r1y = Math.floorDiv(vcy0 + n - 1, REG);
        boolean ok = true;
        for(int ry = r0y; ry <= r1y; ry++){
            for(int rx = r0x; rx <= r1x; rx++){
                if(!Regiones.lista(rx * REG, ry * REG)) ok = false;   // sin cortocircuito: así se lanzan todas las lecturas
            }
        }
        return ok;
    }

    // ================================================================ planificar
    static void planificar(){
        if(hayPlan || Streamer.meta == null || Streamer.nch <= 0) return;
        final int n = Streamer.nch;
        calcularOrigen(Mundos.posicionVirtual());
        int dx = nox - Streamer.ox, dy = noy - Streamer.oy;
        if(dx == 0 && dy == 0) return;
        dcx = dx / TAM;
        dcy = dy / TAM;
        int m = MundoInfinitoMod.MARGEN_REBASE + 2;
        int npcx = (int)Streamer.jugadorX / TAM - dcx, npcy = (int)Streamer.jugadorY / TAM - dcy;
        if(npcx < m || npcy < m || npcx >= n - m || npcy >= n - m) return;   // quedaría pegado al otro borde: no merece la pena
        epocaPlan = Streamer.epoca;
        if(staged == null || staged.length != n * n){
            staged = new boolean[n * n];
            descartado = new boolean[n * n];
        }else{
            java.util.Arrays.fill(staged, false);
            java.util.Arrays.fill(descartado, false);
        }
        nStaged = 0;
        framesPeligro = 0;
        final int rd = Math.max(Ajustes.carga(), Streamer.rVista) + 2;
        final boolean circ = Ajustes.rebaseCircular();
        if(orden.length < n * n) orden = new long[n * n];
        int cnt = 0;
        for(int k = 0; k < n * n; k++){
            int cx = k % n, cy = k / n;
            if(circ && !Ajustes.dentro(cx - npcx, cy - npcy, rd)) continue;
            long d2 = (long)(cx - npcx) * (cx - npcx) + (long)(cy - npcy) * (cy - npcy);
            orden[cnt++] = (d2 << 16) | k;
        }
        java.util.Arrays.sort(orden, 0, cnt);
        nOrden = cnt;
        inicio = 0;
        hayPlan = true;
        regionesListas();   // lanza ya la lectura anticipada de las regiones de la ventana nueva
    }

    // ================================================================ adelantar (cada fotograma, con presupuesto)
    static long presupuesto(){
        long b = Streamer.presupuesto() * 2L;
        return Math.max(300_000L, Math.min(b, 2_500_000L));
    }

    private static float jx, jy, camx, camy, rJ, rC;

    /** Lo que ve el jugador AHORA (posición viva, no la del último escaneo) y cuánto margen hay que dejarle. */
    private static void leerVista(){
        Unit u = Vars.player == null ? null : Vars.player.unit();
        if(u != null && !Vars.player.dead()){
            jx = u.x / (8f * TAM);
            jy = u.y / (8f * TAM);
        }else{
            jx = Streamer.jugadorX / TAM;
            jy = Streamer.jugadorY / TAM;
        }
        float vel = Streamer.motor.velocidad();   // chunks/s: a más velocidad, más margen
        rJ = Streamer.rVista + 3f + vel;
        if(Core.camera != null){
            camx = Core.camera.position.x / (8f * TAM);
            camy = Core.camera.position.y / (8f * TAM);
            rC = (float)Math.hypot(Core.camera.width, Core.camera.height) / 2f / (8f * TAM) + 2f + vel;
        }else{
            camx = jx;
            camy = jy;
            rC = rJ;
        }
    }

    private static boolean cerca(float x, float y, float ex, float ey, float r){
        float dx = x - ex, dy = y - ey;
        return dx * dx + dy * dy <= r * r;
    }

    private static boolean seguro(int cx, int cy){
        float x = cx + 0.5f, y = cy + 0.5f;
        return !cerca(x, y, jx, jy, rJ) && !cerca(x, y, camx, camy, rC);
    }

    private static boolean peligro(int n){
        leerVista();
        for(int k = 0; k < staged.length; k++){
            if(!staged[k]) continue;
            float x = k % n + 0.5f, y = k / n + 0.5f;
            if(cerca(x, y, jx, jy, rJ - 1.5f) || cerca(x, y, camx, camy, rC - 1.5f)) return true;
        }
        return false;
    }

    private static boolean tieneEdificios(int cx, int cy){
        int x0 = cx * TAM, y0 = cy * TAM;
        for(int y = y0; y < y0 + TAM; y++){
            for(int x = x0; x < x0 + TAM; x++){
                Tile t = Vars.world.rawTile(x, y);
                if(t.build != null) return true;
            }
        }
        return false;
    }

    private static void pedirTerreno(long vk){
        synchronized(Streamer.cola){
            if(Streamer.colaBaja.size() > 160) return;
            if(Streamer.pendBaja.add(vk)){
                Streamer.colaBaja.addLast(vk);
                Streamer.cola.notifyAll();
            }
        }
    }

    static void avanzar(long finNs){
        if(!hayPlan) return;
        if(epocaPlan != Streamer.epoca){ cancelar(); return; }
        if(Streamer.restaurando || Mundos.rebase != null || Mundos.viajando || Streamer.meta == null) return;
        final int n = Streamer.nch;
        // ¿lo ya reescrito se acerca a lo que se ve? Entonces se reubica YA: lo gordo está hecho
        if(nStaged > 0 && peligro(n)){
            if(++framesPeligro > 90){ cancelar(); return; }
            Mundos.rebasar();
            return;
        }
        framesPeligro = 0;
        leerVista();
        final int mid = CacheChunks.mundo();
        final int vcx0 = nox / TAM, vcy0 = noy / TAM;
        boolean avanza = true;
        int vistos = 0;
        for(int i = inicio; i < nOrden && vistos < 160 && System.nanoTime() < finNs; i++, vistos++){
            int k = (int)(orden[i] & 0xffff);
            if(staged[k] || descartado[k]){
                if(avanza) inicio = i + 1;
                continue;
            }
            int cx = k % n, cy = k / n;
            if(!seguro(cx, cy)){ avanza = false; continue; }
            if(Streamer.actual != null && Streamer.actual.cx == cx && Streamer.actual.cy == cy){ avanza = false; continue; }
            if(tieneEdificios(cx, cy)){
                // bajo un edificio no se puede igualar el muro: ese chunk se copia al reubicar, ya sin el edificio
                descartado[k] = true;
                if(avanza) inicio = i + 1;
                continue;
            }
            long vk = Coord.claveVirtual(vcx0 + cx, vcy0 + cy);
            if(!CacheChunks.tiene(mid, vk)){ pedirTerreno(vk); avanza = false; continue; }
            ChunkData cd = CacheChunks.copiar(mid, vk, cx, cy, Streamer.epoca);
            if(cd == null){ avanza = false; continue; }
            if(!Entidades.despejar(cx, cy)){ cd.liberar(); avanza = false; continue; }
            Streamer.aplicarTramo(cd, 0, TAM * TAM);
            cd.liberar();
            staged[k] = true;
            nStaged++;
            Streamer.generados.remove(Streamer.clave(cx, cy));   // el terreno de esa casilla ya no es el de la ventana actual
            if(avanza) inicio = i + 1;
        }
    }

    // ================================================================ cancelar / comprometer
    /** Descarta el plan. Lo ya reescrito vuelve a cargarse con el origen viejo (si no, quedaría terreno de otra zona). */
    static void cancelar(){
        if(!hayPlan) return;
        hayPlan = false;
        if(staged != null){
            for(int k = 0; k < staged.length; k++){
                if(!staged[k]) continue;
                staged[k] = false;
                reparar.add(k);
            }
        }
        nStaged = 0;
        nOrden = 0;
        inicio = 0;
    }

    /**
     * Al reubicar: entrega qué chunks destino ya tienen su terreno (o null si ninguno) y da el plan por consumido.
     * Llamar ANTES de subir la época del Streamer.
     */
    static boolean[] tomar(){
        boolean[] r = null;
        if(hayPlan && epocaPlan == Streamer.epoca && staged != null && nStaged > 0) r = staged.clone();
        hayPlan = false;
        if(staged != null) java.util.Arrays.fill(staged, false);
        nStaged = 0;
        nOrden = 0;
        inicio = 0;
        reparar.clear();
        return r;
    }
}
