package mundoinfinito;

import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Muestreo;
import mundoinfinito.MundoInfinitoMod.Muestreo.Spawn;
import mundoinfinito.MundoInfinitoMod.Reg;
import mundoinfinito.MundoInfinitoMod.Regiones;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

/**
 * HIBERNACIÓN DE ESTRUCTURAS (bases enemigas y ruinas). Mindustry actualiza TODOS los Building vivos, así que una base lejana
 * costaba CPU aunque nadie la viera. Aquí, cuando una estructura YA está construida y el jugador se aleja de ella, se guarda
 * entera (edificios con su estado + sus guardias) en el almacén de regiones, en coordenadas VIRTUALES, y se quita del mundo.
 * Al volver a acercarse (con el terreno de su huella ya generado) se vuelve a colocar, de a poco por fotograma.
 *
 * Reglas que evitan perder o duplicar cosas:
 *  - Mientras una estructura duerme (o despierta), sus chunks quedan "dormidos": capturarEnMemoria no los reescribe y la
 *    reubicación de la ventana no restaura sus edificios (los restaura esta clase).
 *  - Mientras se despierta no se reubica ni se guarda (ocupada()), para no capturar a medias.
 *  - Una estructura solo duerme si ya está terminada (Estructuras.colocadas) y no hay trabajos de colocación en marcha.
 *  - Las claves son virtuales: sobreviven a mover la ventana. No se guarda qué duerme: si se cierra el juego, las piezas ya
 *    están en las regiones y al abrir se restauran como siempre (luego vuelven a dormir si están lejos).
 */
final class Bases{
    static final class Dorm{
        long id;
        double vx, vy;
        int radio;
        boolean despertando;
        final HashSet<Long> chunks = new HashSet<>();
    }

    static final HashMap<Long, Dorm> dormidas = new HashMap<>();
    static final HashMap<Long, Integer> chunksDormidos = new HashMap<>();   // chunk virtual -> estructuras que lo usan
    static final ArrayDeque<Reg> pend = new ArrayDeque<>();
    static final Seq<Estructuras.RegU> unidadesPend = new Seq<>();
    static Dorm enCurso;
    static long ultimaDormida;

    /**
     * Hibernación POR TROZOS. Antes dormir() serializaba TODOS los edificios de la estructura y los quitaba del mundo en UN
     * fotograma (cientos de Reg.desde + setAir: decenas de ms = el congelamiento al alejarse de una base), y cada 0,5 s se
     * recorrían todos los edificios enemigos calculando su estructura por ruido. Ahora:
     *   escaneo: reparte el recorrido por fotogramas (pocos edificios cada vez);
     *   fase A:  serializa los edificios dentro del presupuesto del fotograma (el mundo NO cambia; si el jugador vuelve, se cancela);
     *   confirmar: anota la estructura en el almacén (barato) y marca sus chunks como dormidos;
     *   fase C:  quita los edificios del mundo dentro del presupuesto del fotograma.
     */
    static final class Hib{
        long id;
        int cx, cy, radio;
        Seq<Building> edificios;
        final Seq<Building> vivos = new Seq<>();
        final Seq<Reg> regs = new Seq<>();
        int idx, idxRetirar, espera;
        Dorm dorm;   // != null: ya confirmada, solo falta retirarla del mundo
    }
    static Hib hib;

    // escaneo repartido en fotogramas
    static Seq<Building> escLista;
    static int escI;
    static HashMap<Long, Seq<Building>> escGrupos;
    static HashMap<Long, int[]> escDatos;
    static int escSd;
    static Spawn escSp;
    static long escFin;
    static boolean puedeEscanear;
    static final int[] escCen = new int[3], escEst = new int[5];

    private Bases(){}

    /** Mundo nuevo / continuar / cambio de dimensión. */
    static void limpiar(){
        dormidas.clear();
        chunksDormidos.clear();
        pend.clear();
        unidadesPend.clear();
        enCurso = null;
        hib = null;
        reiniciarEscaneo();
        puedeEscanear = false;
    }

    private static void reiniciarEscaneo(){
        escLista = null;
        escGrupos = null;
        escDatos = null;
    }

    /** Al mover la ventana las claves virtuales siguen valiendo: no hay nada que rehacer. */
    static void reiniciarVentana(){
        // Lo que estaba a medio despertar (cola en coordenadas virtuales) sigue siendo válido; se retoma solo.
        // Lo que estaba a medio hibernar SIN confirmar usa coordenadas locales de la ventana vieja: se descarta (nada cambió en el mundo).
        if(hib != null && hib.dorm == null) hib = null;
        reiniciarEscaneo();
    }

