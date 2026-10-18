# Corpus de regresión del terreno base

`esperado.txt` guarda el hash del terreno base (5 semillas x 3 dimensiones x 29 coordenadas de chunk) de la generación vigente (`GEN_ACTUAL`).

**Primera vez:** el CI no encuentra este archivo, lo genera en el artefacto `corpus` de la ejecución (`esperado.txt`). Cópialo aquí (`corpus/esperado.txt`) y haz commit. Desde entonces el CI verifica contra él.

**Regla:** este archivo solo cambia si cambia el mundo a propósito. Entonces se sube `GEN_ACTUAL` en `MundoInfinitoMod.java` y se vuelve a congelar. Si el CI marca "DISTINTO" sin que lo hayas querido, el cambio rompió el mundo de los jugadores.

Hashes calculados por `mundoinfinito.Corpus` (ver su cabecera). No cubre los hooks de la API (fenómenos, mods), que necesitan el juego cargado.
