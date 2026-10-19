# Reparto de áreas (fase 6)

Qué pasa cuando varias fuentes (el mundo base, un mod, un modpack) quieren lo mismo en el mismo sitio. Tres piezas independientes: terreno disputado, distancia mínima global entre fenómenos y procedencia por macrocélula.

**Macrocélula** = 2x2 chunks = 64x64 casillas.

## 1. Terreno disputado (`Reparto`)
Cuando hay más de un candidato de terreno (la tubería del terreno base y/o contribuciones de terreno que no son etapas), **cada macrocélula tiene un solo dueño** y todos sus chunks usan el mismo. Con un solo candidato (el caso de hoy, solo el terreno base) no cambia nada.

- Las macrocélulas se agrupan en bloques de 5x5 (25). En cada bloque cada candidato recibe una **cuota** proporcional a su peso. La parte decimal se reparte al azar con un hash determinista, así que nadie se queda sin nada por tener poco peso.
- Qué macrocélulas concretas le tocan a cada uno lo decide un hash de sus coordenadas: el reparto parece aleatorio, pero la proporción es casi exacta.
- Con pesos 1 y 3, en una región alineada de 10x10 macrocélulas el candidato ligero tiene entre 24 y 28, siempre (límite del plan: menos de 5 puntos de 25).
- Pura función de (semilla, dimensión, macrocélula, candidatos). No depende del orden de carga, del hilo ni del reloj. Solo aritmética básica: igual en cualquier dispositivo.
- Un candidato con peso 0, negativo o NaN no se elige nunca.
- El peso de una contribución es `Contribucion.peso()` (por defecto 1). La tubería del terreno base pesa 1.

Limitación conocida: el dueño cambia en los bordes de macrocélula, así que si dos terrenos son muy distintos se verá un corte. Suavizarlo no entra en esta fase.

## 2. Distancia mínima global entre fenómenos (`Fenomenos.puntosFinales`)
Cada generador de fenómenos sigue respetando **su propia** distancia (disco de Poisson por celdas). Si el mundo fija un valor en `mundo-infinito:fenomenos-distancia-minima` (> 0), además **ningún par de fenómenos de cualquier generador queda a menos de esa distancia**. Con 0 (por defecto) todo funciona exactamente como antes.

- Cuando dos fenómenos de distintas fuentes chocan, gana el de **mejor clave**: `-ln(u) / peso`, con `u` salido de un hash de sus coordenadas y de la semilla. Gana con probabilidad proporcional al peso: con pesos 1 y 3, el segundo gana el 75 %.
- Un generador declara su peso con `GeneradorFenomeno.peso()` (por defecto 1; método nuevo con valor por defecto, no rompe a nadie). Peso 0 o negativo: sus fenómenos pierden siempre los conflictos, pero se colocan donde no estorban.
- El resultado no depende del orden en que se registraron los generadores ni del chunk desde el que se pregunte: la comparación usa clave, luego (prioridad, id) del generador, luego coordenadas.
- Un generador desactivado por un fallo sigue reservando su espacio, para que el resultado de los demás no cambie.
- Efecto secundario aceptado: si A pierde contra B y B pierde contra C, A queda rechazado aunque B no esté. Es conservador; nunca deja un par demasiado cerca.

## 3. Procedencia por macrocélula (`Procedencia`, `Extensiones.procedencia`)
Consulta de diagnóstico: `Extensiones.procedencia(mcx, mcy)` devuelve, para una macrocélula del mundo abierto, qué contribución es dueña del terreno y cuántos fenómenos de cada generador hay dentro (ya con la distancia global aplicada). Recalcula la respuesta; no guarda nada.

## Qué se comprueba (`Corpus comprobar`)
- 36 regiones de 10x10 macrocélulas (4 semillas, 3 dimensiones y 3 orígenes alineados con signos mezclados): pesos 1 y 3 quedan a menos de 5 puntos de 25.
- Tres candidatos con pesos 1, 1 y 2 reparten 25, 25 y 50 con tolerancia 5.
- El dueño de cada macrocélula es el mismo con las 6 ordenaciones de los candidatos.
- Pesos no válidos nunca ganan; un peso 1 contra 100 sigue apareciendo; otra semilla da otro reparto; con solo el terreno base el dueño es la tubería base.
- Tres generadores que chocan en cada celda: sin distancia global se pisan; con distancia 50 queda un fenómeno por celda y el par más cercano de cualquier fuente está a 50 casillas o más.
- Conflictos ponderados con pesos 1, 3 y 1: 20 %, 60 % y 20 % con tolerancia 5.
- Resultado idéntico con las 6 ordenaciones de los generadores.
- La procedencia por macrocélula suma lo mismo que el cálculo directo.
- La distancia global vale 0 por defecto, se recorta a su rango y se resuelve por media ponderada al crear el mundo.

## Qué NO hace todavía
- No hay forma pública de que un mod proponga terreno disputado: `Nucleo.registrar` sigue siendo interno hasta la API pública. El reparto se prueba directamente.
- La "cuota por peso" de los fenómenos se resuelve en cada conflicto (carrera ponderada), no con un cupo fijo por macrocélula. Si se quiere un cupo exacto por macrocélula hay que decidirlo y pasaría por una fase aparte.
- El mínimo garantizado por macrocélula de cada generador (`minimoPorMacro`) se calcula antes del arbitraje global, así que con distancia global activa puede quedar por debajo.
