# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**easyWp** — a lightweight Fabric mod for Minecraft that adds persistent, per-world waypoints (create/edit/list, hide/focus, share across Overworld↔Nether, optional TP for operators). Client-side only; the common/server entrypoint does nothing but log. Mod ID: `easywp`. Licensed CC0 1.0.

Full technical documentation (data model, rendering internals, UI, i18n, persistence format) lives in [`DOCUMENTACION.md`](DOCUMENTACION.md) (Spanish). Cross-Minecraft-version API migration notes live in [`PORTING.md`](PORTING.md). A blow-by-blow log of 17 attempts to fix a shader-darkening render bug lives in [`SHADER_SOLUTIONS_LOG.md`](SHADER_SOLUTIONS_LOG.md) — read it before touching rendering code for 26.2, since several tempting-looking approaches are already proven dead ends there.

## Repository structure

Multi-module Gradle layout declared in `settings.gradle` (`include 'common', 'versions:26.1', 'versions:26.2'`). Only two Minecraft versions are supported — there is no legacy single-module copy and no historical-version scaffolding.

- `common/src/main/java/com/easywp/EasyWp.java` — the shared/server `ModInitializer` (just logging). There is no client code in `common/` — the entire client layer (`versions/*/src/client/java/com/easywp/client/`) is intentionally duplicated per version rather than shared. This isn't just a style choice: `common/build.gradle` declares only `fabric-loader` and `slf4j-api` as dependencies, no Minecraft artifact at all, and `common` is its own real Gradle subproject with its own `:common:compileJava` task — so any class in `common/src/main/java` that imports `net.minecraft.*` (client-only or not, e.g. `BlockPos`) would fail to compile standalone, even though it would compile fine once merged into a version's own sourceSet (see below). Pinning `common` to one Minecraft version just to satisfy its own compile would be fragile (it wouldn't actually validate compatibility with the *other* version), and dropping the `java` plugin to stop compiling it standalone is a bigger structural change than the duplication it would save. Six client files happen to be byte-identical between versions today (`Waypoint.java`, `ModConfig.java`, `I18nHelper.java`, `ModernButton.java`, `WaypointDisplayMode.java`, `WaypointPing.java`) — keep them in sync by editing one, diffing, and copying to the other, not by trying to relocate them.
- `common/src/main/resources/` **does** hold real shared content — non-code resources aren't compiled, so none of the above applies. Both versions already merge it in via `resources.srcDirs += project(":common").file("src/main/resources")`. Everything byte-identical between versions lives there: the lang JSON files, the mod icon, `textures/waypoint_marker.png`, the 14 icons under `textures/gui/`, and `easywp.mixins.json`. `fabric.mod.json` stays per-version — its `depends` block legitimately differs.
- `versions/26.1/` — stable branch, Minecraft `26.1.2`, Fabric API `0.155.2+26.1.2`, uses the "billboard 3D" renderer (see below).
- `versions/26.2/` — Minecraft `26.2`, Fabric API `0.156.0+26.2`, uses the newer `SubmitNodeCollector` render pipeline: same 3D billboard architecture as 26.1, but geometry is submitted through `LevelRenderEvents.COLLECT_SUBMITS` instead of drawn directly in `END_MAIN`. Do not port 26.1's `WaypointRenderer.java` here verbatim — the render APIs diverged (see `PORTING.md`).
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

The mod version is global and set once in root `gradle.properties` (`mod_version=1.2.4`); each version subproject appends its own `minecraft_version_suffix` from its own `gradle.properties` to form the jar name. There is no separate lint or test task — correctness is verified via `build` (compilation) plus manual in-game testing through `runClient`.

`run/` is the dev-client sandbox (`run/saves/prueba/`, `run/config/easywp/waypoints_sp_prueba.json`) — useful as a live example of the waypoint JSON format, not something to treat as mod source.

## Architecture

### Entry points (`fabric.mod.json`, per version)

- `com.easywp.EasyWp` (in `common/`) — `ModInitializer`, server/common side, effectively a no-op (just logs startup).
- `com.easywp.client.EasyWpClient` (per-version) — `ClientModInitializer`; wires up keybindings and the renderer.

### Data model

`Waypoint` is a POJO: `name`, `pos` (`BlockPos`), `color` (ARGB int), `dimension`, `shared` (mirrored between Overworld/Nether at a 1:8 ratio), `visible`, `focused`, `forceVisible`, `death` (marks an automatic death waypoint), `createdAtMillis`. The live list for the current world is held in-memory as a static `List<Waypoint>` on `WaypointRenderer`, read/written directly by the UI screens and the renderer — there is no separate state/store abstraction.

### Persistence

Two separate JSON stores, both under `<game_dir>/config/easywp/`:
- **Per world/server**: `waypoints_<worldId>.json` (`worldId` = `sp_<sanitized_world_name>`, `mp_<sanitized_ip>`, or `mp_lan`). Loaded lazily — `WaypointRenderer.checkAndLoadWorldWaypoints()` runs every render frame but only re-reads the file when `worldId` changes. Saved with Gson pretty-printing immediately after any mutating action (create/edit/delete/visibility/focus/share) — no periodic autosave, no dirty-cache batching. `visible`/`focused` are optional/nullable in the JSON for backward compatibility with older save files.
- **Global mod settings**: `config.json`, independent of any world, managed by `ModConfig` (marker size/opacity, label display, death-waypoint behavior, confirmations, visibility persistence, ping raycast settings). Grouped by feature into nested classes so new settings groups don't reshape the file; each group is null-checked on load for backward compatibility with older `config.json` files.

### Rendering — two genuinely different architectures per Minecraft version

This is the trickiest part of the codebase and the reason `SHADER_SOLUTIONS_LOG.md` exists: naive "see-through" 3D rendering gets intercepted by Iris/Oculus shaderpacks' shadow-map pass and turns markers/text pure black when occluded by a block.

- **26.1 — "3D billboard" approach.** `WaypointRenderer.render()` hooks `LevelRenderEvents.END_MAIN`. Per waypoint: filter by dimension (recomputing position for `shared` waypoints), scale marker size by angular distance, clamp render distance to avoid float precision artifacts, sort back-to-front, then draw a single marker pass through whichever `RenderType` `WaypointRenderTypes.marker(isShaderActive)` returns for the current environment (see below) — overlapping passes were tried and rejected because they wash the marker color out. The label backdrop is hand-drawn geometry (`drawBackdrop`) rather than Font's built-in background box, for the same depth-write reason described below. Text glyphs are billboarded toward the camera via `Axis.YP`/`Axis.XP`.
- **26.2 — same 3D billboard approach, different submission path.** Minecraft 26.2's render pipeline rewrite (`SubmitNodeCollector`, see `PORTING.md`) means geometry is *queued* rather than drawn immediately, so the hook is `LevelRenderEvents.COLLECT_SUBMITS` instead of `END_MAIN`. The per-waypoint math (dimension filter, angular scaling, distance clamp, back-to-front sort) is the same as 26.1's. Differences: the label gets its own billboard transform, and marker/backdrop geometry is handed over as `submitCustomGeometry`/`submitText` callbacks rather than written straight into a `VertexConsumer`.
- **Shader immunity comes from `WaypointRenderTypes`, not from render location.** Without a shaderpack the marker and backdrop ride a **private `RenderPipeline`** built on the same shaders as vanilla's `textSeeThrough`/`textBackgroundSeeThrough` (so Iris's shader swap and Minecraft's depth test still treat it identically to vanilla when no pack is loaded), just with the depth state overridden — see below. With a shaderpack loaded, the marker draws through a separate private `RenderPipeline` built on `RenderPipelines.BEACON_BEAM_SNIPPET`, registered with Iris **via reflection** (`IrisApi.assignPipeline(pipeline, IrisProgram.BEACON_BEAM)`) in `WaypointRenderTypes.init()`. Beacon beams are the one piece of world geometry shaderpacks keep emissive and fog-free. `init()` must run before the first frame — Iris resolves the vertex format at assignment time. If the reflection fails for any reason it silently falls back to the vanilla beacon beam render type. Both versions render in 3D world space through `LevelRenderer`; there is no HUD-space rendering anywhere in this mod.
- **Depth state: test always passes, but the pipelines now write depth.** Every custom pipeline in `WaypointRenderTypes` uses `DepthStencilState(CompareOp.ALWAYS_PASS, /*write=*/true)` — the marker/label are still never occluded by solid terrain (the test always passes), but they now leave a real depth value at their own (deliberately near, ~4 block) projected distance. This matters because Minecraft renders clouds and translucent terrain (water) into their *own* render targets, separate from the one the marker draws into, and composites them back over the main target afterward using depth. A pipeline that never writes depth (the previous behavior, and still how vanilla's own see-through text types work) leaves nothing there for that composite to test against, so clouds/water painted later in the frame simply draw over the marker/label regardless of how close they actually are — writing depth gives the compositor something real to test against instead.
- `ShaderDetector` detects an active shaderpack via reflection (tries modern Iris, then legacy Oculus/Iris, then OptiFine; returns `false` with no exception if none are present), caching the result for 1 second.

### UI

Hand-built on vanilla `Screen`, no third-party UI toolkit. Shared widget is `ModernButton` (fluent builder: `ModernButton.modernBuilder(...).pos(x,y).size(w,h).build()`), with normal/hover/disabled states in a translucent dark-blue palette. Three screens:
- `WaypointCreateScreen` — create/edit modal (name, X/Y/Z, 56-color picker grid, dimension-share toggle, Enter-to-save). The same constructor path is used to pre-fill coordinates from the player's feet (`N` key) or from a crosshair raycast (`V` key, see `WaypointPing`) — the screen itself is agnostic to where the `BlockPos` came from.
- `WaypointListScreen` — searchable/paginated list with per-row TP/focus/show-hide/edit/share-to-chat/toggle-shared/delete actions; TP is gated on `Permissions.COMMANDS_GAMEMASTER`.
- `ModConfigScreen` — mod settings, built on vanilla's own `OptionsSubScreen` + `OptionsList` (the same scrollable widget Minecraft's Video Settings/Controls screens use) rather than `ModernButton`, so it looks and scrolls like a native options screen. One setting per row (control + its own "Reset" button), grouped under section headers, backed by `ModConfig`.

Related classes not previously listed here: `DeathWaypointManager` (creates a temporary waypoint on player death, deletes it once the player stands near it again for a grace period), `ModConfig`/`ModConfigScreen` (settings, see Persistence above), `WaypointDisplayMode` (the visibility-cycle enum driving the toggle key), `WaypointRenderTypes` (per-environment marker `RenderType` selection, see Rendering above).

### i18n

`I18nHelper` does **manual** translation via a `switch` over UI strings (English/Spanish), detecting the client's language with a fallback to `en_us`. This is separate from the `.lang`/`Component.translatable` files under `assets/easywp/lang/` (which only cover keybinding names) — adding a new UI language means extending the `switch` in `I18nHelper`, not just adding a lang JSON file.

### Mixins

One mixin config exists and is registered: `easywp.mixins.json` (package `com.easywp.mixin`, common/server, currently empty — no injected mixins yet), shared via `common/src/main/resources/` and requiring `JAVA_25` compatibility with `requireAnnotations: true`. A client-only counterpart was apparently planned — an empty `com.easywp.client.mixin` package exists under each version's client source — but `easywp.client.mixins.json` was never created and isn't referenced in either `fabric.mod.json`. If client-side mixins are ever needed, that config still needs to be written and added to both `fabric.mod.json`'s `mixins` array.
