# Corpus de regresión del terreno base

`esperado.txt` guarda el hash del terreno base (5 semillas x 3 dimensiones x 29 coordenadas de chunk = 435 chunks) de la generación vigente (`GEN_ACTUAL`, hoy 4). Hash total: `9303ee2d87560fb5`.

El CI lo verifica en cada compilación con el terreno servido por el núcleo (`Corpus verificar corpus/esperado.txt`). Si algo no coincide, el build falla y el log da el chunk exacto.

**Regla:** este archivo solo cambia si cambia el mundo **a propósito**. En ese caso se sube `GEN_ACTUAL` en `MundoInfinitoMod.java` y se vuelve a congelar: el CI lo genera en el artefacto `corpus` cuando el archivo falta o es de otra generación (sustitúyelo y haz commit). Si el CI marca "DISTINTO" sin que lo hayas querido, el cambio rompió el mundo de los jugadores.

El archivo se congela con la ruta directa (`Muestreo`), independiente del núcleo y de las etapas.

No cubre los hooks de la API (fenómenos, mods): necesitan el juego cargado.
