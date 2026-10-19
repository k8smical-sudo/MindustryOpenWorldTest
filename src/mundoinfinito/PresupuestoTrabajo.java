package mundoinfinito;

/**
 * PRESUPUESTO DE TRABAJO declarado por un contribuyente (plan, sección 5). Se mide en unidades CONTADAS, nunca en tiempo real:
 * así la degradación (fase 7) es determinista. Cada contribuyente define y documenta su propia unidad.
 * No confundir con {@link Presupuesto}, que reparte tiempo de fotograma en el hilo del juego.
 */
final class PresupuestoTrabajo{
    /** Si un contribuyente no declara presupuesto (D-N4): una unidad por casilla de un chunk, ninguna consulta, 64 KB. */
    static final PresupuestoTrabajo CONSERVADOR = new PresupuestoTrabajo(MundoInfinitoMod.TAM_CHUNK * MundoInfinitoMod.TAM_CHUNK, 0, 64 * 1024, "casilla");

    /** Unidades de trabajo máximas por chunk. */
    final int unidadesChunk;
    /** Consultas máximas a otros contribuyentes por chunk. */
    final int consultas;
    /** Memoria de trabajo máxima por hilo, en bytes. */
    final long memoriaBytes;
    /** Qué es una unidad para este contribuyente (documentación; sale en los avisos). */
    final String unidad;

    PresupuestoTrabajo(int unidadesChunk, int consultas, long memoriaBytes, String unidad){
        this.unidadesChunk = unidadesChunk;
        this.consultas = consultas;
        this.memoriaBytes = memoriaBytes;
        this.unidad = unidad;
    }
}
