# Configuración de modpack y datos del mundo (fase 13)

## Configuración de modpack (ajuste global)
- `ApiModpack.desactivar(id)`, `activar(id)`, `pesoAporte(id, peso)`, `restablecer(id)`: actúan sobre un aporte por su id `fuente:nombre`.
- Es un ajuste global del jugador: se puede cambiar en cualquier momento y se aplica en el siguiente fotograma al canal de iluminación.
- El mundo guarda la configuración con la que se creó. Al reabrir, si cambió, el mod avisa de cada aporte que cambió de estado o de peso.
- Sin configuración, todo funciona como antes.

## Datos del mundo
- `ApiMundo.dimensionActual()`: `ApiMundo.SERPULO` (0), `ApiMundo.EREKIR` (1), o -1 sin mundo abierto o con terreno mezclado.
- `ApiMundo.hayMundoAbierto()`.

## Límites
- La configuración de modpack hoy solo afecta al canal de iluminación. Los demás canales (shaders, efectos, ajustes de generación) se conectarán en la fase de integración de modpacks.
