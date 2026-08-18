# easyWp — Documentación Técnica

Mod de waypoints (puntos de referencia) para **Minecraft Fabric**, ligero y no intrusivo. Permite crear, listar, editar, ocultar/mostrar, enfocar, compartir entre dimensiones, marcar con un raycast al bloque apuntado ("ping") y teletransportarse (en creativo/OP) a marcadores persistentes por mundo/servidor.

- **Mod ID:** `easywp`
- **Autor:** Carlos Florian
- **Licencia:** CC0 1.0 Universal (dominio público)
- **Repositorio:** https://github.com/cflorians/easyWaypoints
- **Plataforma:** Fabric Loader ≥ 0.19.2, Fabric API, Java ≥ 25

---

## 1. Características principales

| Característica | Descripción |
|---|---|
| **Oclusión "pixel-perfect"** | Los marcadores se dibujan también a través de bloques (see-through) sin perder precisión de posición. |
| **Compatibilidad con shaders** | Un `RenderType` alternativo (pipeline privado registrado en Iris) evita que Iris/Oculus/OptiFine oscurezcan u oculten los marcadores. |
| **Persistencia JSON por mundo** | Cada mundo (singleplayer) o servidor (multiplayer) guarda su propio archivo de waypoints. |
| **Compartir entre dimensiones** | Un waypoint "compartido" se recalcula automáticamente con la razón 1:8 entre Overworld y Nether. |
| **Enfoque (focus) exclusivo** | Al enfocar un waypoint, el resto se ocultan automáticamente (salvo los marcados como "siempre visibles"). |
| **Ping al bloque apuntado** | Un raycast de un solo disparo por pulsación abre el menú de creación con las coordenadas del bloque mirado, no las del jugador. |
| **Atajos de teclado configurables** | Alternar visibilidad, crear, listar y marcar waypoints con una tecla. |
| **Interfaz propia (sin librerías externas)** | Pantallas de creación/edición y listado con estética propia (`ModernButton`), sin dependencias de mods de UI. |
| **i18n** | Español e inglés, con detección automática del idioma del cliente. |

---

## 2. Estado actual del repositorio

El repositorio usa una estructura **multi-módulo** Gradle con dos ramas de Minecraft activas: `26.1` y `26.2`. No hay código legado ni carpetas de versiones históricas — `settings.gradle` declara exactamente `include 'common', 'versions:26.1', 'versions:26.2'`, y eso es todo lo que existe en el árbol de archivos.

- **26.1** usa la arquitectura de renderizado "billboard 3D" clásica (sección 5.1), enganchada a `LevelRenderEvents.END_MAIN`.
- **26.2** usa la misma arquitectura "billboard 3D" (sección 5.2), pero enganchada a `LevelRenderEvents.COLLECT_SUBMITS`, necesario por el rediseño del pipeline de render (`SubmitNodeCollector`) de esa versión. No es una arquitectura de renderizado distinta, solo una vía de envío de geometría distinta.

Un intento anterior de portar el mod a versiones previas de Minecraft (`1.21.1`, `1.21.3`, `1.21.8`, `1.21.11`) fue descartado; si se retoma en el futuro, ver el historial de git para la última referencia de esas carpetas.

---

## 3. Arquitectura del repositorio

```text
easyWaypoints/
├── build.gradle, settings.gradle, gradle.properties   # raíz multi-módulo
├── common/                                # (:common) recursos compartidos + un único archivo de código
│   ├── src/main/java/com/easywp/EasyWp.java            # entry point del lado servidor/común
│   └── src/main/resources/...                          # lang, iconos, texturas y mixin config compartidos
├── versions/
│   ├── 26.1/                              # (:versions:26.1) rama estable actual
│   │   ├── gradle.properties              # minecraft_version=26.1.2, fabric_api=0.155.2+26.1.2
│   │   └── src/client/java/com/easywp/client/...
│   └── 26.2/                              # (:versions:26.2) rama con Blaze3D/SubmitNodeCollector nuevo
│       ├── gradle.properties              # minecraft_version=26.2, fabric_api=0.156.0+26.2
│       └── src/client/java/com/easywp/client/...
├── PORTING.md                             # notas de migración de API entre 26.1 y 26.2
└── SHADER_SOLUTIONS_LOG.md                # bitácora de 17 intentos para resolver el bug de shaders
```

