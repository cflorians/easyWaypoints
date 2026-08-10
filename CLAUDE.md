# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**easyWp** — a lightweight Fabric mod for Minecraft that adds persistent, per-world waypoints (create/edit/list, hide/focus, share across Overworld↔Nether, optional TP for operators). Client-side only; the common/server entrypoint does nothing but log. Mod ID: `easywp`. Licensed CC0 1.0.

Full technical documentation (data model, rendering internals, UI, i18n, persistence format) lives in [`DOCUMENTACION.md`](DOCUMENTACION.md) (Spanish). Cross-Minecraft-version API migration notes live in [`PORTING.md`](PORTING.md). A blow-by-blow log of 17 attempts to fix a shader-darkening render bug lives in [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md) — read it before touching rendering code for 26.2, since several tempting-looking approaches are already proven dead ends there.

## Repository structure

Multi-module Gradle layout declared in `settings.gradle` (`include 'common', 'versions:26.1', 'versions:26.2'`). Only two Minecraft versions are supported — there is no legacy single-module copy and no historical-version scaffolding.

- `common/src/main/java/com/easywp/EasyWp.java` — the shared/server `ModInitializer` (just logging; the `common/src/main/java/com/easywp/client` directory is currently empty).
- `versions/26.1/` — stable branch, Minecraft `26.1.2`, Fabric API `0.155.2+26.1.2`, uses the "billboard 3D" renderer (see below).
- `versions/26.2/` — Minecraft `26.2`, Fabric API `0.156.0+26.2`, uses the newer `SubmitNodeCollector` render pipeline and a completely different waypoint-rendering architecture (world→HUD projection, see below). Do not port 26.1's `WaypointRenderer.java` here verbatim — the render APIs diverged (see `PORTING.md`).
- Each version subproject compiles `common/` directly into its own jar (`java.srcDirs += project(":common")...` plus `implementation project(":common")`) — `common` is not published as a separate artifact.

A prior attempt to also support older Minecraft versions (`1.21.1`, `1.21.3`, `1.21.8`, `1.21.11`) was abandoned and removed; see git history if that work is ever resumed.

## Build & run

Requires JDK 25. Gradle Wrapper (Gradle 9.5.1) and Fabric Loom `1.17-SNAPSHOT` are pinned in `gradle/wrapper/` and `gradle.properties`.

```bash
./gradlew build
```

Builds every included subproject and produces:
- `versions/26.1/build/libs/easywp-<mod_version>+26.1.x.jar`
- `versions/26.2/build/libs/easywp-<mod_version>+26.2.jar`

Other useful tasks:

```bash
./gradlew :versions:26.1:build      # build a single version
./gradlew :versions:26.1:runClient  # launch a dev client for that version (working dir: run/)
./gradlew :versions:26.2:runClient
./gradlew clean
```

The mod version is global and set once in root `gradle.properties` (`mod_version=1.2.2`); each version subproject appends its own `minecraft_version_suffix` from its own `gradle.properties` to form the jar name. There is no separate lint or test task — correctness is verified via `build` (compilation) plus manual in-game testing through `runClient`.

`run/` is the dev-client sandbox (`run/saves/prueba/`, `run/config/easywp/waypoints_sp_prueba.json`) — useful as a live example of the waypoint JSON format, not something to treat as mod source.

## Architecture

### Entry points (`fabric.mod.json`, per version)

- `com.easywp.EasyWp` (in `common/`) — `ModInitializer`, server/common side, effectively a no-op (just logs startup).
- `com.easywp.client.EasyWpClient` (per-version) — `ClientModInitializer`; wires up keybindings and the renderer.

### Data model

`Waypoint` is a POJO: `name`, `pos` (`BlockPos`), `color` (ARGB int), `dimension`, `shared` (mirrored between Overworld/Nether at a 1:8 ratio), `visible`, `focused`, `forceVisible`. The live list for the current world is held in-memory as a static `List<Waypoint>` on `WaypointRenderer`, read/written directly by the UI screens and the renderer — there is no separate state/store abstraction.

### Persistence

