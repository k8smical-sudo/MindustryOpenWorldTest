# Guía del núcleo (capa 2)

Fase 2 del plan. El núcleo es el único componente que conoce a los contribuyentes; el resto del juego pide resultados y no sabe quién aportó qué.

## Piezas
| Clase | Qué es |
|---|---|
| `Contribucion` | Contrato común: id, fuente, versión, tipo, alcance (`aplicaA`), peso, prioridad y presupuesto declarado |
| `ContribucionTerreno` | Contribución de terreno: `aportar(ContextoAporte, AporteTerreno)` devuelve las unidades de trabajo gastadas |
| `PresupuestoTrabajo` | Unidades por chunk, consultas y memoria, en unidades **contadas** (nunca tiempo). Sin declarar: conservador (D-N4) |
| `Orden` | Orden canónico: (prioridad, id de fuente, id) y (cy, cx) |
| `Degradaciones` | Registro de recortes por mundo. **Vacío en la fase 2** |
| `TerrenoBase` | El terreno base envuelto tal cual como una contribución (prioridad -1000) |
| `Nucleo` | Registro ordenado, evaluación en hilos de fondo, contadores y diagnóstico |

## Contrato de determinismo
La función de aporte es pura: su salida depende solo de semilla, dimensión, coordenadas, versión de generación y la propia contribución. No usa reloj, estado compartido mutable, mundo vivo ni GPU. El resultado no depende del orden de registro, de carga ni del hilo que termine antes.

## Qué cambió en el juego
`ChunkData.generar` ya no muestrea casillas: pide el terreno base a `Nucleo.terreno(...)` y solo traduce los códigos a ids de bloque con la paleta. Las casillas fuera del borde del mundo llegan marcadas `FUERA` y el consumidor pone el muro del borde. El resultado debe ser idéntico al anterior.

## Cómo se comprueba
- `Corpus comprobar`: la ruta del núcleo da lo mismo que la ruta directa, y lo mismo con 1, 2 y 4 hilos.
- `Corpus verificar corpus/esperado.txt`: el terreno servido por el núcleo coincide con lo congelado en la fase 0 (la congelación usa la ruta directa, independiente del núcleo).
- Límite conocido: el corpus llega hasta el chunk ±990, así que **no cubre el borde del mundo** (±LIMITE_MUNDO). Se comprueba a mano en el dispositivo.

## Qué NO hace todavía
No recorta a nadie (`DEGRADACION_ACTIVA = false`): cuenta unidades y avisa una vez por mundo si alguien excede lo declarado. Evalúa solo la primera contribución aplicable; combinar varias y resolver conflictos es la fase 5. Descomponer el terreno base en contribuciones independientes es la fase 3.
