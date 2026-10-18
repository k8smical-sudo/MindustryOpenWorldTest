# Lista de comprobación visual (versión inicial)

Se revisa en cada rebase de MX-TEST y en cada cambio de la capa visual (plan, sección 8). Es manual, en dispositivo.

**Procedimiento:** mundo nuevo con semilla `12345`, dimensión indicada, sin mods visuales, calidad media, misma resolución y zoom en cada captura. Guarda las capturas en `docs/visual/` con el nombre de la escena. Tolerancia: sin diferencias apreciables a simple vista en forma, color y orden de capas; un cambio de brillo menor al 5 % se acepta.

| # | Escena | Dónde y cómo | Qué debe verse | Captura |
|---|---|---|---|---|
| 1 | Suelo Serpulo | Origen (0,0), zoom por defecto | Claro inicial, pisos y bordes de muro sin costuras entre chunks | pendiente |
| 2 | Suelo Erekir | Origen (0,0), dimensión Erekir | Pisos de Erekir, muros y oscuridad en los macizos | pendiente |
| 3 | Bloques | Colocar 3 conveyors y 1 torreta junto al origen | Orden de dibujo correcto, sin parpadeo | pendiente |
| 4 | Unidades | Dron del jugador moviéndose sobre el claro | Dibujado sobre los bloques, sin parpadeo | pendiente |
| 5 | Un efecto | Disparar la torreta | Efecto en la posición y capa correctas, desaparece al terminar | pendiente |
| 6 | Un shader | Agua profunda cerca del origen (o el shader de prueba de la fase 9) | Sin artefactos | pendiente |
| 7 | Un post-procesado | Bloom activo con luz cerca de unos bloques | Brillo suave, sin cortes en los bordes de chunk | pendiente |
| 8 | Niebla y oscuridad | Alejarse del claro hacia un macizo | Niebla y oscuridad sin costuras en el borde del anillo | pendiente |
