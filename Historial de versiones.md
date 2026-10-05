# Historial de versiones

Registro técnico de lo planificado, decidido y hecho en cada versión de easyWp. Complementa a
`CLAUDE.md`: ahí está cómo funciona el mod hoy; aquí, qué se va a cambiar, por qué y en qué estado
está. Al empezar una conversación nueva sobre una versión, leer primero este archivo.

**Convenciones**
- Cada punto tiene un ID estable (`B1`, `O3`, `U2`, `F7`...) para referirse a él sin ambigüedad.
- Estado de cada punto: `PENDIENTE`, `EN PROGRESO` o `HECHO`. Al marcar `HECHO`, anotar la versión
  (alpha/beta) en la que entró.
- Las referencias `archivo:línea` son de la 1.3.4 y se desplazan con los cambios; buscar por el
  nombre del método si no coinciden.
- Todo archivo de cliente se toca en las **tres** versiones (`versions/26.1`, `26.2`, `26.3`):
  `checkSharedSources` falla si no son idénticos.
- Todo texto nuevo va en los **8** archivos de idioma (`en_us` + los 7 `es_*`, idénticos a
  `es_es`): `checkLangParity` falla si falta una clave.

---

## Versión 1.4.0

**Estado general:** EN PROGRESO. Planificación cerrada el 2026-10-02. `alpha.1` implementada,
probada en el juego y confirmada el 2026-10-02 (commit `v1.4.0-alpha.1` en `release/1.4.0`).
`alpha.2` implementada el 2026-10-02, probada y confirmada el 2026-10-05 (commit
`v1.4.0-alpha.2`). `alpha.3` implementada, probada y cerrada el 2026-10-05 (commit
`v1.4.0-alpha.3`); su último ajuste ("Guardar y duplicar" también al crear, ver F6) entró en ese
commit **sin probar en el juego**: probarlo al principio de la alpha 4. Sigue pendiente confirmar
el plan de publicación (ver "Publicación"). `alpha.4` (F7 y F8) implementada, probada y
confirmada el 2026-10-05 (commit `v1.4.0-alpha.4`), junto con el ajuste de F6 de la alpha 3.

**Siguiente paso:** `alpha.5` — F9 (menú de compartir), F10 (portapapeles) y F11 (`[+]` en el
chat). Es la primera que se publica (Modrinth y CurseForge, canal Alpha).

**Versión en `gradle.properties`:** `mod_version=1.4.0-alpha.4` (la 1.3.5 nunca se publicará). Se
cambia en cada entrega (`1.4.0-alpha.2`, ..., `1.4.0-beta.1`, ..., `1.4.0`). Los jars salen como
`easywp-1.4.0-alpha.1+26.1.x.jar`.

**Git:** todo el trabajo va en la rama `release/1.4.0`, con un commit por cada alpha/beta. Se
fusiona a `main` con la versión final.

### Forma de trabajo por alpha

Acordada con el usuario al empezar la 1.4.0; se sigue en cada alpha (también en una conversación
nueva).

1. Rama `release/1.4.0`. Al empezar la alpha N, `mod_version=1.4.0-alpha.N` en `gradle.properties`.
2. Implementar **solo** los puntos de esa alpha. No reabrir decisiones ya tomadas; si el plan choca
   con el código real, parar y preguntar antes de improvisar. Lo que el plan no concrete se decide,
   se anota aquí como "decisión nueva" y se avisa en el resumen.
3. Todo cambio de cliente, idéntico en 26.1, 26.2 y 26.3 (se edita en 26.1 y se copia). Todo texto
   nuevo, en los 8 archivos de idioma.
4. Al arreglar un bug, buscar el mismo patrón en todo el código: si es el mismo tipo de bug,
   arreglarlo; si no, anotarlo aquí.
5. `./gradlew build` (compila las tres versiones y corre `checkSharedSources`, `checkLangParity` y
   `:common:test`) y dar el resultado real. La lógica pura nueva va en `common/` con tests, y cada
   test se comprueba contra una rotura deliberada del código.
6. Actualizar este historial (estado de cada punto, decisiones nuevas, "Pruebas de regresión") y
   `CLAUDE.md` con lo que cambió en el código.
7. **No lanzar el juego** (`runClient`): las pruebas en el juego las hace el usuario.
8. Entregar la guía de pruebas y **parar**. Sin commit ni siguiente alpha hasta que el usuario
   confirme. Si reporta fallos, se corrigen dentro de la misma alpha y se da solo la guía de lo
   afectado.
9. Al confirmar: commit `v1.4.0-alpha.N: <resumen>` en `release/1.4.0` (con la línea
   `Co-Authored-By`) y pasar a la siguiente. Sin push ni publicación salvo que el usuario lo pida.

**Formato de la guía de pruebas.** Por cada ID: qué cambió (una línea), cómo prepararlo, pasos
exactos, resultado esperado concreto, casos límite que apliquen (lista vacía y con más de 6,
nombres con espacios/acentos/símbolos, teclas reasignadas, Overworld/Nether/End, un jugador o
servidor, con y sin Xaero, config de 1.3.x) y qué podría haberse roto. **Organizada por versión**:
26.1 completa; para 26.2 y 26.3 poner "(igual que 26.1)" y avisar solo de lo que varía (26.2 tiene
Xaero en `run/mods` y renombra cambio de pantalla y HUD; 26.3 usa SDL para el teclado). Al final,
la prueba de compatibilidad con 1.3.4 y la regresión acumulada.

### Reglas que aplican a toda la versión

1. **Las optimizaciones no cambian la experiencia del jugador.** Ni en esta versión ni en las
   siguientes. Si un cambio "de rendimiento" altera algo visible o el comportamiento de los
   waypoints, no es una optimización: o se descarta, o se ofrece como opción cuyo valor por defecto
   conserva el comportamiento actual.
2. **Formato de guardado compatible hacia atrás.** `waypoints_<worldId>.json` sigue siendo un array
   JSON en la raíz, para que volver a una 1.3.x no rompa la carga. Los campos nuevos de un waypoint
   (`forceVisible`, `favorite`, `groups`) son opcionales (pueden faltar), y Gson los ignora en
   versiones viejas. Para cada campo nuevo está decidido qué hace `WaypointStore.mergeFrom` (import
   entre mundos) con él.
3. **Regla de Gson en `config.json`** (ver Persistence en `CLAUDE.md`): un campo nuevo dentro de un
   grupo existente se lee como `false`/`0`/`null` en un `config.json` viejo. Si su valor por
   defecto no es ese, va en un grupo nuevo o se nombra al revés para que `false` sea el default.
4. **Cambiar el default de un campo que ya existe** solo afecta a configs nuevos: los
   `config.json` existentes guardan el valor escrito y lo conservan. No hay migración.
5. **Tests:** toda lógica de texto pura nueva (formato de compartir, coordenadas relativas) va en
   `common/` con tests en `:common:test`, comprobados contra roturas deliberadas, igual que
   `XaeroWaypointFormatTest`.
6. **`CLAUDE.md` se actualiza** al cerrar cada etapa con lo que cambió de verdad en el código.

### Plan de entregas

Se avanza de lo más sencillo a lo más complejo. Las alphas añaden cosas; las betas solo prueban y
corrigen.

| Entrega | Contenido | Depende de | Publicación (propuesta) |
|---|---|---|---|
| `1.4.0-alpha.1` | Bugs B1–B7 y optimizaciones O1–O5 | — | Interna |
| `1.4.0-alpha.2` | Cambios de UX U1–U4, distancia en km (F1), coordenadas `~` (F2), límite de waypoints visibles (F3) | — | Interna |
| `1.4.0-alpha.3` | Página "Más opciones" (U5), deshacer al borrar (F4), favoritos (F5), duplicar en la otra dimensión (F6) | U1 | Interna |
| `1.4.0-alpha.4` | Formato de compartir nuevo (F7) y comandos `/easywp` (F8) | F2 | Interna |
| `1.4.0-alpha.5` | Menú de compartir: público / privado / copiar (F9), portapapeles (F10), `[+]` en el chat (F11) | F7, F8 | **Modrinth y CurseForge, canal Alpha** |
| `1.4.0-alpha.6` | Grupos (F12) | U5 | Interna |
| `1.4.0-alpha.7` | Mensaje de bienvenida con teclas y novedades (F13) | Todo lo anterior (resume las novedades finales) | Interna |
| `1.4.0-beta.1` | Congelado de funciones. Pruebas en 26.1, 26.2 y 26.3 con `runClient`, correcciones, revisión de los 8 idiomas, `CLAUDE.md` completo | Todas las alphas | **Modrinth y CurseForge, canal Beta** |
| `1.4.0-beta.2` | Solo si la beta 1 deja correcciones pendientes | beta.1 | **Canal Beta** (si existe) |
| `1.4.0` | Versión final | — | **Canal Release** |

