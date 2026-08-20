# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

**easyWp** — a lightweight Fabric mod for Minecraft that adds persistent, per-world waypoints (create/edit/list, hide/focus, share across Overworld↔Nether, optional TP for operators). Client-side only; the common/server entrypoint does nothing but log. Mod ID: `easywp`. Licensed CC0 1.0.

This file is the sole source of technical documentation for the project.

## Repository structure

Multi-module Gradle layout declared in `settings.gradle` (`include 'common', 'versions:26.1', 'versions:26.2'`). Only two Minecraft versions are supported — there is no legacy single-module copy and no historical-version scaffolding.

- `common/src/main/java/com/easywp/EasyWp.java` — the shared/server `ModInitializer` (just logging). There is no client code in `common/` — the entire client layer (`versions/*/src/client/java/com/easywp/client/`) is intentionally duplicated per version rather than shared. This isn't just a style choice: `common/build.gradle` declares only `fabric-loader` and `slf4j-api` as dependencies, no Minecraft artifact at all, and `common` is its own real Gradle subproject with its own `:common:compileJava` task — so any class in `common/src/main/java` that imports `net.minecraft.*` (client-only or not, e.g. `BlockPos`) would fail to compile standalone, even though it would compile fine once merged into a version's own sourceSet (see below). Pinning `common` to one Minecraft version just to satisfy its own compile would be fragile (it wouldn't actually validate compatibility with the *other* version), and dropping the `java` plugin to stop compiling it standalone is a bigger structural change than the duplication it would save. Nine client files happen to be byte-identical between versions today (`Waypoint.java`, `ModConfig.java`, `I18nHelper.java`, `ModernButton.java`, `WaypointDisplayMode.java`, `WaypointSortMode.java`, `WaypointPing.java`, `UiPalette.java`, `Icons.java`) — keep them in sync by editing one, diffing, and copying to the other, not by trying to relocate them. The root `checkSharedSources` task enforces exactly that list on every `build`, so a drifted copy fails the build instead of shipping. `.gitattributes` pins the whole tree to LF precisely so that `diff` stays meaningful here.
- `common/src/main/java/com/easywp/JsonStore.java` is the exception that proves the rule above: it imports nothing but `java.nio`, so it compiles standalone against this module's Minecraft-free dependency set and both versions pick it up from the shared source directory. It is where the durable read/write for both JSON stores lives (see Persistence below). Anything else that is genuinely Minecraft-free belongs here too.
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

The mod version is global and set once in root `gradle.properties` (`mod_version=1.3.0`); each version subproject appends its own `minecraft_version_suffix` from its own `gradle.properties` to form the jar name. There is no separate lint or test task — correctness is verified via `build` (compilation) plus manual in-game testing through `runClient`. `build` does run `checkSharedSources` (see Repository structure), which fails if the duplicated client sources have drifted between the two versions; it can also be run on its own:

```bash
./gradlew checkSharedSources
```

`run/` is the dev-client sandbox (`run/saves/prueba/`, `run/config/easywp/waypoints_sp_prueba.json`) — useful as a live example of the waypoint JSON format, not something to treat as mod source.

## Architecture

### Entry points (`fabric.mod.json`, per version)

- `com.easywp.EasyWp` (in `common/`) — `ModInitializer`, server/common side, effectively a no-op (just logs startup).
- `com.easywp.client.EasyWpClient` (per-version) — `ClientModInitializer`; wires up keybindings and the renderer.
- `com.easywp.client.ModMenuIntegration` (per-version) — optional `modmenu` entrypoint returning `ModConfigScreen`. Fabric only instantiates it when Mod Menu is installed, and Mod Menu is a `clientCompileOnly` dependency (`18.0.0` for 26.1, `20.0.1` for 26.2, from the Terraformers maven), so it is never bundled. Note that Loom 1.17 exposes no `mod*` configurations at all — this project declares even Fabric API through plain `implementation`, and remapping happens anyway.

`environment` is `client`: the common entrypoint only logs, and nothing in the mod needs to run server-side.

### Data model

`Waypoint` is a POJO: `name`, `pos` (`BlockPos`), `color` (ARGB int), `dimension`, `shared` (mirrored between Overworld/Nether at a 1:8 ratio), `visible`, `focused`, `forceVisible`, `death` (marks an automatic death waypoint), `createdAtMillis`. It also carries a small render-side label cache (`cachedLabelText`/`cachedLabelWidth` plus the inputs they were built from, checked via `labelCacheMatches()`/written via `setCachedLabel()`) — riding on the waypoint itself rather than a separate map, since nothing else in the mod uses a store abstraction either, and it needs no cleanup when a waypoint is deleted (see Rendering below). The live list for the current world is held in-memory as a static `List<Waypoint>` on `WaypointRenderer`, read/written directly by the UI screens and the renderer — there is no separate state/store abstraction.

### Persistence

Two separate JSON stores, both under `<game_dir>/config/easywp/`:
- **Per world/server**: `waypoints_<worldId>.json` (`worldId` = `sp_<sanitized_world_name>`, `mp_<sanitized_ip>`, or `mp_lan`). Loaded lazily — `WaypointRenderer.checkAndLoadWorldWaypoints()` runs every render frame, but the actual `getWorldId()` computation inside it is gated on `client.level` having changed identity since the last check (true on join/dimension-change, never on an ordinary frame), so the file is only re-read when `worldId` changes. Saved with Gson pretty-printing immediately after any mutating action (create/edit/delete/visibility/focus/share) — no periodic autosave, no dirty-cache batching. `visible`/`focused` are optional/nullable in the JSON for backward compatibility with older save files.
- **Global mod settings**: `config.json`, independent of any world, managed by `ModConfig` (marker size/opacity, label display, death-waypoint behavior, confirmations, visibility persistence, ping raycast settings, waypoint-list sort mode). Grouped by feature into nested classes so new settings groups don't reshape the file; each group is null-checked on load for backward compatibility with older `config.json` files.

Both stores go through `JsonStore` (in `common/`) rather than writing straight to their destination: the document is serialized in full, written to a sibling `.tmp`, forced to disk, and then moved onto the target atomically, so a crash mid-save can no longer truncate a file that is the only copy those waypoints exist in. Every successful write first snapshots the previous revision to `<file>.bak`, and every load falls back to that snapshot when the main file is missing, empty, or unparseable. `ModConfig` deviates in one place on purpose: if neither the config nor its backup can be read it runs on in-memory defaults **without** saving, because writing would push the unreadable file into the backup slot and destroy the last good revision.

### Rendering — two genuinely different architectures per Minecraft version

This is the trickiest part of the codebase, and the reason the render types carry as much explanatory comment as they do: naive "see-through" 3D rendering gets intercepted by Iris/Oculus shaderpacks' shadow-map pass and turns markers/text pure black when occluded by a block. The reasoning behind each pipeline choice - and the failure it exists to avoid - lives in the javadoc of `WaypointRenderTypes` in each version; read those before changing anything there, since most of the obvious-looking alternatives have already been tried and rejected.

- **26.1 — "3D billboard" approach.** `WaypointRenderer.render()` hooks `LevelRenderEvents.END_MAIN`. Per waypoint: filter by dimension (recomputing position for `shared` waypoints), cull it if it's more than `CULL_MIN_DISTANCE` (8 blocks) away and its direction from the camera is more than `CULL_DOT_THRESHOLD` (dot < -0.5, ≈120°) off the camera's forward vector — a deliberately generous margin over any vanilla/dynamic FOV, computed once per frame from `cameraState.orientation` and reused for every waypoint, so a false cull (visible bug) stays far less likely than skipping the perf win on a borderline case — scale marker size by angular distance, clamp render distance to avoid float precision artifacts, sort back-to-front, then draw a single marker pass through whichever `RenderType` `WaypointRenderTypes.marker(isShaderActive)` returns for the current environment (see below) — overlapping passes were tried and rejected because they wash the marker color out. The label backdrop is hand-drawn geometry (`drawBackdrop`) rather than Font's built-in background box, for the same depth-write reason described below. The label text itself is cached on the `Waypoint` (see Data model above) and only rebuilt/re-measured when the name, uppercase setting, distance toggle, or displayed distance-in-meters actually changed, instead of concatenating and calling `font.width()` every frame regardless. Text glyphs are billboarded toward the camera via `Axis.YP`/`Axis.XP`.
- **26.2 — same 3D billboard approach, different submission path.** Minecraft 26.2's render pipeline rewrite (`SubmitNodeCollector`, see `PORTING.md`) means geometry is *queued* rather than drawn immediately, so the hook is `LevelRenderEvents.COLLECT_SUBMITS` instead of `END_MAIN`. The per-waypoint math (dimension filter, camera-angle cull, angular scaling, distance clamp, back-to-front sort, label caching) is the same as 26.1's. Differences: the label gets its own billboard transform, projected to a separate, much nearer fixed distance than the marker (`LABEL_PROJECTION_DIST`, derived from half the player's collision width — the closest a solid block can ever get to the eye standing flush against a wall, with a small margin below that), and marker/backdrop geometry is handed over as `submitCustomGeometry`/`submitText` callbacks rather than written straight into a `VertexConsumer`. The nearer projection exists only because of the depth-testing tradeoff described below: pulling the billboard closer than any reachable wall guarantees it wins the depth test unconditionally, at the cost of shrinking the glyph quads (a perspective projection cancels the shrink back out on screen, but Iris still derives per-pixel lighting from the quad's own deltas — pull the distance in too far and the letters come out grey instead of white, as documented in the constant's own comment).
- **Shader immunity comes from `WaypointRenderTypes`, not from render location.** Without a shaderpack the marker and backdrop ride a **private `RenderPipeline`** built on the same shaders as vanilla's `textSeeThrough`/`textBackgroundSeeThrough` (so Iris's shader swap and Minecraft's depth test still treat it identically to vanilla when no pack is loaded), just with the depth state overridden — see below. With a shaderpack loaded, the marker draws through a separate private `RenderPipeline` built on `RenderPipelines.BEACON_BEAM_SNIPPET`, registered with Iris **via reflection** (`IrisApi.assignPipeline(pipeline, IrisProgram.BEACON_BEAM)`) in `WaypointRenderTypes.init()`. Beacon beams are the one piece of world geometry shaderpacks keep emissive and fog-free. `init()` must run before the first frame — Iris resolves the vertex format at assignment time. If the reflection fails for any reason it silently falls back to the vanilla beacon beam render type. **The label backdrop needs the same care and gets it differently**: a private pipeline Iris was never handed is not dimmed, it is skipped entirely, so a shader-active backdrop riding one simply vanishes and leaves the glyphs unreadable against fog or sky. Rather than claim a second Iris program, both versions follow the rule that the backdrop rides whatever pipeline the glyphs ride while a pack is loaded - `RenderTypes.textBackgroundSeeThrough()` in 26.1, `RenderTypes.textPolygonOffset(...)` in 26.2 - which costs the depth write (clouds/water paint over the backdrop again, but only with shaders on) and guarantees the box and the letters can never disagree about being drawn. `labelBackdrop(shaderPackActive)` picks between that and the depth-writing private pipeline. Both versions render in 3D world space through `LevelRenderer`; there is no HUD-space rendering anywhere in this mod.
- **Depth state: test always passes, but the pipelines now write depth.** Every *custom* pipeline in `WaypointRenderTypes` — the marker always, and the backdrop except in 26.2's shader path — uses `DepthStencilState(CompareOp.ALWAYS_PASS, /*write=*/true)`: never occluded by solid terrain (the test always passes), but leaving a real depth value at their own (deliberately near, ~4 block) projected distance. This matters because Minecraft renders clouds and translucent terrain (water) into their *own* render targets, separate from the one the marker draws into, and composites them back over the main target afterward using depth. A pipeline that never writes depth (the previous behavior, and still how vanilla's own see-through text types work) leaves nothing there for that composite to test against, so clouds/water painted later in the frame simply draw over the marker/label regardless of how close they actually are — writing depth gives the compositor something real to test against instead. The one exception is 26.2's label glyphs (and, riding along with them, its shader-mode backdrop) under a shaderpack: those ride vanilla's own `textPolygonOffset`, which *does* depth-test, by design — see `LABEL_PROJECTION_DIST` above for why that is safe rather than a regression.
- `ShaderDetector` detects an active shaderpack via reflection (tries modern Iris, then legacy Oculus/Iris, then OptiFine; returns `false` with no exception if none are present), caching the result for 1 second.

### UI

Hand-built on vanilla `Screen`, no third-party UI toolkit. Shared widget is `ModernButton` (fluent builder: `ModernButton.modernBuilder(...).pos(x,y).size(w,h).build()`), with normal/hover/disabled states in a near-black translucent palette centralized in `UiPalette` (color constants only, no Minecraft imports, so it stays trivially byte-identical across versions). GUI icons under `textures/gui/` are drawn as plain white masks and tinted at draw time via `Icons`' `drawMarkerIcon`/`drawPortalIcon` helpers and the tinted 11-arg `GuiGraphicsExtractor.blit(..., int color)` overload (multiplicative: `result = texture × color`), rather than baking color into each PNG — this is also how the waypoint-list marker swatch and the focus button get colored with the waypoint's own ARGB color instead of drawing a flat `fill()` square. Three screens:
- `WaypointCreateScreen` — create/edit modal (name, X/Y/Z, 56-color picker grid, dimension-share toggle, Enter-to-save). The same constructor path is used to pre-fill coordinates from the player's feet (`N` key) or from a crosshair raycast (`V` key, see `WaypointPing`) — the screen itself is agnostic to where the `BlockPos` came from.
- `WaypointListScreen` — searchable/paginated list with per-row TP/focus/show-hide/edit/share-to-chat/toggle-shared/delete actions; TP is gated on `Permissions.COMMANDS_GAMEMASTER`. Row order is driven by `WaypointSortMode`, cycled with the button beside the search box and persisted in `ModConfig.list.sortMode`; its default `CREATED` deliberately performs no sort at all, since waypoints are appended on creation and reloaded in the same order, so insertion order already is creation order. Sorting is applied when the list is rebuilt, not per frame, so `DISTANCE` does not reshuffle rows while the player walks.
- `ModConfigScreen` — mod settings, built on vanilla's own `OptionsSubScreen` + `OptionsList` (the same scrollable widget Minecraft's Video Settings/Controls screens use) rather than `ModernButton`, so it looks and scrolls like a native options screen. One setting per row (control + its own "Reset" button), grouped under section headers, backed by `ModConfig`.

Related classes not previously listed here: `DeathWaypointManager` (creates a temporary waypoint on player death, deletes it once the player stands near it again for a grace period), `ModConfig`/`ModConfigScreen` (settings, see Persistence above), `WaypointDisplayMode` (the visibility-cycle enum driving the toggle key), `WaypointRenderTypes` (per-environment marker `RenderType` selection, see Rendering above).

### i18n

`I18nHelper` does **manual** translation via a `switch` over UI strings (English/Spanish), detecting the client's language with a fallback to `en_us`. This is separate from the `.lang`/`Component.translatable` files under `assets/easywp/lang/` (which only cover keybinding names) — adding a new UI language means extending the `switch` in `I18nHelper`, not just adding a lang JSON file.

### Mixins

The mod injects no mixins at all. One mixin config exists and is registered: `easywp.mixins.json` (package `com.easywp.mixin`, common/server, empty), shared via `common/src/main/resources/` and requiring `JAVA_25` compatibility with `requireAnnotations: true` — kept as a wired-up extension point rather than deleted, so adding a common/server mixin needs no new plumbing. There is no client-side config: if client mixins are ever needed, `easywp.client.mixins.json` has to be written and added to both `fabric.mod.json`'s `mixins` array.
