# Capa visual (fases 9 y 10)

Registro de dibujos, shaders y pasadas de post-procesado (fase 9) y, en la fase 10, efectos con pool, color ambiental con política y mallas preparadas fuera del hilo de render, con orden declarado, propiedad de uniformes, coste declarado, calidad configurable y aislamiento de fallos. **Es solo dibujo: nada de aquí puede cambiar la simulación ni el mundo.**

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
| Calidad | Llamadas de dibujo | Pasadas | Texturas | Densidad de efectos | Efectos vivos | Coste de efectos | Mallas subidas por fotograma |
|---|---|---|---|---|---|---|---|
| Baja | 40 | 0 | 0 | 0.5 | 32 | 64 | 1 |
| Media | 120 | 2 | 8 MB | 0.75 | 128 | 256 | 2 |
| Alta (por defecto) | 400 | 6 | 64 MB | 1.0 | 512 | 1024 | 4 |

La unidad de "llamada de dibujo" es la que declara cada contribución.

**Qué afecta hoy la calidad:** los dibujos, pasadas, efectos y subidas de mallas **registrados con esta capa**, y las variantes de shaders del juego según su rango de calidad. Los shaders de Mindustry no cambian por sí solos con la calidad: hace falta registrar una variante más barata para baja o media (un mod, o el propio Mundo Infinito más adelante, cuando se mida dónde está el coste). El control de calidad de Ajustes sigue considerándose de prueba.

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

## Efectos y partículas (fase 10)
Un efecto es algo que se dispara en una posición, con su capa y su duración, y se dibuja cada fotograma mientras vive.
```java
VisualEfectos.global().registrarEfecto(VisualEfectoDef.de(
    "minmod", "minmod:chispa", VisualCapa.SOBRE_BLOQUES, /* prioridad */ 0, /* duración en ticks */ 30f, /* coste */ 1, /* esencial */ false,
    e -> { Draw.color(e.r, e.g, e.b, e.a * e.fout()); Lines.circle(e.x, e.y, 4f + 10f * e.fin()); }));

VisualEfectos.global().emitir("minmod:chispa", x, y, rotacion, r, g, b, a, datos);   // devuelve false si se descartó
```
- **Pool de tamaño fijo** (512 instancias): emitir no crea objetos. La instancia que recibe el dibujo solo vale durante esa llamada; no hay que guardarla.
- **Duración:** `fin()` va de 0 a 1 y `fout()` de 1 a 0. Al cumplirse la duración el efecto desaparece y su instancia vuelve al pool. Con el juego en pausa no envejece.
- **Capa:** se dibuja en la z de su capa más 0.095, por encima de los dibujos normales de esa capa.
- **Densidad:** de las emisiones de un efecto **opcional** solo entra la fracción que dice la calidad (media 0.75 = tres de cada cuatro), con un acumulador, sin azar. Los **esenciales** no se adelgazan.
- **Tope y prioridad:** la calidad fija cuántos efectos pueden estar vivos. Si está lleno, entra un efecto de **mejor** prioridad (número menor) que el peor vivo, al que desaloja (el más antiguo de esa prioridad); uno peor o igual no entra.
- **Coste:** si el coste declarado de los vivos excede el presupuesto de la calidad, no se dibujan los opcionales que no caben (por prioridad, los más antiguos primero). Siguen vivos y envejeciendo.
- **Fallos:** un efecto cuyo dibujo lanza una excepción se desactiva con aviso para la sesión, sus instancias se liberan y el juego sigue.

## Color ambiental y luz (fase 10)
Varios mods pueden aportar un color ambiental (RGBA; en Mindustry el alfa es cuánta oscuridad añade). El **canal** declara la política que los combina.
```java
VisualAmbiente.global().registrar("minmod", "minmod:atardecer", /* prioridad */ 0, /* peso */ 1f);
VisualAmbiente.global().fijar("minmod:atardecer", 1f, 0.5f, 0.2f, 0.3f);   // cada fotograma, o cuando cambie
VisualAmbiente.global().quitar("minmod:atardecer");                         // deja de aportar
```
| Política | Resultado |
|---|---|
| `MEDIA_PONDERADA` (la del canal del juego) | Media de los colores ponderada por el peso, en orden canónico: idéntica bit a bit con cualquier orden de carga |
| `PRIORIDAD_ABSOLUTA` | Manda la contribución de menor prioridad |

- Un valor fuera de [0, 1] se recorta (aviso una vez); un color no numérico o un peso menor o igual que 0 se ignora.
- Sin contribuciones activas no se cambia nada. El color combinado se aplica **solo mientras se dibuja el fotograma** y se restaura al terminar: nunca llega a las reglas guardadas del mundo, así que al quitar un mod no queda ningún efecto residual.
- **Solo se nota en mundos con la iluminación activada** (`rules.lighting`).

