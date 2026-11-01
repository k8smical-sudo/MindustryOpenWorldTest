package mundoinfinito;

/**
 * PRUEBAS DE LA NAVEGACIÓN CON ZOOM Y PANEO (plan v6, D14). Sin el juego: solo matemática de {@link NavegacionVista}.
 * Cubren el criterio de F6 y F14: la transformación es invertible, el zoom mantiene fijo su punto de anclaje, la escala tiene límites, el
 * contenido no se pierde fuera de la ventana y, si cabe, se centra. Salida 0 si todo pasa; 1 si no.
 */
final class PruebasNavegacion{
    private static int fallos;

    private PruebasNavegacion(){}

    public static void main(String[] args){
        int rc = 3;
        try{
            pruebaInvertible();
            pruebaAnclaje();
            pruebaLimitesEscala();
            pruebaPaneo();
            pruebaAjusteContenidoGrande();
            pruebaAjusteContenidoPequeno();
            pruebaEncajar();
            System.out.println(fallos == 0 ? "[Nav] OK: todas las pruebas pasan." : "[Nav] FALLO: " + fallos + " prueba(s) no pasan.");
            rc = fallos == 0 ? 0 : 1;
        }catch(Throwable e){
            e.printStackTrace();
            rc = 3;
        }
        System.exit(rc);
    }

    private static void pruebaInvertible(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.paneo(37.5f, -12f);
        n.zoomEn(100f, 80f, 0.6f);
        boolean ok = true;
        for(float c = -300f; c <= 300f; c += 75f){
            ok &= cerca(n.aContenidoX(n.aPantallaX(c)), c) && cerca(n.aContenidoY(n.aPantallaY(c)), c);
        }
        fallos += chequear("la transformación pantalla-contenido es invertible tras paneo y zoom", ok);
    }

    private static void pruebaAnclaje(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.paneo(-40f, 15f);
        final float px = 210f, py = 130f;
        final float antesX = n.aContenidoX(px), antesY = n.aContenidoY(py);
        n.zoomEn(px, py, 1.2f);
        fallos += chequear("el zoom mantiene fijo el punto de anclaje (sin desplazamiento)",
            cerca(n.aContenidoX(px), antesX) && cerca(n.aContenidoY(py), antesY));
    }

    private static void pruebaLimitesEscala(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.zoomEn(0f, 0f, 100f);
        final boolean tope = cerca(n.escala, 1.5f);
        n.zoomEn(0f, 0f, 0.0001f);
        final boolean suelo = cerca(n.escala, 0.25f);
        fallos += chequear("la escala queda acotada a [min, max]", tope && suelo);
    }

    private static void pruebaPaneo(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.paneo(10f, -5f);
        n.paneo(2.5f, 4f);
        fallos += chequear("el paneo se acumula", cerca(n.panX, 12.5f) && cerca(n.panY, -1f));
    }

    /** Un contenido mayor que la ventana no puede alejarse más que su margen fuera de ella. */
    private static void pruebaAjusteContenidoGrande(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.panX = 1000f;
        n.ajustar(500f, 500f, 2000f, 2000f, 40f);
        final boolean derecha = cerca(n.panX, 460f);              // ventana - margen
        n.panX = -5000f;
        n.ajustar(500f, 500f, 2000f, 2000f, 40f);
        final boolean izquierda = cerca(n.panX, -1960f);          // margen - contenido
        fallos += chequear("un contenido mayor que la ventana conserva su margen visible", derecha && izquierda);
    }

    /** Un contenido menor que la ventana se centra. */
    private static void pruebaAjusteContenidoPequeno(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.panX = -300f;
        n.panY = 900f;
        n.ajustar(500f, 500f, 100f, 100f, 40f);
        fallos += chequear("un contenido que cabe se centra en la ventana", cerca(n.panX, 200f) && cerca(n.panY, 200f));
    }

    private static void pruebaEncajar(){
        final NavegacionVista n = new NavegacionVista(0.25f, 1.5f);
        n.encajar(500f, 500f, 1000f, 500f, 40f);
        final boolean escalaOk = cerca(n.escala, 0.5f);           // limitado por el ancho
        final boolean centrado = cerca(n.panX, 0f) && cerca(n.panY, 125f);
        n.encajar(500f, 500f, 200f, 200f, 40f);
        final boolean sinPasarDeUno = cerca(n.escala, 1f);        // no se amplía lo que ya cabe
        fallos += chequear("encajar escala sin pasar de 1 y centra el contenido", escalaOk && centrado && sinPasarDeUno);
    }

    private static boolean cerca(float a, float b){
        return Math.abs(a - b) <= 1e-3f;
    }

    private static int chequear(String texto, boolean ok){
        System.out.println("[Nav] " + (ok ? "ok: " : "FALLO: ") + texto);
        return ok ? 0 : 1;
    }
}
