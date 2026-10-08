package mundoinfinito;

import arc.files.Fi;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.Tile;
import mundoinfinito.MundoInfinitoMod.Almacen;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Muestreo;
import mundoinfinito.MundoInfinitoMod.Muestreo.Spawn;
import mundoinfinito.MundoInfinitoMod.Streamer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Estructuras del mundo: bases enemigas y ruinas perdidas. Todo sale de la semilla (función pura de celda):
 * cada mundo tiene su propio ritmo (tamaño de celda y probabilidad) y zonas con más o menos estructuras.
 */
final class Estructuras{
    static final int T_NADA = 0, T_BASE = 1, T_RUINA = 2;
    static final int DIST_MIN = 120;   // nada de estructuras encima del spawn

    // ---------------------------------------------------------------- geometría (pura, sin objetos)
    /** Tipo de estructura de la celda (i, j) o T_NADA. Rellena cen[0..2] = x, y, radio de despeje. */
    static int tipo(int s, Spawn sp, int i, int j, int[] cen){
        int c = sp.celdaE;
        boolean forzada = i == Math.floorDiv(sp.rx, c) && j == Math.floorDiv(sp.ry, c);
        int t;
        if(forzada){
            t = T_RUINA;
        }else{
            if(Muestreo.rnd(i, j, Muestreo.sem(s, 9801)) > sp.probE * 1.65f) return T_NADA;   // descarte barato
            float cx = (i + 0.5f) * c, cy = (j + 0.5f) * c;
            float dsp = (float)Math.hypot(cx, cy);
            float reg = Muestreo.fbm(Muestreo.sem(s, 9800), 900f, 2, cx, cy);
            float p = sp.probE * (0.35f + 1.30f * reg);
            if(Muestreo.rnd(i, j, Muestreo.sem(s, 9801)) > p) return T_NADA;
            float pBase = Mathf.clamp((dsp - 150f) / 600f, 0.08f, 0.65f);
            t = Muestreo.rnd(i, j, Muestreo.sem(s, 9802)) < pBase ? T_BASE : T_RUINA;
        }
        int radio = t == T_BASE ? 20 + (int)(Muestreo.rnd(i, j, Muestreo.sem(s, 9803)) * 10f)
                                : 14 + (int)(Muestreo.rnd(i, j, Muestreo.sem(s, 9803)) * 8f);
        int margen = radio + 6, rango = Math.max(1, c - 2 * margen);
        int px, py;
        if(forzada){
            px = Mathf.clamp(sp.rx, i * c + margen, i * c + c - margen);
            py = Mathf.clamp(sp.ry, j * c + margen, j * c + c - margen);
        }else{
            px = i * c + margen + (int)(Muestreo.rnd(i, j, Muestreo.sem(s, 9804)) * rango);
            py = j * c + margen + (int)(Muestreo.rnd(i, j, Muestreo.sem(s, 9805)) * rango);
            if(Math.hypot(px, py) < DIST_MIN) return T_NADA;
        }
        cen[0] = px;
        cen[1] = py;
        cen[2] = radio;
        return t;
    }

    /** Desplazamiento (dx, dy) de la zona de núcleo de una ruina respecto a su centro: uno de 4 lados. */
    static int ladoZona(int s, int i, int j){
        return (int)(Muestreo.rnd(i, j, Muestreo.sem(s, 9806)) * 4f) & 3;
    }

    static final int[] ZX = {10, -10, 0, 0}, ZY = {0, 0, 10, -10};

    /**
     * Qué hay en (x, y): 0 nada, 1 terreno despejado de estructura, 2 zona de núcleo (suelo donde se puede colocar un núcleo).
     * aux[5..11] guarda la celda actual (aux[11] = 0 al empezar cada chunk): un chunk casi siempre toca una sola celda.
     */
    static int huella(int s, Spawn sp, int x, int y, float[] aux){
        int c = sp.celdaE;
        int i = Math.floorDiv(x, c), j = Math.floorDiv(y, c);
        int t, px, py, r;
        if(aux[11] != 0f && aux[5] == i && aux[6] == j){
            t = (int)aux[7]; px = (int)aux[8]; py = (int)aux[9]; r = (int)aux[10];
        }else{
            int[] cen = new int[3];
            t = tipo(s, sp, i, j, cen);
            px = cen[0]; py = cen[1]; r = cen[2];
            aux[5] = i; aux[6] = j; aux[7] = t; aux[8] = px; aux[9] = py; aux[10] = r; aux[11] = 1f;
        }
        if(t == T_NADA) return 0;
        float dx = x - px, dy = y - py;
        float d2 = dx * dx + dy * dy;
        if(d2 > r * r * 1.7f) return 0;
        if(t == T_RUINA){
            int l = ladoZona(s, i, j);
            if(Math.abs(x - (px + ZX[l])) <= 4 && Math.abs(y - (py + ZY[l])) <= 4) return 2;
        }
        float d = (float)Math.sqrt(d2) * (1f + (Muestreo.fbm(Muestreo.sem(s, 9810), 9f, 2, x, y) - 0.5f) * 0.30f);
        return d <= r ? 1 : 0;
    }

