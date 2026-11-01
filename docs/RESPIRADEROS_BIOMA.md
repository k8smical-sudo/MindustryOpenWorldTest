# Respiraderos por bioma (Erekir)

Antes: la probabilidad de respiradero era única por celda (0,30–0,55) y el terreno abierto exigido (0,50–0,70) era fijo. Con la separación de 120 casillas, esto dejaba muy pocos respiraderos, y el arranque del mundo depende de ellos.

Ahora hay cuatro ajustes de generación, uno por grupo de bioma (riolita, arkyic, amarillo, rojo), que multiplican la probabilidad, y un multiplicador de la exigencia de terreno abierto.

- Mundos nuevos: multiplicadores 1,6 y umbral 0,85 (valores iniciales, ajustables en Ajustes del mundo cuando exista la pantalla de creación).
- Mundos anteriores: multiplicadores 1 y umbral 1. Su resultado es idéntico al de antes.

La probabilidad efectiva de un bioma es `densidad × multiplicador_bioma`. La densidad de la celda usa el mayor multiplicador, y cada bioma se reduce a su valor en `valido`, así que el resultado es exacto por bioma.

Pendiente: calibrar los valores por bioma en el dispositivo (contar respiraderos en una región fija de cada bioma).