### Entry points (`fabric.mod.json`)

```json
"entrypoints": {
  "main":   ["com.easywp.EasyWp"],
  "client": ["com.easywp.client.EasyWpClient"]
}
```

- `com.easywp.EasyWp` (módulo `common`) — `ModInitializer` estándar, solo registra el logger (`EasyWp.LOGGER`). El mod es puramente client-side; el lado servidor no hace nada.
- `com.easywp.client.EasyWpClient` (módulo cliente por versión) — `ClientModInitializer`, engancha keybindings y el renderer al ciclo de vida del cliente.

### Por qué el código de cliente está duplicado y no vive en `common/`

No es solo una decisión de estilo: `common/build.gradle` declara únicamente `fabric-loader` y `slf4j-api` como dependencias — **no tiene ninguna dependencia de Minecraft**. Y `common` es su propio subproyecto Gradle real (con el plugin `java` aplicado), así que Gradle ejecuta `:common:compileJava` de forma independiente, sin Minecraft en su classpath.

Cada versión mezcla el código fuente de `common/` dentro de su **propio** sourceSet `main` (`java.srcDirs += project(":common").file("src/main/java")`), así que ese código sí compila una vez insertado en 26.1 o 26.2 — pero el `:common:compileJava` independiente fallaría igual si algún archivo ahí importara `net.minecraft.*` (aunque sea una clase disponible en cliente y servidor, como `BlockPos`).

Arreglarlo "de verdad" costaría más de lo que vale: fijar una versión de Minecraft en `common/build.gradle` solo para que compile solo sería frágil (no validaría de verdad la otra versión), y quitarle el plugin `java` a `common` es una reestructuración más grande que la duplicación que ahorraría. Por eso **6 archivos de cliente son hoy byte-idénticos entre 26.1 y 26.2** (`Waypoint.java`, `ModConfig.java`, `I18nHelper.java`, `ModernButton.java`, `WaypointDisplayMode.java`, `WaypointPing.java`) y siguen así a propósito: se editan en una versión, se verifican con `diff`, y se copian a la otra — no se mueven a `common/`.

**Los recursos (no compilados) sí se comparten de verdad.** El merge ya está cableado en ambos `build.gradle` (`resources.srcDirs += project(":common").file("src/main/resources")`), y como los recursos no pasan por ningún compilador, el problema de arriba no aplica. Hoy viven ahí, compartidos entre las dos versiones: los JSON de idioma, el ícono del mod, `textures/waypoint_marker.png`, los 14 iconos de `textures/gui/`, y `easywp.mixins.json`. `fabric.mod.json` sigue siendo por versión, porque su bloque `depends` legítimamente difiere.

---

## 4. Modelo de datos: `Waypoint`

`Waypoint.java` es un POJO simple con estos campos:

| Campo | Tipo | Descripción |
|---|---|---|
| `name` | `String` | Nombre visible |
| `pos` | `BlockPos` | Coordenadas del bloque |
| `color` | `int` (ARGB) | Color del marcador y del punto en la lista |
| `dimension` | `String` | Identificador de dimensión (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`) |
| `shared` | `boolean` | Si es visible también en la dimensión "pareja" (Overworld ⇄ Nether), recalculando la posición ×8 / ÷8 |
| `visible` | `boolean` | Visibilidad manual (toggle "ojo") |
| `focused` | `boolean` | Si está en modo enfoque exclusivo |
| `forceVisible` | `boolean` | Permite que un waypoint siga visible aunque haya otro enfocado |
| `death` | `boolean` | Marca un waypoint de muerte automático (creado por `DeathWaypointManager`); un waypoint editado a mano pierde esta marca |
| `createdAtMillis` | `long` | Instante de creación en milisegundos |

La lista viva de waypoints del mundo actual se mantiene en memoria en `WaypointRenderer.waypoints` (una `List<Waypoint>` estática), que las pantallas de UI y el renderer leen/escriben directamente.

---

## 5. Sistema de renderizado

El renderizado 3D de Minecraft (`gbuffers`) es interceptado por los shaderpacks (Iris/Oculus) para aplicar iluminación y sombras. Cualquier geometría "see-through" dibujada ahí queda sujeta al mapa de sombras del shader, lo que en la práctica **oscurecía los marcadores a negro puro cuando quedaban detrás de un bloque con shaders activos**. Resolver esto llevó a dos arquitecturas distintas según la versión de Minecraft — documentadas en detalle, intento por intento, en [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md).

### 5.1 Arquitectura "billboard 3D" (usada en 26.1)

`WaypointRenderer.render(LevelRenderContext)`, enganchado a `LevelRenderEvents.END_MAIN`:

1. **Filtrado**: descarta waypoints invisibles, o no pertenecientes a la dimensión activa (salvo que sean `shared`, en cuyo caso se recalcula la posición con la razón 1:8 Overworld↔Nether).
2. **Escalado angular por distancia**: el tamaño del marcador crece linealmente con la distancia real (`WAYPOINT_VISUAL_ANGLE = 0.055f`) entre `WAYPOINT_MIN_SIZE` (0.25) y `WAYPOINT_MAX_SIZE` (500), simulando un tamaño angular aparente constante (como un "beacon").
3. **Clamp de distancia de render**: la posición de dibujo se recorta (`clampDist`) a `max(32, renderDistance*16 - 16)` bloques desde la cámara, mantiene la dirección real pero evita artefactos de precisión float a distancias enormes.
4. **Orden de pintado**: los waypoints se ordenan de más lejano a más cercano para una superposición correcta.
5. **Un único render pass por marcador**, cuyo `RenderType` se elige según el entorno (`WaypointRenderTypes.marker(isShaderActive)`, ver §5.3 bis): pases superpuestos se probaron y se descartaron porque lavan el color del marcador.
6. **Fondo de la etiqueta**: geometría dibujada a mano (`drawBackdrop`) en lugar del fondo que `Font` genera internamente — necesario para que el fondo pueda escribir profundidad (ver §5.3 bis).
7. **Texto**: nombre + distancia en metros (`NOMBRE (123m)`), dibujado con `Font.DisplayMode.SEE_THROUGH`, billboardeado hacia la cámara con `Axis.YP`/`Axis.XP`, sobre el fondo ya dibujado en el paso anterior.

Este enfoque es simple y funciona bien en 26.1, pero **no implementa oclusión geométrica real basada en raycast** (a diferencia de los intentos 12–16 documentados en el log, que sí lo hacían con una máquina de estados por cara de bloque). Es, en la práctica, la versión estabilizada y simplificada que terminó en producción para esta rama.

### 5.2 Arquitectura "billboard 3D vía submits" (usada en 26.2)

Minecraft 26.2 reescribió el pipeline de render (`SubmitNodeCollector`, ver `PORTING.md` §3): la geometría ya no se dibuja de inmediato dentro de `LevelRenderer`, sino que se **encola** para que el motor la ordene y la emita. Por eso el hook es `LevelRenderEvents.COLLECT_SUBMITS` en lugar de `END_MAIN`.

La arquitectura de waypoints es **la misma que en 26.1** (§5.1): mismo filtrado por dimensión, mismo escalado angular, mismo clamp de distancia, mismo orden back-to-front. Las diferencias son de emisión, no de concepto:

- El marcador y el fondo de la etiqueta se entregan como callbacks (`submitCustomGeometry`) en vez de escribirse directamente en un `VertexConsumer`.
- El texto se entrega con `submitText` sobre un `FormattedCharSequence`.
- La etiqueta tiene su propia transformación de billboard, separada de la del marcador.

(El fondo de la etiqueta dibujado a mano con `drawBackdrop` ya no es una diferencia entre versiones: 26.1 también lo hace ahora, ver §5.1 y §5.3 bis.)

La inmunidad a los shaderpacks **no** viene de renderizar en el HUD, sino de `WaypointRenderTypes` (§5.3 bis): el marcador se dibuja a través de un `RenderPipeline` privado basado en el snippet del rayo de beacon, que los packs mantienen emisivo y sin niebla. Ambas versiones renderizan en espacio 3D del mundo a través de `LevelRenderer`; no existe renderizado en espacio de HUD en ningún punto del mod.

> Ver `EasyWpClient.java` de `versions/26.2` para el registro del hook, y `WaypointRenderer.java` de la misma carpeta para la implementación completa.

### 5.3 Detección de shaders — `ShaderDetector`

Utilidad basada en **reflection** (sin dependencia de compilación con Iris/OptiFine) que detecta si hay un shaderpack activo, cacheando el resultado 1 segundo para no penalizar el rendimiento:

- Intenta cargar `net.irisshaders.iris.api.v0.IrisApi` (Iris moderno) y, si falla, `net.coderbot.iris.api.v0.IrisApi` (Oculus/Iris legado), invocando `getInstance().isShaderPackInUse()`.
- Si no hay Iris/Oculus, intenta `net.optifine.Config.isShaders()`.
- Si ninguna clase existe (no hay mod de shaders instalado), devuelve `false` sin lanzar excepciones.

### 5.3 bis Selección de pase — `WaypointRenderTypes`

`WaypointRenderTypes.marker(shaderPackActive)` elige el `RenderType` del marcador según el entorno; `labelBackdrop(...)` hace lo propio para el fondo de la etiqueta (en 26.1 no depende de `shaderPackActive`, ver más abajo):

- **Sin shaderpack**: un `RenderPipeline` **privado**, construido con los mismos shaders que `textSeeThrough`/`textBackgroundSeeThrough` de vanilla (así que Iris y el resto del motor lo tratan igual que a vanilla cuando no hay un pack cargado), pero con el estado de profundidad propio — ver más abajo.
- **Con shaderpack**: el marcador se dibuja a través de otro `RenderPipeline` **privado**, construido sobre `RenderPipelines.BEACON_BEAM_SNIPPET`. Este pipeline se registra ante Iris **por reflection** (`IrisApi.assignPipeline(pipeline, IrisProgram.BEACON_BEAM)`) dentro de `WaypointRenderTypes.init()`, que debe ejecutarse antes del primer frame porque Iris resuelve el formato de vértice en el momento de la asignación. El rayo de beacon es la única geometría del mundo que los shaderpacks mantienen emisiva y sin niebla, de ahí la elección. Si la reflection falla por cualquier motivo, cae de vuelta silenciosamente al `RenderType` de beacon beam vanilla.

**Estado de profundidad: la prueba siempre pasa, pero ahora sí se escribe.** Todos los pipelines privados de `WaypointRenderTypes` usan `DepthStencilState(CompareOp.ALWAYS_PASS, /*write=*/true)`: el marcador y la etiqueta se siguen viendo a través de bloques sólidos (la prueba siempre pasa), pero ahora dejan un valor de profundidad real en su propia posición proyectada (cercana a la cámara, ~4 bloques). Antes de este cambio se usaba `write=false` (o directamente ningún estado de profundidad, como en los `RenderType` de vanilla que se usaban originalmente) — funcionaba para el caso que motivó el diseño (nunca ocluido por terreno sólido), pero tenía un efecto secundario no documentado: **las nubes y el agua (terreno translúcido) se dibujaban por encima del waypoint**, dándole la apariencia de estar "detrás" de ellas.

*Causa raíz*: Minecraft (ambas versiones, arquitectura de render idéntica en este aspecto) renderiza las nubes y el terreno translúcido en sus **propios render targets**, separados del target principal donde dibuja el marcador, y los combina de vuelta sobre la imagen final comparando profundidad. Un `RenderType` que nunca escribe profundidad no deja nada en el target principal contra lo cual esa combinación pueda comparar, así que las nubes/agua dibujadas más tarde en el frame simplemente se pintan encima del marcador sin importar qué tan cerca esté en realidad. Escribir una profundidad real (aunque la prueba propia siga sin usarla) le da a esa combinación algo real contra qué comparar, sin reintroducir la oclusión por terreno sólido que el modo "see-through" existe para evitar.

En 26.1 esto exigió además dejar de usar el fondo de etiqueta que `Font` genera internamente (que usa los `RenderType` de vanilla, sin control sobre su estado de profundidad) y dibujarlo a mano con `drawBackdrop`, igual que ya hacía 26.2 por otro motivo (compatibilidad de formato de vértice con Iris, ver el bloque de comentarios en `WaypointRenderTypes.java` de 26.2). Las letras en sí siguen usando el `RenderType` de texto de vanilla (sin control posible sobre su profundidad sin un mixin, que este mod no usa), pero al ocupar los mismos píxeles que el fondo ya protegido, quedan protegidas transitivamente.

---

## 6. Persistencia de datos

### 6.1 Ubicación de archivos

```
<carpeta_del_juego>/config/easywp/waypoints_<worldId>.json
```

`WaypointRenderer.getWorldId()` genera el identificador según el contexto:

| Contexto | `worldId` |
|---|---|
| Mundo singleplayer | `sp_<nombre_del_mundo_saneado>` |
| Servidor multiplayer | `mp_<ip_saneada>` |
| LAN | `mp_lan` |

Los caracteres inválidos para nombre de archivo (`\/:*?"<>| `) se reemplazan por `_` mediante un `Pattern` compilado una sola vez (no en cada llamada, ya que `getWorldId()` se invoca en cada frame). La carga es "perezosa": `checkAndLoadWorldWaypoints()` se llama en cada frame de render y solo recarga el archivo si el `worldId` cambió desde el último frame (p. ej. al entrar a un mundo nuevo).

Además existe un **segundo archivo**, independiente del mundo: `config/easywp/config.json`, gestionado por `ModConfig` (tamaño/opacidad del marcador, mayúsculas y distancia en la etiqueta, comportamiento de waypoints de muerte, confirmaciones, recordar visibilidad, y ajustes del ping). Se agrupa por función en clases anidadas para que nuevas opciones no reestructuren el archivo, y cada grupo se comprueba a `null` al cargar para ser retrocompatible con archivos de versiones anteriores del mod.

### 6.2 Formato del archivo

Ejemplo real (`run/config/easywp/waypoints_sp_prueba.json`):

```json
[
  {
    "name": "Portal",
    "x": -56,
    "y": -60,
    "z": 348,
    "color": -8781824,
    "dimension": "minecraft:overworld",
    "shared": true
  }
]
```

- `color` se guarda como `int` con signo (ARGB de 32 bits), no como hexadecimal.
- `visible` y `focused` son opcionales en el JSON (nullable en `WaypointData`): si faltan, se asume `visible = true`, `focused = false` — por lo que el formato es retrocompatible con archivos guardados por versiones anteriores del mod que no tenían esos campos.
- El guardado (`saveToFile()`) usa `Gson` con `setPrettyPrinting()` y se dispara after cada acción que muta el estado (crear, editar, borrar, cambiar visibilidad/foco/compartido) — no hay autoguardado periódico ni caché sucia.

---

## 7. Interfaz de usuario

Toda la UI está construida a mano sobre la API vanilla de `Screen`/`GuiGraphicsExtractor`, sin toolkit de terceros. El widget base es `ModernButton` (extiende `Button`), con un builder fluido (`ModernButton.modernBuilder(...).pos(x,y).size(w,h).build()`) y tres estados visuales por CSS-like color: normal, hover y deshabilitado (paleta azul oscuro translúcido, `0xB0141A26` / `0xE025324A` / `0x3010141E`).

### 7.1 `WaypointCreateScreen` — crear/editar

- Modal centrado (270×198 px) con campo de **nombre**, campos **X/Y/Z** editables, un **selector de color** (grid de 4 filas × 14 columnas = 56 colores predefinidos, agrupados en neones, pasteles, oscuros y tierra/metálicos), y un toggle de **"compartir dimensión"** (solo habilitado si la dimensión actual es Overworld o Nether).
- Al crear un waypoint nuevo, el color inicial se elige **al azar** del grid.
- `Enter` guarda y cierra directamente (sin necesidad de hacer clic en "Guardar").
- Al guardar, si el modo de visualización estaba `DISABLED`, se reactiva automáticamente a `WORLD_MARKERS` (para que el usuario vea el resultado inmediatamente).
- Muestra un mensaje overlay (`§a¡Waypoint creado!/editado! ...`) tipo *action bar* al confirmar.

### 7.2 `WaypointListScreen` — listado y gestión

- Modal centrado (290×220 px) con **buscador** (filtra por nombre, sin distinguir mayúsculas), **paginación** de 5 elementos por página con flechas arriba/abajo y scrollbar, y **selector de dimensión** (`<` / `>` cicla Overworld → Nether → The End).
- Cada fila de waypoint muestra: punto de color, nombre (truncado a 14 caracteres, con prefijo `[OW]`/`[N]` si se está mostrando un waypoint compartido convertido de otra dimensión), coordenadas, e iconos de acción:
  - **TP** (solo visible si el jugador tiene permiso `Permissions.COMMANDS_GAMEMASTER`; ejecuta `/execute in <dim> run tp @s x y z`)
  - **Foco** (exclusivo: enfocar uno desenfoca todos los demás)
  - **Mostrar/ocultar**
  - **Editar** (abre `WaypointCreateScreen` en modo edición)
  - **Compartir en chat** (envía `NOMBRE -> [x, y, z] at Overworld/Nether/The End` al chat)
  - **Alternar compartido entre dimensiones** (deshabilitado en The End)
  - **Borrar** (con `ConfirmScreen` de confirmación)
- Los waypoints atenúan su color/nombre cuando están ocultos o cuando hay otro enfocado y no son ni el enfocado ni "siempre visible".

### 7.3 `ModConfigScreen` — ajustes del mod

A diferencia de las dos pantallas anteriores, no está construida sobre `ModernButton` sino sobre `OptionsSubScreen` + `OptionsList` de vanilla — el mismo widget desplegable que usan las pantallas nativas de Vídeo/Controles de Minecraft — para que se vea y se desplace como una pantalla de opciones nativa. Una fila por ajuste (control + su propio botón "Reset"), agrupadas bajo encabezados de sección:

- **Apariencia**: tamaño y opacidad del marcador (sliders), mostrar distancia, texto en mayúsculas.
- **Waypoint de muerte**: activar/desactivar, radio y tiempo de gracia antes del borrado automático.
- **Ping**: alcance del raycast (16–512 bloques, 128 por defecto), un toggle para que el alcance **siga la distancia de renderizado** en vez del valor manual (bloqueando el slider mientras está activo), y si el rayo se detiene en fluidos.
- **Comportamiento**: confirmar antes de borrar, recordar visibilidad entre reinicios.

Cada control escribe directamente en `ModConfig` y llama a `ModConfig.save()` al cambiar; el botón de reset restaura el valor por defecto de esa clase anidada y reabre la pantalla (`reopen()`) para que todos los widgets reflejen el valor recién reseteado.

---

## 8. Controles (keybindings)

Registrados en `ModKeyBindings.register()`, categoría `key.category.easywp.easywp_controls` ("Easy Waypoints" en el menú de controles):

| Acción | Tecla por defecto | Comportamiento |
|---|---|---|
| Alternar visibilidad | **K** | Cicla `WaypointDisplayMode`: `WORLD_MARKERS` → `DISABLED` → ... Muestra mensaje overlay del modo activo. |
| Crear waypoint | **N** | Abre `WaypointCreateScreen` con las coordenadas actuales del jugador precargadas. |
| Abrir lista | **J** | Abre `WaypointListScreen`. |
| Ping de waypoint | **V** | Lanza un raycast desde la cámara (`WaypointPing`) y abre `WaypointCreateScreen` con las coordenadas del bloque apuntado. Usa `ClipContext.Block.VISUAL`, así que atraviesa hierba, antorchas y cristal. Si el rayo no golpea nada, usa el punto final del rayo. Alcance (128 por defecto) y tratamiento de fluidos configurables en `ModConfigScreen`. |

Todas se procesan en `ClientTickEvents.END_CLIENT_TICK` mediante `consumeClick()` (patrón estándar de Fabric para bindings que no son de movimiento).

El raycast del ping se ejecuta **una sola vez por pulsación**, nunca por frame. El alcance útil real está limitado por el render distance del cliente: más allá de los chunks cargados el rayo solo puede fallar, ya que el cliente lee aire en chunks descargados y nunca los genera.

---

## 9. Internacionalización (i18n)

`I18nHelper` implementa una traducción **manual** (no usa el sistema de `.lang`/`Component.translatable` de Minecraft para los textos de la UI, solo para el nombre de las teclas en `assets/easywp/lang/{en_us,es_es}.json`):

- `getLanguageCode()` lee el idioma configurado en el cliente (`Minecraft.getInstance().getLanguageManager().getSelected()`), con fallback a `en_us` si falla.
- `isSpanish()` = el código empieza con `es_`.
- `translate(key)` es un `switch` con todas las cadenas de la UI (menú, creación, HUD) en inglés/español.
- `getComponent(key, args...)` combina `translate` + `String.format` + `Component.literal(...)`, usado en toda la UI para textos y mensajes overlay.

Para añadir un idioma adicional habría que ampliar esta clase (no basta con agregar un archivo `.json` de lang, ya que los textos de pantallas están hardcodeados aquí).

---

## 10. Mixins

El mod declara y registra una única configuración de mixin, **actualmente vacía**, lista para usarse si en el futuro se necesita interceptar clases vanilla: `easywp.mixins.json` (paquete `com.easywp.mixin`, entorno común/servidor), compartida vía `common/src/main/resources/`, con `compatibilityLevel: JAVA_25` y `requireAnnotations: true`.

Existe además un paquete vacío `com.easywp.client.mixin` en el código de cliente de cada versión — el plan original parece haber sido tener también una config de mixins solo-cliente, pero **`easywp.client.mixins.json` nunca se creó** y no está referenciada en ningún `fabric.mod.json`. Si se necesitan mixins de cliente en el futuro, ese archivo todavía hay que escribirlo y añadirlo al array `mixins` de ambos `fabric.mod.json`.

---

## 11. Compilación

### Requisitos

- JDK 25
- Gradle Wrapper incluido (usa **Gradle 9.5.1**, ver `gradle/wrapper/gradle-wrapper.properties`)
- Fabric Loom `1.17-SNAPSHOT`

### Comandos

```bash
./gradlew build
```

Genera (para la estructura multi-módulo declarada en `settings.gradle`):

- `versions/26.1/build/libs/easywp-<mod_version>+26.1.x.jar`
- `versions/26.2/build/libs/easywp-<mod_version>+26.2.jar`

La versión del mod es única y global, definida en `gradle.properties` (`mod_version=1.3.0`) y se le concatena el sufijo de Minecraft (`minecraft_version_suffix` de cada subproyecto) al nombre del artefacto.

Cada subproyecto de versión usa `loom.splitEnvironmentSourceSets()` y añade `common` como fuente compartida (`sourceSets.main.java.srcDirs += project(":common")...`), además de `implementation project(":common")` como dependencia — es decir, el código de `common/` se compila e incluye directamente en cada JAR de versión, no se publica como artefacto separado.

### Carpeta `run/`

Es la carpeta de ejecución del cliente de desarrollo (`./gradlew runClient`), con un mundo de prueba (`run/saves/prueba/`) y su archivo de waypoints correspondiente en `run/config/easywp/waypoints_sp_prueba.json` — útil como referencia real del formato de datos, pero no debería commitearse como parte del mod en sí (son datos de una partida de prueba local).

---

## 12. Versiones de Minecraft soportadas

| Carpeta | Minecraft | Fabric API | Incluida en `settings.gradle` |
|---|---|---|---|
| `versions/26.1` | 26.1.2 | 0.155.2+26.1.2 | **Sí** |
| `versions/26.2` | 26.2 | 0.156.0+26.2 | **Sí** |

---

## 13. Referencia cruzada de documentos

- [`PORTING.md`](PORTING.md) — guía de migración de API 26.1 → 26.2 (arquitectura multi-módulo, tabla de versiones de tooling, cambios de API de `Minecraft.setScreen`, `Gui.setOverlayMessage`, y el nuevo `SubmitNodeCollector`).
- [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md) — bitácora completa (17 intentos) del proceso de diagnóstico y resolución del bug de marcadores/texto en negro bajo shaderpacks, incluyendo hipótesis descartadas y la causa raíz confirmada. Nota: la arquitectura final documentada en el log en términos de "proyección HUD" no es la que terminó en producción — la implementación real y la solución al oscurecimiento por shaders son las descritas en las secciones 5.2 y 5.3 bis de este documento (`WaypointRenderTypes` + pipeline de beacon privado), no un render HUD 2D.

---

## 14. Ideas para documentación futura (no cubiertas aquí)

- Diagrama de secuencia del ciclo de vida de un waypoint (crear → guardar → cargar al reentrar al mundo).
- Capturas de pantalla de las pantallas de creación/lista.
- Guía paso a paso para portar el mod a una nueva versión de Minecraft (generalizando `PORTING.md`).
