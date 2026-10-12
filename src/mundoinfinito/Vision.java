package mundoinfinito;

import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mundoinfinito.MundoInfinitoMod.Mundos;

import java.util.HashMap;

/**
 * VISIÓN DEL DRON (el jugador) y NIEBLA DE GUERRA, ambas según los ajustes OpenWorld y aplicadas EN VIVO.
 *
 * Referencia: Mindustry v4 (FogRenderer / Renderer.showFog). Allí el jugador revela a su alrededor con su propio radio de visión
 * (player.getViewDistance()) y la niebla se puede desactivar con el ajuste "fogofwar". Aquí:
 *
 *  - Niebla desactivada: rules.fog = rules.staticFog = false. Logic deja de actualizar el FogControl y el Renderer deja de dibujar
 *    la niebla, así que se ve todo el terreno cargado. Reactivarla es seguro en ambos sentidos: los FrameBuffer de FogRenderer se
 *    crean en el constructor (no dependen de las reglas) y el progreso explorado sigue en Vars.fogControl.
 *  - Niebla activada: FogControl revela alrededor de CADA unidad con UnitType.fogRadius. El radio vanilla de un dron del núcleo es
 *    pequeño (~20 tiles) y no tenía relación con el mundo que el mod mantiene cargado; ahora el dron del jugador revela el radio
 *    elegido en Ajustes (por defecto, el de la distancia de carga). Se restaura el valor original al salir del mundo.
 *
 * Coste: una comparación cada 30 ticks. Nada de esto toca casillas ni entidades.
 */
final class Vision{
    /** fogRadius original de cada tipo de unidad que se ha tocado (para restaurarlo al salir). */
    private static final HashMap<UnitType, Float> originales = new HashMap<>();
    private static float reloj;

    private Vision(){}

    /** Hilo del juego, cada fotograma (barato: solo actúa cada 30 ticks). */
    static void tick(){
        if(Vars.state == null || Vars.player == null) return;
        reloj += Time.delta;
        if(reloj < 30f) return;
        reloj = 0f;

        // 1) niebla activada / desactivada
        final Rules r = Vars.state.rules;
        final boolean quiere = Ajustes.niebla();
        if(r.fog != quiere || r.staticFog != quiere){
            r.fog = quiere;
            r.staticFog = quiere;
            if(quiere) Mundos.refrescarNiebla();   // el renderizador vuelve a copiar lo explorado desde la CPU
            Log.info("[MundoInfinito] Niebla de guerra @", quiere ? "activada" : "desactivada");
        }
        if(!quiere) return;   // sin niebla no hay nada que revelar

        // 2) radio que revela el dron del jugador
        final Unit u = Vars.player.unit();
        if(u == null || Vars.player.dead() || u.type == null) return;
        final UnitType t = u.type;
        Float base = originales.get(t);
        if(base == null){
            base = t.fogRadius;
            originales.put(t, base);
        }
        final float quiereR = Math.max(base, Ajustes.visionTiles());   // nunca más ciego que el dron original
        if(Math.abs(t.fogRadius - quiereR) > 0.01f){
            t.fogRadius = quiereR;
            // FogControl solo empuja un evento cuando la unidad cambia de casilla: sin esto el nuevo radio no se vería hasta moverse
            u.lastFogPos = -1;
        }
    }

    /** Al salir del mundo (ResetEvent): los tipos de unidad vuelven a su radio de visión original. */
    static void restaurar(){
        for(java.util.Map.Entry<UnitType, Float> e : originales.entrySet()) e.getKey().fogRadius = e.getValue();
        originales.clear();
        reloj = 0f;
    }
}