### Publicación

**Propuesta pendiente de confirmar.** Publicar solo tres (o cuatro) entregas, siempre para las tres
versiones de Minecraft:

- **`alpha.5` en el canal Alpha:** es el primer punto en el que hace falta gente de fuera para
  probar. Todo lo anterior (bugs, UX, deshacer, favoritos, comandos) se puede probar en solitario,
  pero compartir por chat, por privado y el `[+]` necesitan a otro jugador con el mod.
- **`beta.1` en el canal Beta:** con todas las funciones dentro, incluidos los grupos y el mensaje
  de bienvenida, para encontrar fallos antes de la final.
- **`beta.2` en el canal Beta**, solo si existe.
- **`1.4.0` en el canal Release.**

Las demás alphas quedan internas: cambian cosas que la siguiente entrega vuelve a tocar (el formato
de compartir de la alpha 4 sin el `[+]` de la 5, la página "Más opciones" de la 3 que la 6 amplía),
y publicarlas haría que los jugadores prueben estados a medias. Publicar una alpha es seguro para
los datos gracias a la regla 2.

---

### Bugs (alpha.1)

**B1 — Escribir "j" en el buscador de la lista la cierra** · HECHO en alpha.1
- Problema: `keyPressed` comprueba la tecla de la lista (J por defecto) antes de dejar que el campo
  de búsqueda reciba la tecla. No se puede buscar "Jungla".
- Decisión: ignorar la tecla de la lista mientras el buscador (o cualquier `EditBox`) tenga el foco.
- Archivo: `WaypointListScreen.keyPressed` (`WaypointListScreen.java:787`).
- Hecho: la tecla de la lista solo cierra si `getFocused()` no es un `EditBox`.
- Comportamiento a saber (de vanilla, no cambiado): hacer clic en un hueco vacío **no** quita el
  foco del buscador (vanilla solo mueve el foco al widget que se pulsa). Con el buscador enfocado,
  para cerrar hay que usar Esc o pulsar antes cualquier botón de la lista. Al abrir la lista con J
  el buscador no queda enfocado (vanilla solo da foco inicial si la última entrada fue Tab o una
  flecha), así que J-J sigue abriendo y cerrando como antes.
- Mismo patrón, revisado: las demás pantallas con `EditBox` (`WaypointCreateScreen`) solo
  interceptan Enter, que un `EditBox` no usa; es el "Enter para guardar" previsto. Ninguna otra
  pantalla tiene atajos. Nada más que arreglar.

**B2 — Al borrar en la última página se pierde una fila** · HECHO en alpha.1
- Problema: `scrollIndex` no se ajusta al nuevo total. Con 7 waypoints, bajando y borrando uno, el
  primero queda oculto y no se puede volver a subir porque `mouseScrolled` exige más de 6.
- Decisión: limitar `scrollIndex` a `max(0, total - ITEMS_PER_PAGE)` en `refreshItemWidgets`.
- Archivo: `WaypointListScreen.java:383`.
- Hecho tal cual. Cubre también los demás casos en que la lista encoge con la pantalla abierta
  (importar de Xaero que borra filas, volver del `ConfirmScreen` de borrar, redimensionar).
- Mismo patrón (estado guardado por índice de fila que no se ajusta cuando la lista cambia),
  arreglado:
  - El aviso "¡Copiado!" tras el doble clic se guardaba por índice: borrar otra fila dentro de ese
    segundo lo movía a la fila equivocada. Ahora sigue al waypoint (`copiedFeedbackWaypoint`).
  - Un doble clic a medias también se recordaba por índice; ahora se olvida al reconstruir las
    filas.
  - `ImportWorldScreen` ya limitaba su `scrollIndex` en `init()`: sin cambios.

**B3 — El icono del ojo no refleja la realidad con un waypoint enfocado** · HECHO en alpha.1
- Problema: si se oculta el waypoint enfocado, el renderer deja de dibujarlo pero el icono sigue
  en "visible".
- Decisión: el icono usa la misma regla que el renderer:
  `visible && (!hayFoco || enfocado || forceVisible)`.
- Archivo: `WaypointListScreen.java:496`.
- Hecho: la regla vive una sola vez en `Waypoint.isShown(hayFoco)`; el renderer, el icono y el
  atenuado de filas la usan. `hayFoco` sale de `WaypointStore.hasAnyFocused()`.
- **Decisión nueva (mismo tipo de bug):** la lista calculaba `hayFoco` solo entre las filas que
  mostraba (pestaña actual + búsqueda), mientras que el renderer lo calcula sobre **todos** los
  waypoints del mundo. Con el foco en otra dimensión, o filtrado por la búsqueda, la lista mostraba
  como visibles filas que el mundo estaba ocultando, y el clic en el ojo cambiaba `visible` (sin
  efecto visible) en vez de `forceVisible`. Ahora la lista usa el mismo `hayFoco` global que el
  renderer, así que en esos casos las filas salen atenuadas y el ojo alterna `forceVisible`. El
  renderer no cambia.

**B4 — Fuga de memoria al salir al menú** · HECHO en alpha.1
- Problema: `WaypointStore.lastCheckedLevel` mantiene vivo el `ClientLevel` anterior (chunks,
  entidades) hasta entrar a otro mundo.
- Decisión: al salir de un mundo se suelta `lastCheckedLevel`, se vacía la lista en memoria y se
  reinicia `lastWorldId`. Al volver a entrar se recarga desde disco (`readWaypointData` ya hace
  `flush()` antes de leer, así que no se pierde ningún guardado pendiente).
- Archivo: `WaypointStore.java:41`. El enganche puede ser `ClientPlayConnectionEvents.DISCONNECT`
  o la rama "sin jugador" de un tick.
- Hecho: `WaypointStore.forgetWorld()`, llamado desde un `END_CLIENT_TICK` propio en
  `EasyWpClient` mientras `client.level == null` (sale al instante si no queda nada que soltar).
  Se eligió el tick y no `DISCONNECT` porque el tick corre seguro en el hilo del cliente, el único
  que puede tocar la lista.
- Comprobado en el código de vanilla 26.1: un cambio de dimensión cambia el nivel directamente
  (`handleRespawn` → `setLevel`), sin pasar por `null`, así que no dispara esto. Sí lo dispara una
  reconfiguración del servidor (`clearClientLevel`, p. ej. un proxy que mueve al jugador a otro
  servidor): la lista se recarga del disco y, hasta U3, se pierde el `forceVisible` en memoria.
  Aceptado: U3 (alpha.2) lo guarda en disco.
- Consecuencia: volver a entrar al **mismo** mundo ahora relee el archivo (antes reutilizaba la
  lista en memoria). Mismo contenido, porque cada cambio se guarda al momento.
- Mismo patrón, revisado: `DeathWaypointManager` ya suelta su `lastPlayer` sin jugador; el pool de
  `WaypointRenderer` y la lista auxiliar de muertes guardan solo unos pocos `Waypoint` (sin nivel
  ni chunks). No es el mismo bug; no se toca.

**B5 — Importar desde el menú principal mete los waypoints en el mundo equivocado** · HECHO en alpha.1
- Problema: desde Mod Menu en la pantalla de título, `mergeFrom` modifica la lista en memoria del
  último mundo, `saveToFile` no guarda (mundo `unknown`) y aun así dice "N importados".
- Decisión: el botón "Importar" de `ModConfigScreen` se desactiva fuera de un mundo, con un tooltip
  que explica que hay que estar dentro de un mundo (clave de idioma nueva). Además,
  `ImportConfirmScreen` rechaza la importación si no hay mundo, como segunda barrera. B4 ya evita
  que la lista vieja siga en memoria.
- Archivos: `ModConfigScreen.addOptions`, `ImportConfirmScreen.java:64`.
- Hecho: clave nueva `easywp.config.import_needs_world` en los 8 idiomas. Helper
  `WaypointStore.isInWorld()`.
