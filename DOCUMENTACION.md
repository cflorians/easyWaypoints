# easyWp — Documentación Técnica

Mod de waypoints (puntos de referencia) para **Minecraft Fabric**, ligero y no intrusivo. Permite crear, listar, editar, ocultar/mostrar, enfocar, compartir entre dimensiones y teletransportarse (en creativo/OP) a marcadores persistentes por mundo/servidor.

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
| **Compatibilidad con shaders** | Un segundo *render pass* evita que Iris/Oculus/OptiFine oscurezcan u oculten los marcadores. |
| **Persistencia JSON por mundo** | Cada mundo (singleplayer) o servidor (multiplayer) guarda su propio archivo de waypoints. |
| **Compartir entre dimensiones** | Un waypoint "compartido" se recalcula automáticamente con la razón 1:8 entre Overworld y Nether. |
| **Enfoque (focus) exclusivo** | Al enfocar un waypoint, el resto se ocultan automáticamente (salvo los marcados como "siempre visibles"). |
| **Atajos de teclado configurables** | Alternar visibilidad, crear y listar waypoints con una tecla. |
| **Interfaz propia (sin librerías externas)** | Pantallas de creación/edición y listado con estética propia (`ModernButton`), sin dependencias de mods de UI. |
| **i18n** | Español e inglés, con detección automática del idioma del cliente. |

---

## 2. Estado actual del repositorio (importante)

El repositorio está **a mitad de una migración de arquitectura**, y esto se refleja en el árbol de archivos:

- **`src/` (raíz)** — el código "clásico" de un único módulo Fabric. Es el que compila `build.gradle` de la raíz junto con `common/`. Corresponde 1:1 al contenido de `versions/26.1/` (se verificó con `diff`, son idénticos).
- **`common/`** y **`versions/`** (carpetas **sin trackear en git** todavía) — la nueva estructura **multi-módulo** descrita en [`PORTING.md`](PORTING.md), que permite compilar simultáneamente varias versiones de Minecraft (`26.1`, `26.2`, y ramas antiguas `1.21.1`, `1.21.3`, `1.21.8`, `1.21.11`) desde el mismo repositorio.
- `settings.gradle` en la raíz **ya** apunta al esquema nuevo (`include 'common', 'versions:26.1', 'versions:26.2'`), pero el `src/` de la raíz sigue presente y no forma parte de ningún subproyecto declarado — es decir, hoy por hoy es código legado que convive con la nueva estructura hasta que se elimine o se termine de migrar.
- `git status` muestra además que `WaypointHudRenderer.java` fue **eliminado** de `src/` — la arquitectura de renderizado por HUD (ver sección 5.3) se probó y se descartó para la rama principal de `26.1`, pero **sí se conserva** como solución definitiva en `versions/26.2/`, donde era necesaria.

En resumen: si vas a tocar código de **26.1**, edítalo en `versions/26.1/` (y espejarlo en `src/` mientras ambos convivan, o eliminar `src/` cuando la migración se dé por completa). El código de **26.2** vive únicamente en `versions/26.2/` porque usa una arquitectura de render distinta.

---

## 3. Arquitectura del repositorio

