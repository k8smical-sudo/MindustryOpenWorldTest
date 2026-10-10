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
import mindustry.gen.Groups;
import mindustry.world.blocks.units.UnitFactory;
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

        /** Radio de vigilancia: la guardia nota intrusos bastante antes de tenerlos al alcance del arma. */
        @Override
        public mindustry.gen.Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground){
            return super.findMainTarget(x, y, Math.max(range, 22f * 8f), air, ground);
        }

        @Override
        public void updateMovement(){
            casa.set(Coord.mundoX(hvx), Coord.mundoY(hvy));
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
        int clase;                 // 0 = guardia enemiga (con IA de guardia), 1 = unidad aliada dormida (se despierta cerca del jugador)
        float rot, escudo, municion;

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

        /** Unidad aliada que se manda a dormir: conserva vida, escudo, munición y orientación. */
        static RegU aliada(Unit u, int ox, int oy){
            RegU r = new RegU();
            r.clase = 1;
            r.tipo = u.type.name;
            r.equipo = u.team.id;
            r.vx = ox + u.x / 8.0;
            r.vy = oy + u.y / 8.0;
            r.hvx = r.vx;
            r.hvy = r.vy;
            r.vida = u.health;
            r.escudo = u.shield;
            r.rot = u.rotation;
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
            out.writeByte(clase);
            out.writeFloat(rot);
            out.writeFloat(escudo);
            out.writeFloat(municion);
        }

        static RegU leer(DataInputStream in, int ver) throws java.io.IOException{
            RegU r = new RegU();
            r.tipo = in.readUTF();
            r.equipo = in.readUnsignedByte();
            r.vx = in.readDouble();
            r.vy = in.readDouble();
            r.hvx = in.readDouble();
            r.hvy = in.readDouble();
            r.vida = in.readFloat();
            r.fuga = in.readFloat();
            if(ver >= 6){
                r.clase = in.readUnsignedByte();
                r.rot = in.readFloat();
                r.escudo = in.readFloat();
                r.municion = in.readFloat();
            }
            return r;
        }
    }

    static void crearGuardia(String tipo, Team equipo, double vx, double vy, double hvx, double hvy, float fuga, float vida){
        try{
            UnitType t = Vars.content.unit(tipo);
            if(t == null) return;
            double lx = vx - Coord.origenX(), ly = vy - Coord.origenY();
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
        String item;             // config de unloader (null = sin config)
        int plan = -1;           // config de fábrica de unidades
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
        vigilar();
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

    /** Tope de unidades vivas por base (las fábricas se apagan al llegar). */
    static int topeBase(int tier){
        return 4 + 2 * tier;
    }

    /**
     * Mantiene a las unidades enemigas "en su sitio": las que salen de las fábricas de una base reciben la IA de guardia
     * (si no, irían a atacar por todo el mapa) y las fábricas se apagan cuando la base ya tiene su tope de unidades.
     */
    static void vigilar(){
        Meta m = Streamer.meta;
        if(m == null || !m.dimensional()) return;
        int dim = m.dim;
        int sd = Muestreo.semDim(m.semilla, dim);
        Spawn sp = Muestreo.spawn(sd, dim);
        int c = sp.celdaE;
        int[] cen = new int[3];
        java.util.HashMap<Long, Integer> cuenta = new java.util.HashMap<>();
        Seq<Unit> nuevas = new Seq<>();
        for(Unit u : Groups.unit){
            if(!u.isValid() || (u.team != Team.crux && u.team != Team.malis)) continue;
            if(u.controller() instanceof Guardia g){
                long k = Math.round(g.hvx) * 1000003L + Math.round(g.hvy);
                cuenta.merge(k, 1, Integer::sum);
            }else{
                nuevas.add(u);
            }
        }
        for(Unit u : nuevas){
            double vx = Coord.virtualX(u.x), vy = Coord.virtualY(u.y);
            double mejor = 60.0, hx = vx, hy = vy;
            int ci = Math.floorDiv((int)vx, c), cj = Math.floorDiv((int)vy, c);
            for(int i = ci - 1; i <= ci + 1; i++){
                for(int j = cj - 1; j <= cj + 1; j++){
                    if(tipo(sd, sp, i, j, cen) != T_BASE) continue;
                    double d = Math.hypot(cen[0] - vx, cen[1] - vy);
                    if(d < mejor){ mejor = d; hx = cen[0]; hy = cen[1]; }
                }
            }
            u.controller(new Guardia(hx, hy, mejor < 60.0 ? 30f : 20f));
            cuenta.merge(Math.round(hx) * 1000003L + Math.round(hy), 1, Integer::sum);
        }
        // fábricas de unidades: encendidas solo si su base tiene menos del tope
        for(Team et : new Team[]{Team.crux, Team.malis}){
            for(mindustry.gen.Building b : et.data().buildings){
                if(!(b instanceof UnitFactory.UnitFactoryBuild)) continue;
                double vx = Coord.virtualX(b.x), vy = Coord.virtualY(b.y);
                double mejor = 40.0;
                int tier = 0;
                long key = Long.MIN_VALUE;
                int ci = Math.floorDiv((int)vx, c), cj = Math.floorDiv((int)vy, c);
                for(int i = ci - 1; i <= ci + 1; i++){
                    for(int j = cj - 1; j <= cj + 1; j++){
                        if(tipo(sd, sp, i, j, cen) != T_BASE) continue;
                        double d = Math.hypot(cen[0] - vx, cen[1] - vy);
                        if(d < mejor){ mejor = d; key = Math.round((double)cen[0]) * 1000003L + Math.round((double)cen[1]); tier = Mathf.clamp((int)(Math.hypot(cen[0], cen[1]) / 450.0), 0, 3); }
                    }
                }
                if(key == Long.MIN_VALUE) continue;
                b.enabled = cuenta.getOrDefault(key, 0) < topeBase(tier);
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
            Tile t = Vars.world.tile(p.x - Coord.origenX(), p.y - Coord.origenY());
            if(b == null || t == null) return;
            if(t.build != null && t.build.team == Team.sharded) return;   // nunca se pisa lo del jugador
            t.setBlock(b, Team.get(p.equipo), p.rot);
            if(t.build != null){
                if(p.item != null){
                    mindustry.type.Item it = Vars.content.item(p.item);
                    if(it != null) t.build.configured(null, it);
                }
                if(p.plan >= 0) t.build.configured(null, p.plan);
            }
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

    /** Pone un bloque cuya HUELLA es exactamente el rectángulo [x0..x1] x [y0..y1] (relativo al centro de la estructura). */
    static boolean ponerRect(Trabajo t, String nombre, int x0, int y0, int x1, int y1, int rot, Team eq, String item, int plan){
        Block b = Vars.content.block(nombre);
        if(b == null) return false;
        int low = (b.size - 1) / 2;
        int nx = Math.min(x0, x1), ny = Math.min(y0, y1);
        int cantidad = t.piezas.size;
        if(!poner(t, nombre, nx + low, ny + low, rot, eq)) return false;
        Pieza p = t.piezas.get(cantidad);
        p.item = item;
        p.plan = plan;
        return true;
    }

    /** Coordenadas (relativas) del punto a lo largo de un radio: origen (ox, oy), dirección (dx, dy), lateral l. */
    static int[] pt(int ox, int oy, int dx, int dy, int a, int l){
        return new int[]{ox + dx * a + (-dy) * l, oy + dy * a + dx * l};
    }

    /** Rectángulo en el marco del radio: a en [a0..a1], l en [l0..l1] -> (xmin, ymin, xmax, ymax). */
    static int[] rectRadio(int ox, int oy, int dx, int dy, int a0, int a1, int l0, int l1){
        int[] p = pt(ox, oy, dx, dy, a0, l0), q = pt(ox, oy, dx, dy, a1, l1);
        return new int[]{Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.max(p[0], q[0]), Math.max(p[1], q[1])};
    }

    static int tamBloque(String nombre){
        Block b = Vars.content.block(nombre);
        return b == null ? 1 : b.size;
    }

    /**
     * Radio defensivo "de campaña": un unloader en el borde del núcleo saca la munición, una cinta la lleva hacia afuera,
     * cada router reparte a dos torretas laterales y la cinta termina DENTRO de una torreta final.
     */
    static void radio(Trabajo t, java.util.Random r, int dir, int cl, int ch, String ammo, String[] torres, int routers, int hMuro, Team eq){
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int dx = dirs[dir][0], dy = dirs[dir][1];
        // el unloader va en el borde del núcleo, en la fila central (0)
        int ext = dx > 0 || dy > 0 ? ch + 1 : cl - 1;           // coordenada del borde (con signo)
        int ox = dx != 0 ? ext : 0, oy = dy != 0 ? ext : 0;
        int signo = (dx + dy) > 0 ? 1 : -1;
        int[] u = pt(ox, oy, dx, dy, 0, 0);
        ponerRect(t, "unloader", u[0], u[1], u[0], u[1], 0, eq, ammo, -1);
        String fin = torres[r.nextInt(torres.length)];
        int sf = tamBloque(fin);
        int maxFin = hMuro - 2 - Math.abs(ext);                    // hasta dónde llega la huella de la torreta final (a lo largo)
        int tFin = Math.max(3, maxFin - (sf - 1));
        int usados = 0;
        for(int a = 1; a < tFin; a++){
            int[] c = pt(ox, oy, dx, dy, a, 0);
            boolean router = a >= 3 && (a - 3) % 3 == 0 && usados < routers && a + 3 <= tFin;
            if(router){
                ponerRect(t, "router", c[0], c[1], c[0], c[1], 0, eq, null, -1);
                usados++;
                for(int lado = -1; lado <= 1; lado += 2){
                    String tn = torres[r.nextInt(torres.length)];
                    int sz = tamBloque(tn);
                    int[] rc = lado > 0 ? rectRadio(ox, oy, dx, dy, a, a + sz - 1, 1, sz) : rectRadio(ox, oy, dx, dy, a, a + sz - 1, -sz, -1);
                    ponerRect(t, tn, rc[0], rc[1], rc[2], rc[3], 0, eq, null, -1);
                }
            }else{
                int rot = dir;   // 0 E, 1 N, 2 W, 3 S: la cinta mira hacia afuera
                ponerRect(t, "conveyor", c[0], c[1], c[0], c[1], rot, eq, null, -1);
            }
        }
        int lo = -((sf - 1) / 2), hi = sf - 1 + lo;
        int[] fr = rectRadio(ox, oy, dx, dy, tFin, tFin + sf - 1, lo, hi);
        ponerRect(t, fin, fr[0], fr[1], fr[2], fr[3], 0, eq, null, -1);
    }

    /** Base enemiga: núcleo, radios defensivos con logística real, planta solar, fábrica de unidades y portal derelicto. */
    static void base(Trabajo t, java.util.Random r, int dim, int tier){
        Team eq = dim == 1 ? Team.malis : Team.crux;
        String[] muros = (dim == 1 ? MUROS_E : MUROS_S)[tier];
        int h = Math.max(11, (int)(t.radio * 0.58f));
        String nucleo = dim == 1 ? (tier >= 2 ? "core-citadel" : "core-bastion") : "core-shard";
        int tn = tamBloque(nucleo);
        int cl = -((tn - 1) / 2), ch = cl + tn - 1;            // huella del núcleo en cada eje
        ponerRect(t, nucleo, cl, cl, ch, ch, 0, eq, null, -1);

        // --- radios defensivos (este y oeste siempre; el tercero con tier >= 2) ---
        String[] torres = (dim == 1 ? TORRES_E : TORRES_S)[tier];
        int routers = 1 + tier;
        if(dim == 1){
            radio(t, r, 0, cl, ch, "beryllium", new String[]{"breach"}, routers, h, eq);
            radio(t, r, 2, cl, ch, "graphite", new String[]{"diffuse"}, routers, h, eq);
        }else{
            String[] ls = tier >= 2 ? new String[]{"duo", "hail", "salvo", "ripple"} : new String[]{"duo", "hail"};
            radio(t, r, 0, cl, ch, "graphite", ls, routers, h, eq);
            radio(t, r, 2, cl, ch, "graphite", ls, routers, h, eq);
        }

        // --- fábrica de unidades al sur, alimentada por un unloader sin filtro, con 2 paneles solares grandes ---
        int fy1 = cl - 2, fy0 = cl - 4;                          // huella 3x3 de la fábrica (y)
        ponerRect(t, "unloader", 0, cl - 1, 0, cl - 1, 0, eq, null, -1);
        String fab = dim == 1 ? "tank-fabricator" : "ground-factory";
        ponerRect(t, fab, -1, fy0, 1, fy1, 3, eq, null, 0);
        ponerRect(t, "solar-panel-large", 2, fy0, 4, fy1, 0, eq, null, -1);
        ponerRect(t, "solar-panel-large", -4, fy0, -2, fy1, 0, eq, null, -1);
        ponerRect(t, "mender", 2, cl - 1, 2, cl - 1, 0, eq, null, -1);
        ponerRect(t, "mender", -2, cl - 1, -2, cl - 1, 0, eq, null, -1);

        // --- portal derelicto al norte (dentro de los muros) ---
        ponerRect(t, "silo-interdimensional", -1, ch + 3, 2, ch + 6, 0, Team.derelict, null, -1);

        // --- muro perimetral con 3 huecos de 3 tiles ---
        int[] hs = new int[3], ho = new int[3];
        for(int k = 0; k < 3; k++){ hs[k] = r.nextInt(4); ho[k] = r.nextInt(2 * h - 5) - (h - 3); }
        for(int k = -h; k <= h; k++){
            for(int lado = 0; lado < 4; lado++){
                int x = lado < 2 ? k : (lado == 2 ? -h : h);
                int y = lado == 0 ? -h : (lado == 1 ? h : k);
                boolean hueco = false;
                for(int g = 0; g < 3; g++) if(hs[g] == lado && Math.abs(k - ho[g]) <= 1) hueco = true;
                if(!hueco) poner(t, muros[0], x, y, 0, eq);
            }
        }
        // la guarnición inicial sale de la fábrica; además hay unos pocos guardias fuera
        int n = 1 + tier + r.nextInt(2);
        for(int k = 0; k < n; k++){
            Plan pl = new Plan();
            pl.tipo = nombreUnidad(dim, tier, r.nextInt(8));
            pl.equipo = eq;
            double ang = r.nextDouble() * 6.2831853, d = h - 4;
            pl.vx = t.cx + Math.cos(ang) * d;
            pl.vy = t.cy + Math.sin(ang) * d;
            pl.hvx = t.cx;
            pl.hvy = t.cy;
            pl.fuga = h + 14f;
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