- **Decisión nueva:** la segunda barrera es doble: el botón "Importar" de `ImportConfirmScreen`
  también sale desactivado con el mismo tooltip, y su acción vuelve a comprobar el mundo antes de
  fusionar (si no hay mundo, no importa ni muestra mensaje; vuelve al selector).
- Mismo patrón, revisado: ninguna otra acción que modifique waypoints se alcanza sin mundo (la
  lista, crear y Xaero exigen jugador). Nada más que arreglar.

**B6 — `createdAtMillis` siempre es 0 salvo en los waypoints de muerte** · HECHO en alpha.1
- Problema: los creados a mano, con Fast Ping o importados de Xaero usan constructores que dejan la
  fecha en 0.
- Decisión: guardar `System.currentTimeMillis()` al crear. Solo se guarda (no se muestra); lo usa
  el orden "Creado" (U4). Los waypoints existentes se quedan en 0: no hay forma de recuperar su
  fecha.
- Archivos: `WaypointCreateScreen.java:422`, `ModKeyBindings.java:124`, `XaeroSync.java:114`.
- **Decisión nueva (de implementación):** en vez de tocar las tres llamadas, los constructores
  cortos de `Waypoint` (todos terminan en el de 7 argumentos) ponen la hora actual. Solo se usan
  para crear waypoints nuevos (las tres rutas de arriba); cargar desde disco e importar de otro
  mundo usan el constructor completo y conservan la fecha original. Así una ruta de creación nueva
  no puede olvidarlo.

**B7 — El guardado en segundo plano puede congelar el juego** · HECHO en alpha.1
- Problema: si el hilo de guardado lanza un `Error` (no una `RuntimeException`), `draining` queda en
  `true` y `flush()` espera para siempre al entrar a un mundo o al cerrar el juego.
- Decisión: `try/finally` en `drain()` para que siempre se reinicie `draining` y se haga
  `notifyAll()`. Añadir un caso a `AsyncJsonWriterTest` con un sink que lanza un `Error`, y
  comprobar que el test falla sin el arreglo.
- Archivo: `common/src/main/java/com/easywp/AsyncJsonWriter.java:111`.
- Hecho, con un matiz: la salida normal (cola vacía) sigue poniendo `draining = false` dentro del
  mismo bloque `synchronized` que ve la cola vacía; el `finally` solo actúa si se sale por un
  `Error`. Ponerlo todo en el `finally` abriría una carrera: un `write` que llegase entre medias
  vería `draining` aún a `true`, no programaría nada y su documento quedaría sin escribir.
- Mismo patrón, arreglado: si `executor.execute` lanza en `write()` (tarea rechazada, o sin hilo
  disponible), `draining` también quedaba en `true` para siempre. Ahora se reinicia y la excepción
  sigue su curso como antes.
- Aceptado: tras un `Error`, lo que quedase en cola se escribe con el siguiente guardado (como un
  fallo normal); `flush()` vuelve sin esperarlo, que es lo que evita el cuelgue.
- Tests nuevos: `anErrorFromTheSinkDoesNotLeaveFlushWaitingForever` y
  `aDrainThatCannotBeScheduledDoesNotLeaveFlushWaitingForever`. Comprobados contra dos roturas
  deliberadas (quitar el reinicio del `finally`; quitar el reinicio tras el `execute` rechazado):
  cada una hace fallar su test. La carrera del matiz anterior no tiene test (necesitaría controlar
  el entrelazado de hilos).

### Optimizaciones (alpha.1)

Todas sin cambio visible para el jugador (regla 1).

**O1 — El selector de importación relee los archivos en cada scroll** · HECHO en alpha.1
- `countWaypointsIn` hace `flush()`, lee y parsea con Gson cada archivo visible en cada paso de
  scroll. Contar una vez por pantalla y guardar el resultado por archivo.
- Archivo: `ImportWorldScreen.java:82`.
- Hecho: `Map<Path, Integer>` en la pantalla, rellenado la primera vez que se muestra cada fila
  (no cuenta los que nunca se ven). Dura lo que dura la pantalla, incluso al volver de
  `ImportConfirmScreen`. Mismo resultado: el único archivo que escribe una importación es el del
  mundo actual, que nunca está en la lista.

**O2 — Importar de Xaero compara N×M** · HECHO en alpha.1
- `alreadyHasSimilar` recorre todos los waypoints de easyWp por cada uno de Xaero y crea listas en
  cada llamada. Calcular antes del bucle un `HashSet` con las identidades (incluidas las
  proyecciones 1:8) y añadir a él lo que se va importando. Resultado idéntico.
- Archivo: `XaeroSync.java:364`.
- Hecho: `XaeroSync.existingIdentities()` sustituye a `alreadyHasSimilar`. La clave
  (`dimensión|x|z|nombre`) no puede colisionar entre identidades distintas: una dimensión no puede
  contener `|` y el nombre va al final. Los waypoints sin nombre se excluyen, como antes.
- Visto y no tocado (no es este punto): `waypoints.removeAll(doomed)` en el mismo `importNow` es
  también N×M, pero solo con los waypoints que se borran.

**O3 — View bob por reflexión** · HECHO en alpha.1
- Cambiar `Method.invoke` por `MethodHandle` en `WaypointRenderer.applyBob`. Misma matriz, mismo
  fallo controlado si no se encuentra el método.
- Hecho: búsqueda igual que antes (`getDeclaredMethod` + `setAccessible`) y `unreflect` a
  `MethodHandle`, llamado con `invokeExact`. Comprobado con `javap` que la firma es
  `private void (CameraRenderState, PoseStack)` en los jars de 26.1.2, 26.2 y 26.3. Se captura
  `Throwable`, que es lo que `Method.invoke` capturaba en la práctica (envolvía cualquier cosa del
  método, `Error` incluido, en `InvocationTargetException`).

**O4 — Nombre por defecto recalculado en cada frame** · HECHO en alpha.1
- `WaypointCreateScreen.extractRenderState` llama a `resolveWaypointName()`, que traduce el nombre
  por defecto en cada frame. Calcularlo una vez en `init()`. Coordinar con U2, que cambia cómo se
  calcula ese nombre.
- Hecho: campo `defaultName`, calculado en `init()`, que leen la sugerencia gris, la vista previa y
  el guardado. U2 solo tendrá que cambiar esa línea de `init()`.
- Única diferencia posible: si la lista de waypoints cambia con la pantalla abierta (p. ej. una
  muerte que se borra sola al llegar), antes la vista previa y el guardado usaban el número nuevo
  mientras la sugerencia mostraba el viejo; ahora los tres usan el de la sugerencia, que es lo que
  el jugador ve escrito.

**O5 — `ModernButton` evalúa su texto en cada frame** · HECHO en alpha.1
- `getMessage().getString()` se resuelve en cada frame por botón solo para saber si está vacío.
  Guardarlo en caché, actualizándolo si cambia el mensaje (`setMessage`).
- Hecho: campo `hasLabel`, fijado en el constructor (vanilla asigna el mensaje en el constructor
  de `AbstractWidget` sin pasar por `setMessage`) y en un `setMessage` sobrescrito.

### Hallazgos de la alpha.1 sin arreglar (otro tipo de problema)

- La cabecera de la lista (`DimensionNames.title(...) + " (" + total + ")"`) se construye en cada
  frame. Coste pequeño; candidato a optimización futura, no planificado.
- Con el buscador enfocado, hacer clic en un hueco no le quita el foco (comportamiento de vanilla,
  ver B1). Si molesta en las pruebas, se puede plantear como cambio de UX.

---

### Cambios de UX

**U1 — Volver a la lista tras editar o crear desde ella** · HECHO en alpha.2
- Hoy: guardar una edición cierra todo y devuelve al juego.
- Decisión: si la pantalla de crear/editar se abrió desde la lista (botón editar o botón "Nuevo"),
  guardar, cancelar y su propio botón de lista vuelven a **esa misma** lista, que se refresca al
  volver. Abierta con las teclas N/H, guardar y cancelar siguen cerrando al juego.
- Esto evita que lista → editar → lista → editar se vaya apilando (hoy el botón de lista de la
  pantalla de crear abre una lista nueva con la pantalla de crear como padre).
