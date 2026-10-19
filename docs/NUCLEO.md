# Guía del núcleo (capa 2)

Fases 2 y 3 del plan. El núcleo es el único componente que conoce a los contribuyentes; el resto del juego pide resultados y no sabe quién aportó qué.

## Piezas
| Clase | Qué es |
|---|---|
| `Contribucion` | Contrato común: id, fuente, versión, tipo, alcance (`aplicaA`), peso, prioridad y presupuesto declarado |
| `ContribucionTerreno` | Contribución de terreno por chunk: `aportar(ContextoAporte, AporteTerreno)` devuelve las unidades gastadas |
| `EtapaBase` | Contribución de terreno que trabaja **casilla a casilla** sobre el estado común `CeldaBase` |
| `EtapasBase` | Las 11 etapas del terreno base (tabla de abajo) |
| `CeldaBase` | Estado de una casilla: servicio común de solo lectura + lo que se pasan las etapas |
| `PipelineBase` | Recorre las 1024 casillas **una vez** y aplica las etapas activas en orden canónico |
| `PresupuestoTrabajo` | Unidades por chunk, consultas y memoria, en unidades **contadas** (nunca tiempo). Sin declarar: conservador (D-N4) |
| `Orden` | Orden canónico: (prioridad, id de fuente, id) y (cy, cx) |
| `Degradaciones` | Registro de recortes por mundo. **Vacío todavía** |
| `Nucleo` | Registro ordenado, evaluación en hilos de fondo, contadores y diagnóstico |

## Las etapas del terreno base
El orden canónico es el orden en que antes se ejecutaba `Muestreo.muestrear`. Cada etapa lee lo que dejó la anterior y escribe lo suyo.

| Prioridad | Etapa | Qué hace |
|---|---|---|
| -900 | `suelo-serpulo` | Clima (temperatura, humedad, altura) y salinas |
| -900 | `suelo-erekir` | Piso de Erekir (cristal, carbón, zonas berílica y roja, lagos de escoria) |
| -880 | `volcanico-serpulo` | Campos de magma y roca caliente |
| -870 | `brea-agua-serpulo` | Brea, océanos, lagos, orillas y playas |
| -800 | `claro-inicial` | El claro del spawn (quita lo peligroso o intransitable) |
| -700 | `pozos-spawn` | Pozos garantizados cerca del spawn |
| -600 | `crateres` | Cráteres de impacto, cráteres con respiradero y respiraderos antiguos |
| -500 | `vetas` | Dónde hay depósitos y de qué tipo; deja en seco las vetas iniciales |
| -400 | `muros` | Macizos, pasillos y muros que imponen las vetas |
| -300 | `menas` | Grafito, arenal, menas del suelo y de pared |
| -200 | `decoracion` | Rocas y cristales sueltos |

Cada etapa decide por casilla si actúa (`ere` separa Serpulo de Erekir en los mundos mezclados); todas se aplican a las dimensiones -1, 0 y 1. Una etapa que falta deja su parte en neutro (piso de piedra, sin mena, sin muro, sin decoración).

## Contrato de determinismo
La función de una contribución es pura: su salida depende solo de semilla, dimensión, coordenadas, versión de generación y la propia contribución. No usa reloj, estado compartido mutable, mundo vivo ni GPU. El resultado no depende del orden de registro, de carga ni del hilo que termine antes.

## Cómo se comprueba (`Corpus comprobar`)
1. El terreno servido por el núcleo (todas las etapas) es idéntico a `Muestreo.muestrear`, que se conserva **sin tocar** como referencia independiente.
2. Las etapas de suelo solas dan exactamente `Muestreo.pisoBase` y no tocan mena, muro ni decoración.
3. Sin la decoración, piso, mena y muro quedan idénticos al resultado completo.
4. Quitando cada etapa por turnos, el resultado es reproducible; se informa de su hash.
5. Con 1, 2 y 4 hilos el resultado es idéntico.
6. `Corpus verificar corpus/esperado.txt` compara con lo congelado en la fase 0.

Límite conocido: el corpus llega hasta el chunk ±990, así que no cubre el borde del mundo.

## Qué NO hace todavía
No recorta a nadie (`DEGRADACION_ACTIVA = false`). Si se registran varias contribuciones de terreno, las etapas se aplican en pasada única y una contribución por chunk que no sea etapa solo se usa si no hay etapas; combinar contribuciones de mods y resolver conflictos es la fase 5.

## Pendiente de limpieza
`Muestreo.muestrear` y los pisos de `Muestreo` duplican ahora el código de las etapas. Se conservan como referencia hasta que el corpus pase en CI con `esperado.txt`; entonces se retiran y las etapas quedan como única fuente de verdad.