JSON per world/server at `<game_dir>/config/easywp/waypoints_<worldId>.json` (`worldId` = `sp_<sanitized_world_name>`, `mp_<sanitized_ip>`, or `mp_lan`). Loaded lazily — `WaypointRenderer.checkAndLoadWorldWaypoints()` runs every render frame but only re-reads the file when `worldId` changes. Saved with Gson pretty-printing immediately after any mutating action (create/edit/delete/visibility/focus/share) — no periodic autosave, no dirty-cache batching. `visible`/`focused` are optional/nullable in the JSON for backward compatibility with older save files.

### Rendering — two genuinely different architectures per Minecraft version

This is the trickiest part of the codebase and the reason `SHADER_SOLUTIONS_LOG.md` exists: naive "see-through" 3D rendering gets intercepted by Iris/Oculus shaderpacks' shadow-map pass and turns markers/text pure black when occluded by a block.

- **26.1 — "3D billboard" approach.** `WaypointRenderer.render()` hooks `LevelRenderEvents.END_MAIN`. Per waypoint: filter by dimension (recomputing position for `shared` waypoints), scale marker size by angular distance, clamp render distance to avoid float precision artifacts, sort back-to-front, then draw two render passes — `RenderTypes.textSeeThrough(...)` always, plus `RenderTypes.beaconBeam(...)` (the vanilla beacon-beam render type, which shaderpacks treat as emissive) only when `ShaderDetector.isShaderPackActive()` is true. Text is billboarded toward the camera via `Axis.YP`/`Axis.XP`.
- **26.2 — "world→HUD projection" approach.** Minecraft 26.2's render pipeline rewrite (`SubmitNodeCollector`, see `PORTING.md`) plus the same shader-darkening problem led to abandoning `LevelRenderer` drawing entirely. Instead: a read-only 3D hook (`LevelRenderEvents.END_MAIN` → `WaypointRenderer::captureFrameState`) captures/clones `cameraPos`, view rotation matrix, and projection matrix for the frame; a 2D hook (`HudElementRegistry.addLast(...)` → `WaypointRenderer::renderHud`) then manually projects each waypoint's camera-relative vector through those matrices to NDC/screen pixel coordinates and draws in HUD space, which shaderpacks cannot touch. Waypoints behind the camera plane (`w ≤ 0.00001`) are hidden. This is immune to shader darkening by construction — don't reintroduce 3D-space rendering (RenderTypes, `Font.DisplayMode`, raycasting, polygon offset, state machines between block faces) here; all of those were tried and rejected, see the log.
- `ShaderDetector` detects an active shaderpack via reflection (tries modern Iris, then legacy Oculus/Iris, then OptiFine; returns `false` with no exception if none are present), caching the result for 1 second.

### UI

Hand-built on vanilla `Screen`, no third-party UI toolkit. Shared widget is `ModernButton` (fluent builder: `ModernButton.modernBuilder(...).pos(x,y).size(w,h).build()`), with normal/hover/disabled states in a translucent dark-blue palette. Two screens: `WaypointCreateScreen` (create/edit modal — name, X/Y/Z, 56-color picker grid, dimension-share toggle, Enter-to-save) and `WaypointListScreen` (searchable/paginated list with per-row TP/focus/show-hide/edit/share-to-chat/toggle-shared/delete actions; TP is gated on `Permissions.COMMANDS_GAMEMASTER`).

### i18n

`I18nHelper` does **manual** translation via a `switch` over UI strings (English/Spanish), detecting the client's language with a fallback to `en_us`. This is separate from the `.lang`/`Component.translatable` files under `assets/easywp/lang/` (which only cover keybinding names) — adding a new UI language means extending the `switch` in `I18nHelper`, not just adding a lang JSON file.

### Mixins

Two mixin configs, both currently empty (no injected mixins yet), reserved for future use: `easywp.mixins.json` (`com.easywp.mixin`, common/server) and `easywp.client.mixins.json` (`com.easywp.client.mixin`, client-only). Both require `JAVA_25` compatibility and `requireAnnotations: true`.