- Archivos: `WaypointCreateScreen` (pantalla de retorno opcional), `WaypointListScreen`.
- Hecho: campo `returnScreen` en `WaypointCreateScreen`, que la lista pasa desde editar y Nuevo.
- **Decisión nueva:** Esc también vuelve a la lista (es un "cancelar"); se hace sobrescribiendo
  `onClose()`, por el que pasan guardar, cancelar, Enter y Esc. El botón de lista, abierta desde
  la lista, vuelve a ella **sin guardar**, igual que cancelar. Abierta con N/H no cambia nada: su
  botón de lista sigue abriendo una lista cuyo "Volver" regresa al formulario (eso no se apila).

**U2 — Nombre por defecto con el número libre más bajo, por dimensión** · HECHO en alpha.2
- Hoy: `WAYPOINT #N` con N = total de waypoints + 1, que repite nombres tras borrar.
- Decisión: N es el número más bajo cuyo nombre traducido exacto (`create.default_name`) no use
  ningún waypoint **de la dimensión actual**. Se prueba N = 1, 2, … contra el nombre traducido, en
  vez de reutilizar la regex de Fast Ping, porque `WAYPOINT #N` y `Ping #N` comparten el patrón
  `#N` y la regex contaría también los pings. Fast Ping no cambia (sigue siendo global).
- Archivo: `WaypointCreateScreen` (y O4).
- Hecho: `WaypointCreateScreen.nextDefaultName`, calculado una vez en `init()` (O4). La búsqueda
  del número libre es `NameNumbering.lowestUnused` en `common/`, con `NameNumberingTest`
  (comprobado contra dos roturas deliberadas: empezar en 0 y "cantidad + 1").
- **Decisión nueva (interpretación):** "waypoints de la dimensión actual" = los que **se ven** en
  ella. Desde U6 (alpha.3) eso son solo los suyos; en alpha.2 incluía también los compartidos
  proyectados desde la otra mitad del par. La dimensión es la del jugador, que es donde se guarda
  el waypoint nuevo.

**U3 — Guardar `forceVisible`** · HECHO en alpha.2
- Hoy: el foco sobrevive a un reinicio, pero los waypoints que lo acompañan (`forceVisible`) no.
- Decisión: campo opcional nuevo en `WaypointData`. Al quitar el foco se siguen limpiando todos,
  como ahora. `mergeFrom` lo limpia en la copia, igual que `focused`.
- Archivos: `WaypointStore.WaypointData`, `WaypointStore.mergeFrom`.
- Hecho: `Boolean forceVisible` en `WaypointData` (ausente = `false`). `mergeFrom` crea la copia
  sin él.
- Ajuste del plan: "al quitar el foco se siguen limpiando todos, como ahora" no era exacto; el
  código solo limpiaba el del waypoint que estaba enfocado, y los demás esperaban al siguiente
  foco (sin efecto visible, porque sin foco `forceVisible` no cuenta). Ahora quitar el foco limpia
  **todos**, para no guardar marcas huérfanas. Mismo resultado en pantalla.
- Mismo patrón, arreglado: borrar el waypoint enfocado (desde la lista, al llegar a una muerte o al
  importar de Xaero) también dejaba marcas sin foco. En vez de tocar cada ruta, al guardar se
  escribe `forceVisible = false` si no hay ningún waypoint enfocado.

**U4 — El orden "Creado" usa la fecha de creación** · HECHO en alpha.2
- Decisión: con B6 hecho, el orden `CREATED` ordena por `createdAtMillis` con un orden estable: los
  que tienen 0 (anteriores a 1.4) conservan entre sí su orden de inserción y quedan primero. Un
  waypoint importado de otro mundo se coloca según su fecha original.
- Archivos: `WaypointListScreen.sortEntries`, `WaypointSortMode` (javadoc de `CREATED`).
- Hecho tal cual: `List.sort` con `comparingLong(createdAtMillis)`, que es estable.

**U5 — Página "Más opciones" en la pantalla de crear/editar** · HECHO en alpha.3
- La pantalla de crear ya está llena (224px de alto). En vez de hacerla más alta, se añade una
  segunda página, "Más opciones", a la que se llega con un botón desde la página principal.
- Contiene: favorito (F5), grupos (F12, a partir de la alpha 6) y, solo al editar, duplicar en la
  otra dimensión (F6).
- Guardar desde cualquiera de las dos páginas guarda todo; cambiar de página no pierde lo escrito
  en la otra.
- Archivo: `WaypointCreateScreen`.
- Hecho: botón `⋯` en la fila de abajo, entre Guardar (ahora de 158px) y el botón de lista; en
  "Más opciones" el mismo botón muestra `‹` y vuelve. Cancelar, Guardar y lista son iguales en las
  dos páginas. La página muestra arriba el nombre del waypoint tal como se guardaría.
- **Decisiones nuevas:**
  - Esc en "Más opciones" vuelve a la página principal (como Esc en una subpantalla de opciones de
    vanilla); en la principal sigue cerrando. Enter guarda desde las dos.
  - Al crear un waypoint nuevo, "Más opciones" solo tenía el favorito y un texto que decía que
    duplicar estaba disponible al editar. **Cambiado el 2026-10-05 a petición:** ahora también
    ofrece "Guardar y duplicar" al crear (ver F6).
- Mismo patrón, arreglado: los campos de texto se reconstruían en cada `init()`, así que también
  se perdía lo escrito al **redimensionar la ventana** y al **volver de la lista** abierta con el
  botón de lista desde N/H. Ahora los cuatro campos se crean una sola vez y solo se recolocan, como
  el buscador de la lista. La familia de color activa tampoco se recalcula en cada `init()`.

**U6 — Quitar los waypoints compartidos entre Overworld y Nether** · HECHO en alpha.3
- Añadido el 2026-10-05 a petición, fuera del plan original: la función "compartir coordenadas"
  (un waypoint que también se dibujaba, convertido 1:8, en la otra mitad del par) se elimina y la
  sustituye "Guardar y duplicar" (F6).
- Se quitan: el interruptor del portal del formulario (el campo de nombre ocupa ahora su ancho),
  el botón del portal de cada fila de la lista, las filas `[OW]`/`[N]` en amarillo, la proyección
  compartida al exportar e importar con Xaero, `Waypoint.shared`, `forEachPosition`,
  `DimensionNames.isConvertible`, `Icons.drawPortalIcon` y las texturas `portal_fill.png` y
  `portal_frame.png`.
- **Decisión (tomada al implementarlo, para revisar):** los waypoints compartidos que ya existen
  (de 1.3.x o de alpha.1/alpha.2) se **convierten al cargar** en dos waypoints independientes: el
  original y una copia en la otra dimensión, justo donde se dibujaba su espejo, con el mismo nombre,
  color, visibilidad, favorito y fecha. La copia nunca está enfocada, pero se mantiene visible junto
  al foco del original, como el espejo; tampoco es de muerte ni Fast Ping. No se crea si ya hay un
  waypoint con ese nombre en ese mismo punto, ni si el compartido no estaba en Overworld/Nether.
  - Motivo: el jugador sigue viendo lo mismo, y una copia que un "Exportar a Xaero" anterior ya
    había escrito en la otra dimensión sigue coincidiendo (mismo nombre, X y Z), así que el
    siguiente Exportar **no la borra de Xaero**. Quitar solo la marca la habría hecho desaparecer.
  - El archivo del mundo actual se reescribe al momento (el `.bak` guarda la versión anterior) y
    se anota una línea INFO en el log. Los archivos de otros mundos, al importarlos, se convierten
    solo en memoria; el conteo del selector cuenta ya con las copias.
  - Formato (regla 2): `shared` se lee pero ya no se escribe; las versiones 1.3.x leen la clave
    ausente como `false`, así que al volver a 1.3.x se ven las dos copias, cada una en su dimensión.

**U7 — Botón "Más opciones" en cada fila de la lista** · HECHO en alpha.3
- Añadido el 2026-10-05 a petición: en el hueco del botón del portal (segundo por la derecha), un
  botón `⋯`.
- **Cambiado el 2026-10-05, a petición, tras la primera versión:** al principio abría el formulario
  de edición directamente en su página "Más opciones". Ahora abre un **minimenú junto a la fila, sin
  salir de la lista**: "Favorito: Sí/No" y "Duplicar en el Nether/Overworld". La página "Más
  opciones" del formulario sigue existiendo (desde su propio `⋯`).
