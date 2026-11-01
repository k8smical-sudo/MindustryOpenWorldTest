# Canal de iluminación (fases 10 y 12)

Un único canal con dos magnitudes: color ambiental y oscuridad. Cada aporte fija solo lo que toca, y cada magnitud se combina por separado con la política del canal.

## API
```java
ApiVisual.registrarAmbiente("minmod", "minmod:bruma", 0, 1f);
ApiVisual.fijarOscuridad("minmod:bruma", 0.4f);              // solo oscuridad
ApiVisual.fijarColorAmbiente("otromod:atardecer", 1f, 0.5f, 0.2f); // solo color
ApiVisual.quitarOscuridad("minmod:bruma");
```
`fijarAmbiente(id, r, g, b, a)` sigue existiendo: fija color y oscuridad a la vez (la `a` es la oscuridad).

## Cómo se aplica
- Cada fotograma, antes de dibujar, se combina el canal y se escribe en el ambiente del mundo solo mientras se dibuja. Al terminar el fotograma se restauran el ambiente y el estado de iluminación originales. No queda ningún residuo en las reglas del mundo.
- Si hay oscuridad mayor que 0 o color fijado, la iluminación se activa para ese fotograma. Si no hay ningún aporte, el mundo se dibuja como siempre.
- Un valor no numérico hace que se ignore ese aporte en la magnitud afectada. Un peso no válido ignora el aporte entero.

## Ciclo día/noche
Es un aporte de oscuridad más (`mundo-infinito:ciclo-dia-noche`). Solo está activo en la dimensión de Serpulo de un mundo con el ajuste activado. Al apagarlo, quita su aporte; no deja nada en las reglas.

## Lo que cambia respecto a la versión anterior
- La iluminación ya no se activa solo porque el mundo la tenga encendida: ahora la activan los aportes con oscuridad o color. Un aporte de prueba de la capa visual puede encender la iluminación en un mundo que no la tenía; es intencionado.
- La versión de la API visual sube a 1.1 por las operaciones nuevas.