```text
easywp-template/
├── build.gradle, settings.gradle, gradle.properties   # raíz multi-módulo
├── common/                                # (:common) código y recursos 100% compartidos
│   └── src/main/java/com/easywp/EasyWp.java           # entry point del lado servidor/común
├── versions/
│   ├── 1.21.1/  1.21.3/  1.21.8/  1.21.11/  # ramas históricas (no incluidas en settings.gradle aún)
│   ├── 26.1/                              # (:versions:26.1) rama estable actual
│   │   ├── gradle.properties              # minecraft_version=26.1.2, fabric_api=0.155.2+26.1.2
│   │   └── src/client/java/com/easywp/client/...
│   └── 26.2/                              # (:versions:26.2) rama con Blaze3D/SubmitNodeCollector nuevo
│       ├── gradle.properties              # minecraft_version=26.2, fabric_api=0.156.0+26.2
│       └── src/client/java/com/easywp/client/...
├── src/                                   # copia legada de 26.1 (ver sección 2)
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

La lista viva de waypoints del mundo actual se mantiene en memoria en `WaypointRenderer.waypoints` (una `List<Waypoint>` estática), que las pantallas de UI y el renderer leen/escriben directamente.

---

## 5. Sistema de renderizado

El renderizado 3D de Minecraft (`gbuffers`) es interceptado por los shaderpacks (Iris/Oculus) para aplicar iluminación y sombras. Cualquier geometría "see-through" dibujada ahí queda sujeta al mapa de sombras del shader, lo que en la práctica **oscurecía los marcadores a negro puro cuando quedaban detrás de un bloque con shaders activos**. Resolver esto llevó a dos arquitecturas distintas según la versión de Minecraft — documentadas en detalle, intento por intento, en [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md).

### 5.1 Arquitectura "billboard 3D" (usada en 26.1 y en `src/` legado)

`WaypointRenderer.render(LevelRenderContext)`, enganchado a `LevelRenderEvents.END_MAIN`:

1. **Filtrado**: descarta waypoints invisibles, o no pertenecientes a la dimensión activa (salvo que sean `shared`, en cuyo caso se recalcula la posición con la razón 1:8 Overworld↔Nether).
2. **Escalado angular por distancia**: el tamaño del marcador crece linealmente con la distancia real (`WAYPOINT_VISUAL_ANGLE = 0.055f`) entre `WAYPOINT_MIN_SIZE` (0.25) y `WAYPOINT_MAX_SIZE` (500), simulando un tamaño angular aparente constante (como un "beacon").
3. **Clamp de distancia de render**: la posición de dibujo se recorta (`clampDist`) a `max(32, renderDistance*16 - 16)` bloques desde la cámara, mantiene la dirección real pero evita artefactos de precisión float a distancias enormes.
4. **Orden de pintado**: los waypoints se ordenan de más lejano a más cercano para una superposición correcta.
5. **Doble render pass por marcador**:
   - `RenderTypes.textSeeThrough(...)` — pase estándar, visible a través de bloques sin shaders.
   - `RenderTypes.beaconBeam(...)` — solo si `ShaderDetector.isShaderPackActive()` es `true`; este *render type* es el mismo que usa el rayo de los beacons vanilla, que los shaderpacks sí respetan/iluminan de forma emisiva, evitando el oscurecimiento.
6. **Texto**: nombre + distancia en metros (`NOMBRE (123m)`), dibujado con `Font.DisplayMode.SEE_THROUGH` en dos sub-pasadas (fondo semitransparente + texto blanco), billboardeado hacia la cámara con `Axis.YP`/`Axis.XP`.

Este enfoque es simple y funciona bien en 26.1, pero **no implementa oclusión geométrica real basada en raycast** (a diferencia de los intentos 12–16 documentados en el log, que sí lo hacían con una máquina de estados por cara de bloque). Es, en la práctica, la versión estabilizada y simplificada que terminó en producción para esta rama.

### 5.2 Arquitectura "proyección mundo→HUD" (usada en 26.2)

Minecraft 26.2 reescribió el pipeline de render (`SubmitNodeCollector`, ver `PORTING.md` §3), lo que —sumado al problema de oscurecimiento de shaders— llevó a descartar por completo el dibujo dentro de `LevelRenderer`. La solución final (**Intento 17** del log) mueve todo el renderizado a la capa de **HUD 2D**, inmune a cualquier post-proceso de shaderpacks:

- **Hook 3D de solo lectura** (`LevelRenderEvents.END_MAIN` → `WaypointRenderer::captureFrameState`): captura y clona `cameraPos`, la matriz de vista (`viewRotationMatrix`) y la matriz de proyección de ese frame.
- **Hook 2D** (`HudElementRegistry.addLast(...)` → `WaypointRenderer::renderHud`): por cada waypoint visible, calcula el vector cámara→waypoint, lo multiplica por las matrices de vista/proyección capturadas, hace la división de perspectiva (NDC) y lo mapea a coordenadas de píxel de pantalla.
  - Si el waypoint queda detrás del plano de cámara (`w ≤ 0.00001`) se oculta.
  - El estilo visual (ícono con color + etiqueta con fondo semitransparente encima) se mantiene idéntico al de 26.1 para que la experiencia de usuario no cambie entre versiones.
- Resultado validado en juego: 0% de bamboleo, 0% de parpadeo, comportamiento **idéntico con y sin shaders activos** (visible directo, visible a través de bloques).

> Ver `EasyWpClient.java` de `versions/26.2` para el registro de ambos hooks, y `WaypointRenderer.java` de la misma carpeta para la implementación completa de la proyección.

### 5.3 Detección de shaders — `ShaderDetector`

Utilidad basada en **reflection** (sin dependencia de compilación con Iris/OptiFine) que detecta si hay un shaderpack activo, cacheando el resultado 1 segundo para no penalizar el rendimiento:

- Intenta cargar `net.irisshaders.iris.api.v0.IrisApi` (Iris moderno) y, si falla, `net.coderbot.iris.api.v0.IrisApi` (Oculus/Iris legado), invocando `getInstance().isShaderPackInUse()`.
- Si no hay Iris/Oculus, intenta `net.optifine.Config.isShaders()`.
- Si ninguna clase existe (no hay mod de shaders instalado), devuelve `false` sin lanzar excepciones.

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

Los caracteres inválidos para nombre de archivo (`\/:*?"<>| `) se reemplazan por `_`. La carga es "perezosa": `checkAndLoadWorldWaypoints()` se llama en cada frame de render y solo recarga el archivo si el `worldId` cambió desde el último frame (p. ej. al entrar a un mundo nuevo).

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