    static boolean dormida(long vk){
        return chunksDormidos.containsKey(vk);
    }

    /** ¿Hay estructuras despertando? Entonces no se reubica la ventana ni se guarda. */
    static boolean ocupada(){
        return enCurso != null || !pend.isEmpty() || !unidadesPend.isEmpty() || (hib != null && hib.dorm != null);
    }

    // ---------------------------------------------------------------- utilidades
    /** ¿La posición virtual está a menos de radioActivo()+extra chunks (círculo) del jugador? */
    static boolean cerca(double vx, double vy, int extra){
        int lcx = Math.floorDiv((int)Math.floor(vx - Streamer.ox), MundoInfinitoMod.TAM_CHUNK);
        int lcy = Math.floorDiv((int)Math.floor(vy - Streamer.oy), MundoInfinitoMod.TAM_CHUNK);
        int pcx = (int)Streamer.jugadorX / MundoInfinitoMod.TAM_CHUNK, pcy = (int)Streamer.jugadorY / MundoInfinitoMod.TAM_CHUNK;
        return Ajustes.dentro(lcx - pcx, lcy - pcy, Entidades.radioActivo() + extra);
    }

    private static long claveChunk(int x, int y){
        return Coord.claveVirtual(Math.floorDiv(x, MundoInfinitoMod.TAM_CHUNK), Math.floorDiv(y, MundoInfinitoMod.TAM_CHUNK));
    }

    /** Estructura (id, centro, radio) a la que pertenece una posición virtual, o null. out = {cx, cy, radio, i, j}. */
    private static boolean estructuraDe(int sd, Spawn sp, double vx, double vy, int[] out, int[] cen){
        int c = sp.celdaE;
        int ci = Math.floorDiv((int)Math.floor(vx), c), cj = Math.floorDiv((int)Math.floor(vy), c);
        double mejor = Double.MAX_VALUE;
        boolean hay = false;
        for(int i = ci - 1; i <= ci + 1; i++){
            for(int j = cj - 1; j <= cj + 1; j++){
                if(Estructuras.tipo(sd, sp, i, j, cen) == Estructuras.T_NADA) continue;
                double d = Math.hypot(cen[0] - vx, cen[1] - vy);
                if(d > cen[2] * 1.05 + 4 || d >= mejor) continue;
                mejor = d;
                out[0] = cen[0]; out[1] = cen[1]; out[2] = cen[2]; out[3] = i; out[4] = j;
                hay = true;
            }
        }
        return hay;
    }

    // ---------------------------------------------------------------- ciclo (cada ~0,5 s)
    static void tick(){
        puedeEscanear = false;
        Meta m = Streamer.meta;
        if(m == null || !m.dimensional() || !Streamer.activo || Streamer.restaurando || Mundos.rebase != null || Mundos.viajando) return;
        puedeEscanear = false;
        if(!Estructuras.cola.isEmpty() || !Estructuras.enCola.isEmpty()) return;
        despertarCercanas();
        if(enCurso != null) return;     // una cosa a la vez
        puedeEscanear = true;           // el escaneo y la hibernación avanzan por trozos en procesar()
    }

    /**
     * Recorre los edificios enemigos/abandonados A TROZOS (una porción de tiempo por fotograma) y, al terminar la vuelta, empieza a
     * hibernar la primera estructura terminada y lejana. Un ciclo completo como máximo por segundo.
     */
    private static void escanearLejanas(Meta m, long finNs){
        if(escLista == null){
            if(System.nanoTime() - escFin < 1_000_000_000L) return;
            escLista = new Seq<>();
            for(Team eq : new Team[]{Team.crux, Team.malis, Team.derelict}) escLista.addAll(Vars.state.teams.get(eq).buildings);
            escI = 0;
            escGrupos = new HashMap<>();
            escDatos = new HashMap<>();
            escSd = Muestreo.semDim(m.semilla, m.dim);
            escSp = Muestreo.spawn(escSd, m.dim);
        }
        int hechos = 0;
        while(escI < escLista.size){
            if((++hechos & 7) == 0 && System.nanoTime() >= finNs) return;   // sigue en el próximo fotograma
            Building b = escLista.get(escI++);
            if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
            double vx = Coord.virtualX(b.x), vy = Coord.virtualY(b.y);
            if(!estructuraDe(escSd, escSp, vx, vy, escEst, escCen)) continue;
            long id = Estructuras.clave(escEst[3], escEst[4], 0);
            if(!Estructuras.colocadas.contains(id)) continue;       // aún no está terminada
            if(cerca(escEst[0], escEst[1], 2)) continue;            // histéresis: duerme 2 chunks más lejos de lo que despierta
            Seq<Building> g = escGrupos.get(id);
            if(g == null){ g = new Seq<>(); escGrupos.put(id, g); escDatos.put(id, new int[]{escEst[0], escEst[1], escEst[2]}); }
            g.add(b);
        }
        // vuelta completa
        HashMap<Long, Seq<Building>> grupos = escGrupos;
        HashMap<Long, int[]> datos = escDatos;
        reiniciarEscaneo();
        escFin = System.nanoTime();
        for(java.util.Map.Entry<Long, Seq<Building>> e : grupos.entrySet()){
            if(dormidas.containsKey(e.getKey())) continue;
            int[] c = datos.get(e.getKey());
            empezarHibernacion(e.getKey(), c[0], c[1], c[2], e.getValue());
            return;   // una por vuelta
        }
    }