- Detalles (decididos al implementarlo):
  - El menú va justo a la derecha del modal, alineado con la fila; si la ventana es estrecha se
    mete dentro de la pantalla y tapa parte de los botones de la fila.
  - Se cierra con Esc (un segundo Esc ya cierra la lista), con un clic fuera (ese clic no hace nada
    más, para que no caiga en el botón de borrar de debajo), al hacer scroll o si su fila sale de la
    página.
  - "Favorito" deja el menú abierto y lo mueve con la fila (que sube al principio). "Duplicar"
    cierra el menú y muestra el mensaje en el HUD.
  - Se dibuja a mano por encima de todo, no con widgets: así queda sobre el texto de las filas y
    los clics no llegan a los botones de debajo.

### Funciones nuevas

**F1 — Distancia en km** · HECHO en alpha.2
- A partir de 1000 bloques la etiqueta muestra `1.2km` en vez de `1234m`, con un decimal y
  `Locale.ROOT` (punto decimal en cualquier idioma).
- Configurable, **activada por defecto**. Por la regla 3, el campo se nombra para que `false` sea
  el default (p. ej. `labelDisplay.metersOnly = false`) o va en un grupo nuevo.
- La caché de etiqueta (`Waypoint.labelCacheMatches`) pasa a invalidarse por el texto mostrado, así
  que en km se recalcula cada 100 bloques en vez de cada bloque.
- También se usa en la salida de `/easywp list` (F8).
- Archivos: `WaypointRenderer.labelText`, `ModConfig`, `ModConfigScreen` (fila en Apariencia).
- Hecho: `DistanceFormat` en `common/` (`format` y `cacheKey`), con `DistanceFormatTest`
  (comprobado contra cuatro roturas deliberadas). Campo `labelDisplay.metersOnly = false`. Fila
  "Distancia en km" (encendido = km), justo debajo de "Mostrar distancia", con tooltip.
- **Decisión nueva:** el decimal se **trunca**, no se redondea (`1999` → `1.9km`), igual que los
  metros, que ya se truncaban (`999.9` → `999m`). El texto se construye a mano, sin
  `String.format`, así que el punto decimal no depende del idioma ni de `Locale`.
- Claves nuevas: `config.distance_km`, `config.distance_km_tooltip`.

**F2 — Coordenadas relativas `~`** · HECHO en alpha.2
- En los campos X/Y/Z de la pantalla de crear: `~` es la posición del jugador en ese eje, y `~5`
  o `~-3` le suman un desplazamiento (como en vanilla). Un valor que no se pueda interpretar
  conserva el comportamiento actual (se queda la coordenada original).
- El parser es texto puro: va en `common/` con tests, porque los comandos (F8) lo reutilizan.
- Archivos: `WaypointCreateScreen.saveWaypoint`, clase nueva en `common/`.
- Hecho: `RelativeCoordinate.parse(texto, base)` en `common/`, con `RelativeCoordinateTest`
  (comprobado contra cuatro roturas deliberadas). La base es la posición exacta del jugador, y
  `BlockPos.containing` la baja al bloque, como los argumentos de bloque de vanilla.
- **Decisiones nuevas:** `~ 5` (con espacio) no se acepta, como en vanilla. `NaN`, `Infinity` y
  números fuera de rango, que antes se aceptaban y daban coordenada 0 o 2147483647, ahora cuentan
  como ilegibles (se queda la coordenada original). Todo lo que antes se leía sigue leyéndose.

**F3 — Límite de waypoints visibles** · HECHO en alpha.2
- Opción propia: "máximo de waypoints visibles", deslizador de **0 a 100 en pasos de 5**. Por
  defecto `0` = sin límite, así que no cambia nada para quien no la toque (regla 1). `0` es el
  valor cero de Gson, así que no necesita grupo nuevo.
- Con límite, se dibujan los N más cercanos; el enfocado se dibuja siempre.
- Motivo: cada waypoint visible hace un `nextStratum()` (`WaypointRenderer.java:281`), y con cientos
  en pantalla son cientos de lotes de dibujo.
- Archivos: `WaypointRenderer.render`, `ModConfig`, `ModConfigScreen`.
- Hecho: `visibility.maxVisible` (grupo existente, `0` por defecto). Deslizador "Máx. visibles" al
  final de Apariencia, que muestra "Sin límite" en 0, con tooltip. `WaypointRenderer.applyVisibleLimit`.
- **Decisiones nuevas (el plan no lo concretaba):**
  - Los N más cercanos se eligen **entre los que están en pantalla** (después de descartar los que
    quedan fuera de la vista), no entre todos los de la dimensión: así el límite cuenta lo que se
    dibuja de verdad, y al girarse aparecen los más cercanos en esa dirección en vez de que los de
    detrás ocupen sitio. El descarte de fuera de pantalla es generoso (margen de unos 64 px), así
    que uno justo en el borde puede contar.
  - El enfocado **ocupa uno de los N** (siempre se dibuja, pero el máximo se cumple exacto).
- Claves nuevas: `config.max_visible_row`, `config.max_visible_unlimited`,
  `config.max_visible_tooltip`.

**F4 — Deshacer al borrar** · HECHO en alpha.3
- Solo para los borrados hechos desde la lista. No aplica a muertes, sincronización con Xaero ni
  `/easywp remove`.
- **Cada borrado tiene su propio deshacer de 7 segundos.** Varios borrados seguidos se apilan:
  "Deshacer" restaura el más reciente que siga vigente (orden inverso al borrado, para que los
  índices sigan siendo válidos), y el aviso indica cuántos quedan.
- El aviso "Eliminado · Deshacer" va **abajo al centro de la pantalla, fuera del modal**, en el
  hueco libre entre el engranaje y los botones de Xaero, anclado a la pantalla como ellos.
- Si se cierra y se vuelve a abrir la lista dentro de los 7 segundos, el aviso sigue ahí: el estado
  (waypoint, índice, mundo, hora) vive fuera de la pantalla.
- Deshacer reinserta el waypoint en su **mismo índice** (limitado al tamaño actual de la lista) y
  guarda. Las entradas se descartan si el jugador cambia de mundo.
- "Confirmar antes de borrar" pasa a desactivado por defecto **solo en configs nuevas** (regla 4).
- Archivos: `WaypointListScreen`, `ModConfig.Confirmations`, idiomas.
- Hecho: `TimedUndoStack` en `common/` (pila con caducidad por entrada y reinserción con índice
  limitado), con `TimedUndoStackTest` (comprobado contra cinco roturas deliberadas). El estado vive
  en `WaypointStore` (`removeWithUndo`, `undoLastRemoval`, `pendingUndoCount`) y se vacía en cada
  cambio de mundo. También se puede deshacer un borrado hecho con la confirmación activada.
- Aviso: "Eliminado" o "N eliminados" más el botón "Deshacer". Mismo alto que el engranaje y los
  botones de Xaero cuando hay sitio bajo el modal; si la pantalla es baja, justo debajo del modal.
  **A saber:** con escala de GUI 4 en 1080p (la automática) la pantalla mide 270 de alto y el modal
  acaba en 248, así que el aviso queda justo bajo el modal, más arriba que el engranaje.
- Deshacer restaura el mismo waypoint con todo (foco, oculto, favorito, muerte).
- **Rediseñado el 2026-10-05, a petición, tras la primera versión (sustituye al aviso de abajo al
  centro y a `TimedUndoStack`):**
  - Al borrar, el waypoint **se queda en su fila**, atenuado como uno oculto, y desaparece del mundo.
    Su segunda línea dice "Se borrará en N s" en rojo.
  - Sus botones (TP, visible, editar, compartir, más opciones, eliminar) se sustituyen por dos que
    ocupan el mismo ancho: **Deshacer** y **Eliminar** (borra ya, sin esperar). El cuadro de color
    queda atenuado y sin acción.
  - A los 7 s la fila desaparece sola, con la lista abierta o cerrada. Cada borrado lleva su propia
    cuenta.
  - Ya no hay pila ni índices: el waypoint nunca sale de la lista hasta que el borrado es definitivo.
    `PendingRemovals` en `common/` lleva las cuentas atrás, con `PendingRemovalsTest` (comprobado
    contra cinco roturas deliberadas); `TimedUndoStack` y su test se eliminaron.
  - **Decisiones (tomadas al implementarlo):**
    - El archivo lo da por borrado desde el primer momento (no se guarda mientras está pendiente),
      igual que antes: cerrar el juego en esos 7 s lo deja borrado.
    - Si estaba enfocado, el foco desaparece del mundo al instante, y Deshacer lo devuelve.
    - Importar o exportar con Xaero durante esos segundos hace definitivos los borrados pendientes
      antes de sincronizar.
    - Un pendiente de borrar no impide importar desde otro mundo uno igual, y el doble clic sobre su
      fila no copia nada.

