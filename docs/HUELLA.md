# Huella de los generadores (validación en el juego)

Cada generador de fenómenos declara `radio()`: el alcance máximo de lo que escribe, medido desde su ancla. Los chunks consultan a sus vecinos con ese margen. Si lo que escribe sale de ese radio, el vecino no lo ve y aparece un corte.

**Qué comprueba:** al cargar el juego, para cada ancla del generador, se aplica el generador a las 9 piezas de chunk que la rodean y se cuentan las casillas escritas fuera de `radio()`.

**Dónde corre:** solo dentro del juego (`ValidadorAddons.alCargar`), porque necesita los bloques cargados para construir la paleta. No corre en el corpus.

**Cómo leerlo en el registro:**
- Sin línea de huella: ninguna casilla sale del radio declarado.
- `casillas fuera del radio declarado R (máx. exceso E; ej. ...)`: el generador escribe más lejos de lo que declara. Corregir el `radio()` o acotar la escritura.
- `huella no verificable`: el generador lanzó una excepción en la comprobación.

**Límite:** mide la huella real frente al radio declarado. No corrige nada en tiempo de ejecución.