    private static void empezarHibernacion(long id, int cx, int cy, int radio, Seq<Building> edificios){
        Hib h = new Hib();
        h.id = id; h.cx = cx; h.cy = cy; h.radio = radio;
        h.edificios = edificios;
        hib = h;
    }

    /** Avanza la hibernación en curso (fases A y C) dentro del presupuesto del fotograma. */
    private static void avanzarHibernacion(long finNs){
        Hib h = hib;
        if(h == null) return;
        try{
            if(h.dorm == null){
                // fase A: serializar. El mundo no cambia, así que si el jugador vuelve se cancela sin consecuencias.
                if(cerca(h.cx, h.cy, 1) || Streamer.restaurando || Mundos.rebase != null || Mundos.viajando){ hib = null; return; }
                int n = 0;
                while(h.idx < h.edificios.size){
                    if((++n & 3) == 0 && System.nanoTime() >= finNs) break;
                    Building b = h.edificios.get(h.idx++);
                    if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
                    h.regs.add(Reg.desde(b, Streamer.ox, Streamer.oy));
                    h.vivos.add(b);
                }
                if(h.idx < h.edificios.size) return;
                if(!confirmarHibernacion(h)){
                    if(++h.espera > 600) hib = null;   // las regiones de disco tardan demasiado: se vuelve a intentar en otro ciclo
                    return;
                }
            }
            // fase C: quitar del mundo, por trozos
            int n = 0;
            while(h.idxRetirar < h.vivos.size){
                if((++n & 3) == 0 && System.nanoTime() >= finNs) return;
                Building b = h.vivos.get(h.idxRetirar++);
                if(b.tile != null && b.tile.build == b) b.tile.setAir();
            }
            hib = null;
            ultimaDormida = System.nanoTime();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo hibernar una estructura", t);
            hib = null;
        }
    }