## Mallas y texturas preparadas fuera del hilo de render (fase 10)
```java
VisualMallas.global().preparar("minmod:malla-nube",
    () -> calcularVertices(),                 // corre en un hilo de fondo: puro, sin GPU ni mundo vivo; no puede devolver null
    vertices -> new Mesh(...).setVertices(vertices));   // corre SOLO en el hilo de render: aquí va la GPU
```
- `preparar` devuelve false si hay demasiados pendientes (256); se puede volver a pedir.
- Cada fotograma el enlace llama a `subirListos(max)`, con el máximo de la calidad: sube **en orden de petición** los trabajos ya terminados. El primero que no está listo detiene la cola.
- `subirListos` desde cualquier hilo que no sea el de render **no sube nada** y avisa: ninguna llamada de GPU se ejecuta fuera de él.
- Un trabajo que falla, que no devuelve datos o una subida que falla se descartan con aviso; las demás mallas siguen.
- La malla de terreno del propio mod (`ChunkGeometryCooker`) usa el mismo reparto con su propia cola.

## Shaders del juego: puntos de sustitución
Los shaders de superficie de Mindustry (agua, barro, brea, escoria, arkycita, criofluido y espacio) se pueden sustituir sin pisarse con otros mods, y elegir por calidad cuál usar. Detalle y reglas de convivencia en `API.md`. Resumen:
- **Cómo funciona el juego:** cada capa de suelo líquido dibuja el terreno en un búfer y lo vuelca a pantalla completa con su shader (`CacheLayer.ShaderLayer.shader`, un campo público). Aquí se cambia ese campo solo durante el dibujo de cada fotograma y se restaura al terminar; sin variantes registradas no se toca nada. No hace falta ningún parche en MX-TEST.
- **Variante:** un shader alternativo para un punto y un rango de calidades. Si dos compiten por el mismo punto y calidad, gana la primera en orden canónico y la otra avisa y no se aplica. Variantes de calidades distintas no compiten.
- **Límite de zoom:** a partir de un ancho de cámara los shaders de superficie no se animan (el suelo líquido se dibuja plano). Varios mods se combinan por media ponderada.

## Probar en un dispositivo, sin mods
En Ajustes -> OpenWorld hay dos casillas apagadas por defecto:
- **Prueba visual: shader de prueba.** Un cuadrado sobre el dron teñido de naranja con un shader que pulsa. Al apagarla desaparece sin afectar a nada.
- **Prueba visual: shader que falla.** Un cuadrado bajo el dron con un shader escrito mal a propósito. Debe salir **igual, sin tinte**, el log debe avisar **una sola vez** (`Visual: el shader '...' falló`) y la partida debe seguir.
- **Prueba visual: sustituir el shader del agua.** El agua se dibuja con una variante de prueba (tinte turquesa, sin la distorsión de siempre). Al apagarla vuelve el agua del juego.
- **Prueba visual: sin shaders de superficie al alejar el zoom.** Al alejar la cámara más allá de un ancho de 600 unidades de mundo, el suelo líquido se dibuja plano.
- **Prueba visual: efecto y luz ambiental.** Cada 1,5 s sale a la izquierda del dron un anillo amarillo que se expande y se apaga en 1 segundo, y desaparece solo. Además aporta un color ambiental naranja, que solo se nota en un mundo con iluminación activada.

## Qué se comprueba (`Corpus comprobar`)
- Mismo orden de dibujo con las 5040 ordenaciones de registro de 7 dibujos en varias capas; la z sale creciente.
- Un shader se registra, se aplica, se desactiva y se reactiva sin afectar a los demás dibujos.
- Dos shaders que escriben el mismo uniforme: aviso y mismo resultado con cualquier orden; uno de mayor prioridad que llega después pasa a ser el dueño.
- Shader que no compila, que falla al aplicarse, dibujo que falla y condición que falla: se desactivan con aviso, no se atribuyen al shader equivocado y el resto sigue.
- Presupuesto: con calidad baja se omite lo opcional de atrás hacia delante (24 ordenaciones); lo esencial no se omite; las pasadas salen en orden mientras quepan.
- Medición automática: ignora la carga, baja, recupera, no pasa de lo elegido, y un tirón suelto no decide.
- Cambiar la calidad no cambia ni un chunk del corpus.
- Efectos: aparecen en la posición y la z declaradas, duran sus 60 ticks y desaparecen devolviendo la instancia al pool; pool de tamaño fijo tras 20000 emisiones; densidad 500, 750 y 1000 de 1000 según la calidad; tope y desalojo por prioridad; presupuesto de coste; un efecto que falla se desactiva sin afectar a los demás.
- Color ambiental: pesos 1 y 3 dan (0.25, 0.75, 0.5, 0.625); idéntico bit a bit con las 24 ordenaciones; prioridad absoluta; recorte, valores no numéricos y peso 0; sin contribuciones no hay cambio ni residuo.
- Mallas: se preparan en un hilo del pool y se suben en el de render, en orden y con el máximo por fotograma; desde otro hilo no se sube nada; los fallos se descartan; la cola tiene tope.
- La simulación y la generación no referencian ninguna clase `Visual*` (se mira el código compilado).

## Qué NO hace todavía
- El registro ordena y aísla las pasadas de post-procesado, pero no trae ayudas para escribirlas (framebuffers, ping-pong).
- No hay API pública para otros mods ni puntos de extensión de interfaz.
- La luz puntual (focos de luz en posiciones) no está: solo el color ambiental. Se añadiría como otro tipo de contribución.
- El color ambiental solo se ve donde el mundo tiene la iluminación activada; la casilla de prueba no la activa por su cuenta.
- Los fallos que solo ocurren al compilar el GLSL se detectan; los errores de GL que no lanzan excepción no.
