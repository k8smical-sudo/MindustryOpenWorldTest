# Pozos del spawn: fuera de los mundos nuevos

## Qué eran
Una lista de objetos (pozos de líquidos y energía, respiraderos de 3 casillas y depósitos) colocada alrededor del spawn. Su lista se separa para que no se solapen, pero quedan muy juntos: en Erekir dan el anillo de cráteres apiñados que se ve en la captura, y el tipo de respiradero de 3 casillas es el origen de los respiraderos cortados.

## Qué cambia
- Ajuste `mundo-infinito:pozos-spawn`, de generación. **Mundos nuevos: desactivado.** Mundos anteriores: activado (sin la clave en su perfil se usa el valor de entonces, así que su terreno no cambia).
- Con el ajuste desactivado, no se genera ningún pozo del spawn. Los respiraderos de Erekir y el resto de fenómenos se reparten con su generación normal (disco de Poisson).
- El claro inicial del spawn se mantiene.

## Qué no cambia
- Los mundos guardados: su perfil no tiene la clave y se abren con los pozos.
- La generación de respiraderos, depósitos y terreno.

## Limitaciones
- El corpus de regresión sigue verificando el terreno con pozos (el perfil por defecto de la mod). Así el terreno de los mundos anteriores queda protegido, pero el terreno de los mundos nuevos (sin pozos) no está cubierto por un hash; hace falta un segundo corpus para eso.
- `Respiraderos.valido` aún evita la zona de los pozos del spawn aunque ya no existan; deja un hueco pequeño junto al spawn, sin efecto visible.