    /**
     * Anota la estructura en el almacén y marca sus chunks como dormidos (barato). @return false si alguna región de disco aún no
     * está en memoria: leerla aquí (hilo del juego) congelaría el juego, así que se pide la lectura y se reintenta en el siguiente fotograma.
     */
    private static boolean confirmarHibernacion(Hib h){
        long t0 = Perfil.t();
        HashSet<Long> tocados = new HashSet<>();
        for(Reg r : h.regs) tocados.add(claveChunk(r.x, r.y));
        // guardias de la casa de esta base
        Seq<Unit> guardias = new Seq<>();
        for(Unit u : Groups.unit){
            if(!u.isValid() || !(u.controller() instanceof Estructuras.Guardia)) continue;
            Estructuras.Guardia g = (Estructuras.Guardia)u.controller();
            if(Math.hypot(g.hvx - h.cx, g.hvy - h.cy) > 3.0) continue;
            guardias.add(u);
            tocados.add(claveChunk((int)Math.floor(Coord.virtualX(u.x)), (int)Math.floor(Coord.virtualY(u.y))));
        }
        boolean listas = true;
        for(long vk : tocados){
            if(!Regiones.lista(Coord.vcxDeClave(vk), Coord.vcyDeClave(vk))) listas = false;   // sin cortocircuito: lanza todas las lecturas
        }
        if(!listas) return false;

        Dorm d = new Dorm();
        d.id = h.id; d.vx = h.cx; d.vy = h.cy; d.radio = h.radio;
        double ahora = Mundos.reloj();
        // 1) antes se borra lo enemigo que ya hubiera en esos chunks (la copia de un guardado anterior), para no duplicarlo al despertar
        for(long vk : tocados){
            Regiones.ChunkGuardado cg = Regiones.chunk(Coord.vcxDeClave(vk), Coord.vcyDeClave(vk), true);
            for(int i = cg.edificios.size - 1; i >= 0; i--) if(cg.edificios.get(i).equipo != Team.sharded.id) cg.edificios.remove(i);
            for(int i = cg.unidades.size - 1; i >= 0; i--) if(cg.unidades.get(i).clase == 0) cg.unidades.remove(i);
            cg.tick = ahora;
        }
        for(Reg r : h.regs) Regiones.chunk(Math.floorDiv(r.x, MundoInfinitoMod.TAM_CHUNK), Math.floorDiv(r.y, MundoInfinitoMod.TAM_CHUNK), true).edificios.add(r);
        for(Unit u : guardias){
            Estructuras.RegU ru = Estructuras.RegU.desde(u, Streamer.ox, Streamer.oy);
            Regiones.chunk(Math.floorDiv((int)Math.floor(ru.vx), MundoInfinitoMod.TAM_CHUNK), Math.floorDiv((int)Math.floor(ru.vy), MundoInfinitoMod.TAM_CHUNK), true).unidades.add(ru);
        }
        // 2) marcar los chunks como dormidos ANTES de quitar nada
        d.chunks.addAll(tocados);
        for(long vk : d.chunks) chunksDormidos.merge(vk, 1, Integer::sum);
        dormidas.put(h.id, d);
        // 3) los guardias salen ya (pocos); los edificios, por trozos en la fase C
        for(Unit u : guardias) u.remove();
        h.dorm = d;
        Perfil.evento("hibernar: confirmar", t0);
        return true;
    }

    private static void despertarCercanas(){
        if(enCurso != null) return;
        for(Dorm d : dormidas.values()){
            if(hib != null && hib.dorm == d) continue;   // todavía se está retirando del mundo: primero termina de dormir
            if(!cerca(d.vx, d.vy, 0)) continue;
            if(!Estructuras.listo((int)d.vx, (int)d.vy, d.radio + 4)) continue;   // el terreno de su huella aún no está generado
            despertar(d);
            return;
        }
    }

    private static void despertar(Dorm d){
        double ahora = Mundos.reloj();
        for(long vk : d.chunks){
            Regiones.ChunkGuardado cg = Regiones.chunk(Coord.vcxDeClave(vk), Coord.vcyDeClave(vk), false);
            if(cg == null) continue;
            for(Reg r : cg.edificios){
                if(r.equipo == Team.sharded.id) continue;
                r.dt = cg.tick > 0 ? Math.max(0.0, ahora - cg.tick) : 0.0;
                pend.addLast(r);
            }
            for(Estructuras.RegU u : cg.unidades) if(u.clase == 0) unidadesPend.add(u);
        }
        d.despertando = true;
        enCurso = d;
    }

    /** Coloca las piezas pendientes dentro del presupuesto del fotograma. */
    static void procesar(long finNs){
        if(enCurso == null){
            // sin nada que despertar: hibernación y escaneo por trozos
            if(hib != null) avanzarHibernacion(finNs);
            else if(puedeEscanear && Streamer.meta != null) escanearLejanas(Streamer.meta, finNs);
            return;
        }
        while(System.nanoTime() < finNs && !pend.isEmpty()) Mundos.restaurarEdificio(pend.pollFirst());
        if(!pend.isEmpty()) return;
        // enlaces entre edificios (nodos, puentes...) ya con todo colocado
        for(Reg r : Streamer.configsPendientes){
            try{
                mindustry.world.Tile t = Vars.world.tile(r.x - Streamer.ox, r.y - Streamer.oy);
                if(t != null && t.build != null) t.build.configured(null, r.configObjeto());
            }catch(Throwable ignored){}
        }
        Streamer.configsPendientes.clear();
        for(Estructuras.RegU u : unidadesPend) Estructuras.restaurarUnidad(u);
        unidadesPend.clear();
        Dorm d = enCurso;
        enCurso = null;
        dormidas.remove(d.id);
        for(long vk : d.chunks){
            Integer n = chunksDormidos.get(vk);
            if(n == null || n <= 1) chunksDormidos.remove(vk); else chunksDormidos.put(vk, n - 1);
        }
    }
}
