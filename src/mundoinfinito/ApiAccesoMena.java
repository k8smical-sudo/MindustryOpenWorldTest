package mundoinfinito;

/**
 * Regla de acceso de las menas de pared: decide si un muro candidato es aprovechable desde espacio libre.
 *
 * Parámetros del mundo (ParametrosGeneracion, sellados al crear el mundo):
 *  - radio (R): hasta dónde puede buscar el espacio libre desde el muro.
 *  - caminoMinimo: tamaño mínimo de la zona abierta conectada al muro (el "camino" al que se conecta).
 *  - espacioLocal: casillas abiertas mínimas en el entorno inmediato del muro (radio 2).
 *  - taladroTamano: lado (1 a 3 casillas) del bloque libre donde cabe el taladro, dentro de la zona conectada.
 *
 * Para devs y modders: {@link #establecer} sustituye el criterio por uno propio. El criterio recibe un {@link Contexto}
 * con las casillas abiertas relativas al muro y los parámetros, y debe ser determinista (solo depender de la semilla,
 * las coordenadas y los parámetros), para que el terreno no dependa del orden de generación de los chunks.
 */
public final class ApiAccesoMena {
    private ApiAccesoMena(){}

    /** Criterio de acceso. Devuelve true si el muro del contexto admite mena de pared. */
    public interface Criterio {
        boolean aceptar(Contexto c);
    }

    /** Vista de un muro candidato. Las coordenadas de {@link #abierta} son relativas al muro. */
    public static final class Contexto {
        private final int s, x, y;
        private final MundoInfinitoMod.Muestreo.Spawn sp;
        public final int radio, caminoMinimo, espacioLocal, taladroTamano;

        Contexto(int s, int x, int y, MundoInfinitoMod.Muestreo.Spawn sp, int radio, int caminoMinimo, int espacioLocal, int taladroTamano){
            this.s = s; this.x = x; this.y = y; this.sp = sp;
            this.radio = radio; this.caminoMinimo = caminoMinimo; this.espacioLocal = espacioLocal; this.taladroTamano = taladroTamano;
        }

        /** ¿La casilla (muro + dx, muro + dy) es abierta (no muro)? Misma fórmula que el terreno base. */
        public boolean abierta(int dx, int dy){
            return !MundoInfinitoMod.Muestreo.muroEn(s, x + dx, y + dy, sp);
        }
    }

    /** Un criterio registrado para un asset, con su dueño (el add-on o el mod que lo puso). */
    private static final class Entrada {
        final String dueno;
        final Criterio criterio;
        Entrada(String dueno, Criterio criterio){ this.dueno = dueno; this.criterio = criterio; }
    }

    private static final java.util.Map<String, Entrada> POR_ASSET = new java.util.LinkedHashMap<String, Entrada>();

    /**
     * Pone el criterio de UN asset (por id, p. ej. "mindustry:berilio-pared"). Un asset solo puede tener un criterio:
     * si ya tiene uno de otro dueño, se rechaza en vez de pisarlo, para que dos add-ons no se rompan entre sí.
     * El criterio solo afecta a ese asset, en todos los mundos. Es un criterio puro, sin estado global.
     */
    public static synchronized void establecer(String assetId, String dueno, Criterio criterio){
        if(assetId == null || dueno == null || criterio == null) throw new IllegalArgumentException("asset, dueño y criterio son obligatorios");
        if(!existeAsset(assetId)) throw new IllegalArgumentException("asset no registrado: " + assetId);
        final Entrada previa = POR_ASSET.get(assetId);
        if(previa != null) throw new IllegalStateException("el asset " + assetId + " ya tiene criterio de '" + previa.dueno + "'; no se pisa");
        POR_ASSET.put(assetId, new Entrada(dueno, criterio));
    }

    /** Quita el criterio de un asset. Solo lo puede quitar su dueño. */
    public static synchronized boolean quitar(String assetId, String dueno){
        final Entrada e = POR_ASSET.get(assetId);
        if(e == null || !e.dueno.equals(dueno)) return false;
        POR_ASSET.remove(assetId);
        return true;
    }

    /** Criterio que se aplica a las menas con ese código: el del asset que lo produce, o el por defecto. */
    public static synchronized Criterio criterioPara(int codigoMena){
        if(codigoMena == 0) return ApiAccesoMena::porDefecto;
        for(ApiAsset a : ApiAsset.todos()){
            if(a.codigo == codigoMena){
                final Entrada e = POR_ASSET.get(a.id);
                return e == null ? ApiAccesoMena::porDefecto : e.criterio;
            }
        }
        return ApiAccesoMena::porDefecto;
    }

    private static boolean existeAsset(String assetId){
        for(ApiAsset a : ApiAsset.todos()) if(a.id.equals(assetId)) return true;
        return false;
    }

    /** Lo llama la etapa de menas, con el código de la mena candidata. */
    static boolean aceptar(int s, int x, int y, MundoInfinitoMod.Muestreo.Spawn sp, ParametrosGeneracion pg, int codigoMena){
        return criterioPara(codigoMena).aceptar(new Contexto(s, x, y, sp, pg.menasAccesoRadio, pg.menasAccesoMinimo, pg.menasEspacioLocal, pg.menasTaladroTamano));
    }

    private static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};

    /** Criterio por defecto: espacio local, zona conectada de tamaño mínimo y un bloque libre para el taladro dentro de la zona. */
    static boolean porDefecto(Contexto c){
        // espacio libre inmediato (radio 2): sin él, el muro está encerrado
        int local = 0;
        for(int dy = -2; dy <= 2; dy++)
            for(int dx = -2; dx <= 2; dx++)
                if((dx != 0 || dy != 0) && c.abierta(dx, dy)) local++;
        if(local < c.espacioLocal) return false;

        // zona abierta conectada al muro, dentro de la ventana de radio R (recorrido en anchura, completo)
        final int R = c.radio, lado = 2 * R + 1, n = lado * lado;
        final byte[] est = new byte[n];      // 0 sin mirar, 1 abierta de la zona, 2 muro o fuera de la zona
        final int[] cola = new int[n];
        final int centro = R * lado + R;
        est[centro] = 2;
        int cab = 0, fin = 0, zona = 0;
        for(int d = 0; d < 4; d++){
            final int i = (R + DY[d]) * lado + (R + DX[d]);
            if(est[i] != 0) continue;
            if(c.abierta(DX[d], DY[d])){ est[i] = 1; cola[fin++] = i; } else est[i] = 2;
        }
        while(cab < fin){
            final int i = cola[cab++];
            zona++;
            final int px = i % lado - R, py = i / lado - R;
            for(int d = 0; d < 4; d++){
                final int nx = px + DX[d], ny = py + DY[d];
                if(Math.abs(nx) > R || Math.abs(ny) > R) continue;
                final int j = (ny + R) * lado + (nx + R);
                if(est[j] != 0) continue;
                if(c.abierta(nx, ny)){ est[j] = 1; cola[fin++] = j; } else est[j] = 2;
            }
        }
        if(zona < c.caminoMinimo) return false;

        // bloque libre del tamaño del taladro, con todas sus casillas dentro de la zona conectada
        final int T = c.taladroTamano;
        for(int ty = -R; ty + T - 1 <= R; ty++){
            for(int tx = -R; tx + T - 1 <= R; tx++){
                boolean libre = true;
                for(int by = 0; by < T && libre; by++)
                    for(int bx = 0; bx < T && libre; bx++)
                        if(est[(ty + by + R) * lado + (tx + bx + R)] != 1) libre = false;
                if(libre) return true;
            }
        }
        return false;
    }
}
