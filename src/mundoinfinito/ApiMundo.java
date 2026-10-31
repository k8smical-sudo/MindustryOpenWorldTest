package mundoinfinito;

public final class ApiMundo{
    public static final int SERPULO = 0;
    public static final int EREKIR = 1;

    private ApiMundo(){}

    public static boolean hayMundoAbierto(){
        return MundoInfinitoMod.Streamer.meta != null;
    }

    public static int dimensionActual(){
        final MundoInfinitoMod.Meta m = MundoInfinitoMod.Streamer.meta;
        if(m == null) return -1;
        return m.dimGen();
    }
}
