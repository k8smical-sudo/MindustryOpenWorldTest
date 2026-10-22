# Capa visual, base (fase 9)

Registro de dibujos, shaders y pasadas de post-procesado, con orden declarado, propiedad de uniformes, coste declarado, calidad configurable y aislamiento de fallos. **Es solo dibujo: nada de aquí puede cambiar la simulación ni el mundo.**

> Todavía es interno de la mod (`VisualRegistro.global()`). Se expone a otros mods en la fase de API pública; el código de un addon se ve igual que el de `VisualPrueba`.

## Las seis reglas
1. **Sin efecto sobre la simulación.** Todas las clases `Visual*` están separadas de la simulación y la generación: el corpus mira el código compilado y falla si alguna clase de simulación referencia una `Visual*`.
2. **Coste declarado.** Cada dibujo declara sus llamadas de dibujo, cada pasada sus pasadas y bytes de textura. Si se exceden los de la calidad, se omite lo opcional empezando por lo último en orden canónico. Lo esencial no se omite nunca.
3. **Calidad configurable.** Baja, media y alta (Ajustes -> OpenWorld). Una medición de fotogramas puede bajarla sola; nunca la sube por encima de lo elegido.
4. **GPU en el hilo de render.** Dibujar y ejecutar pasadas solo se hace desde él. Registrar se puede hacer desde cualquier hilo.
5. **Orden declarado.** (capa, prioridad, id de fuente, id). Nunca el orden de carga.
6. **Degradación sin error.** Un dibujo, un shader o una pasada que fallan se desactivan para la sesión con un aviso, y el juego sigue.

## Capas (en el orden real del motor, z creciente)
| Capa | z | Dónde queda |
|---|---|---|
| `SUELO` | 10 | Marcas sobre el suelo, bajo escombros y bloques |
| `BLOQUES` | 30 | Con los bloques |
| `SOBRE_BLOQUES` | 35 | Guías y brillos sobre los bloques |
| `UNIDADES` | 60 | Con las unidades terrestres |
| `BALAS` | 100 | Con las balas |
| `SOBRE_UNIDADES` | 117 | Sobre las unidades voladoras |
| `INTERFAZ` | 150 | Superposiciones del mundo (como los nombres de jugador). No sustituye a la interfaz de Scene2D |

El plan enumera las balas después de "sobre unidades"; en el motor las balas (z 100) se pintan antes que las unidades voladoras (z 115), así que se sigue el orden real. Dentro de una capa cada dibujo recibe un desplazamiento de 0.001 por posición para conservar el orden (hasta 0.09).

## Calidad
| Calidad | Llamadas de dibujo | Pasadas | Texturas | Densidad de efectos |
|---|---|---|---|---|
| Baja | 40 | 0 | 0 | 0.5 |
| Media | 120 | 2 | 8 MB | 0.75 |
| Alta (por defecto) | 400 | 6 | 64 MB | 1.0 |

La unidad de "llamada de dibujo" es la que declara cada contribución. La densidad de efectos la leerán las contribuciones de efectos (fase 10).

**Medición automática** (activada por defecto, desactivable): ventanas de 120 fotogramas; una ventana de más de 33.4 ms de media baja un nivel; 5 ventanas seguidas de menos de 20 ms suben uno. Los primeros 180 fotogramas (la carga) no cuentan y un tirón suelto está limitado a 1000 ms. Solo afecta al dibujo.

## Registrar un dibujo
```java
VisualRegistro.global().registrarDibujo(VisualDibujo.de(
    "minmod", "minmod:aura", VisualCapa.SOBRE_BLOQUES, 0,
    /* coste */ 3, /* esencial */ false, /* shader */ "minmod:brillo", /* condición */ null,
    () -> { /* Draw.color(...), Fill.circle(...) ... */ }));
```
- El id es `fuente:nombre`. Una condición falsa hace que ese fotograma no se dibuje ni cuente en el coste.
- Si el shader no existe, falló o está desactivado, el dibujo sale igual sin él.

