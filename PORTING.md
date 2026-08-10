# Porting Guide & Migration Notes (Minecraft 26.1 to 26.2)

This document records the exact multi-module architecture, dependencies, and API migrations implemented when supporting Minecraft 26.2 alongside Minecraft 26.1.x.

---

## 🛠️ Multi-Module Project Architecture (Gradle Option B)

The project has been structured into an isolated Gradle multi-module layout so that both `26.1.x` and `26.2` builds coexist cleanly on the same Git branch without breaking backwards compatibility:

```text
easywp-template/
├── common/                                 <-- Shared domain data & helpers (:common)
│   └── src/main/java/com/easywp/           (Waypoint, I18nHelper, assets)
├── versions/
│   ├── 26.1/                               <-- Target subproject for Minecraft 26.1.x (:versions:26.1)
│   │   ├── gradle.properties               (minecraft_version=26.1.2, fabric_api=0.155.2+26.1.2)
│   │   └── src/client/java/com/easywp/
│   └── 26.2/                               <-- Target subproject for Minecraft 26.2 (:versions:26.2)
│       ├── gradle.properties               (minecraft_version=26.2, fabric_api=0.156.0+26.2)
│       └── src/client/java/com/easywp/
├── build.gradle
├── gradle.properties
└── settings.gradle                         (include 'common', 'versions:26.1', 'versions:26.2')
```

---

## 📦 Tooling & Dependency Versions for 26.2

| Tool / Dependency | Version | Notes |
| :--- | :--- | :--- |
| **Gradle** | `9.5.1` | Configured in `gradle-wrapper.properties` |
| **Java SDK** | `25` | `sourceCompatibility` & `targetCompatibility` = 25 |
| **Minecraft** | `26.2` | Target version for `:versions:26.2` |
| **Fabric Loader** | `0.19.3` | Minimum loader requirement |
| **Fabric Loom** | `1.17-SNAPSHOT` | Loom plugin version |
| **Fabric API** | `0.156.0+26.2` | Dedicated build for MC 26.2 |
| **Mappings** | Mojang Official | Official Mojang Mappings (Yarn deprecated since 26.1) |

---

## 🔄 API Migration Log (26.1.x -> 26.2)

### 1. `Minecraft.setScreen` renamed / updated
- **Old (26.1.x)**: `client.setScreen(screen)`
- **New (26.2)**: `client.setScreenAndShow(screen)`
- **Files Modified**:
  - [ModKeyBindings.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/ModKeyBindings.java)
  - [WaypointCreateScreen.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/WaypointCreateScreen.java)
  - [WaypointListScreen.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/WaypointListScreen.java)

### 2. `Gui.setOverlayMessage` relocated to `Hud`
- **Old (26.1.x)**: `client.gui.setOverlayMessage(component, animate)`
- **New (26.2)**: `client.gui.hud.setOverlayMessage(component, animate)`
- **Files Modified**:
  - [ModKeyBindings.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/ModKeyBindings.java)
  - [WaypointCreateScreen.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/WaypointCreateScreen.java)

### 3. Blaze3D Engine & Fabric LevelRenderContext Refactor (`SubmitNodeCollector`)
- **Old (26.1.x)**:
  `context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH)` and manual `font.drawInBatch(...)` followed by `context.bufferSource().endBatch(...)`.
- **New (26.2)**:
  Minecraft 26.2 introduces `SubmitNodeCollector` and `OrderedSubmitNodeCollector`.
  - **Custom Geometry**:
    ```java
    context.submitNodeCollector().submitCustomGeometry(
        poseStack,
        WAYPOINT_SEE_THROUGH,
        (pose, buffer) -> drawMarker(pose.pose(), buffer, r, g, b, 255, markerSize, false)
    );
    ```
  - **Text & Outline Submission**:
    ```java
    context.submitNodeCollector().submitText(
        poseStack,
        xOffset, 0.0f,
        Component.literal(nameText).getVisualOrderText(),
        false,
        Font.DisplayMode.SEE_THROUGH,
        0xF000F0,
        0xFFFFFFFF,
        0x40000000,
        0
    );
    ```
- **Files Modified**:
  - [WaypointRenderer.java](file:///d:/Proyectos/easywp-template-26.1.2/versions/26.2/src/client/java/com/easywp/client/WaypointRenderer.java)

---

## 🚀 How to Build Both Artifacts

Run from the repository root:
```bash
./gradlew build
```

Generated outputs:
- **MC 26.1.x JAR**: `versions/26.1/build/libs/easywp-1.2.0+26.1.x.jar`
- **MC 26.2 JAR**: `versions/26.2/build/libs/easywp-1.2.0+26.2.jar`
