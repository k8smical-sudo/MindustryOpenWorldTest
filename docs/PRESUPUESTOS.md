# Presupuestos y degradación de simulación (fase 7)

## Idea
Cada contribución declara cuánto trabajo puede hacer por chunk, en **unidades que ella misma cuenta** (`PresupuestoTrabajo`). Si pide más de lo permitido, el núcleo aplica una **regla de degradación** definida y registrada. **Nada se mide en tiempo** y **nada interrumpe la generación**: la salida degradada es siempre la misma para la misma entrada.

Una etapa del terreno base gasta **1 unidad por casilla** y declara 1024 por chunk. Un chunk en el borde del mundo pide menos (solo cuenta las casillas dentro).

## Qué se permite
`permitidas = unidades declaradas * porcentaje / 100` (división entera), donde el porcentaje es el ajuste de generación `mundo-infinito:terreno-presupuesto-pct` del mundo (por defecto 100, rango 1 a 100). Como es un parámetro del perfil, se guarda con el mundo: **reabrirlo reproduce exactamente la misma degradación**. Con 100 ninguna etapa se degrada y el recorrido es el de siempre, sin coste extra por casilla.

## Las tres reglas
Las define el plan solo como principio (D-N4: "recorte por orden canónico"); esta es la interpretación implementada.

| Regla | Qué hace | Cuándo conviene |
|---|---|---|
| `RECORTE_CANONICO` (por defecto) | Hace solo las primeras `permitidas` unidades en orden canónico (casillas fila a fila); el resto queda como lo dejaron las etapas anteriores | Cuando lo que importa es el tope exacto de trabajo |
| `REDUCCION_DE_RESOLUCION` | Hace 1 casilla de cada k x k (k potencia de 2, la menor que cabe), con la retícula anclada a las coordenadas **virtuales**: dos chunks vecinos coinciden y no hay costuras | Contenido repartible: decoración, menas. La etapa `decoracion` la usa |
| `OMISION` | No hace nada de esa contribución en ese chunk | Todo o nada. Es también lo que se aplica a una contribución de mod que no se puede detener a medias |

Una contribución elige su regla sobrescribiendo `Contribucion.reglaDegradacion()`.

### Contribuciones que no son etapas (mods)
Su función no se puede cortar a medias: si gasta más de lo permitido, o si falla (lanza una excepción), se **omite** su aporte y el chunk lo genera el terreno base (o piedra si no hay base). Se registra y la generación sigue.

## Registro por mundo
`Degradaciones` guarda por cada recorte: contribución, chunk, unidades pedidas, unidades permitidas y regla. Se reinicia al abrir un mundo y guarda como máximo 4096 entradas (después solo cuenta). Como la degradación es determinista, regenerar los chunks del mundo reconstruye el mismo registro; no se escribe en el archivo del mundo.

## Aviso de contribuyente sistemático
Una contribución es **sistemática** cuando se degrada en al menos la mitad de sus evaluaciones y en al menos 32 chunks. Se avisa **una vez por mundo** en el log, con un ejemplo de lo pedido y lo permitido. El resumen de `Perfil` muestra cuántas son. Es la señal de que su presupuesto declarado es demasiado bajo.

## Qué se comprueba (`Corpus comprobar`)
- Recorte canónico: con 300 permitidas solo salen las 300 primeras casillas, en chunks de coordenadas positivas y negativas.
- Omisión: no se hace nada. Reducción: retícula 2x2 con 300, 4x4 con 100 y 16x16 con 10, anclada a coordenadas virtuales; sin presupuesto, omitida.
- Sin exceso no se degrada nada; el porcentaje del perfil escala lo permitido.
- Con el 25 % el terreno es **siempre el mismo**: dos pasadas en frío y 4 hilos dan lo mismo, y es distinto del completo.
- El registro anota cada etapa recortada y las 11 etapas se detectan como sistemáticas.
- Un mundo reabierto (perfil guardado y leído) reproduce el mismo terreno y el mismo registro.
- Con el 1 % (10 casillas por etapa) los 435 chunks se generan sin ninguna excepción.
- Una contribución de mod dentro de presupuesto aporta lo suyo; una que gasta de más o que falla se omite y el chunk sale idéntico al del terreno base.

## Qué NO hace todavía
- No hay presupuesto de **consultas** ni de **memoria** por contribución: se declaran, pero solo se hace cumplir el de unidades de trabajo.
- Las reglas de reducción y recorte actúan sobre las etapas del terreno base. Las contribuciones de mods solo tienen omisión hasta que exista una API que permita cortarlas a medias.
- Los fenómenos (`Fenomenos`) tienen su propio límite por macrocélula y no pasan aún por este presupuesto.