    // ================================================================ estado por dimensión (se guarda en estructuras.dat)
    static final class Punto{
        String nombre;
        double vx, vy;      // posición VIRTUAL del portal
    }

    static final HashSet<Long> colocadas = new HashSet<>();   // estructuras ya construidas (aunque luego las destruyan)
    static final HashSet<Long> enCola = new HashSet<>();
    static final ArrayDeque<Trabajo> cola = new ArrayDeque<>();
    static final Seq<Punto> puntos = new Seq<>();             // checkpoints de esta dimensión

    static void cargar(Meta m){
        colocadas.clear();
        enCola.clear();
        cola.clear();
        puntos.clear();
        if(m == null || !m.dimensional()) return;
        Fi f = Almacen.archivoEstructuras(m);
        if(!f.exists()) return;
        try{
            DataInputStream in = new DataInputStream(new GZIPInputStream(f.read()));
            in.readInt();
            int n = in.readInt();
            for(int i = 0; i < n; i++) colocadas.add(in.readLong());
            int np = in.readInt();
            for(int i = 0; i < np; i++){
                Punto p = new Punto();
                p.nombre = in.readUTF();
                p.vx = in.readDouble();
                p.vy = in.readDouble();
                puntos.add(p);
            }
            in.close();
        }catch(Throwable t){
            Log.err("[MundoInfinito] Estructuras ilegibles; se reinician", t);
            colocadas.clear();
            puntos.clear();
        }
    }

