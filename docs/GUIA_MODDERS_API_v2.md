# Guía para modders: API del mundo abierto, versión 2

Dependencia en tu `mod.json`, más la declaración propia de versión (Mindustry no valida versiones de dependencias; el base la lee y deja en el registro, con el prefijo `[MundoInfinito][API]`, lo que pidas y no exista):

```json
"dependencies": ["mundo-infinito"],
"openWorld": { "api": 2, "capacidades": ["terreno", "fenomeno", "estructura", "entidades", "mundos", "almacen"] }
```

## Reglas que hay que conocer

1. **Coordenadas virtuales.** Todo generador recibe coordenadas virtuales (tile = chunk · 32 + 0..31), no locales. En este mundo virtual == local (el origen de la ventana es 0).
2. **Dos fases.** Terreno y fenómenos corren en *hilos de fondo* y deben ser funciones puras de (semilla, coordenadas). Estructuras, entidades y mundos corren en el *hilo del juego*.
3. **Sin acceso al mundo desde el fondo.** `ContextoTerreno` es lo único que se recibe; no hay `Vars.world`, edificios ni unidades. El contexto lanza `IllegalStateException` si se usa fuera del generador o desde otro hilo.
4. **Registro al cargar el mod** (constructor, `init` o `loadContent`). Al abrir el primer mundo se cierra para terreno, fenómenos, mundos y escuchas de `TerrenoGenerado`: un generador tardío dejaría chunks ya generados sin él. Se rechaza con un mensaje en el registro.
5. **Ids `idmod:nombre`.** De cada id sale la semilla del generador (`semillaMod`): dos mods nunca se pisan la aleatoriedad. Usa solo `ctx.azar(...)` y `ctx.ruido(...)`.
6. **Orden.** Por `(prioridad, id)`, nunca por orden de carga. Si dos generadores escriben la misma casilla, gana el de mayor prioridad. Los del base usan prioridades negativas (−100, −90).
7. **Aislamiento.** Una excepción desactiva ese generador para el mundo en curso, queda en el registro y el chunk se publica igualmente.
8. **Tiempo.** `presupuestoMs()` es orientativo: si se supera se avisa en el registro, pero NO se difiere (diferir haría el terreno dependiente del tiempo y rompería el determinismo).
9. **Generación del mundo.** Los mundos guardados con `gen = 3` conservan su terreno exacto; los generadores del base (`FenomenosBase`) solo actúan en `gen >= 4`. Tus generadores actúan en todos los mundos de las dimensiones que aceptes en `aplicaA`.

## Pipeline

Terreno base → fenómenos (por prioridad) → terreno de mods (por prioridad) → evento `TerrenoGenerado` → publicación → (hilo del juego) edificios guardados, estructuras pendientes, evento `ChunkCargado`.

## Puntos de extensión

| API | Qué hace |
|---|---|
| `registrarTerreno(GeneradorTerreno)` | Modifica piso, mena y muros del chunk. |
| `registrarFenomeno(GeneradorFenomeno)` | Fenómenos puntuales con distancia mínima real (disco de Poisson), mínimo garantizado por macrocelda y reintentos acotados. Tú defines `proponer`, `valido`, `aplicar`. |
| `registrarEstructura(GeneradorEstructura)` | `ancla` (pura) + `colocar` (hilo del juego, **una sola vez por ancla**, cuando todos los chunks que cubre están cargados y con sus edificios restaurados). La marca vive en `AlmacenModulo` del chunk del ancla. |
| `registrarPolitica(PoliticaSueno)` | Tus unidades duermen fuera de vista y vuelven al acercarse el jugador (se guarda tipo, equipo, posición, vida, escudo y rotación). Evento `EntidadDespertada`. |
| `registrarEquipo(Team)` | El equipo se guarda y restaura siempre. En mundos nuevos (`limpio`) ya se conserva todo lo que pongan los mods; `limpio = false` solo existe en mundos antiguos, donde lo enemigo/abandonado guardado se descartaba. |
| `registrarMundo(MundoRegistrado)` | Dimensión nueva con su generador (`GeneradorMundo`), reglas, núcleo, ítems iniciales y punto de llegada. Sale como destino en los portales; `viajarA(id)` teletransporta por código. La dimensión (≥ 16) se deriva del id y es estable. Cada mundo tiene sus propios chunks, edificios, checkpoints y niebla. |
| `AlmacenModulo.leer/escribir(modulo, cx, cy, bytes)` | Datos propios por chunk, en el mismo archivo de región. **Solo hilo del juego**; si la región aún se lee del disco devuelve null/false y se reintenta. |
| `alTerrenoGenerado(id, escucha)` | Lee o modifica casillas justo antes de publicar (hilo de fondo). |

Eventos de Arc (hilo del juego): `MundoAbierto`, `ChunkCargado`, `ChunkDescargado`, `EdificioRestaurado`, `EntidadDespertada`, `MundoRegistradoEvento`.

## Limitaciones conocidas

- `AlmacenModulo` no se puede usar desde hilos de fondo (el terreno no necesita guardar nada).
- Los fenómenos solo pueden muestrear el terreno base en las dimensiones 0, 1 y −1; los mundos registrados trabajan sobre el chunk con `ContextoTerreno`.
- La logística orbital (silo/drones) solo une Serpulo y Erekir.
- El formato de región pasa a la versión 7: un mundo guardado con esta versión no lo lee la anterior.

Ejemplo mínimo con las extensiones: `ejemplo-mod/`.
