# Planetas: rotación y órbita

## Valores por defecto (mundos nuevos)
| Planeta | Rotación (día) | Órbita (año) | Origen |
|---|---|---|---|
| Serpulo | 85080 ticks (23 min 38 s) | 39600 ticks (~11 min) | Datos del plan |
| Erekir | 18000 ticks (5 min) | 9000 ticks (2,5 min) | Rotación **estimada**; órbita según el plan |

Un año tiene cuatro temporadas, cada una un cuarto de órbita.

Los mundos anteriores conservan 36000 ticks de día y 1008000 de año (10 min y 28 días), así que sus temporadas y su luz no cambian.

## Cómo lo cambia un addon
```java
ApiPlaneta.proponer("minmod", "minmod:serpulo-rapido", ApiPlaneta.SERPULO, 60000, 30000, -100);
```
Gana la propuesta de menor prioridad (número). Un addon que quiera sobrescribir el valor del juego usa un número muy bajo.

Para un planeta nuevo:
```java
ApiPlaneta.registrar("minmod:nuevo", 24000, 12000);
```
Solo antes de abrir un mundo. Un planeta nuevo tiene ajustes de rotación y órbita, pero todavía no tiene un tipo de mundo asociado: eso requiere la dimensión correspondiente.

## Lo que afecta cada valor
- **Rotación:** la duración de un día. Controla el día del calendario y la luz de Serpulo (el ciclo día/noche).
- **Órbita:** la duración del año. Controla las temporadas.

Erekir no tiene ciclo de luz en Mindustry, así que su rotación solo afecta al calendario.

## Límites
- Solo Serpulo y Erekir están conectados a un mundo. Un planeta nuevo necesita su propia dimensión.
- La animación de deploy y el menú de mundo abierto no están en esta entrega.
- El valor de rotación de Erekir es una estimación: hay que calibrarlo en el juego.
