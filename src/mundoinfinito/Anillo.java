package mundoinfinito;

import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.ChunkDarkness;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.Floor;
import mundoinfinito.MundoInfinitoMod.ChunkData;
import mundoinfinito.MundoInfinitoMod.Meta;
import mundoinfinito.MundoInfinitoMod.Mundos;
import mundoinfinito.MundoInfinitoMod.Paleta;
import mundoinfinito.MundoInfinitoMod.Reg;
import mundoinfinito.MundoInfinitoMod.Regiones;
import mundoinfinito.MundoInfinitoMod.Streamer;

/**
 * MUNDO EN ANILLO (motor MX-Test, parches OW-0001..0007): ya NO hay ventana de tiles ni reubicación.
 *
 * El motor guarda las casillas en un búfer circular de 32 x 32 chunks con coordenadas VIRTUALES (rango de un short). Cada chunk
 * tiene su hueco: cargar un chunk = construir sus 1024 casillas en un hilo de fondo y publicarlas (32 arraycopy); descargarlo =
 * guardar sus edificios y soltar el hueco. Nada más se mueve jamás: coordenadas locales == virtuales (Streamer.ox = oy = 0), sin
 * pared negra, sin recargas y sin parpadeos. La única dependencia son los chunks.
 *
 * Esta clase reúne el ciclo de vida de un chunk: construir (hilo de fondo) -> publicar -> restaurar lo guardado -> (jugar) ->
 * capturar y soltar. Todo lo que toca el mundo corre en el hilo del juego.
 */
final class Anillo{
    static final int T = MundoInfinitoMod.TAM_CHUNK;
    /** Descargas máximas por escaneo (~0,5 s): se reparten para no provocar tirones. */
    static final int MAX_DESCARGAS = 4;
    /** Chunks extra alrededor del disco de carga que se mantienen antes de soltarlos (histéresis anti-parpadeo). */
    static final int HISTERESIS = 3;

    /** Chunks cuyos edificios guardados ya se restauraron (y que por tanto se reescriben al guardar). Hilo del juego. */
    static final IntSet restaurados = new IntSet();
    /** Chunks publicados a la espera de poder restaurarse (vecinos o región de disco sin cargar). Hilo del juego. */
    static final IntSet pendRestaurar = new IntSet();

    private static volatile boolean[] osc;      // por id de bloque: ¿oscurece (muro estático)?
    private static final int P = ChunkDarkness.padded;

    // chunk reservado del núcleo proxy (-1 = ninguno)
    static int proxyCx = Integer.MIN_VALUE, proxyCy = Integer.MIN_VALUE;

    private Anillo(){}

    // ================================================================== inicio / reinicio
    static void iniciar(){
        boolean[] o = new boolean[Vars.content.blocks().size + 1];
        for(Block b : Vars.content.blocks()){
            o[b.id] = b.solid && !b.synthetic() && b.fillsTile;
        }
        osc = o;
        reiniciar();
    }

    static void reiniciar(){
        restaurados.clear();
        pendRestaurar.clear();
        proxyCx = proxyCy = Integer.MIN_VALUE;
    }

    // ================================================================== 1) construir (hilo de fondo)
    private static final class Esc{
        final boolean[] dk = new boolean[P * P];
        final byte[] out = new byte[T * T], dark = new byte[P * P], buf = new byte[P * P];
        final short[][] bl = new short[9][];
        final ChunkData[] prest = new ChunkData[9];
    }

    private static final ThreadLocal<Esc> ESC = ThreadLocal.withInitial(Esc::new);

