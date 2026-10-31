# Tiempo, calendario y eventos (fase 11)

## Dos relojes, elegidos por el desarrollador
| | Reloj de juego | Reloj de pared (segundo plano) |
|---|---|---|
| API | `ApiTiempo.programar(id, tiempoDeJuego)` | `ApiTiempo.programarDeFondo(id, segundos)` |
| Avanza | solo jugando (no en pausa, no con el juego cerrado) | siempre, también cerrado |
| Determinista | sí | **no**: depende de cuándo se cerró el juego |
| Al reabrir con eventos vencidos | se disparan en su tiempo exacto | se aplican al volver, una sola vez |
| Uso | cambios del mundo | avisos, recompensas, notas (no cambian el terreno ni la simulación) |

El día, la temporada y `tiempoMundo()` siempre salen del reloj de juego: el mundo sigue siendo el mismo con la misma semilla.

## Garantías
- **Una sola vez.** El evento se quita de la lista y el calendario se escribe a disco **antes** de ejecutar su acción. Si el juego cae durante la acción, no se repite (en el peor caso se pierde). Nunca se repite.
- **Sin acción registrada, no se pierde.** Queda pendiente y se dispara cuando el mod registre su acción.
- **Guardado periódico.** El calendario se escribe cada 30 s aunque no haya disparos, y con cada guardado del mundo.

## Límite que queda
Si el juego cae después de que un evento de juego ya haya cambiado el mundo y antes de que el mundo se guarde, ese cambio puede no estar en el mundo guardado: el evento ya no se repite, pero el efecto puede faltar. Para eventos que no deben poder perderse, usa acciones idempotentes o regístralas como "reconciliación" al abrir.

## Ejemplo
```java
ApiTiempo.registrarAccion("minmod:tormenta", e -> { /* cambios del mundo */ });
ApiTiempo.programar("minmod:tormenta", ApiTiempo.tiempoMundo() + 60f * 60f * 5f);   // reloj de juego

ApiTiempo.registrarAccion("minmod:recordatorio", e -> { /* aviso al jugador */ });
ApiTiempo.programarDeFondo("minmod:recordatorio", 3600);                            // dentro de una hora de reloj real
```
