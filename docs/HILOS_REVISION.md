# Hilos: reparto, revisión de puntos de enganche, cachés y medición (fase 8)

## Quién corre en qué hilo
| Hilo | Cuántos | Qué hace | Lo gobierna |
|---|---|---|---|
| Principal | 1 | Juego y dibujo (en Mindustry son el mismo): estado del mundo, GPU, interfaz | — |
| Generación de terreno (`Streamer`) | auto: entre 1 y 3 (núcleos - 2) | Genera chunks: terreno base por el núcleo, hooks de la API, construcción de casillas | `ow-hilos` vía `Ajustes.hilos()` |
| Cocinado de mallas (`ChunkGeometryCooker`) | auto: entre 1 y 2 (núcleos - 1) | Convierte terreno cacheado en `float[]` de vértices | `ow-hilos` |
| Pool puro (`Hilos`) | auto: entre 1 y 4 (núcleos - 1) | Cálculo puro con combinación en orden canónico | `ow-hilos` |

`ow-hilos`: `0` automático, `1` ruta de un solo hilo (todo el trabajo de fondo en un hilo), `n` n hilos. Con la ruta de un solo hilo el resultado es idéntico (comprobado por el corpus).

El reparto real del juego es **por chunk**: cada hilo de generación toma un chunk entero. La función del núcleo para un chunk es pura, así que no hay orden que combinar entre hilos: cada chunk depende solo de sus entradas.

## Revisión de la lista de puntos de enganche
Revisión por lectura de código: ningún archivo que corre en hilos de fondo llama a `Vars.world`, `Groups`, `Events.fire`, `Draw`, `Gl`, `new Mesh` ni a `Core.app`, `Core.graphics`, `Core.camera` o `Core.input`. Lo único que leen del juego es `Vars.content` (la lista de bloques, que no cambia tras la carga).

| Punto | Hilo | Qué toca | Veredicto |
|---|---|---|---|
| `Streamer.tick` (`Trigger.update`) y `Perfil.cierre` | Principal | Colas, mundo; marca el hilo principal | Correcto |
| `Anillo.publicar`, `Anillo.soltar`, `Anillo.trasladarProxy` | Principal | `Vars.world.publishChunk/unloadChunk`, casillas, unidades (`Groups`) | Correcto por llamada desde el tick. **Vigilado** en ejecución (solo aviso) |
| `Streamer.finalizarApertura` | Principal | Publica los chunks iniciales | **Vigilado** en ejecución: si sale un aviso, el cargado se hace en otro hilo y hay que revisarlo |
| `ChunkMeshRenderer.subirListos`, `tomarMalla`, dibujo | Principal | GPU (`Mesh`, GL) | Correcto. **Vigilado** (`subirListos`) |
| `ChunkGeometryCooker.construirTabla` | Principal | `Core.atlas` (único sitio donde se toca) | Correcto: los hilos de cocinado solo leen la tabla ya construida |
| `bucleTrabajador` -> `ChunkData.generar` -> `Nucleo.terreno` y etapas | Fondo | Arrays propios del hilo; lectura de `Vars.content` | Correcto |
| `Extensiones.aplicarTerreno` y generadores de la API (fenómenos, estructuras) | Fondo | `ContextoTerreno` y `ContextoFenomeno`: datos del chunk, sin acceso al mundo | Correcto por diseño de la API |
| `Anillo.construir` | Fondo | Crea casillas sueltas (sin publicar) y lee `Vars.content` | Correcto |
| Hilos de cocinado | Fondo | Producen `float[]`; no tocan GPU ni mundo | Correcto |
| `Hilos` (pool) | Fondo | Tareas puras (contrato) | Correcto por contrato |
| Parche `0003` de MX-TEST (`World.publishChunk/unloadChunk`, eventos de chunk) | Principal | Mundo | Lo llama la mod desde el hilo principal |

**Cómo confirmarlo en un dispositivo:** abrir un mundo, moverse y mirar el log. Si no sale ningún `Hilos: '...' se ejecutó en el hilo '...' y no en el principal`, la revisión se confirma. Si sale alguno, no es un fallo del juego (no se bloquea nada): es el dato que hace falta para corregir ese punto.

## Cachés con tope de memoria
| Caché | Tope | Al alcanzarlo |
|---|---|---|
| Terreno (`CacheChunks`) | 48 MB en total: N1 (sin comprimir) el 40 % y N2 (comprimido) el 60 % | Descarta lo menos reciente (LRU); solo se vacía si cambia el mundo, la dimensión o el conjunto de generadores |
| Fenómenos (`Fenomenos`) | 80 000 celdas candidatas, 80 000 celdas aceptadas y 20 000 macrocélulas | **Vacía la caché entera** (no solo lo menos reciente). Es correcto porque son funciones puras y se recalculan igual, pero no es un LRU: queda como mejora |
| Mallas (`ChunkMeshRenderer`) | Máximo de mallas en el pool (hasta 192, según la memoria del equipo: unos 2 MB de heap por malla) | Desaloja la malla de un chunk que no se ve, el menos reciente primero |
| Terreno del núcleo | Sin caché propia | Usa `CacheChunks` |

## Medición contra la línea base
Datos del CI (equipo de 4 núcleos, `Corpus comprobar` del último log). Coste por chunk del terreno base, mediana: ruta directa 3.0 a 3.2 ms; servido por el núcleo 3.1 a 3.3 ms (+2 a +4 %).

Corpus completo (435 chunks) con el pool y comprobación de resultado idéntico: 1 hilo 1314 ms, 2 hilos 516 ms, 4 hilos 513 ms. **Esta cifra no es una medición limpia**: la de 1 hilo incluye el calentamiento de la JVM. La medición limpia la hace el modo `Corpus escala` (calienta, y toma la mejor de 3 pasadas con 1, 2, 4... hilos), que el CI guarda en el artefacto `corpus` como `escalado.txt` y cuyo veredicto dice si hay aceleración.

Criterio del plan: con varios núcleos, la generación por chunk debe ser más rápida que con uno en el mismo dispositivo; si no lo es, se documenta el motivo y no se fuerza el paralelismo. Estado: la medición limpia del CI está pendiente de leerse en `escalado.txt`; la del dispositivo requiere comparar `ow-hilos=1` contra automático (ver abajo).

Posibles motivos si en un equipo no hay aceleración: núcleos compartidos o limitados, coste del reparto frente al de un chunk (unos 3 ms), o que los hilos del pool compitan con los de generación y los de cocinado por los mismos núcleos.

### Cómo medir en un dispositivo
`[Perfil] generación por chunk (hilos de fondo): ... media=..ms` sale cada ~15 s. Se compara con el ajuste `ow-hilos` en `1` y en automático (en escritorio, `-Dmow.hilos=1`; el ajuste se aplica al abrir un mundo). No hay todavía una pantalla para cambiarlo en Android.

## Qué NO hace todavía
- Las cachés de fenómenos no son LRU.
- No hay pantalla para elegir `ow-hilos` en el dispositivo.
- Los puntos vigilados son solo aviso. Pasarlos a exigencia exige haber visto el log de un dispositivo sin avisos.