**F5 — Favoritos** · HECHO en alpha.3
- Se marca en la página "Más opciones" (U5). En la lista se muestra una estrella delante del nombre
  (resta ancho a `NAME_MAX_WIDTH`, hoy 133px).
- Los favoritos van primero en cualquier orden de la lista.
- En el mundo se atenúan menos cuando no se les mira: opción propia "opacidad de favoritos no
  mirados", **80% por defecto** (a medio camino entre el 60% por defecto de la atenuación normal y
  el 100%), deslizador en pasos de 5% en Apariencia. Por la regla 3 va en un **grupo nuevo** de
  `config.json` (p. ej. `favorites.unlookedOpacityPercent`): dentro de `lookFade` un config viejo lo
  leería como 0% y los favoritos desaparecerían.
- Campo opcional nuevo `favorite`. `mergeFrom` lo conserva.
- Archivos: `Waypoint`, `WaypointStore.WaypointData`, `WaypointCreateScreen`,
  `WaypointListScreen`, `WaypointRenderer.lookOpacity`, `ModConfig`, `ModConfigScreen`.
- Hecho: botón "Favorito: Sí/No" con estrella en "Más opciones". Estrella de 9x9 dibujada a mano
  (`Icons.drawStar`) en ámbar (`UiPalette.FAVORITE_STAR`), distinta del dorado del foco. Grupo
  `favorites.unlookedOpacityPercent = 80`; deslizador "Favoritos sin mirar" justo debajo de
  "Opacidad sin mirar". El enfocado sigue al 100%.
- **Decisiones nuevas:**
  - Solo las filas favoritas desplazan el nombre (11px) y pierden ese ancho; las demás no cambian,
    así que una lista sin favoritos se ve igual que antes.
  - Favoritos primero = segundo orden estable tras el del modo, así que dentro de cada mitad se
    mantiene el orden elegido.
  - Lo importado de Xaero no es favorito; lo importado de otro mundo conserva su favorito.

**F6 — Duplicar en la otra dimensión** · HECHO en alpha.3
- Botón en la página "Más opciones" (U5), solo al editar. Crea una copia independiente en la otra
  dimensión del par Overworld/Nether, en la posición convertida 1:8 (misma conversión que
  `Waypoint.positionIn`), con el mismo nombre y color, no compartida y con fecha de creación nueva.
- Desactivado si el waypoint ya es compartido (se vería dos veces) o si no está en Overworld/Nether.
  (Desde U6 ya no hay compartidos: solo se desactiva fuera de Overworld/Nether.)
- Archivos: `WaypointCreateScreen`, `Waypoint` (exponer la conversión).
- Hecho: `Waypoint.pairedDimension()` y `Waypoint.convertedPosition()`, la única definición de la
  conversión 1:8 (también la usa la conversión de los compartidos viejos, U6).
- **Decisión nueva (el plan no decía qué pasa con lo editado sin guardar):** el botón es **"Guardar y
  duplicar en el Nether/Overworld"**: primero guarda el formulario y después crea la copia a partir
  de lo guardado, y cierra (vuelve a la lista o al juego). Así la copia y el original coinciden
  siempre.
- La copia no hereda favorito (el plan solo nombra nombre y color). Mensaje en el HUD con la
  dimensión y las coordenadas de la copia.
- **Añadido el 2026-10-05, a petición: no se duplica dos veces.** Si la copia ya existe, el botón
  (del formulario y del minimenú de la lista) sale desactivado con "Ya hay una copia en el Nether".
  Si la copia se borra, se puede volver a duplicar.
  - **Decisión (qué cuenta como "la copia"):** un waypoint en la otra dimensión con el **mismo
    nombre** cuyas X y Z corresponden a 1:8. La **Y no cuenta**, para que siga siendo la copia
    aunque le ajustes la altura; si la renombras o la mueves en X/Z, deja de serlo. Una copia
    pendiente de borrar tampoco cuenta.
  - Funciona en los dos sentidos pese al redondeo: `-15` en el Overworld se duplica a `-2` en el
    Nether, y duplicar ese `-2` de vuelta no crea un `-16` junto al `-15`. La regla
    (`PairedCoordinates`, en `common/`) compara en coordenadas del Nether; tiene su test,
    `PairedCoordinatesTest`, comprobado contra tres roturas deliberadas.
  - "Guardar y duplicar" vuelve a mirarlo después de guardar (el formulario puede haber cambiado
    el nombre o la posición); si ya existe, solo guarda y avisa.
- **Añadido el 2026-10-05, a petición: también al crear.** "Más opciones" de un waypoint nuevo
  ofrece "Guardar y duplicar en el Nether" si el jugador está en el Overworld, "…en el Overworld"
  si está en el Nether, y desactivado en cualquier otra dimensión. Guarda el nuevo y su copia en un
  paso. (`saveWaypoint()` devuelve ahora el waypoint guardado; `DimensionNames.paired` da la otra
  mitad del par para una dimensión sin waypoint.) Entró en el commit de la alpha 3 sin probar en el
  juego.

**F7 — Formato de compartir nuevo** · HECHO en alpha.4
- Hoy: `easywp.share.chat` = `%s >>> %s %s %s %s` (NOMBRE en mayúsculas, dimensión legible, X Y Z),
  sacado del archivo de idioma. `CLAUDE.md` lo describe mal (`-->` y `NAME[x, y, z] -> Dimension`);
  corregirlo al cerrar este punto.
- Decisión:
  - Formato fijo en código, fuera de los archivos de idioma (se borra `easywp.share.chat` de los
    8), para que una traducción no pueda romper el parser.
  - El nombre va tal cual está escrito (sin mayúsculas forzadas). Sin color.
  - Dimensión: las tres vanilla con su nombre legible (`Overworld`, `Nether`, `The End`); las de
    mods con su id completo (`twilightforest:twilight_forest`), que hoy se pierde.
  - Ejemplos: `Base principal >>> Nether 12 64 -30`,
    `Torre >>> twilightforest:twilight_forest 100 70 200`.
- Parser: se lee desde la derecha (el nombre puede contener `>>>` y la dimensión puede tener
  espacios). Acepta también las líneas de 1.3.x (nombre en mayúsculas; una dimensión de mod
  irreconocible se resuelve con la dimensión actual de quien recibe).
- Va en `common/` (clase nueva, p. ej. `WaypointShareFormat`) con tests. Es la base de F8–F11.
- Hecho: `WaypointShareFormat` en `common/` (`format`, `parse`, `dimensionLabel`,
  `dimensionFromLabel`), con `WaypointShareFormatTest` (comprobado contra seis roturas
  deliberadas). `WaypointShare.chatLine` (cliente) arma la línea para el botón de la lista y para
  `/easywp share`; con `xaeroChatFormat` sigue saliendo la línea de Xaero. `easywp.share.chat` se
  borró de los 8 idiomas. `CLAUDE.md` corregido.
- **Decisiones nuevas:**
  - `parse` exige que **toda** la línea sea una línea F7. Buscarla dentro de un mensaje más largo
    (con prefijo de `/msg`, nombre del jugador...) es trabajo de F11.
  - Los nombres vanilla se leen sin distinguir mayúsculas (`NETHER`, `the end`), y también se acepta
    el id (`minecraft:the_nether`). Cualquier texto que no sea un id con espacio de nombres (p. ej.
    `Twilight Forest` de 1.3.x) da dimensión desconocida.
  - Se toleran espacios de más entre las partes.

**F8 — Comandos de cliente `/easywp`** · HECHO en alpha.4
- Con `ClientCommandRegistrationCallback` (Fabric API, `fabric-command-api-v2`). Sin alias corto.
- Subcomandos:
  - `list`: waypoints de la **dimensión actual** en el chat, con su distancia (en km a partir de
    1000, F1) y acciones clicables (enfocar; tp si tiene permisos).
  - `add <nombre> [x y z] [color]`: sin coordenadas usa la posición del jugador; las coordenadas
    aceptan `~` (parser de F2); color en hexadecimal (`#RRGGBB` o `RRGGBB`), y sin color uno **al
    azar**, como Fast Ping. Nombres con espacios entre comillas.
  - `remove <nombre>`: borra **sin deshacer**.
  - `focus <nombre>`.
  - `share <nombre>`: chat público con el formato F7, o la línea de Xaero si `xaeroChatFormat`
    está activo (misma regla que el botón de compartir).
  - `tp <nombre>`: requiere permisos, igual que el botón de la lista.
  - `open <dim> <x> <y> <z> <nombre>`: abre la pantalla de crear rellenada. Existe para el `[+]`
    del chat (F11), aunque aparece en el autocompletado como los demás.
  - Con nombres repetidos actúa sobre el **más cercano** y avisa de que hay varios con ese nombre.
  - Autocompletado de nombres existentes.