## Registrar un shader
```java
VisualRegistro.global().registrarShader(VisualShaderDef.de(
    "minmod", "minmod:brillo", 0,
    new String[]{"minmod_fuerza"},                     // uniformes que ESCRIBE: cada uno tiene un solo dueño
    () -> VisualArc.crear(VisualArc.VERTICE, FRAGMENTO, sh -> sh.setUniformf("minmod_fuerza", 0.7f))));
```
- Los uniformes (la función que se pasa a `VisualArc.crear`) se escriben en `Shader.apply()`, que el lote llama justo después de enlazar el programa. **No hay que escribirlos en ningún otro sitio:** `Draw.shader(...)` solo anota qué shader se usará, y escribir un uniforme antes, con otro programa activo, da `GL_INVALID_OPERATION` en cada fotograma (así falló el primer intento en un dispositivo real).
- El programa se crea la primera vez que se usa, en el hilo de render. Si el GLSL no compila, Arc lanza una excepción: el shader se desactiva con aviso y los dibujos salen sin él.
- **Propiedad de uniformes:** si dos shaders declaran escribir el mismo uniforme, hay aviso y gana el primero en orden canónico (prioridad, fuente, id); el otro queda desactivado. Pasa igual con cualquier orden de carga. Conviene prefijar los uniformes con el id del mod.
- Para sprites, `VisualArc.VERTICE` es el vértice por defecto de Arc.

## Registrar una pasada de post-procesado
```java
VisualRegistro.global().registrarPasada(VisualPasada.de(
    "minmod", "minmod:viñeta", 0, /* pasadas */ 1, /* bytes de textura */ 4L << 20,
    () -> { /* lo que hace sobre la pantalla ya dibujada */ }));
```
Se ejecutan al final del dibujo (`Trigger.postDraw`), en orden canónico y solo mientras quepan en el presupuesto de la calidad (la que no cabe se omite). La pasada se ocupa de sus propios `FrameBuffer`; este registro solo la ordena, la mide y la aísla.

## Probar en un dispositivo, sin mods
En Ajustes -> OpenWorld hay dos casillas apagadas por defecto:
- **Prueba visual: shader de prueba.** Un cuadrado sobre el dron teñido de naranja con un shader que pulsa. Al apagarla desaparece sin afectar a nada.
- **Prueba visual: shader que falla.** Un cuadrado bajo el dron con un shader escrito mal a propósito. Debe salir **igual, sin tinte**, el log debe avisar **una sola vez** (`Visual: el shader '...' falló`) y la partida debe seguir.

## Qué se comprueba (`Corpus comprobar`)
- Mismo orden de dibujo con las 5040 ordenaciones de registro de 7 dibujos en varias capas; la z sale creciente.
- Un shader se registra, se aplica, se desactiva y se reactiva sin afectar a los demás dibujos.
- Dos shaders que escriben el mismo uniforme: aviso y mismo resultado con cualquier orden; uno de mayor prioridad que llega después pasa a ser el dueño.
- Shader que no compila, que falla al aplicarse, dibujo que falla y condición que falla: se desactivan con aviso, no se atribuyen al shader equivocado y el resto sigue.
- Presupuesto: con calidad baja se omite lo opcional de atrás hacia delante (24 ordenaciones); lo esencial no se omite; las pasadas salen en orden mientras quepan.
- Medición automática: ignora la carga, baja, recupera, no pasa de lo elegido, y un tirón suelto no decide.
- Cambiar la calidad no cambia ni un chunk del corpus.
- La simulación y la generación no referencian ninguna clase `Visual*` (se mira el código compilado).

## Qué NO hace todavía
- No hay efectos ni partículas con presupuesto y pooling: es la fase 10.
- El registro ordena y aísla las pasadas de post-procesado, pero no trae ayudas para escribirlas (framebuffers, ping-pong).
- No hay API pública para otros mods ni puntos de extensión de interfaz.
- Los fallos que solo ocurren al compilar el GLSL se detectan; los errores de GL que no lanzan excepción no.
