# Respiraderos de Erekir calibrados como en la campaña

## Qué hace la campaña (ErekirPlanetGenerator)
- **Respiradero principal:** solo sobre riolita (y riolita rugosa), con probabilidad 0,002 por casilla, si el entorno de 5×5 está libre de sólidos y es riolita. Cada respiradero deja libre su entorno, así que aparecen en grupos pequeños.
- **Garantía mínima:** por mapa, entre 6 y 9 respiraderos. Si el principal no llega, se añaden buscando en cualquier bioma, con probabilidad 0,00018 por casilla y hasta 5 pasadas, sin tocar un radio de 5 casillas alrededor del spawn.
- **Tipo según bioma:** arkyic → respiradero de arkyic; amarillo → de amarillo; rojo → de rojo; carbono → de carbono; resto → de riolita.

## Lo que se ha trasladado a la mod
- Cinco grupos de bioma: riolita, arkyic, amarillo, rojo y carbono (el de carbono se añade ahora).
- **Multiplicadores por defecto para mundos nuevos:** riolita 1,0; arkyic, amarillo, rojo y carbono 0,35. La riolita es el bioma principal de la campaña; el resto solo recibe la garantía mínima, y 0,35 es un valor inicial que hay que calibrar.
- **Umbral de terreno abierto:** 1,0, sin relajar. Antes se usaba 0,85 para tener más respiraderos; ya no hace falta porque la riolita es la principal.
- Los mundos anteriores siguen con multiplicadores 1 y umbral 1, sin cambios.

## Lo que esto cambia respecto a la versión anterior
Con estos valores, fuera de la riolita habrá **menos** respiraderos que con 1,6. Es lo que hace la campaña. Si quieres más respiraderos en un bioma concreto, sube su multiplicador en los ajustes del mundo, o un addon puede proponerlo.

## Modularidad
Los ids de los multiplicadores son públicos (`ParametrosGeneracion.ID_RESP_BIOMA`). Un addon puede proponer un valor para cada bioma con `ApiAjustes.proponer`, y el juego lo resuelve con la política que declara el ajuste. Un addon que añada un bioma nuevo puede registrar su propio generador de fenómenos con `OpenWorldAPI`.

## Pendiente
- Calibrar el valor 0,35 en un mundo nuevo: contar respiraderos en una región fija de cada bioma.
- Comprobar que la garantía mínima por macrocélula no sustituye a la campaña en exceso.