- Abrir una pantalla desde un comando hay que aplazarlo un tick: el chat se cierra después de
  ejecutar el comando.
- Mensajes en los 8 idiomas.
- Hecho: `EasyWpCommands` (cliente). La clase de Fabric se llama `ClientCommands` en las tres
  versiones (no `ClientCommandManager`). Comprobado en los jars de Fabric API de las tres: Fabric
  intercepta `sendCommand` y `sendUnattendedCommand`, así que un clic `RUN_COMMAND` sobre
  `/easywp ...` se ejecuta **sin** confirmación de vanilla (resuelve la duda de F11).
  Refactor de paso: `WaypointStore.focus`/`clearFocus` (lista y comando),
  `ModKeyBindings.showWaypointsAfterCreate` (formulario, Fast Ping y `add`),
  `EasyWpCommands.teleportCommand`/`canTeleport` (lista y comando).
- **Decisiones nuevas:**
  - **Nombres:** en `remove`, `focus`, `share`, `tp` y `open` el nombre es el resto de la línea,
    así que no necesita comillas (si se ponen, se quitan). En `add` va entre comillas si tiene
    espacios. Los nombres aceptan acentos y símbolos sin comillas (el texto sin comillas de
    Brigadier no los admite, así que se leen con tipos de argumento propios).
  - **Nombre de 32 caracteres como máximo** (el límite del campo del formulario, que si no lo
    cortaría al editarlo): `add` da error, `open` lo recorta.
  - **Qué waypoint es "ese nombre":** primero coincidencia exacta, si no la hay sin distinguir
    mayúsculas. Nunca uno pendiente de borrar. Con varios, el más cercano en la dimensión del
    jugador (si ninguno está en ella, el primero de otra) y avisa cuántos hay. El autocompletado
    ofrece todos los nombres del mundo.
  - **`list`:** solo la dimensión actual, ordenada por distancia, sin los pendientes de borrar.
    Cada línea: cuadrado del color del waypoint, nombre (al pasar el ratón, sus coordenadas),
    distancia, `[Enfocar]` y `[TP]` (solo con permisos). Los clics ejecutan `/easywp focus` y
    `/easywp tp` con el nombre, así que con nombres repetidos actúan sobre el más cercano. El TP no
    ejecuta el `/execute ... tp` de vanilla directamente porque vanilla pide confirmación al hacer
    clic en un comando que requiere permisos.
  - **`add` con color y sin coordenadas** también vale (`add Base #FF0000`). Un número de 6 cifras
    solo tras el nombre se toma como color, no como una X incompleta.
  - **`focus`** siempre enfoca (no alterna). **`remove`** borra al instante, sin deshacer.
  - **`open`:** una dimensión que el servidor no tiene (o irreconocible) pasa a la actual. Las
    coordenadas aceptan `~`. El waypoint se guarda en esa dimensión aunque el jugador esté en otra.
  - **`/easywp` solo** muestra una línea de ayuda con los subcomandos.
- Claves nuevas: `command.help`, `command.list_header`, `command.list_empty`,
  `command.list_focus(_hover)`, `command.list_tp(_hover)`, `command.added`, `command.removed`,
  `command.focused`, `command.not_found`, `command.ambiguous`, `command.no_permission`,
  `command.empty_name`, `command.name_too_long`, `command.bad_coordinate`, `command.bad_color`.

**F9 — Menú de compartir: público / privado / copiar** · PENDIENTE · alpha.5
- El botón de compartir de la lista abre un menú pequeño:
  - **Público:** el comportamiento actual, con el formato F7 (o la línea de Xaero si
    `xaeroChatFormat` está activo).
  - **Privado:** elegir un jugador conectado (lista de `connection.getOnlinePlayers()` sin uno
    mismo) y enviar `/msg <jugador> <línea>`. Respeta `xaeroChatFormat` igual que Público. Oculto
    en un solo jugador o sin otros jugadores.
  - **Copiar:** copia la línea F7 de ese waypoint al portapapeles.
- Archivos: `WaypointListScreen`, pantalla nueva para el selector de jugador.

**F10 — Exportar e importar por portapapeles** · PENDIENTE · alpha.5
- Exportar:
  - un waypoint, desde "Copiar" (F9);
  - la pestaña actual o el resultado de la búsqueda, desde un botón nuevo en la **esquina inferior
    izquierda, junto al engranaje**, anclado a la pantalla como él.
  - Una línea F7 por waypoint.
- Importar: Ctrl+V en la lista (solo si el buscador no tiene el foco; si lo tiene, pega en el
  buscador). Acepta líneas F7 y líneas `xaero-waypoint:` (mismo parser que F11); se ignoran las
  demás. Se muestra una vista previa como la de `ImportConfirmScreen` y se fusiona con
  `mergeFrom`, que ya ignora los duplicados (mismo nombre, posición y dimensión).

**F11 — `[+]` en el chat** · PENDIENTE · alpha.5
- Escucha `ClientReceiveMessageEvents.CHAT` y `GAME` (los `/msg` y los plugins de chat llegan como
  mensajes de sistema con prefijo), busca una línea F7 **dentro** del mensaje (no solo al inicio) y
  añade debajo un mensaje local con `[+]`.
- Al hacer clic ejecuta `/easywp open ...` (F8), que abre la pantalla de crear rellenada para
  ajustar nombre y color antes de guardar.
- Dimensión no reconocida: la dimensión actual de quien recibe. Se ignoran los mensajes propios.
- Líneas `xaero-waypoint:` también reciben `[+]`, **solo si Xaero no está instalado** (si lo está,
  Xaero ya ofrece su `[Add]`). Esa línea no trae dimensión: se usa la actual. El color sale de su
  índice de paleta (`XaeroWaypointFormat.rgbForColorOrdinal`). Hace falta un parser para el formato
  de chat de Xaero (hoy solo existe el que lo genera, `chatShareLine`).
- ~~A comprobar en 26.x: que un clic `RUN_COMMAND` llegue al comando de cliente sin que vanilla pida
  confirmación.~~ Comprobado en alpha.4 (ver F8): llega sin confirmación en las tres versiones.

**F12 — Grupos** · PENDIENTE · alpha.6
- **Un waypoint puede estar en varios grupos** (son etiquetas). Por defecto, en ninguno.
- **Asignar:** en la página "Más opciones" (U5), un desplegable que deja elegir un grupo que ya
  existe en este mundo o crear uno nuevo escribiendo su nombre (texto libre). Los grupos asignados
  se muestran como etiquetas que se pueden quitar. Vanilla no tiene desplegable: es un widget
  propio.
- **Qué grupos existen:** se deducen de los waypoints del mundo (la unión de sus grupos). Un grupo
  que se queda sin waypoints desaparece solo, sin pantalla de gestión aparte.
- **Filtrar en la lista:** se achica el buscador y en ese espacio va el filtro de grupos (Todos /
  Sin grupo / cada grupo), junto al botón de orden. Con un grupo elegido aparece al lado un botón
  para mostrarlo u ocultarlo entero.
- **Ocultar un grupo** lo oculta también en el mundo. Un waypoint queda oculto si **alguno** de sus
  grupos está oculto (ocultar "Minas" oculta todas las minas, tengan o no otros grupos). **El
  waypoint enfocado se ve igual** aunque su grupo esté oculto.
- **Dónde se guarda:** los grupos de cada waypoint, en el campo opcional nuevo `groups` (lista de
  textos) de `waypoints_*.json`. Los grupos ocultos, aparte, en `groups_<worldId>.json` (con
  `JsonStore`, como los demás archivos), para no tocar la forma del archivo de waypoints (regla 2).
  Las entradas de grupos que ya no existen se descartan al guardar.
- `mergeFrom` conserva los grupos. No forman parte del formato F7 (se pierden al compartir o
  copiar), no se exportan a los sets de Xaero, y `/easywp add` no los recibe.