    /**
     * Construye las 1024 casillas del chunk d (d.cx, d.cy VIRTUALES) con su oscuridad de muros ya calculada (la del chunk y la de sus
     * vecinos se leen de la caché de terreno o se generan: el terreno es una función pura de la coordenada). Seguro en hilos de fondo.
     */
    static void construir(ChunkData d, int mid, Meta m, Paleta p, int dim, int ep){
        final Esc e = ESC.get();
        final boolean[] osc = Anillo.osc;
        final int cx = d.cx, cy = d.cy;
        try{
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    int idx = (dy + 1) * 3 + dx + 1;
                    if(dx == 0 && dy == 0){
                        e.bl[idx] = d.bloque;
                        e.prest[idx] = null;
                        continue;
                    }
                    long vk = Coord.claveVirtual(cx + dx, cy + dy);
                    ChunkData n = CacheChunks.copiar(mid, vk, cx + dx, cy + dy, ep);
                    if(n == null){
                        n = ChunkData.generar(p, m.semilla, dim, m.tam, cx + dx, cy + dy, ep, 0, 0);
                        CacheChunks.poner(mid, vk, n);
                    }
                    e.bl[idx] = n.bloque;
                    e.prest[idx] = n;
                }
            }
            for(int py = 0; py < P; py++){
                int ly = py - ChunkDarkness.margin;
                int dyc = Math.floorDiv(ly, T), lyy = Math.floorMod(ly, T);
                for(int px = 0; px < P; px++){
                    int lx = px - ChunkDarkness.margin;
                    int dxc = Math.floorDiv(lx, T), lxx = Math.floorMod(lx, T);
                    short id = e.bl[(dyc + 1) * 3 + dxc + 1][lyy * T + lxx];
                    e.dk[py * P + px] = id > 0 && id < osc.length && osc[id];
                }
            }
            ChunkDarkness.compute(e.dk, e.out, e.dark, e.buf);

