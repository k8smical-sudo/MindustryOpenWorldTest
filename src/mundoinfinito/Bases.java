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

    private Bases(){}

    /** Mundo nuevo / continuar / cambio de dimensión. */
    static void limpiar(){
        dormidas.clear();
        chunksDormidos.clear();
        pend.clear();
        unidadesPend.clear();
        enCurso = null;
    }

    /** Al mover la ventana las claves virtuales siguen valiendo: no hay nada que rehacer. */
    static void reiniciarVentana(){
        // Lo que estaba a medio despertar (cola en coordenadas virtuales) sigue siendo válido; se retoma solo.
    }

    static boolean dormida(long vk){
        return chunksDormidos.containsKey(vk);
    }

    /** ¿Hay estructuras despertando? Entonces no se reubica la ventana ni se guarda. */
    static boolean ocupada(){
        return enCurso != null || !pend.isEmpty() || !unidadesPend.isEmpty();
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
        Meta m = Streamer.meta;
        if(m == null || !m.dimensional() || !Streamer.activo || Streamer.restaurando || Mundos.rebase != null || Mundos.viajando) return;
        if(!Estructuras.cola.isEmpty() || !Estructuras.enCola.isEmpty()) return;
        despertarCercanas();
        if(enCurso != null) return;     // una cosa a la vez
        dormirLejanas(m);
    }

    private static void dormirLejanas(Meta m){
        int sd = Muestreo.semDim(m.semilla, m.dim);
        Spawn sp = Muestreo.spawn(sd, m.dim);
        int[] cen = new int[3], est = new int[5];
        HashMap<Long, Seq<Building>> grupos = new HashMap<>();
        HashMap<Long, int[]> datos = new HashMap<>();
        for(Team eq : new Team[]{Team.crux, Team.malis, Team.derelict}){
            for(Building b : Vars.state.teams.get(eq).buildings){
                if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
                double vx = Coord.virtualX(b.x), vy = Coord.virtualY(b.y);
                if(!estructuraDe(sd, sp, vx, vy, est, cen)) continue;
                long id = Estructuras.clave(est[3], est[4], 0);
                if(!Estructuras.colocadas.contains(id)) continue;       // aún no está terminada
                if(cerca(est[0], est[1], 2)) continue;                  // histéresis: duerme 2 chunks más lejos de lo que despierta
                Seq<Building> g = grupos.get(id);
                if(g == null){ g = new Seq<>(); grupos.put(id, g); datos.put(id, new int[]{est[0], est[1], est[2]}); }
                g.add(b);
            }
        }
        for(java.util.Map.Entry<Long, Seq<Building>> e : grupos.entrySet()){
            if(dormidas.containsKey(e.getKey())) continue;
            int[] c = datos.get(e.getKey());
            dormir(e.getKey(), c[0], c[1], c[2], e.getValue());
            return;   // una por ciclo
        }
    }

    /** Guarda la estructura entera en el almacén y la quita del mundo. */
    private static void dormir(long id, int cx, int cy, int radio, Seq<Building> edificios){
        try{
            Dorm d = new Dorm();
            d.id = id; d.vx = cx; d.vy = cy; d.radio = radio;
            double ahora = Mundos.reloj();
            // 1) edificios -> chunk donde está cada uno. Antes se borra lo enemigo que ya hubiera en esos chunks (la copia de un
            //    guardado anterior), para no duplicarlo al despertar.
            HashSet<Long> tocados = new HashSet<>();
            Seq<Reg> regs = new Seq<>();
            for(Building b : edificios){
                Reg r = Reg.desde(b, Streamer.ox, Streamer.oy);
                regs.add(r);
                tocados.add(claveChunk(r.x, r.y));
            }
            // 2) guardias de la casa de esta base
            Seq<Unit> guardias = new Seq<>();
            for(Unit u : Groups.unit){
                if(!u.isValid() || !(u.controller() instanceof Estructuras.Guardia)) continue;
                Estructuras.Guardia g = (Estructuras.Guardia)u.controller();
                if(Math.hypot(g.hvx - cx, g.hvy - cy) > 3.0) continue;
                guardias.add(u);
                tocados.add(claveChunk((int)Math.floor(Coord.virtualX(u.x)), (int)Math.floor(Coord.virtualY(u.y))));
            }
            for(long vk : tocados){
                Regiones.ChunkGuardado cg = Regiones.chunk(Coord.vcxDeClave(vk), Coord.vcyDeClave(vk), true);
                for(int i = cg.edificios.size - 1; i >= 0; i--) if(cg.edificios.get(i).equipo != Team.sharded.id) cg.edificios.remove(i);
                for(int i = cg.unidades.size - 1; i >= 0; i--) if(cg.unidades.get(i).clase == 0) cg.unidades.remove(i);
                cg.tick = ahora;
            }
            for(Reg r : regs) Regiones.chunk(Math.floorDiv(r.x, MundoInfinitoMod.TAM_CHUNK), Math.floorDiv(r.y, MundoInfinitoMod.TAM_CHUNK), true).edificios.add(r);
            for(Unit u : guardias){
                Estructuras.RegU ru = Estructuras.RegU.desde(u, Streamer.ox, Streamer.oy);
                Regiones.chunk(Math.floorDiv((int)Math.floor(ru.vx), MundoInfinitoMod.TAM_CHUNK), Math.floorDiv((int)Math.floor(ru.vy), MundoInfinitoMod.TAM_CHUNK), true).unidades.add(ru);
            }
            // 3) marcar los chunks como dormidos ANTES de quitar nada
            d.chunks.addAll(tocados);
            for(long vk : d.chunks) chunksDormidos.merge(vk, 1, Integer::sum);
            dormidas.put(id, d);
            // 4) quitar del mundo
            for(Unit u : guardias) u.remove();
            Seq<Building> copia = new Seq<>(edificios);
            for(Building b : copia){
                if(b.tile != null && b.tile.build == b) b.tile.setAir();
            }
            ultimaDormida = System.nanoTime();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudo hibernar una estructura", t);
        }
    }

    private static void despertarCercanas(){
        if(enCurso != null) return;
        for(Dorm d : dormidas.values()){
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
        if(enCurso == null) return;
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
