package mundoinfinito;

/**
 * MEDICIÓN AUTOMÁTICA DE FOTOGRAMAS (fase 9). Puede BAJAR la calidad visual cuando el juego va lento, y volver a subirla cuando va bien, pero nunca por
 * encima de lo que eligió el jugador. Solo mira el tiempo de dibujo y solo cambia cosas visuales: no hay ninguna vía hacia la simulación ni hacia la
 * generación (regla 3 de la capa visual), y por eso se le permite medir tiempo, a diferencia del presupuesto de simulación.
 *
 * Se promedia por ventanas de {@link #VENTANA} fotogramas. Una ventana lenta baja un nivel; {@link #VENTANAS_PARA_SUBIR} ventanas seguidas rápidas
 * suben uno. Los primeros {@link #IGNORAR} fotogramas (la carga del mundo) no cuentan.
 */
final class VisualMonitor{
    static final float MS_LENTO = 33.4f, MS_RAPIDO = 20f;
    static final int VENTANA = 120, VENTANAS_PARA_SUBIR = 5, IGNORAR = 180;

    private int vistos, enVentana, rapidas, niveles;
    private float suma;

    /** Anota el tiempo de un fotograma, en milisegundos. */
    void muestra(float ms){
        if(vistos < IGNORAR){
            vistos++;
            return;
        }
        if(Float.isNaN(ms) || ms < 0f) return;
        suma += Math.min(ms, 1000f);   // un tirón enorme no debe decidir por sí solo
        if(++enVentana < VENTANA) return;
        final float media = suma / enVentana;
        suma = 0f;
        enVentana = 0;
        if(media > MS_LENTO){
            if(niveles < VisualCalidad.values().length - 1) niveles++;
            rapidas = 0;
        }else if(media < MS_RAPIDO){
            if(++rapidas >= VENTANAS_PARA_SUBIR && niveles > 0){
                niveles--;
                rapidas = 0;
            }
        }else{
            rapidas = 0;
        }
    }

    /** La calidad que se usa: la elegida menos los niveles que ha bajado la medición (nunca más que la elegida). */
    VisualCalidad efectiva(VisualCalidad elegida){
        return VisualCalidad.deIndice(elegida.ordinal() - niveles);
    }

    int niveles(){
        return niveles;
    }

    void reiniciar(){
        vistos = 0;
        enVentana = 0;
        rapidas = 0;
        niveles = 0;
        suma = 0f;
    }
}