- Archivos: `Waypoint`, `WaypointStore`, `WaypointCreateScreen`, `WaypointListScreen`,
  `WaypointRenderer.render`, idiomas.

**F13 — Mensaje de bienvenida con teclas y novedades** · PENDIENTE · alpha.7
- Al entrar al primer mundo tras instalar o actualizar, un mensaje local en el chat con:
  - las cinco teclas con su asignación **actual** (`KeyMapping.getTranslatedKeyMessage()`; el
    jugador puede haberlas cambiado);
  - dónde está la configuración (el engranaje de la lista con J, o Mod Menu);
  - un resumen corto (3 a 5 líneas) con **solo las novedades que más afectan**, como controles o
    funciones nuevas (claves de idioma por versión, en los 8 archivos).
- Sale una vez por versión, comparando la versión **completa**: también en cada alpha y beta.
- Grupo nuevo en `config.json` (p. ej. `onboarding.lastSeenVersion`), comparado con la versión de
  `FabricLoader`. Los usuarios de 1.3.x también lo ven una vez al actualizar, que sirve de anuncio.

### Descartado para 1.4.0

- **Ocultar etiquetas** de los waypoints que no se miran o más allá de cierta distancia: la
  atenuación que ya existe es suficiente.
- **Flecha en el borde de la pantalla** para el waypoint enfocado.
- **Traducir los nombres de dimensión** de la lista: no se planteó; si se hace en el futuro, no debe
  tocar el formato F7.

### Pruebas de regresión 1.4.0

Una línea por bug arreglado y por función entregada: la comprobación mínima que demuestra que sigue
funcionando. Crece con cada alpha; en las betas se repasa entera, en 26.1, 26.2 y 26.3.

**alpha.1**
- B1 — Con el buscador de la lista enfocado (clic en él), escribir `jungla`: el texto aparece
  entero y la lista no se cierra. Con el buscador sin foco (recién abierta), J la cierra.
- B2 — Con 7 waypoints en una dimensión: rueda abajo hasta el final, borrar uno: se ven los 6
  restantes, empezando por el primero.
- B3 — Enfocar un waypoint y ocultarlo con su ojo: el ojo sale cerrado, la fila atenuada y el
  waypoint no se dibuja en el mundo.
- B3 (foco global) — Enfocar un waypoint del Nether y mirar la pestaña Overworld: las filas sin
  "mantener visible" salen atenuadas y con el ojo cerrado, igual que en el mundo.
- B4 — Entrar a un mundo, salir al menú, volver a entrar: los waypoints siguen ahí, sin
  duplicados.
- B5 — Desde la pantalla de título (Mod Menu → easyWp): "Importar waypoints..." está gris y su
  tooltip pide entrar a un mundo. Dentro de un mundo funciona.
- B6 — Crear un waypoint con N y otro con V: en `waypoints_<mundo>.json` su `createdAt` no es 0.
- B7 — `./gradlew :common:test` pasa (los casos de `Error` y `execute` rechazado de
  `AsyncJsonWriterTest`). Cerrar el juego tras crear un waypoint no se queda colgado.
- Doble clic en coordenadas — Doble clic en el nombre de una fila: "¡Copiado!" aparece junto a esa
  fila durante 1 s y el portapapeles tiene sus coordenadas.
- O1 — El selector de importación muestra el mismo número de waypoints por mundo que en 1.3.4, y
  hacer scroll no da tirones.
- O2 — Importar de Xaero dos veces seguidas: la segunda añade 0.
- O3 — Caminar con el balanceo de cámara de vanilla (View Bobbing) activado: los waypoints se
  mueven pegados a su sitio, sin bailar; en `logs/latest.log` no aparece "view bob".
- O4 — Crear con N sin escribir nombre: la sugerencia gris, la vista previa y el nombre guardado
  coinciden.
- O5 — Los botones con texto (Nuevo, Volver, orden) muestran su texto; los de icono no muestran
  texto.

**alpha.2**
- U1 — Lista → editar → Guardar: vuelve a la misma lista (misma pestaña, búsqueda y scroll) con el
  cambio ya visible. Con N → Guardar: vuelve al juego.
- U2 — En el Overworld con `WAYPOINT #1`, `#2` y `#3`, borrar `#2` y pulsar N: la sugerencia es
  `WAYPOINT #2`. En el Nether vacío, N sugiere `WAYPOINT #1`.
- U3 — Enfocar uno, marcar otro como visible (ojo), salir y volver a entrar: los dos siguen
  visibles y el resto oculto.
- U4 — Orden "Creación": un waypoint importado de otro mundo con fecha antigua aparece antes que
  los creados hoy; los de 1.3.x siguen primero y en su orden.
- F1 — Waypoint a más de 1000 bloques: la etiqueta dice `(1.2km)`; con "Distancia en km" apagado,
  `(1234m)`.
- F2 — Pulsar N, escribir `~10` en X y guardar: el waypoint queda 10 bloques al este del jugador.
  `abc` en Y conserva la Y original.
- F3 — "Máx. visibles" en 5 con 10 waypoints a la vista: solo se dibujan los 5 más cercanos; el
  enfocado se ve aunque esté lejos. En "Sin límite" se ven todos.

**alpha.3**
- U5 — En el formulario, escribir un nombre, ir a "Más opciones" (`⋯`), volver (`‹`): el nombre
  sigue escrito. Guardar desde "Más opciones" guarda también lo de la página principal.
- U5 (campos) — Escribir un nombre y redimensionar la ventana: el nombre sigue ahí.
- F4 — Borrar un waypoint desde la lista: la fila se queda atenuada, dice "Se borrará en N s", sus
  botones pasan a ser "Deshacer" y "Eliminar", y desaparece del mundo. "Deshacer" la devuelve tal
  cual; "Eliminar" la borra al momento.
- F4 (tiempo) — Borrar, cerrar la lista y abrirla antes de 7 s: la fila sigue con su cuenta; a los
  7 s desaparece sola, también con la lista abierta.
- F5 — Marcar un waypoint como favorito: sale primero en la lista con estrella, en cualquier orden;
  sin mirarlo se ve más opaco que los demás.
- F6 — Editar un waypoint del Overworld en X=800 Z=-160 y pulsar "Guardar y duplicar en el
  Nether": aparece una copia en el Nether en X=100 Z=-20.
- F6 (sin doble) — Con la copia ya hecha, "Duplicar" sale desactivado ("Ya hay copia en el
  Nether"); al borrar la copia vuelve a estar disponible.
- F6 (al crear) — Con N en el Overworld, "Más opciones" ofrece "Guardar y duplicar en el Nether"
  (en el Nether, "…en el Overworld"; en el End, desactivado): guarda el nuevo y su copia a la vez.
- U6 — Abrir un mundo con un waypoint compartido de 1.3.x (Overworld X=800 Z=-160): quedan dos
  waypoints, uno en cada dimensión (Nether X=100 Z=-20), y "Exportar a Xaero" no borra la copia
  del Nether en Xaero.
- U7 — En la lista, el botón `⋯` de una fila abre un minimenú junto a ella (favorito, duplicar)
  sin salir de la lista; Esc o un clic fuera lo cierran.

**alpha.4**
- F7 — Compartir desde la lista un waypoint `Base principal` del Nether en 12 64 -30: el chat
  muestra `Base principal >>> Nether 12 64 -30` (nombre sin mayúsculas forzadas).
- F7 (Xaero) — Con "Formato de chat de Xaero" activado, compartir sigue enviando la línea
  `xaero-waypoint:`.
- F8 — `/easywp add "Mina de oro" ~ ~-5 ~ #FFAA00` crea el waypoint 5 bloques por debajo con ese
  color; `/easywp list` lo muestra con su distancia y `[Enfocar]`, que lo enfoca al hacer clic.
- F8 (nombres) — `/easywp remove Mina de oro` (sin comillas) lo borra al instante; con dos del
  mismo nombre, actúa sobre el más cercano y avisa.
- F8 (tp) — Con permisos, `[TP]` de `/easywp list` y `/easywp tp <nombre>` teletransportan sin
  pedir confirmación; sin permisos, `[TP]` no aparece y `/easywp tp` da error.
- F8 (open) — `/easywp open minecraft:the_nether 100 70 -20 Portal` abre el formulario relleno, y
  al guardar el waypoint queda en el Nether aunque el jugador esté en el Overworld.
