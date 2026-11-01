# Manifiesto de mundo (fase 13)

Cada mundo guarda los mods que lo crearon, con su versión, y la versión de la API. Al reabrirlo:

- **Avisa** de cualquier diferencia (mod que falta, versión distinta, mod nuevo) con el nombre y la versión, antes de cargar.
- **Rechaza** solo si el mundo necesita una API mayor que la instalada. En ese caso no se abre y el mensaje dice qué versión hace falta.
- **No bloquea** por nada más. Los mundos anteriores a este cambio se comparan solo por nombre.

## Qué queda fuera de esta entrega
- La configuración de modpack como ajuste global (D-N9): no existe todavía un mecanismo de configuración de modpack. Cuando exista, su cambio se registrará en el manifiesto.
- El manifiesto no incluye la versión exacta de Mindustry ni de MX-TEST; solo mods y API.
