# Guía de ajustes, perfil de mundo y resolución de parámetros (fases 4 y 5)

## Un solo concepto: `AjusteMundo`
Las reglas de juego (gamerules) y los parámetros de generación son el mismo concepto: un id `fuente:nombre`, un tipo (`BOOL`, `ENTERO`, `DECIMAL`), un ámbito, un valor por defecto, un rango y, opcionalmente, una política de resolución.

| Ámbito | Qué es | Cuándo se registra | Se puede cambiar en un mundo existente |
|---|---|---|---|
| `JUEGO` | Se aplica sobre las reglas de Mindustry (las gamerules de siempre) | En cualquier momento | Sí (según la regla) |
| `GENERACION` | Lo lee la generación del terreno | Antes de abrir el primer mundo | **No**: queda guardado y fijo (cambiarlo abriría costuras) |

`GameRule.nueva(...)` y `OpenWorldAPI.registrarGameRule(...)` siguen funcionando igual: la regla se registra además como un ajuste booleano de ámbito `JUEGO`.

## Validación
Un valor fuera de rango **se recorta** al rango (nunca se rechaza ni se aborta) y queda anotado en `RegistroAjustes` (aviso en el log una vez por ajuste; el resumen de `Perfil` muestra el total). Un valor no numérico se sustituye por el valor por defecto. Un `ENTERO` se redondea. Un id que ningún mod registra ahora se conserva tal cual para no perder datos.

## Perfil de un mundo (`PerfilMundo`)
Se guarda en el archivo del mundo:
- `"ajustes": {id: valor}`: todos los valores, con el tipo de su definición.
- `"gamerules": {id: booleano}`: espejo para que una versión anterior del mod siga leyendo el mundo.

Un mundo **nuevo** guarda el valor de todos los parámetros de generación al crearse (ver "Varias contribuciones"). Un mundo **antiguo** no tiene perfil y se abre con los valores por defecto, sin error. Al abrir, el perfil queda congelado.

Regla: cambiar el valor por defecto de un ajuste de generación cambia los mundos que no lo guardaron (los antiguos), así que exige subir `GEN_ACTUAL`.

## Varias contribuciones sobre un mismo parámetro (fase 5)
Una fuente puede **proponer** un valor para un ajuste de generación con una `PropuestaParametro` (valor, peso, prioridad), registrada con `Nucleo.registrarParametro(...)` antes de abrir el primer mundo. Las propuestas se resuelven **al crear el mundo** y el resultado se guarda en su perfil: abrirlo después no depende de qué mods estén cargados ni de su orden.

Orden de precedencia al crear un mundo: 1) valor fijado expresamente por el creador (o por el perfil de un modpack); 2) resultado de resolver las propuestas; 3) valor por defecto.

### Políticas (las declara el dueño del ajuste)
| Política | Resultado |
|---|---|
| `MEDIA_PONDERADA` | Media de los valores, cada uno recortado al rango, ponderada por el peso. Pesos 1 y 3 sobre 2.0 y 4.0 dan 3.5 |
| `PRIORIDAD_ABSOLUTA` | Gana la primera propuesta en orden canónico: menor prioridad y, en empate, id de fuente y de propuesta |
| `ELECCION_PONDERADA` | Se elige una propuesta con probabilidad proporcional a su peso, con un hash determinista de (semilla, id del ajuste) |

Reglas comunes:
- **Orden canónico:** las propuestas se ordenan por (prioridad, id de fuente, id) antes de sumar o elegir. La suma de decimales no es asociativa; el orden fijo hace que el resultado sea idéntico bit a bit con cualquier orden de carga.
- **Fuera de rango:** cada propuesta se recorta al rango del ajuste y se anota; nunca se impone tal cual.
- **Una sola propuesta:** no necesita política. **Ninguna:** vale el valor por defecto.
- **Varias y sin política:** el ajuste no se resuelve. Se reporta una vez en el log y queda su valor por defecto (el resumen de `Perfil` muestra "ajustes sin política").
- **Peso no válido** (0, negativo, NaN, infinito): la propuesta se ignora con un aviso.

## Parámetros de generación actuales
Todos de ámbito `GENERACION` y política `MEDIA_PONDERADA`. El valor por defecto de los tres primeros es la constante de siempre:

| Id | Por defecto | Rango | Qué hace |
|---|---|---|---|
| `mundo-infinito:serpulo-estiramiento-altura` | 2.3 | 1.0 a 4.0 | Relieve: montañas y mares más marcados al subir |
| `mundo-infinito:serpulo-estiramiento-temperatura` | 3.1 | 1.0 a 4.0 | Zonas heladas y abrasadoras más extensas al subir |
| `mundo-infinito:serpulo-estiramiento-humedad` | 2.7 | 1.0 a 4.0 | Desiertos y pantanos más extensos al subir |
| `mundo-infinito:fenomenos-distancia-minima` | 0 | 0 a 512 | Distancia mínima en casillas entre fenómenos de **cualquier** mod. 0 = desactivada (ver `AREAS.md`) |

Los tres primeros solo afectan a Serpulo (la etapa `suelo-serpulo`); Erekir no cambia.

## Cómo añadir un parámetro
1. Un id y un campo en `ParametrosGeneracion`, con su valor por defecto.
2. Su `AjusteMundo` en `RegistroAjustes`, con rango y política (obligatoria si va a tener varios contribuyentes).
3. La lectura en `PerfilMundo.parametros()`.
4. Usarlo en la etapa correspondiente a través de `ce.par`.
5. `Corpus comprobar` debe seguir dando idéntico con el perfil por defecto.

## Qué NO cambia
Los ajustes de creación que ya existían (límite del terreno, núcleo inicial, mundo limpio) siguen en su sitio. No hay pantalla para editar parámetros ni para proponer valores: llegará con la API pública. Las gamerules (ámbito `JUEGO`) siguen eligiéndose a mano al crear el mundo; las propuestas de la fase 5 solo cubren parámetros de generación.
