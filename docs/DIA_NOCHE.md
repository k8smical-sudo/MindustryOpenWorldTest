# Ciclo día/noche de Serpulo: cómo funciona en campaña y en la mod

## En campaña (Mindustry)
- `Universe.update()` (core/src/mindustry/game/Universe.java) calcula el ciclo solo cuando el estado tiene un **sector** (`state.hasSector()`), el planeta tiene `updateLighting` y el preset del sector no tiene `noLighting`.
- Si se cumplen, cada fotograma calcula `alpha` a partir de la luz del sector y fija `rules.ambientLight.a = 1 - alpha` y `rules.lighting = !equal(alpha, 1)`.
- Serpulo tiene `updateLighting` en true (valor por defecto). Erekir lo tiene en false (líneas 70 de Planets.java: `erekir`). Las líneas 175 corresponden a `makeAsteroid`, no a Serpulo.

## En la mod
- No hay sector: el ciclo de `Universe` no se ejecuta.
- `configurarReglas()` fija `r.lighting = false`, así que aunque hubiera ciclo no se vería.
- Resultado: **el Serpulo de la mod no tiene día ni noche**. Es lo que se observa en las capturas.

## Cómo añadirlo
Haría falta un ciclo propio de la mod que replique la fórmula de `Universe` (alpha desde una luz que avance con el tiempo del mundo) y lo aplique solo en la dimensión de Serpulo, con un ajuste para activarlo. No lo he implementado porque depende de cómo se calcula la luz del sector (`Sector.getLight()`), que no he leído todavía.
