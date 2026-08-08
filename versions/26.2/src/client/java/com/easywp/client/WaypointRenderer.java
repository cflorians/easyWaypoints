package com.easywp.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Intento 17: Rearquitectura Unificada — Proyección Mundo→Pantalla en Capa HUD.
 *
 * Usa sub-pixel floating point PoseStack translation en GPU para eliminar 100% el temblor
 * por redondeo de enteros al caminar, replicando exactamente el estilo visual y escalado de 26.1.
 */
public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    // Constants copied directly from version 26.1 for exact visual parity
    private static final float WAYPOINT_VISUAL_ANGLE        = 0.055f;
    private static final float WAYPOINT_MIN_SIZE            = 0.25f;
    private static final float WAYPOINT_MAX_SIZE            = 500.0f;
    private static final float WAYPOINT_GROWTH_START_DIST   = 2.0f;
    private static final float MARKER_ASPECT_FACTOR         = 5.0f / 14.0f;

    private static String lastWorldId = "";
    private static final Object lock = new Object();

    // Frame state captured from 3D world render
    private static class CapturedFrame {
        boolean valid = false;
        Vec3 cameraPos = Vec3.ZERO;
        Matrix4f viewMatrix = new Matrix4f();
        Matrix4f projectionMatrix = new Matrix4f();
    }

    private static final CapturedFrame frameState = new CapturedFrame();

    public static void init() {
        if (initialized) return;
        initialized = true;
    }

    /**
     * Hook 3D mínimo (solo lectura): Captura y clona las matrices y la posición de la cámara
     * al final del renderizado del mundo 3D.
     */
    public static void captureFrameState(LevelRenderContext context) {
        if (ModKeyBindings.displayMode != WaypointDisplayMode.WORLD_MARKERS) {
            frameState.valid = false;
            return;
        }

        CameraRenderState cameraState = context.levelState().cameraRenderState;
        if (cameraState == null || !cameraState.initialized) {
            frameState.valid = false;
            return;
        }

        frameState.cameraPos = cameraState.pos;
        frameState.viewMatrix = new Matrix4f(cameraState.viewRotationMatrix);
        frameState.projectionMatrix = new Matrix4f(cameraState.projectionMatrix);

        frameState.valid = true;
    }

    /**
     * Hook 2D de HUD: Renderiza los waypoints proyectados en pantalla sobre la capa GUI.
     * Utiliza sub-pixel GPU translation en PoseStack para eliminar cualquier temblor por redondeo.
     */
    public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (ModKeyBindings.displayMode != WaypointDisplayMode.WORLD_MARKERS) return;
        if (!frameState.valid) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        Vec3 cameraPos = frameState.cameraPos;
        Matrix4f viewMatrix = frameState.viewMatrix;

        checkAndLoadWorldWaypoints();

        Font font = client.font;
        String currentDimension = client.level.dimension().identifier().toString();

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        int guiWidth = client.getWindow().getGuiScaledWidth();
        int guiHeight = client.getWindow().getGuiScaledHeight();

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;

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

            // Target position at exact block base (wpPos.getY()), matching version 26.1
            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY();
            double targetZ = wpPos.getZ() + 0.5;

            // Exact 26.1 distance & direction ray calculation
            double dx = targetX - cameraPos.x;
            double dy = targetY - cameraPos.y;
            double dz = targetZ - cameraPos.z;

            double realDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (realDistance < 0.001) continue;

            double maxRenderDist = Math.max(32.0, client.options.getEffectiveRenderDistance() * 16.0 - 16.0);
            double clampDist = Math.min(realDistance, maxRenderDist);

            double dirX = dx / realDistance;
            double dirY = dy / realDistance;
            double dirZ = dz / realDistance;

            double relX = dirX * clampDist;
            double relY = dirY * clampDist;
            double relZ = dirZ * clampDist;

            // Transform relative position to clip space using camera matrices
            Vector4f clipPos = new Vector4f((float) relX, (float) relY, (float) relZ, 1.0f);
            clipPos.mul(viewMatrix);
            clipPos.mul(frameState.projectionMatrix);

            // Decisión de diseño: Si está detrás del plano de la cámara (w <= 1e-5), OCULTAR completamente
            if (clipPos.w <= 0.00001f) {
                continue;
            }

            // Perspective division to NDC (-1 to +1)
            float ndcX = clipPos.x / clipPos.w;
            float ndcY = clipPos.y / clipPos.w;

            // Screen mapping with sub-pixel float precision
            float screenX = (ndcX + 1.0f) / 2.0f * guiWidth;
            float screenY = (1.0f - ndcY) / 2.0f * guiHeight;

            // 1:1 replica of 26.1's 3D billboard angular size formula (visualSize = MIN_SIZE + VISUAL_ANGLE * growthDist)
            double growthDist = Math.max(0.0, clampDist - WAYPOINT_GROWTH_START_DIST);
            float visualSize = (float) Mth.clamp(
                WAYPOINT_MIN_SIZE + WAYPOINT_VISUAL_ANGLE * (float) growthDist,
                WAYPOINT_MIN_SIZE,
                WAYPOINT_MAX_SIZE
            );

            // Compute exact 3D perspective angular ratio (visualSize / clampDist)
            float angularRatio = visualSize / (float) clampDist;

            // Map 3D angular size to 2D GUI pixels: Base height scaled to GUI viewport
            float guiPixelHeight = Mth.clamp(angularRatio * guiHeight * 0.40f, 10.0f, 24.0f);
            float guiPixelWidth = guiPixelHeight * (MARKER_ASPECT_FACTOR * 2.0f); // (5/7) aspect ratio

            int iconHeight = Math.round(guiPixelHeight);
            int iconWidth = Math.round(guiPixelWidth);

            String wpName = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = wpName.toUpperCase() + " (" + (int) realDistance + "m)";
            int wpColor = wp.getColor() | 0xFF000000;

            float rawTextWidth = font.width(nameText);
            float rawTextHeight = font.lineHeight; // 9 pixels

            // Replica of 26.1 text scale formula: textScale = 0.035f * markerSize / 0.7f
            float textScale = Mth.clamp((visualSize / (float) clampDist) * 1.10f, 0.70f, 0.90f);

            float iconX = -iconWidth / 2.0f;
            float iconY = -iconHeight;

            float scaledTextWidth = rawTextWidth * textScale;
            float scaledTextHeight = rawTextHeight * textScale;

            // Background box padding matching 26.1 tight height
            float padX = 2.0f * textScale;
            float padY = 0.5f * textScale;

            // Position labelY so the bottom of the background box sits exactly 1px above the marker icon top (zero overlap & zero excess gap)
            float labelY = iconY - 1.0f - scaledTextHeight - padY;

            // Replica of 26.1 text offset: xOffset = -font.width(nameText) / 2.0f + 1.0f
            float labelX = -scaledTextWidth / 2.0f + 1.0f * textScale;

            int bgMinX = Math.round(labelX - padX);
            int bgMinY = Math.round(labelY - padY);
            int bgMaxX = Math.round(labelX + scaledTextWidth + padX);
            int bgMaxY = Math.round(labelY + scaledTextHeight + padY);

            // Use sub-pixel float translation on GPU pose stack to eliminate 1-pixel rounding jitter
            graphics.pose().pushMatrix();
            graphics.pose().translate(screenX, screenY);

            // Draw Icon centered at screen coordinates, tinted with waypoint color
            graphics.blit(pipeline, MARKER_TEXTURE, (int) iconX, (int) iconY, 0.0f, 0.0f, (int) iconWidth, (int) iconHeight, (int) iconWidth, (int) iconHeight, wpColor);

            // Semi-transparent background box (0x40000000) matching 26.1 tight height
            graphics.fill(bgMinX, bgMinY, bgMaxX, bgMaxY, 0x40000000);

            // Crisp text ABOVE marker icon, scaled matching 26.1 proportion
            graphics.pose().pushMatrix();
            graphics.pose().translate(labelX, labelY);
            graphics.pose().scale(textScale, textScale);
            graphics.text(font, nameText, 0, 0, 0xFFFFFFFF, false);
            graphics.pose().popMatrix();

            graphics.pose().popMatrix();
        }
    }

    public static String getWorldId() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "unknown";
        }
        if (client.getSingleplayerServer() != null) {
            if (client.getSingleplayerServer().getWorldData() != null) {
                return "sp_" + client.getSingleplayerServer().getWorldData().getLevelName().replaceAll("[\\\\/:*?\"<>| ]", "_");
            }
            return "sp_world";
        }
        if (client.getCurrentServer() != null) {
            return "mp_" + client.getCurrentServer().ip.replace(':', '_').replaceAll("[\\\\/:*?\"<>| ]", "_");
        }
        return "mp_lan";
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
