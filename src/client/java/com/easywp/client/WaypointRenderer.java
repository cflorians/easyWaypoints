package com.easywp.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    // =========================================================================
    //  PASO 2 — Desactivación del Depth Test (Depth Test Bypass)
    // =========================================================================
    //
    // RenderTypes.textSeeThrough() usa internamente RenderPipelines.TEXT_SEE_THROUGH,
    // el cual está construido con TEXT_SNIPPET y declara:
    //
    //     .withDepthStencilState(Optional.empty())
    //
    // Esto equivale a un depth function GL_ALWAYS: el fragmento siempre pasa el
    // depth test y se dibuja SOBRE cualquier geometría ya renderizada, sin
    // importar qué hay en el depth buffer.
    //
    // Además, al no escribir al depth buffer (depthWrite=false implícito por
    // Optional.empty), el resto del renderizado del mundo no queda afectado.
    //
    // PASO 4 — Persistencia de Color / Shader Emissive / Sin Niebla
    // =========================================================================
    //
    // TEXT_SEE_THROUGH usa TEXT_SNIPPET, que NO incluye FOG_SNIPPET ni
    // MATRICES_FOG_SNIPPET. El shader "core/rendertype_text_see_through" NO
    // realiza ningún cálculo de fog, iluminación ambiental ni sombras: solo
    // hace texture(Sampler0, uv) * vertexColor. Comportamiento equivalente a
    // un material "unlit/emissive" en términos de gráficos 3D modernos.
    //
    // El lightmap UV (0xF000F0 = 240/240) garantiza que el muestreador de
    // iluminación obtenga siempre el valor máximo de brillo en cualquier
    // shader que lo consulte, lo que actuaría como respaldo adicional.
    public static final RenderType WAYPOINT_SEE_THROUGH = RenderTypes.textSeeThrough(MARKER_TEXTURE);
    public static final RenderType WAYPOINT_VISIBLE     = RenderTypes.text(MARKER_TEXTURE);

    // =========================================================================
    //  PASO 3 — Escalado Proporcional por Distancia (tamaño angular constante)
    // =========================================================================
    //
    // En proyección perspectiva, el tamaño aparente en pantalla de un objeto de
    // tamaño world-space S a distancia d es proporcional a S/d.
    //
    // Para que el tamaño aparente (angular) sea CONSTANTE, necesitamos:
    //     S = VISUAL_ANGLE * d
    //
    // Donde VISUAL_ANGLE es el ángulo semiabarcado del icono (en radianes).
    // Con este esquema, la pantalla siempre verá el icono con el mismo ángulo
    // visual sin importar la distancia (1 bloque o 100.000 bloques).
    //
    // Ángulo elegido: 0.038 rad ≈ 2.2 grados — visible pero no invasivo.
    // Este valor produce:
    //   d=10:    markerSize = 0.38  → tamaño mínimo (se aplica MIN_SIZE).
    //   d=100:   markerSize = 3.8   → aprox. igual que antes del cambio.
    //   d=1000:  markerSize = 38.0  → misma apariencia angular que d=100.
    //   d=10000: markerSize = 380.0 → misma apariencia angular que d=100.
    private static final float WAYPOINT_VISUAL_ANGLE = 0.038f;  // radianes
    private static final float WAYPOINT_MIN_SIZE     = 0.5f;    // mínimo world-space para waypoints muy cercanos
    private static final float WAYPOINT_MAX_SIZE     = 500.0f;  // techo de seguridad para distancias extremas

    private static String lastWorldId = "";
    private static final Object lock = new Object();

    public static void init() {
        if (initialized) return;
        initialized = true;
    }

    /**
     * Renderiza billboards 3D de los waypoints en modo WORLD_MARKERS.
     *
     * Implementa los 4 pasos de visibilidad de largo alcance:
     *
     * PASO 1 — Inyección en la Cola de Renderizado
     *   Este método es invocado desde el evento {@code LevelRenderContext}, el cual
     *   dispara después de que el mundo (chunks, entidades, efectos de niebla,
     *   shaders atmosféricos) ha terminado su render pass. Los waypoints se
     *   dibujan en la fase final del render del nivel, garantizando que se
     *   superpongan sobre TODA la geometría del mundo.
     *
     * PASO 2 — Depth Test Bypass (ver constantes WAYPOINT_SEE_THROUGH)
     *   El RenderType TEXT_SEE_THROUGH deshabilita el depth test con
     *   Optional.empty() en DepthStencilState, lo que es equivalente a
     *   GL_ALWAYS. Los iconos siempre pasan el depth test.
     *
     * PASO 3 — Escalado Proporcional por Distancia (ver constantes)
     *   markerSize = max(MIN, min(MAX, VISUAL_ANGLE * distance))
     *   La proyección perspectiva convierte este valor en tamaño constante.
     *
     * PASO 4 — Emissive / Sin Niebla (ver constantes WAYPOINT_SEE_THROUGH)
     *   El shader text_see_through no incluye FOG_SNIPPET. Color puro.
     */
    public static void render(LevelRenderContext context) {
        if (ModKeyBindings.displayMode != WaypointDisplayMode.WORLD_MARKERS) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        checkAndLoadWorldWaypoints();

        Font font = client.font;
        CameraRenderState cameraState = context.levelState().cameraRenderState;
        if (cameraState == null || !cameraState.initialized) return;

        Vec3 cameraPos = cameraState.pos;
        // playerEyePos: posición estable del ojo sin head-bob, usada para
        // calcular la distancia real al waypoint.
        Vec3 playerEyePos = client.player.getEyePosition();
        PoseStack poseStack = context.poseStack();
        String currentDimension = client.level.dimension().identifier().toString();

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }



        for (Waypoint wp : waypoints) {
            if (wp == null || !wp.isVisible()) continue;
            if (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible()) continue;

            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            BlockPos wpPos = null;

            if (wpDim.equals(currentDimension)) {
                wpPos = wp.getPos();
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && currentDimension.equals("minecraft:the_nether")) {
                    wpPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                } else if (wpDim.equals("minecraft:the_nether") && currentDimension.equals("minecraft:overworld")) {
                    wpPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                }
            }

            if (wpPos == null) continue;

            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY();
            double targetZ = wpPos.getZ() + 0.5;

            // Dirección desde el ojo estable al waypoint (sin head-bob).
            double dirX = targetX - playerEyePos.x;
            double dirY = targetY - playerEyePos.y;
            double dirZ = targetZ - playerEyePos.z;
            double distance = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);


            // Posición relativa a cameraPos para el poseStack del nivel.
            // cameraPos incluye el offset de head-bob, por eso compensamos
            // con el offset entre playerEyePos y cameraPos.
            double bobOffsetX = playerEyePos.x - cameraPos.x;
            double bobOffsetY = playerEyePos.y - cameraPos.y;
            double bobOffsetZ = playerEyePos.z - cameraPos.z;

            double renderX = dirX + bobOffsetX;
            double renderY = dirY + bobOffsetY;
            double renderZ = dirZ + bobOffsetZ;

            // ── PASO 3: Escalado proporcional por distancia ────────────────────
            // markerSize = VISUAL_ANGLE * distance garantiza tamaño aparente
            // constante en pantalla a CUALQUIER distancia por proyección perspectiva.
            // Se aplica un mínimo para waypoints muy cercanos y un techo de seguridad.
            float markerSize = (float) Mth.clamp(
                    WAYPOINT_VISUAL_ANGLE * distance,
                    WAYPOINT_MIN_SIZE,
                    WAYPOINT_MAX_SIZE
            );

            String wpName  = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = wpName.toUpperCase() + " (" + (int) distance + "m)";

            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8)  & 0xFF;
            int b = wpColor         & 0xFF;

            // ── Billboard 3D en posición real ─────────────────────────────────
            poseStack.pushPose();
            poseStack.translate(renderX, renderY, renderZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            // Dibujar el marcador (icono de waypoint)
            VertexConsumer bufferSeeThrough = context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH);
            drawMarker(poseStack, bufferSeeThrough, r, g, b, 255, markerSize, false);
            context.bufferSource().endBatch(WAYPOINT_SEE_THROUGH);

            // Etiqueta de texto encima del marcador
            poseStack.pushPose();
            poseStack.translate(0.0f, markerSize * 1.50f, 0.0f);
            // textScale: proporcional a markerSize, que ya escala linealmente con
            // distance. La razón textScale/distance es constante → texto siempre
            // legible. No se necesita corrección extra para grandes distancias.
            float textScale = 0.035f * markerSize / 0.7f;
            poseStack.scale(-textScale, -textScale, textScale);
            float xOffset = -font.width(nameText) / 2.0f + 1.0f;

            // Pasada A: placa de fondo semi-transparente (texto con alfa=0 para
            //           evitar letras duplicadas sobre el fondo).
            font.drawInBatch(
                    nameText, xOffset, 0.0f,
                    0x00FFFFFF, false,
                    poseStack.last().pose(), context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0
            );

            // Pasada B: texto sólido blanco, ligeramente por delante en Z.
            poseStack.pushPose();
            poseStack.translate(0.0f, 0.0f, -0.03f);
            font.drawInBatch(
                    nameText, xOffset, 0.0f,
                    0xFFFFFFFF, false,
                    poseStack.last().pose(), context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0
            );
            poseStack.popPose();

            context.bufferSource().endBatch();
            poseStack.popPose(); // text
            poseStack.popPose(); // billboard

            context.bufferSource().endBatch();
        }

        context.bufferSource().endBatch();
    }

    private static void drawMarker(PoseStack poseStack, VertexConsumer buffer, int r, int g, int b, int a, float size, boolean hasOverlayAndNormal) {
        Matrix4f poseMatrix = poseStack.last().pose();
        float halfWidth = size * (5.0f / 7.0f) / 2.0f;
        int lightmap = 240;

        if (hasOverlayAndNormal) {
            buffer.addVertex(poseMatrix, -halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 0.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            buffer.addVertex(poseMatrix, halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 0.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            buffer.addVertex(poseMatrix, halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 1.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            buffer.addVertex(poseMatrix, -halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 1.0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
        } else {
            buffer.addVertex(poseMatrix, -halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 0.0f)
                    .setUv2(lightmap, lightmap);
            buffer.addVertex(poseMatrix, halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 0.0f)
                    .setUv2(lightmap, lightmap);
            buffer.addVertex(poseMatrix, halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 1.0f)
                    .setUv2(lightmap, lightmap);
            buffer.addVertex(poseMatrix, -halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 1.0f)
                    .setUv2(lightmap, lightmap);
        }
    }

    public static String getWorldId() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "unknown";
        }
        if (client.isSingleplayer()) {
            if (client.getSingleplayerServer() != null && client.getSingleplayerServer().getWorldData() != null) {
                return "sp_" + client.getSingleplayerServer().getWorldData().getLevelName().replaceAll("[\\\\/:*?\"<>| ]", "_");
            }
            return "sp_world";
        } else {
            if (client.getCurrentServer() != null) {
                return "mp_" + client.getCurrentServer().ip.replace(':', '_').replaceAll("[\\\\/:*?\"<>| ]", "_");
            }
            return "mp_lan";
        }
    }

    public static void checkAndLoadWorldWaypoints() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        String currentWorld = getWorldId();
        if (!currentWorld.equals("unknown") && !currentWorld.equals(lastWorldId)) {
            synchronized (lock) {
                lastWorldId = currentWorld;
                loadFromFile();
            }
        }
    }

    public static void saveToFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        File configFile = new File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        try {
            configFile.getParentFile().mkdirs();
            List<WaypointData> dataList = new ArrayList<>();
            for (Waypoint wp : waypoints) {
                dataList.add(new WaypointData(wp));
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(dataList, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void loadFromFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        File configFile = new File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        waypoints.clear();
        if (!configFile.exists()) return;

        try {
            Gson gson = new Gson();
            try (FileReader reader = new FileReader(configFile)) {
                Type listType = new TypeToken<List<WaypointData>>(){}.getType();
                List<WaypointData> dataList = gson.fromJson(reader, listType);
                if (dataList != null) {
                    for (WaypointData data : dataList) {
                        waypoints.add(data.toWaypoint());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class WaypointData {
        String name;
        int x;
        int y;
        int z;
        int color;
        String dimension;
        boolean shared;
        Boolean visible;
        Boolean focused;

        public WaypointData(Waypoint wp) {
            this.name = wp.getName();
            this.x = wp.getPos().getX();
            this.y = wp.getPos().getY();
            this.z = wp.getPos().getZ();
            this.color = wp.getColor();
            this.dimension = wp.getDimension();
            this.shared = wp.isShared();
            this.visible = wp.isVisible();
            this.focused = wp.isFocused();
        }

        public Waypoint toWaypoint() {
            return new Waypoint(
                name,
                new BlockPos(x, y, z),
                color,
                dimension,
                shared,
                visible == null ? true : visible,
                focused == null ? false : focused
            );
        }
    }
}