    static byte[] bytes(){
        try{
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos));
            out.writeInt(1);
            out.writeInt(colocadas.size());
            for(long id : colocadas) out.writeLong(id);
            out.writeInt(puntos.size);
            for(Punto p : puntos){
                out.writeUTF(p.nombre);
                out.writeDouble(p.vx);
                out.writeDouble(p.vy);
            }
            out.close();
            return bos.toByteArray();
        }catch(Throwable t){
            Log.err("[MundoInfinito] No se pudieron serializar las estructuras", t);
            return null;
        }
    }

    static void reiniciarCola(){
        cola.clear();
        enCola.clear();
    }

    static Punto puntoCerca(double vx, double vy){
        for(Punto p : puntos) if(Math.hypot(p.vx - vx, p.vy - vy) < 6.0) return p;
        return null;
    }

    /** Registra un checkpoint en un portal (si ya había uno ahí, no hace nada). @return true si es nuevo. */
    static boolean registrarPunto(double vx, double vy){
        if(puntoCerca(vx, vy) != null) return false;
        Punto p = new Punto();
        p.nombre = "Checkpoint " + (puntos.size + 1);
        p.vx = vx;
        p.vy = vy;
        puntos.add(p);
        return true;
    }

    // ================================================================ unidades de guardia
    /** IA de guardia: se queda cerca de su casa, ataca a quien se acerque y vuelve. No persigue por todo el mapa. */
    static final class Guardia extends AIController{
        final double hvx, hvy;   // casa en coordenadas VIRTUALES (sobrevive a mover la ventana)
        final float fuga;        // radio máximo de persecución, en tiles
        final Vec2 casa = new Vec2();

        Guardia(double hvx, double hvy, float fuga){
            this.hvx = hvx;
            this.hvy = hvy;
            this.fuga = fuga;
        }

        @Override
        public void updateMovement(){
            casa.set((float)((hvx - Streamer.ox) * 8.0), (float)((hvy - Streamer.oy) * 8.0));
            float d = unit.dst(casa);
            if(target != null && d <= fuga * 8f){
                moveTo(target, unit.type.range * 0.7f);
                faceTarget();
            }else if(d > 6f * 8f){
                moveTo(casa, 4f * 8f);
                faceMovement();
            }
        }
    }

    /** Unidad de guardia guardada junto al chunk donde estaba. */
    static final class RegU{
        String tipo;
        int equipo;
        double vx, vy, hvx, hvy;
        float vida, fuga;

        static RegU desde(Unit u, int ox, int oy){
            RegU r = new RegU();
            Guardia g = (Guardia)u.controller();
            r.tipo = u.type.name;
            r.equipo = u.team.id;
            r.vx = ox + u.x / 8.0;
            r.vy = oy + u.y / 8.0;
            r.hvx = g.hvx;
            r.hvy = g.hvy;
            r.vida = u.health;
            r.fuga = g.fuga;
            return r;
        }

        void escribir(DataOutputStream out) throws java.io.IOException{
            out.writeUTF(tipo);
            out.writeByte(equipo);
            out.writeDouble(vx);
            out.writeDouble(vy);
            out.writeDouble(hvx);
            out.writeDouble(hvy);
            out.writeFloat(vida);
            out.writeFloat(fuga);
        }

        static RegU leer(DataInputStream in) throws java.io.IOException{
            RegU r = new RegU();
            r.tipo = in.readUTF();
            r.equipo = in.readUnsignedByte();
            r.vx = in.readDouble();
            r.vy = in.readDouble();
            r.hvx = in.readDouble();
            r.hvy = in.readDouble();
            r.vida = in.readFloat();
            r.fuga = in.readFloat();
            return r;
        }
    }

    static void crearGuardia(String tipo, Team equipo, double vx, double vy, double hvx, double hvy, float fuga, float vida){
        try{
            UnitType t = Vars.content.unit(tipo);
            if(t == null) return;
            double lx = vx - Streamer.ox, ly = vy - Streamer.oy;
            if(lx < 2 || ly < 2 || lx > Streamer.meta.tam - 2 || ly > Streamer.meta.tam - 2) return;
            Unit u = t.spawn(equipo, (float)(lx * 8.0), (float)(ly * 8.0));
            u.controller(new Guardia(hvx, hvy, fuga));
            if(vida > 0f) u.health = Math.max(1f, vida);
        }catch(Throwable e){
            Log.warn("[MundoInfinito] No se pudo crear la unidad @", tipo);
        }
    }

    static void restaurarUnidad(RegU r){
        crearGuardia(r.tipo, Team.get(r.equipo), r.vx, r.vy, r.hvx, r.hvy, r.fuga, r.vida);
    }

    // ================================================================ trabajos de colocación
    static final class Pieza{
        String bloque;
        int x, y, rot, equipo;   // virtuales
    }

    static final class Plan{
        String tipo;
        Team equipo;
        double vx, vy, hvx, hvy;
        float fuga;
    }

    static final class Trabajo{
        long id;
        int tipo, cx, cy, radio;
        final Seq<Pieza> piezas = new Seq<>();
        final Seq<Plan> unidades = new Seq<>();
        final HashSet<Integer> ocupado = new HashSet<>();
        int idx;
    }

    static long clave(int i, int j, int clase){
        return (((long)i & 0x3fffffffL) << 31) ^ ((long)j & 0x3fffffffL) ^ ((long)clase << 62);
    }

    /** ¿Están ya generados todos los chunks interiores que cubre el círculo (cx, cy, r)? */
    static boolean listo(int cx, int cy, int r){
        int n = Streamer.nch;
        int x0 = Math.floorDiv(cx - r - Streamer.ox, MundoInfinitoMod.TAM_CHUNK), x1 = Math.floorDiv(cx + r - Streamer.ox, MundoInfinitoMod.TAM_CHUNK);
        int y0 = Math.floorDiv(cy - r - Streamer.oy, MundoInfinitoMod.TAM_CHUNK), y1 = Math.floorDiv(cy + r - Streamer.oy, MundoInfinitoMod.TAM_CHUNK);
        for(int a = x0; a <= x1; a++){
            for(int b = y0; b <= y1; b++){
                if(a < 1 || b < 1 || a > n - 2 || b > n - 2) return false;
                if(!Streamer.generados.contains(Streamer.clave(a, b))) return false;
            }
        }
        return true;
    }

    /** Cada ~0,5 s: ¿hay estructuras o enemigos errantes dentro de la ventana que aún no se hayan construido? */
    static void revisar(){
        Meta m = Streamer.meta;
        if(m == null || !m.dimensional() || !Streamer.activo || Streamer.restaurando || Mundos.viajando) return;
        if(cola.size() > 2) return;
        int dim = m.dim;
        int sd = Muestreo.semDim(m.semilla, dim);
        Spawn sp = Muestreo.spawn(sd, dim);
        int tam = m.tam;
        int c = sp.celdaE;
        int[] cen = new int[3];
        for(int i = Math.floorDiv(Streamer.ox, c); i <= Math.floorDiv(Streamer.ox + tam, c); i++){
            for(int j = Math.floorDiv(Streamer.oy, c); j <= Math.floorDiv(Streamer.oy + tam, c); j++){
                int t = tipo(sd, sp, i, j, cen);
                if(t == T_NADA) continue;
                long id = clave(i, j, 0);
                if(colocadas.contains(id) || enCola.contains(id)) continue;
                if(!listo(cen[0], cen[1], cen[2] + 4)) continue;
                Trabajo tr = disenar(sd, sp, t, i, j, cen, dim);
                tr.id = id;
                enCola.add(id);
                cola.addLast(tr);
                if(cola.size() > 2) return;
            }
        }
        // enemigos errantes: celdas de 96 tiles
        for(int i = Math.floorDiv(Streamer.ox, 96); i <= Math.floorDiv(Streamer.ox + tam, 96); i++){
            for(int j = Math.floorDiv(Streamer.oy, 96); j <= Math.floorDiv(Streamer.oy + tam, 96); j++){
                if(Muestreo.rnd(i, j, Muestreo.sem(sd, 9820)) > 0.14f + sp.probE * 0.5f) continue;
                int px = i * 96 + 12 + (int)(Muestreo.rnd(i, j, Muestreo.sem(sd, 9821)) * 72f);
                int py = j * 96 + 12 + (int)(Muestreo.rnd(i, j, Muestreo.sem(sd, 9822)) * 72f);
                if(Math.hypot(px, py) < 90.0) continue;
                long id = clave(i, j, 1);
                if(colocadas.contains(id) || enCola.contains(id) || !listo(px, py, 6)) continue;
                Trabajo tr = new Trabajo();
                tr.id = id;
                tr.tipo = -1;
                int tier = Mathf.clamp((int)(Math.hypot(px, py) / 450.0), 0, 3);
                int cuantos = 1 + (int)(Muestreo.rnd(i, j, Muestreo.sem(sd, 9823)) * 3f);
                Team eq = dim == 1 ? Team.malis : Team.crux;
                for(int k = 0; k < cuantos; k++){
                    Plan pl = new Plan();
                    pl.tipo = nombreUnidad(dim, tier, (int)(Muestreo.rnd(i, j + k * 7, Muestreo.sem(sd, 9824)) * 8f));
                    pl.equipo = eq;
                    pl.vx = px + k * 2 - cuantos;
                    pl.vy = py + (k % 2) * 2;
                    pl.hvx = px;
                    pl.hvy = py;
                    pl.fuga = 22f;
                    tr.unidades.add(pl);
                }
                enCola.add(id);
                cola.addLast(tr);
            }
        }
    }

    /** Avanza los trabajos pendientes dentro del presupuesto de tiempo del frame. */
    static void procesar(long fin){
        while(!cola.isEmpty() && System.nanoTime() < fin){
            Trabajo t = cola.peekFirst();
            if(t.idx < t.piezas.size){
                colocarPieza(t.piezas.get(t.idx++));
            }else{
                for(Plan pl : t.unidades) crearGuardia(pl.tipo, pl.equipo, pl.vx, pl.vy, pl.hvx, pl.hvy, pl.fuga, 0f);
                colocadas.add(t.id);
                enCola.remove(t.id);
                cola.pollFirst();
            }
        }
    }

    static void colocarPieza(Pieza p){
        try{
            Block b = Vars.content.block(p.bloque);
            Tile t = Vars.world.tile(p.x - Streamer.ox, p.y - Streamer.oy);
            if(b == null || t == null) return;
            if(t.build != null && t.build.team == Team.sharded) return;   // nunca se pisa lo del jugador
            t.setBlock(b, Team.get(p.equipo), p.rot);
        }catch(Throwable e){
            Log.warn("[MundoInfinito] No se pudo colocar @", p.bloque);
        }
    }

    // ================================================================ diseño de bases y ruinas
    static final String[][] MUROS_S = {{"copper-wall"}, {"titanium-wall"}, {"thorium-wall"}, {"thorium-wall"}};
    static final String[][] MUROS_E = {{"beryllium-wall"}, {"tungsten-wall"}, {"carbide-wall"}, {"carbide-wall"}};
    static final String[][] TORRES_S = {
        {"duo", "duo", "hail"}, {"duo", "hail", "scatter", "lancer"},
        {"lancer", "salvo", "hail", "scatter", "ripple"}, {"ripple", "salvo", "lancer", "cyclone"}};
    static final String[][] TORRES_E = {
        {"breach", "breach", "diffuse"}, {"breach", "diffuse", "sublimate"},
        {"diffuse", "sublimate", "titan"}, {"titan", "sublimate", "diffuse"}};
    static final String[][] TROPAS_S = {
        {"dagger", "dagger", "flare"}, {"dagger", "mace", "horizon"}, {"mace", "fortress", "zenith"}, {"fortress", "quasar", "zenith"}};
    static final String[][] TROPAS_E = {
        {"stell", "stell"}, {"stell", "locus"}, {"locus", "precept"}, {"precept", "locus"}};

    static String nombreUnidad(int dim, int tier, int azar){
        String[] l = (dim == 1 ? TROPAS_E : TROPAS_S)[tier];
        return l[azar % l.length];
    }

    /** Pone un bloque en el diseño si cabe (sin solaparse y dentro del círculo de despeje). */
    static boolean poner(Trabajo t, String nombre, int dx, int dy, int rot, Team eq){
        Block b = Vars.content.block(nombre);
        if(b == null) return false;
        int low = (b.size - 1) / 2;
        float lim = t.radio * 0.9f;
        for(int a = 0; a < b.size; a++){
            for(int c = 0; c < b.size; c++){
                int x = dx - low + a, y = dy - low + c;
                if(x * x + y * y > lim * lim) return false;
                if(t.ocupado.contains((x + 512) * 1024 + (y + 512))) return false;
            }
        }
        for(int a = 0; a < b.size; a++){
            for(int c = 0; c < b.size; c++) t.ocupado.add((dx - low + a + 512) * 1024 + (dy - low + c + 512));
        }
        Pieza p = new Pieza();
        p.bloque = nombre;
        p.x = t.cx + dx;
        p.y = t.cy + dy;
        p.rot = rot;
        p.equipo = eq.id;
        t.piezas.add(p);
        return true;
    }

    static Trabajo disenar(int s, Spawn sp, int tipo, int i, int j, int[] cen, int dim){
        Trabajo t = new Trabajo();
        t.tipo = tipo;
        t.cx = cen[0];
        t.cy = cen[1];
        t.radio = cen[2];
        java.util.Random r = new java.util.Random(Muestreo.sem(s, 9900) ^ (i * 73856093L) ^ (j * 19349663L));
        int tier = Mathf.clamp((int)(Math.hypot(t.cx, t.cy) / 450.0), 0, 3);
        if(tipo == T_BASE) base(t, r, dim, tier); else ruina(t, r, dim, tier, ladoZona(s, i, j));
        return t;
    }

    /** Base enemiga: núcleo en el centro, muro con huecos, torretas, portal derelicto dentro y guarnición. */
    static void base(Trabajo t, java.util.Random r, int dim, int tier){
        Team eq = dim == 1 ? Team.malis : Team.crux;
        String[] muros = (dim == 1 ? MUROS_E : MUROS_S)[tier];
        String[] torres = (dim == 1 ? TORRES_E : TORRES_S)[tier];
        int h = Math.max(9, (int)(t.radio * 0.58f));

        poner(t, dim == 1 ? (tier >= 2 ? "core-citadel" : "core-bastion") : (tier >= 2 ? "core-foundation" : "core-shard"), 0, 0, 0, eq);
        poner(t, "silo-interdimensional", 0, -(h - 4), 0, Team.derelict);   // el portal queda DENTRO de la base

        // torretas en las esquinas, puntos medios y un anillo interior (orden al azar)
        int o = h - 3;
        int[][] slots = {{o, o}, {-o, o}, {o, -o}, {-o, -o}, {0, o}, {0, -o}, {o, 0}, {-o, 0}, {5, 5}, {-5, 5}, {5, -5}, {-5, -5}};
        for(int k = slots.length - 1; k > 0; k--){
            int q = r.nextInt(k + 1);
            int[] tmp = slots[k]; slots[k] = slots[q]; slots[q] = tmp;
        }
        int nT = 4 + tier * 2;
        for(int k = 0, hechas = 0; k < slots.length && hechas < nT; k++){
            if(poner(t, torres[r.nextInt(torres.length)], slots[k][0], slots[k][1], 0, eq)) hechas++;
        }
        if(dim == 0){
            poner(t, "mender", 4, -3, 0, eq);
            poner(t, "mender", -4, 3, 0, eq);
        }

        // muro perimetral con 3 huecos de 3 tiles
        int[] hs = new int[3], ho = new int[3];
        for(int k = 0; k < 3; k++){ hs[k] = r.nextInt(4); ho[k] = r.nextInt(2 * h - 5) - (h - 3); }
        for(int k = -h; k <= h; k++){
            for(int lado = 0; lado < 4; lado++){
                int x = lado == 0 ? k : (lado == 1 ? k : (lado == 2 ? -h : h));
                int y = lado == 0 ? -h : (lado == 1 ? h : k);
                boolean hueco = false;
                for(int g = 0; g < 3; g++) if(hs[g] == lado && Math.abs(k - ho[g]) <= 1) hueco = true;
                if(!hueco) poner(t, muros[0], x, y, 0, eq);
            }
        }

        // guarnición cerca del núcleo
        int n = 2 + tier + r.nextInt(3);
        for(int k = 0; k < n; k++){
            Plan pl = new Plan();
            pl.tipo = nombreUnidad(dim, tier, r.nextInt(8));
            pl.equipo = eq;
            double ang = r.nextDouble() * 6.2831853, d = 5 + r.nextInt(4);
            pl.vx = t.cx + Math.cos(ang) * d;
            pl.vy = t.cy + Math.sin(ang) * d;
            pl.hvx = t.cx;
            pl.hvy = t.cy;
            pl.fuga = h + 12f;
            t.unidades.add(pl);
        }
    }

    /** Ruina: portal derelicto en el centro, muros rotos, escombros y una zona de núcleo donde el jugador puede fundar su base. */
    static void ruina(Trabajo t, java.util.Random r, int dim, int tier, int lado){
        Team eq = Team.derelict;
        String[] muros = (dim == 1 ? MUROS_E : MUROS_S)[Math.min(tier, 1)];
        int zx = ZX[lado], zy = ZY[lado];
        // marcadores en las esquinas de la zona: hacen que el juego "vea" la zona de núcleo y muestre los núcleos en el menú
        for(int a = -4; a <= 4; a += 8){
            for(int b = -4; b <= 4; b += 8) poner(t, "scrap-wall", zx + a, zy + b, 0, eq);
        }
        poner(t, "silo-interdimensional", 0, 0, 0, eq);

        int h = Math.max(8, (int)(t.radio * 0.6f));
        for(int k = -h; k <= h; k++){
            for(int lado2 = 0; lado2 < 4; lado2++){
                int x = lado2 < 2 ? k : (lado2 == 2 ? -h : h);
                int y = lado2 == 0 ? -h : (lado2 == 1 ? h : k);
                if(Math.abs(x - zx) <= 5 && Math.abs(y - zy) <= 5) continue;   // la zona de núcleo queda libre
                if(r.nextFloat() < 0.45f) poner(t, muros[0], x, y, 0, eq);
            }
        }
        String[] restos = dim == 1 ? new String[]{"scrap-wall", "beryllium-wall", "breach"} : new String[]{"conveyor", "scrap-wall", "container", "duo", "hail", "copper-wall"};
        int n = 6 + r.nextInt(7);
        for(int k = 0; k < n; k++){
            int x = r.nextInt(2 * h - 1) - (h - 1), y = r.nextInt(2 * h - 1) - (h - 1);
            if(Math.abs(x - zx) <= 5 && Math.abs(y - zy) <= 5) continue;
            poner(t, restos[r.nextInt(restos.length)], x, y, r.nextInt(4), eq);
        }
    }
}
