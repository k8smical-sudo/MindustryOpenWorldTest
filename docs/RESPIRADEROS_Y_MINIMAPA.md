# Respiraderos más esparcidos, suelo variable y minimapa ajustable

## Respiraderos (Erekir)
Tres ajustes de generación nuevos, guardados con cada mundo:

| Ajuste | Nuevo | Mundos anteriores | Qué hace |
|---|---|---|---|
| `mundo-infinito:respiraderos-separacion` | 120 (48 a 400) | 48 | Distancia mínima entre dos respiraderos. Antes estaba fija en 48 |
| `mundo-infinito:respiraderos-mezcla` | 0.5 (0 a 1) | 1 | Fracción de respiraderos que alteran el suelo de su alrededor. El resto aparece sobre el terreno natural |
| `mundo-infinito:respiraderos-radio-min` / `-max` | 4 y 12 (1 a 14) | 6 y 14 | Radio de la zona alterada, que ahora varía más |

Los mundos anteriores no tienen estas claves en su perfil: se abren con los valores de entonces, así que su terreno no cambia. Solo los mundos nuevos usan los valores nuevos.

**Qué no está resuelto:** la forma de la zona alterada sigue siendo el mismo círculo con ruido. Con mezcla 0.5 aparecen más respiraderos sobre terreno natural, pero si la forma circular se ve artificial hará falta rediseñarla (por ejemplo, con ruido de contorno más irregular), y eso es un cambio aparte.

**Cortes:** no he encontrado la causa leyendo el código. `ponerPiso` recorta bien al chunk y el bloque 3x3 de cada respiradero se escribe completo en cada chunk que lo alcanza. Hace falta una coordenada de un respiradero cortado para comprobarlo.

## Minimapa
Nuevos ajustes en Ajustes → OpenWorld:
- **Mostrar el minimapa:** casilla, apagada solo si quieres ocultarlo.
- **Tamaño del minimapa:** de 50 % a 200 %.
- **Posición del minimapa:** horizontal y vertical, en unidades de interfaz. Se puede **arrastrar** con el dedo; un toque sin arrastrar sigue cambiando el zoom del mapa.

La posición se guarda en los ajustes y se limita a un rango razonable.

## Qué se comprueba (`Corpus comprobar`)
- Un mundo anterior conserva separación 48, mezcla 100 % y radio 6 a 14.
- Un mundo nuevo guarda 120, 50 % y radio 4 a 12, y lo conserva al guardar y leer.
- Los valores fuera de rango se recortan, y un radio mínimo mayor que el máximo se corrige.
- El generador usa la separación del mundo: retícula 120 en uno nuevo y 48 con retícula 96 en uno anterior.

El minimapa se comprueba en el dispositivo (ver `LISTA_VISUAL.md`), no en el corpus.