            final Tile[] ts = new Tile[T * T];
            final int x0 = cx * T, y0 = cy * T;
            for(int ly = 0; ly < T; ly++){
                for(int lx = 0; lx < T; lx++){
                    int i = ly * T + lx;
                    Block fb = Vars.content.block(d.piso[i]);
                    Floor floor = fb instanceof Floor ? (Floor)fb : (Floor)Blocks.stone;
                    Floor over = d.mena[i] == 0 ? (Floor)Blocks.air : (Floor)Vars.content.block(d.mena[i]);
                    Block wall = d.bloque[i] == 0 ? Blocks.air : Vars.content.block(d.bloque[i]);
                    ts[i] = new Tile(x0 + lx, y0 + ly, floor, over, wall, e.out[i]);
                }
            }
            d.tiles = ts;
        }finally{
            for(int i = 0; i < 9; i++){
                if(e.prest[i] != null){
                    e.prest[i].liberar();
                    e.prest[i] = null;
                }
                e.bl[i] = null;
            }
        }
    }

    // ================================================================== 2) publicar (hilo del juego)
    /**
     * @return true si el chunk quedó resuelto (publicado o ya no hace falta); false si su hueco lo tiene otro chunk que aún no se
     * soltó (se reintenta en el siguiente fotograma).
     */
    static boolean publicar(ChunkData d){
        if(d.tiles == null) return true;
        if(!Vars.world.isRing()) return true;
        final Tiles ts = Vars.world.tiles;
        if(ts.chunkLoaded(d.cx, d.cy)) return true;
        if(ts.chunkSlotBusy(d.cx, d.cy)) return false;
        if(!Vars.world.publishChunk(d.cx, d.cy, d.tiles)) return true;
        final int k = Streamer.clave(d.cx, d.cy);
        Streamer.generados.add(k);
        d.tiles = null;
        alCargar(d.cx, d.cy);
        return true;
    }

    /** Tras publicar (cx, cy): lo guardado de ese chunk y de sus 8 vecinos puede estar ya listo para restaurarse. */
    static void alCargar(int cx, int cy){
        for(int dy = -1; dy <= 1; dy++){
            for(int dx = -1; dx <= 1; dx++){
                int vx = cx + dx, vy = cy + dy;
                if(Streamer.generados.contains(Streamer.clave(vx, vy)) && !restaurados.contains(Streamer.clave(vx, vy))){
                    pendRestaurar.add(Streamer.clave(vx, vy));
                }
            }
        }
        // los edificios multichunk deben verse enteros: si el vecino está cargado, intentamos ya
        procesarPendientes(64);
    }

    // ================================================================== 3) restaurar lo guardado
    private static final IntSeq tmpKeys = new IntSeq();

    private static boolean nieblaSucia;

    static void procesarPendientes(int maximo){
        if(pendRestaurar.isEmpty()) return;
        Regiones.sincronizar();
        tmpKeys.clear();
        IntSet.IntSetIterator it = pendRestaurar.iterator();
        while(it.hasNext && tmpKeys.size < maximo) tmpKeys.add(it.next());
        for(int i = 0; i < tmpKeys.size; i++){
            int k = tmpKeys.items[i];
            if(intentarRestaurar(Streamer.cxDe(k), Streamer.cyDe(k))) pendRestaurar.remove(k);
        }
        if(nieblaSucia){
            nieblaSucia = false;
            Mundos.refrescarNiebla();
        }
    }

    /** ¿Queda por restaurar algún chunk a menos de r chunks (Chebyshev) del jugador? (la pantalla de carga espera solo a lo cercano). */
    static boolean hayPendientesCerca(int pcx, int pcy, int r){
        IntSet.IntSetIterator it = pendRestaurar.iterator();
        while(it.hasNext){
            int k = it.next();
            if(Math.abs(Streamer.cxDe(k) - pcx) <= r && Math.abs(Streamer.cyDe(k) - pcy) <= r && Streamer.generados.contains(k)) return true;
        }
        return false;
    }

    /** @return true si ya no hay que reintentarlo (restaurado, o el chunk se descargó). */
    private static boolean intentarRestaurar(int cx, int cy){
        final int k = Streamer.clave(cx, cy);
        if(!Streamer.generados.contains(k)) return true;
        if(restaurados.contains(k)) return true;
        for(int dy = -1; dy <= 1; dy++){
            for(int dx = -1; dx <= 1; dx++){
                if(!Streamer.enMapa(cx + dx, cy + dy)) return false;   // un edificio grande podría quedar cortado
            }
        }
        if(!Regiones.lista(cx, cy)) return false;                      // la región se lee en el hilo de E/S
        restaurados.add(k);
        Regiones.ChunkGuardado c = Regiones.chunk(cx, cy, false);
        if(c == null) return true;
        final double ausente = c.tick > 0 ? Math.max(0.0, Mundos.reloj() - c.tick) : 0.0;
        for(Reg r : c.edificios){
            r.dt = ausente;
            Streamer.colaRestaurar.addLast(r);
        }
        for(MundoInfinitoMod.Estructuras.RegU u : c.unidades){
            if(u.clase == 0) Streamer.unidadesPend.add(u);
        }
        if(c.niebla != null && Vars.fogControl != null){
            final long[] b = new long[16];
            for(int i = 0; i < 16; i++) b[i] = (c.niebla[2 * i] & 0xFFFFFFFFL) | ((long)c.niebla[2 * i + 1] << 32);
            Vars.fogControl.putStaticChunk(Team.sharded, cx, cy, b);
            nieblaSucia = true;
        }
        return true;
    }

    // ================================================================== 4) descargar
    private static final Seq<Building> tmpEdif = new Seq<>();
    private static final Seq<Unit> tmpGuard = new Seq<>();
    private static final IntSeq tmpFuera = new IntSeq();

    /** Cada escaneo: suelta (guardando) los chunks que quedaron fuera de la vista + histéresis. Se reparte: unos pocos por vez. */
    static void descargar(int pcx, int pcy){
        if(!Vars.world.isRing()) return;
        final int carga = Ajustes.carga();
        tmpFuera.clear();
        Vars.world.tiles.eachLoadedChunk((cx, cy) -> {
            if(cx == proxyCx && cy == proxyCy) return;
            if(!Vista.dentro(cx, cy, pcx, pcy, carga, HISTERESIS)) tmpFuera.add(Streamer.clave(cx, cy));
        });
        if(tmpFuera.size == 0) return;
        // los más lejanos primero (los que más estorban a los huecos del anillo)
        final int n = tmpFuera.size;
        final long[] ord = new long[n];
        for(int i = 0; i < n; i++){
            int cx = Streamer.cxDe(tmpFuera.items[i]), cy = Streamer.cyDe(tmpFuera.items[i]);
            long d2 = (long)(cx - pcx) * (cx - pcx) + (long)(cy - pcy) * (cy - pcy);
            ord[i] = (d2 << 32) | (tmpFuera.items[i] & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(ord);
        int hechos = 0;
        for(int i = n - 1; i >= 0 && hechos < MAX_DESCARGAS; i--){
            int k = (int)(ord[i] & 0xFFFFFFFFL);
            if(soltar(Streamer.cxDe(k), Streamer.cyDe(k), pcx, pcy, carga)) hechos++;
        }
    }

    private static boolean fueraDeVista(int cx, int cy, int pcx, int pcy, int carga){
        return !Streamer.enMapa(cx, cy) || !Vista.dentro(cx, cy, pcx, pcy, carga, HISTERESIS);
    }

    /** @return true si el chunk se soltó. */
    private static boolean soltar(int cx, int cy, int pcx, int pcy, int carga){
        final Tiles ts = Vars.world.tiles;
        if(!ts.chunkLoaded(cx, cy)) return false;
        final int k = Streamer.clave(cx, cy);
        final int x0 = cx * T, y0 = cy * T;

        // 1) edificios que tocan el chunk: todos deben tener su origen aquí y caber enteros fuera de la vista
        tmpEdif.clear();
        for(int ly = 0; ly < T; ly++){
            for(int lx = 0; lx < T; lx++){
                Tile t = ts.get(x0 + lx, y0 + ly);
                if(t == null || t.build == null) continue;
                Building b = t.build;
                if(b.tile != t) {
                    // trozo de un edificio cuyo origen está en otro chunk: ese chunk lo guardará y lo quitará primero
                    return false;
                }
                if(b.tile.pos() == Mundos.proxyPos) return false;
                final int size = b.block.size, off = -(size - 1) / 2;
                for(int a = 0; a < size; a++){
                    for(int c = 0; c < size; c++){
                        int ccx = Math.floorDiv(b.tile.x + off + a, T), ccy = Math.floorDiv(b.tile.y + off + c, T);
                        if(!fueraDeVista(ccx, ccy, pcx, pcy, carga)) return false;
                    }
                }
                tmpEdif.add(b);
            }
        }
        final boolean rest = restaurados.contains(k);
        if((rest || tmpEdif.size > 0) && !Regiones.lista(cx, cy)) return false;   // no leer disco en el hilo del juego

        // 2) unidades de guardia enemigas (se guardan con el chunk) y resto de unidades en él
        tmpGuard.clear();
        for(Unit u : Groups.unit){
            if(u == null || !u.isValid() || u.isPlayer()) continue;
            if(Math.floorDiv(u.tileX(), T) != cx || Math.floorDiv(u.tileY(), T) != cy) continue;
            if(u.controller() instanceof MundoInfinitoMod.Estructuras.Guardia) tmpGuard.add(u);
        }

        // 3) guardar
        capturarChunk(cx, cy, tmpEdif, tmpGuard, rest, true);

        // 4) quitar del mundo: edificios (sin efectos), unidades que no se pueden dormir, fuego y charcos
        for(int i = 0; i < tmpEdif.size; i++){
            Building b = tmpEdif.get(i);
            if(b.isValid() && b.tile != null && b.tile.build == b) b.tile.setAir();
        }
        for(int i = 0; i < tmpGuard.size; i++) if(tmpGuard.get(i).isValid()) tmpGuard.get(i).remove();
        final Seq<Unit> aQuitar = new Seq<>();
        for(Unit u : Groups.unit){
            if(u == null || !u.isValid() || u.isPlayer()) continue;
            if(Math.floorDiv(u.tileX(), T) != cx || Math.floorDiv(u.tileY(), T) != cy) continue;
            if(u.isFlying()) continue;   // vuelan sobre el vacío sin problema
            aQuitar.add(u);              // una unidad de tierra sin casilla bajo los pies moriría explotando
        }
        for(Unit u : aQuitar) u.remove();
        for(int ly = 0; ly < T; ly++){
            for(int lx = 0; lx < T; lx++){
                int x = x0 + lx, y = y0 + ly;
                if(mindustry.entities.Fires.has(x, y)){
                    Tile t = ts.get(x, y);
                    if(t != null) mindustry.entities.Fires.remove(t);
                }
            }
        }

        // 5) niebla explorada (se guarda dentro de capturarChunk) y liberar el hueco
        boolean ok = Vars.world.unloadChunk(cx, cy);
        if(ok){
            Streamer.generados.remove(k);
            restaurados.remove(k);
            pendRestaurar.remove(k);
            Streamer.solicitados.remove(k);
        }else{
            Log.warn("[MundoInfinito] No se pudo soltar el chunk @,@ (aún tiene edificios)", cx, cy);
        }
        return ok;
    }

    // ================================================================== captura por chunk
    /**
     * Guarda en el almacén de regiones lo que hay en el chunk (cx, cy). 'reescribir' = el chunk ya se restauró: lo guardado se
     * sustituye por el estado vivo (así lo destruido deja de existir). Si no, solo se AÑADE (nunca se borra lo que no se restauró).
     * La niebla explorada del chunk se saca de la niebla viva (takeStaticChunk) y se limpia del hueco.
     */
    static void capturarChunk(int cx, int cy, Seq<Building> edificios, Seq<Unit> guardias, boolean reescribir, boolean vaciarNiebla){
        final Regiones.Region reg = Regiones.region(Math.floorDiv(cx, MundoInfinitoMod.REGION_CHUNKS), Math.floorDiv(cy, MundoInfinitoMod.REGION_CHUNKS));
        final int idx = Regiones.indice(cx, cy);
        Regiones.ChunkGuardado viejo = reescribir ? reg.chunks.remove(idx) : reg.chunks.get(idx);
        if(reescribir && viejo != null) reg.sucia = true;

        final boolean hib = Bases.dormida(Coord.claveVirtual(cx, cy));
        final Seq<MundoInfinitoMod.Estructuras.RegU> dorm = new Seq<>();
        final Seq<Reg> edHib = new Seq<>();
        if(reescribir && viejo != null){
            for(MundoInfinitoMod.Estructuras.RegU u : viejo.unidades) if(u.clase == 1 || hib) dorm.add(u);
            if(hib) for(Reg re : viejo.edificios) if(re.equipo != Team.sharded.id) edHib.add(re);
        }

        int[] niebla = viejo == null ? null : viejo.niebla;
        if(reescribir && Vars.fogControl != null && Vars.state.rules.fog){
            final long[] b = new long[16];
            Vars.fogControl.takeStaticChunk(Team.sharded, cx, cy, b);
            boolean alguno = false;
            final int[] filas = new int[32];
            for(int i = 0; i < 16; i++){
                filas[2 * i] = (int)b[i];
                filas[2 * i + 1] = (int)(b[i] >>> 32);
                if(b[i] != 0L) alguno = true;
            }
            if(alguno) niebla = filas;
            if(!vaciarNiebla) Vars.fogControl.putStaticChunk(Team.sharded, cx, cy, b);   // guardado periódico: la niebla viva se queda
        }

        final boolean algo = edificios.size > 0 || guardias.size > 0 || dorm.size > 0 || edHib.size > 0 || niebla != null;
        if(!algo) return;

        final Regiones.ChunkGuardado cg = Regiones.chunk(cx, cy, true);
        if(reescribir){
            cg.edificios.clear();
            cg.unidades.clear();
        }
        if(niebla != null) cg.niebla = niebla;
        for(Building b : edificios){
            if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
            Reg nuevo = Reg.desde(b, 0, 0);
            if(!reescribir){
                // fusionar: quitar la versión anterior del mismo edificio
                for(int i = cg.edificios.size - 1; i >= 0; i--){
                    Reg o = cg.edificios.get(i);
                    if(o.x == nuevo.x && o.y == nuevo.y) cg.edificios.remove(i);
                }
            }
            cg.edificios.add(nuevo);
        }
        cg.unidades.addAll(dorm);
        cg.edificios.addAll(edHib);
        for(Unit u : guardias) cg.unidades.add(MundoInfinitoMod.Estructuras.RegU.desde(u, 0, 0));
        cg.tick = Mundos.reloj();
    }

    private static final IntSeq tmpCargados = new IntSeq();

    /**
     * Guardado completo (cada ~30 s y al salir): captura todos los chunks cargados SIN descargarlos. Los edificios se reparten por el
     * chunk de su origen en una sola pasada.
     */
    static void capturarTodo(){
        if(!Vars.world.isRing()) return;
        final java.util.HashMap<Integer, Seq<Building>> porChunk = new java.util.HashMap<>();
        for(Team eq : Mundos.EQUIPOS_GUARDADO){
            for(Building b : Vars.state.teams.get(eq).buildings){
                if(!b.isValid() || b.tile == null || b.tile.build != b) continue;
                if(b.tile.pos() == Mundos.proxyPos) continue;
                int cx = Math.floorDiv(b.tile.x, T), cy = Math.floorDiv(b.tile.y, T);
                porChunk.computeIfAbsent(Streamer.clave(cx, cy), kk -> new Seq<>()).add(b);
            }
        }
        final java.util.HashMap<Integer, Seq<Unit>> guard = new java.util.HashMap<>();
        for(Unit u : Groups.unit){
            if(!u.isValid() || !(u.controller() instanceof MundoInfinitoMod.Estructuras.Guardia)) continue;
            int cx = Math.floorDiv(u.tileX(), T), cy = Math.floorDiv(u.tileY(), T);
            guard.computeIfAbsent(Streamer.clave(cx, cy), kk -> new Seq<>()).add(u);
        }
        tmpCargados.clear();
        Vars.world.tiles.eachLoadedChunk((cx, cy) -> tmpCargados.add(Streamer.clave(cx, cy)));
        final Seq<Building> vacioB = new Seq<>();
        final Seq<Unit> vacioU = new Seq<>();
        for(int i = 0; i < tmpCargados.size; i++){
            int k = tmpCargados.items[i];
            int cx = Streamer.cxDe(k), cy = Streamer.cyDe(k);
            if(cx == proxyCx && cy == proxyCy) continue;
            Seq<Building> eds = porChunk.getOrDefault(k, vacioB);
            Seq<Unit> gs = guard.getOrDefault(k, vacioU);
            boolean rest = restaurados.contains(k);
            if(!rest && eds.isEmpty() && gs.isEmpty()) continue;
            capturarChunk(cx, cy, eds, gs, rest, false);
        }
    }

    // ================================================================== núcleo proxy
    /**
     * El inventario del equipo vive en el módulo de ítems que COMPARTEN todos sus núcleos. Si el único núcleo se descargara (el jugador
     * se aleja de su base) el inventario se perdería y no se podría construir. Un núcleo "proxy" oculto en un chunk reservado, lejos de
     * la vista, mantiene ese módulo vivo. El chunk reservado se elige en la esquina OPUESTA del anillo respecto al jugador (a 16 chunks:
     * fuera de la vista y sin ocupar un hueco que el jugador necesite) y se traslada cuando el jugador se acerca a él.
     */
    static void vigilarProxy(int pcx, int pcy){
        if(!Vars.world.isRing() || Streamer.meta == null) return;
        final int carga = Ajustes.carga();
        final boolean existe = proxyCx != Integer.MIN_VALUE && Streamer.enMapa(proxyCx, proxyCy) && Mundos.proxyPos != -1
            && Vars.world.tile(Mundos.proxyPos) != null && Vars.world.tile(Mundos.proxyPos).build != null;
        if(existe && !Vista.dentro(proxyCx, proxyCy, pcx, pcy, carga, HISTERESIS + 2)) return;
        trasladarProxy(pcx, pcy, existe);
    }

    static void trasladarProxy(int pcx, int pcy, boolean hayAnterior){
        final int[][] cand = {{16, 16}, {-16, 16}, {16, -16}, {-16, -16}};
        final Tiles ts = Vars.world.tiles;
        for(int[] c : cand){
            final int cx = pcx + c[0], cy = pcy + c[1];
            if(Math.abs(cx) > 1020 || Math.abs(cy) > 1020) continue;
            if(ts.chunkLoaded(cx, cy) || !ts.chunkSlotFree(cx, cy)) continue;
            final Tile[] bloque = new Tile[T * T];
            for(int ly = 0; ly < T; ly++){
                for(int lx = 0; lx < T; lx++){
                    bloque[ly * T + lx] = new Tile(cx * T + lx, cy * T + ly, (Floor)Blocks.stone, (Floor)Blocks.air, Blocks.air, (byte)0);
                }
            }
            if(!Vars.world.publishChunk(cx, cy, bloque)) continue;
            final Tile t = Vars.world.tile(cx * T + T / 2, cy * T + T / 2);
            if(t == null) continue;
            final int viejoCx = proxyCx, viejoCy = proxyCy, viejoPos = Mundos.proxyPos;
            t.setBlock(Streamer.meta.nucleoBlock(), Team.sharded);
            // el nuevo núcleo comparte el módulo de ítems con los existentes: ahora ya se puede quitar el anterior
            if(hayAnterior && viejoPos != -1){
                Tile vt = Vars.world.tile(viejoPos);
                if(vt != null && vt.build != null) vt.setAir();
                Vars.world.unloadChunk(viejoCx, viejoCy);
            }
            proxyCx = cx;
            proxyCy = cy;
            Mundos.proxyPos = t.pos();
            return;
        }
    }
}
