# API pública v1

Lo que un mod (o un modpack) puede usar de Mundo Infinito sin tocar su interior. **Versión del conjunto: 1.0** (visual 1.0, ajustes 1.0). Todo lo demás de la mod es interno y no es público (lo comprueba el corpus: solo las clases listadas abajo son públicas).

## Qué es público
| Clase | Para qué |
|---|---|
| `ApiVersion` | Versiones de cada parte y comprobación de compatibilidad |
| `ApiVisual` | Dibujos, shaders, pasadas, efectos, color ambiental, mallas y shaders del juego |
| `ApiAjustes` | Parámetros de generación propios, propuestas de valor y lectura |
| `VisualCapa`, `VisualCalidad`, `VisualPrograma`, `VisualEfectoInst` | Tipos que aparecen en `ApiVisual` |
| `OpenWorldAPI`, `GameRule`, `GeneradorTerreno`, `GeneradorFenomeno`, `GeneradorEstructura`, `GeneradorMundo`, `ContextoTerreno`, `ContextoFenomeno`, `ContextoColocacion`, `MundoRegistrado`, `AlmacenModulo`, `PoliticaSueno`, `SiloInterdimensional` | La API anterior (v2): gamerules, generadores de terreno, fenómenos, estructuras, mundos y dimensiones. Sin cambios |

Los métodos de `ApiVisual`, `ApiAjustes` y `ApiVersion` solo usan tipos públicos: no filtran clases internas (también se comprueba).

## Compatibilidad entre versiones
- Cada parte tiene `mayor.menor`. Un cambio incompatible sube el mayor; añadir sin romper sube el menor.
- Un mod es compatible si el mayor es el mismo y el menor es al menos el que pide.
- `ApiVersion.requiereVisual("minmod", 1, 0)` y `requiereAjustes(...)` devuelven false y escriben un aviso claro en el log si no sirve.
- Lo marcado `@Deprecated` se mantiene al menos una versión mayor antes de retirarse.

## Reglas comunes (para que muchos mods convivan)
1. **Ids** con el formato `idmod:nombre`. Un id repetido o inválido devuelve false con aviso; nunca se lanza una excepción.
2. **Orden:** siempre por (capa, prioridad, id de fuente, id). Nunca el orden de carga. Menor prioridad = antes.
3. **Conflictos con ganador definido:** dos shaders que escriben el mismo uniforme, o dos variantes que compiten por el mismo punto y calidad: gana el primero en orden canónico, el otro no se aplica y se avisa. Pasa igual con cualquier orden de carga.
4. **Valores combinables, con política:** el color ambiental, los límites de zoom y los parámetros con varias propuestas se combinan por media ponderada (o prioridad absoluta / elección ponderada en los ajustes), en orden canónico: el resultado es idéntico con cualquier orden de carga.
5. **Lo que falla se desactiva con aviso** y el juego sigue.
6. **GPU solo en el hilo de render.** Se puede registrar desde cualquier hilo.
7. **Lo visual no puede cambiar el mundo.**
8. **Sin residuo:** lo que se aplica al dibujar se restaura al terminar el fotograma; al quitar un mod no queda nada.

## Ejemplo mínimo
```java
public class MiMod extends Mod{
    @Override
    public void init(){
        if(!ApiVersion.requiereVisual("mimod", 1, 0)) return;

        // un efecto: un anillo que se expande
        ApiVisual.registrarEfecto("mimod", "mimod:onda", VisualCapa.SOBRE_BLOQUES, 0, 40f, 1, false,
            e -> { Draw.color(e.r(), e.g(), e.b(), e.a() * e.fout()); Lines.circle(e.x(), e.y(), 4f + 20f * e.fin()); });

        // en cualquier momento: ApiVisual.emitirEfecto("mimod:onda", x, y, 0f, 1f, 0.8f, 0.2f, 1f, null);

        // un parámetro de generación propio, que otros mods pueden ajustar
        ApiAjustes.registrarDecimal("mimod:rugosidad", "Rugosidad", "Qué tan abrupto es el terreno de mi mod", 1.0, 0.0, 3.0, ApiAjustes.Politica.MEDIA_PONDERADA);
        // y leerlo (vale lo guardado en el mundo): double v = ApiAjustes.valor("mimod:rugosidad");
    }
}
```

## Shaders del juego (agua, barro, brea, escoria, arkycita, criofluido, espacio)
**Cómo los dibuja Mindustry (leído del código, no medido):** cada capa de suelo líquido dibuja el terreno en un búfer y lo vuelca a pantalla completa con su shader. Ese volcado cuesta lo mismo con cualquier zoom; lo que crece al alejar la cámara es cuánto suelo se dibuja en el búfer. El shader es un campo público (`CacheLayer.ShaderLayer.shader`), así que **no hace falta ningún parche en MX-TEST**: Mundo Infinito lo cambia solo durante el dibujo de cada fotograma y lo restaura al terminar.

### Variante de un shader por calidad
```java
ApiVisual.registrarVarianteShader("mimod", "mimod:agua-barata", ApiVisual.PuntoShader.AGUA, 0,
    VisualCalidad.BAJA, VisualCalidad.MEDIA,                   // vale en calidad baja y media
    () -> new Shader(ApiVisual.verticeSuperficie(), FRAGMENTO_BARATO),
    null);                                                      // condición por fotograma (null = siempre)
```
- El fragmento recibe el búfer del suelo como `uniform sampler2D u_texture` y `varying vec2 v_texCoords`. Escriba uniformes propios en un `Shader` que sobrescriba `apply()` (el programa ya está enlazado ahí).
- Si dos mods dan variante para el mismo punto y calidad, gana el primero en orden canónico. Una puede dar la barata para baja y otra la bonita para alta sin chocar.
- Si el shader no compila, se descarta con aviso y queda el del juego.

### Límite de zoom
```java
ApiVisual.registrarLimiteZoom("mimod", "mimod:sin-ondas-lejos", 0, /* peso */ 1f, /* ancho de cámara */ 800f, null);
```
Por encima de ese ancho de cámara (unidades de mundo) los shaders de superficie no se animan y el suelo líquido se dibuja plano. Varios mods se combinan por media ponderada según el peso.

### Qué NO se puede hacer todavía
- Cambiar shaders que no son de superficie (niebla, luz, bloques en construcción...). Se añaden como más puntos cuando haga falta.
- Cambiar el código de un shader de Mindustry "a medias": se sustituye entero por una variante.
- Mundo Infinito no trae variantes más baratas de los shaders del juego. Primero hay que medir dónde está el coste al alejar el zoom; después se decide qué simplificar.

## Qué falta en la API
- **Contribuciones de terreno de mods** (la tubería por etapas y las contribuciones por chunk): hoy son internas. Se expondrán con un contrato propio cuando se defina cómo conviven con las etapas del terreno base.
- **Tiempo, calendario y eventos** (fase 11 del plan): nacerán ya como API pública.
- **Puntos de extensión de interfaz** y **manifiesto de modpacks** (fases 12 y 13).
- **Luz puntual** (focos): solo hay color ambiental.
