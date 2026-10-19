# Guía para el motor (MX-TEST): enganches y su dueño

Fase 1 del plan: la frontera entre el motor y la mod es esta tabla. **Para el mundo abierto no hace falta ningún parche nuevo en MX-TEST**: lo que el núcleo necesita ya existe. Cualquier enganche nuevo debe añadirse aquí con dueño, motivo y prueba **antes** de entrar a `patches/openworld/`.

| Enganche | Origen | Dueño | Motivo | Prueba |
|---|---|---|---|---|
| Tiles del anillo y coordenadas virtuales | `patches/openworld/0001`, `0002` | MX-TEST | El mundo es un anillo de chunks con coordenadas virtuales | `tests/openworld/` |
| `World.publishChunk/unloadChunk`, `ChunkLoadEvent`, `ChunkUnloadEvent` | `0003` | MX-TEST | Publicar y soltar chunks generados en hilos de fondo | `tests/openworld/` + lista visual 1, 2 y 8 |
| Apertura del anillo y oscuridad | `0004` | MX-TEST | Mundo abierto con macizos oscuros | lista visual 8 |
| Pathfinder, índices de menas, niebla, unidades | `0005`, `0006`, `0007` | MX-TEST | IA y consultas sobre un mundo sin límites reales | `tests/openworld/` |
| Render de suelo, bloques, niebla y muros | `0008`, `0009`, `0011`, `0012` | MX-TEST | Dibujar el anillo sin costuras | lista visual 1 a 8 |
| Combate | `0010` | MX-TEST | Daño y balas con coordenadas del anillo | prueba en dispositivo |
| Tick (`Trigger.update`) | Mindustry | Mindustry | Tick de la mod en el hilo del juego | sin parche |
| `TileChangeEvent`, `TilePreChangeEvent`, `BlockBuildBeginEvent`, `BlockBuildEndEvent` | Mindustry | Mindustry | Reaccionar a cambios y construcción | sin parche |
| `Trigger.preDraw/draw/drawOver/postDraw`, `Layer`, `Draw.draw(capa, ...)` | Mindustry | Mindustry | Capas de dibujo de la mod | sin parche |

Regla del plan: la generación vive entera en la mod. Ningún parche de MX-TEST toca el generador.