---

## 8. Controles (keybindings)

Registrados en `ModKeyBindings.register()`, categoría `key.category.easywp.easywp_controls` ("Easy Waypoints" en el menú de controles):

| Acción | Tecla por defecto | Comportamiento |
|---|---|---|
| Alternar visibilidad | **K** | Cicla `WaypointDisplayMode`: `WORLD_MARKERS` → `DISABLED` → ... Muestra mensaje overlay del modo activo. |
| Crear waypoint | **N** | Abre `WaypointCreateScreen` con las coordenadas actuales del jugador precargadas. |
| Abrir lista | **J** | Abre `WaypointListScreen`. |

Todas se procesan en `ClientTickEvents.END_CLIENT_TICK` mediante `consumeClick()` (patrón estándar de Fabric para bindings que no son de movimiento).

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

El mod declara dos configuraciones de mixin (ambas **actualmente vacías**, listas para usarse si en el futuro se necesita interceptar clases vanilla):

- `easywp.mixins.json` (paquete `com.easywp.mixin`) — entorno común/servidor.
- `easywp.client.mixins.json` (paquete `com.easywp.client.mixin`) — solo cliente.

Ambas fuerzan `compatibilityLevel: JAVA_25` y `requireAnnotations: true`.

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

La versión del mod es única y global, definida en `gradle.properties` (`mod_version=1.2.1`) y se le concatena el sufijo de Minecraft (`minecraft_version_suffix` de cada subproyecto) al nombre del artefacto.

Cada subproyecto de versión usa `loom.splitEnvironmentSourceSets()` y añade `common` como fuente compartida (`sourceSets.main.java.srcDirs += project(":common")...`), además de `implementation project(":common")` como dependencia — es decir, el código de `common/` se compila e incluye directamente en cada JAR de versión, no se publica como artefacto separado.

### Carpeta `run/`

Es la carpeta de ejecución del cliente de desarrollo (`./gradlew runClient`), con un mundo de prueba (`run/saves/prueba/`) y su archivo de waypoints correspondiente en `run/config/easywp/waypoints_sp_prueba.json` — útil como referencia real del formato de datos, pero no debería commitearse como parte del mod en sí (son datos de una partida de prueba local).

---

## 12. Versiones de Minecraft soportadas

| Carpeta | Minecraft | Fabric API | Incluida en `settings.gradle` |
|---|---|---|---|
| `versions/1.21.1` | 1.21.1 | — | No (histórica) |
| `versions/1.21.3` | 1.21.3 | — | No (histórica) |
| `versions/1.21.8` | 1.21.8 | — | No (histórica) |
| `versions/1.21.11` | 1.21.11 | — | No (histórica) |
| `versions/26.1` | 26.1.2 | 0.155.2+26.1.2 | **Sí** |
| `versions/26.2` | 26.2 | 0.156.0+26.2 | **Sí** |

Las carpetas históricas (`1.21.x`) contienen snapshots de código de portados anteriores; si se quiere volver a construir contra ellas hay que añadirlas a `settings.gradle` (`include 'versions:1.21.1'`, etc.) y probablemente resolver diferencias de API respecto a la rama `26.1` actual.

---

## 13. Referencia cruzada de documentos

- [`PORTING.md`](PORTING.md) — guía de migración de API 26.1 → 26.2 (arquitectura multi-módulo, tabla de versiones de tooling, cambios de API de `Minecraft.setScreen`, `Gui.setOverlayMessage`, y el nuevo `SubmitNodeCollector`).
- [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md) — bitácora completa (17 intentos) del proceso de diagnóstico y resolución del bug de marcadores/texto en negro bajo shaderpacks, incluyendo hipótesis descartadas, causas raíz confirmadas y la arquitectura final de proyección HUD.

---

## 14. Ideas para documentación futura (no cubiertas aquí)

- Diagrama de secuencia del ciclo de vida de un waypoint (crear → guardar → cargar al reentrar al mundo).
- Capturas de pantalla de las pantallas de creación/lista.
- Guía paso a paso para portar el mod a una nueva versión de Minecraft (generalizando `PORTING.md`).
