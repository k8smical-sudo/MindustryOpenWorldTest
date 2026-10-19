# Guía de ajustes y perfil de mundo (fase 4)

## Un solo concepto: `AjusteMundo`
Las reglas de juego (gamerules) y los parámetros de generación son el mismo concepto: un id `fuente:nombre`, un tipo (`BOOL`, `ENTERO`, `DECIMAL`), un ámbito, un valor por defecto y un rango.

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

Un mundo **nuevo** guarda el valor vigente de todos los parámetros de generación al crearse. Un mundo **antiguo** no tiene perfil y se abre con los valores por defecto, sin error. Al abrir, el perfil queda congelado.

Regla: cambiar el valor por defecto de un ajuste de generación cambia los mundos que no lo guardaron (los antiguos), así que exige subir `GEN_ACTUAL`.

## Parámetros de generación actuales
Todos de ámbito `GENERACION`, rango 1.0 a 4.0, y su valor por defecto es la constante de siempre:

| Id | Por defecto | Qué hace |
|---|---|---|
| `mundo-infinito:serpulo-estiramiento-altura` | 2.3 | Relieve: montañas y mares más marcados al subir |
| `mundo-infinito:serpulo-estiramiento-temperatura` | 3.1 | Zonas heladas y abrasadoras más extensas al subir |
| `mundo-infinito:serpulo-estiramiento-humedad` | 2.7 | Desiertos y pantanos más extensos al subir |

Solo afectan a Serpulo (la etapa `suelo-serpulo`); Erekir no cambia. Los demás parámetros de `Muestreo` se irán exponiendo a medida que se retire el código de referencia (ver `NUCLEO.md`).

## Cómo añadir un parámetro
1. Un id y un campo en `ParametrosGeneracion`, con su valor por defecto.
2. Su `AjusteMundo` en `RegistroAjustes` con el rango.
3. La lectura en `PerfilMundo.parametros()`.
4. Usarlo en la etapa correspondiente a través de `ce.par`.
5. `Corpus comprobar` debe seguir dando idéntico con el perfil por defecto.

## Qué NO cambia
Los ajustes de creación que ya existían (límite del terreno, núcleo inicial, mundo limpio) siguen en su sitio; se migrarán al perfil cuando haga falta. No hay pantalla para editar parámetros de generación al crear un mundo: se añadirá con la fase de API pública.
