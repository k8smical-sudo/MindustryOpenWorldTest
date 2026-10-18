# Fase 0: verificación y línea base

Respuestas escritas a la sección 0 del plan, sacadas de leer el código (MX-TEST, Mindustry, Arc y la mod). Lo marcado **PENDIENTE** necesita una ejecución (CI o dispositivo).

## 1. Control de hilos de MX-TEST
No existe. Solo hay usos sueltos de `Vars.mainExecutor` y `Threads.daemon` (ping, replay). **D-N2 cerrada:** el núcleo gestiona su propio pool dentro de la mod (`Hilos.java`, ver `GUIA_HILOS.md`). MX-TEST no cambia.

## 2. Qué parches de MX-TEST tocan generación, render, tiles y eventos
Todos los parches del mundo abierto están en `patches/openworld/` (12). Ninguno toca el generador: la generación vive entera en la mod.

| Área | Parches | Qué tocan |
|---|---|---|
| Tiles (anillo, coordenadas virtuales) | 0001, 0002 | `Tile`, `Tiles` |
| Eventos y publicación de chunk | 0003 | `World.publishChunk/unloadChunk`, `EventType` (`ChunkLoadEvent`, `ChunkUnloadEvent`) |
| Mundo y oscuridad | 0004 | `World.openRing`, `ChunkDarkness` |
| IA, índices, niebla | 0005, 0006, 0007 | `Pathfinder`, `BlockIndexer`, `RingOreIndex`, `FogControl`, `UnitComp`, `UnitGroup`... |
| Render | 0008, 0009, 0011, 0012 | `FloorRenderer`, `BlockRenderer`, `FogRenderer`, `RingFbo`, `StaticWall` |
| Combate | 0010 | `Damage`, `BulletComp` |

`patches/client/` (MindustryX) añade `RenderExt` y `Hooks`: son opciones de dibujo, no un registro de capas. `patches/arc/` (9 parches) no toca shaders ni post-procesado.

**Consecuencia para la fase 1:** los enganches de chunk (0003) ya existen. Tick (`Trigger.update`), eventos de tile y de construcción y los cuatro puntos de dibujo (`Trigger.preDraw/draw/drawOver/postDraw`, en `Renderer.draw`) ya los ofrece Mindustry y la mod ya usa `Trigger.draw`. La fase 1 puede quedar en cero parches nuevos; se confirma al diseñar el núcleo (fase 2).

## 3. Línea base de coste por chunk
Herramientas listas, valores **PENDIENTE**:
- CI: `Corpus base` escribe `linea-base.txt` (ms por chunk, por dimensión y por semilla; terreno base sin hooks) en el artefacto `corpus`.
- Dispositivo: `Perfil` escribe cada ~15 s `[Perfil] generación por chunk (hilos de fondo): Serpulo n=.. media=..ms max=..ms | Erekir ...` (incluye los hooks). Anota aquí los valores en varias semillas y en ambas dimensiones.

## 4. El terreno base no depende del orden de carga de los mods
Verificado por lectura: `ChunkData.generar` calcula el terreno base solo con `Muestreo` (puro) y después ejecuta los hooks, ordenados por (prioridad, id). `Corpus comprobar` lo confirma con dos pasadas en frío. Estado: **PENDIENTE de la primera ejecución del CI**.

## 5. Puntos de enganche visual
- Mindustry: `Layer` (constantes de capa), `Draw.draw(capa, runnable)`, `Draw.drawRange`, triggers de dibujo, `Shaders`, luces, bloom, `effectBuffer`.
- Arc: `Shader` (uniformes `int`, `float`, vectores, matrices), `FrameBuffer`, `Bloom`, `ScreenQuad`.
- Un shader que no compila **lanza** `IllegalArgumentException` en el constructor y `isCompiled()` se puede consultar: el registro de shaders debe construirlos dentro de `try/catch` y desactivar el que falle (regla 6 de la capa visual).
- La propiedad de uniformes y la cadena de post-procesado son construcciones de la mod (el motor no las tiene); no requieren cambios de motor.

## D-N3 cerrada
No se añaden parches nuevos a Arc. Todo lo necesario para las fases 9 y 10 existe. MX-TEST ya mantiene 9 parches de Arc por otros motivos; no crecen. Se reabre solo si una fase concreta demuestra una capacidad que falta.

## Cierre de la fase 0
- [x] Cada punto de la sección 0 tiene respuesta escrita (1, 2, 4 y 5 arriba; 3 y la ejecución de 4 dependen del CI y del dispositivo).
- [x] D-N2 y D-N3 cerradas.
- [ ] Corpus reproducible dos veces seguidas: lo comprueba el paso "Corpus - reproducibilidad" del CI.
- [ ] `corpus/esperado.txt` en el repo (ver `corpus/LEEME.md`).
- [ ] Línea base registrada (CI y dispositivo).
- [ ] Lista visual con capturas (ver `LISTA_VISUAL.md`).
