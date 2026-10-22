# Entorno de reglas y muerte del dron

## Qué pasó en el dispositivo
En un mundo con la gamerule **Permitir construcción global**, el dron del jugador (alpha, de Serpulo) aparecía y se destruía una y otra vez. Pasaba también al cruzar un portal de Erekir a Serpulo.

## Causa
Mindustry mata ("unitEnvDeath", una autodestrucción) a toda unidad que no soporta el entorno del mundo (`rules.env`): `UnitType.supportsEnv` exige que ninguno de sus `envDisabled` esté en el entorno. El tipo de unidad por defecto trae `envDisabled = Env.scorching` (calor abrasador); las unidades de Erekir lo ponen a 0.

La gamerule de construcción global (y los mundos antiguos de terreno mezclado) usan `ENTORNO_COMBINADO` para que el menú de construcción ofrezca los bloques de los dos planetas. Ese entorno incluía `Env.scorching`. Resultado: en esos mundos todas las unidades de Serpulo morían al aparecer. Ningún bloque de Mindustry usa `Env.scorching` (se comprobó en todo `core/src`); solo lo usan el efecto visual de calor de Erekir, el editor, un par de avisos y `UnitType.envDisabled`.

## Arreglo
- `ENTORNO_COMBINADO` ya no incluye `Env.scorching`. Los bloques de ambos planetas siguen disponibles y las unidades de Serpulo y de Erekir sobreviven.
- Si el dron guardado de un jugador no soporta el entorno, se elige otro que sí (alpha, o evoke) en lugar de forzar alpha, y se avisa en el log.
- El corpus comprueba que el entorno combinado no contiene `Env.scorching`.

Efecto secundario: en un mundo con construcción global, el efecto visual de calor abrasador solo se ve en la dimensión de Erekir (donde es nativo), no en Serpulo.

## Diagnóstico nuevo en el log
- Al aplicar las reglas: `Reglas del mundo: planeta=..., env=... (calor abrasador: sí/no)`.
- Si muere el dron del jugador (hasta 8 veces por sesión): `Murió el dron del jugador: tipo=..., soporta el entorno=..., env=..., planeta=..., posición=(x, y)`.

Si el bucle volviera a aparecer, esa línea dice si es el entorno (`soporta el entorno=false`) o es otra cosa (por ejemplo, una posición fuera del mapa).
