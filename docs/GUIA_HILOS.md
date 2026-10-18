# Guía del servicio de hilos del núcleo (`Hilos`)

Cierra **D-N2** del plan: MX-TEST no tiene control de hilos y no hace falta añadirlo; el núcleo gestiona su propio pool dentro de la mod (decisión D-N1).

## Qué es
Un pool pequeño de hilos de fondo para **cálculo puro**, con tres carriles por prioridad: `SIMULACION` > `VISUAL` > `PRECARGA`.
Los hilos de generación de terreno (`Streamer`) y de cocinado (`ChunkGeometryCooker`) siguen como están; este pool es el servicio que usarán las contribuciones del núcleo.

## Contrato de una tarea
1. Pura: lee datos que nadie más escribe y devuelve un valor.
2. No toca el mundo vivo, edificios, unidades ni la GPU. Eso solo en el hilo principal (en Mindustry, juego y dibujo son el mismo hilo). Se puede comprobar con `Hilos.exigirPrincipal("sitio")`.
3. No guarda estado compartido mutable entre tareas del mismo lote.

## Uso
```java
Seq<Prov<Float>> tareas = new Seq<>();
for(Contribucion c : ordenCanonico) tareas.add(() -> c.calcular(vcx, vcy));   // el orden de entrega ES el orden canónico
Seq<Float> res = Hilos.ordenado(Hilos.Carril.SIMULACION, "terreno", tareas);  // mismo orden de salida, termine quien termine
```
Asíncrono: `Hilos.Futuro<T> f = Hilos.enviar(Carril.VISUAL, "malla", () -> prepararMalla());` y luego `f.valor()` (no bloquea) o `f.esperar()`.
Un `enviar` pendiente cuando el mundo cambia se descarta solo (`f.descartado()`).

## Ruta de un solo hilo
`Core.settings` clave `ow-hilos`: `0` automático, `1` un solo hilo, `n` n hilos (máx. 8). Para CI: `-Dmow.hilos=1` (tiene prioridad).
Con un núcleo, o con valor 1, `ordenado` ejecuta todo en orden en el hilo que llama. **El resultado de simulación debe ser idéntico** al paralelo: esa es la comprobación de la fase 8.

## Garantías
- Resultado independiente del hilo que acabe antes (se recoge por posición).
- Una tarea que falla devuelve `null` y se avisa una vez por etiqueta; nada se aborta.
- Quien espera ayuda a ejecutar: sin interbloqueos aunque una tarea lance otro lote.
- `ordenado` bloquea al que llama: no usarlo con trabajo pesado en el hilo principal.

## Diagnóstico
`Perfil` escribe una línea `[Perfil] hilos=... | sim 12/12 cola=0(máx 3) calc=0.4ms espera=0.1ms` cada ~15 s si el servicio se usó.
