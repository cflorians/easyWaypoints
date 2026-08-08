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

/**
 * Handles 3D world rendering and JSON storage for waypoints.
 */
public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    // RenderTypes for see-through text and shaderpack beacon beam compatibility
    public static final RenderType WAYPOINT_SEE_THROUGH = RenderTypes.textSeeThrough(MARKER_TEXTURE);
    public static final RenderType WAYPOINT_VISIBLE     = RenderTypes.text(MARKER_TEXTURE);
    public static final RenderType WAYPOINT_SHADER_COMPAT = RenderTypes.beaconBeam(MARKER_TEXTURE, true);

    // Scaling constants for angular distance sizing
    private static final float WAYPOINT_VISUAL_ANGLE        = 0.055f;
    private static final float WAYPOINT_MIN_SIZE            = 0.25f;
    private static final float WAYPOINT_MAX_SIZE            = 500.0f;
    private static final float WAYPOINT_GROWTH_START_DIST   = 2.0f;
    private static final float MARKER_ASPECT_FACTOR         = 5.0f / 14.0f; // Pre-calculated (5/7) / 2

    private static String lastWorldId = "";
    private static final Object lock = new Object();

    private static class WaypointHolder {
        Waypoint waypoint;
        double realDistance;
        double renderX;
        double renderY;
        double renderZ;
        float markerSize;

        public void set(Waypoint waypoint, double realDistance, double renderX, double renderY, double renderZ, float markerSize) {
            this.waypoint = waypoint;
            this.realDistance = realDistance;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.markerSize = markerSize;
        }
    }

    private static final List<WaypointHolder> activeWaypoints = new ArrayList<>();
    private static final List<WaypointHolder> holderPool = new ArrayList<>();

    public static void init() {
        if (initialized) return;
        initialized = true;
    }

    /**
     * Renders 3D billboards for waypoints in world space.
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
        PoseStack poseStack = context.poseStack();
        String currentDimension = client.level.dimension().identifier().toString();

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        activeWaypoints.clear();
        int poolIndex = 0;

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

            double renderX = dirX * clampDist;
            double renderY = dirY * clampDist;
            double renderZ = dirZ * clampDist;

            double growthDist = Math.max(0.0, clampDist - WAYPOINT_GROWTH_START_DIST);
            float markerSize = (float) Mth.clamp(
                    WAYPOINT_MIN_SIZE + WAYPOINT_VISUAL_ANGLE * (float) growthDist,
                    WAYPOINT_MIN_SIZE,
                    WAYPOINT_MAX_SIZE
            );

            WaypointHolder holder;
            if (poolIndex < holderPool.size()) {
                holder = holderPool.get(poolIndex);
            } else {
                holder = new WaypointHolder();
                holderPool.add(holder);
            }
            poolIndex++;

            holder.set(wp, realDistance, renderX, renderY, renderZ, markerSize);
            activeWaypoints.add(holder);
        }

        // Sort farther waypoints first for correct depth ordering
        activeWaypoints.sort((a, b) -> Double.compare(b.realDistance, a.realDistance));

        boolean isShaderActive = ShaderDetector.isShaderPackActive();

        for (WaypointHolder holder : activeWaypoints) {
            Waypoint wp = holder.waypoint;
            double realDistance = holder.realDistance;
            double renderX = holder.renderX;
            double renderY = holder.renderY;
            double renderZ = holder.renderZ;
            float markerSize = holder.markerSize;

            String wpName  = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = wpName.toUpperCase() + " (" + (int) realDistance + "m)";

            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8)  & 0xFF;
            int b = wpColor         & 0xFF;

            poseStack.pushPose();
            poseStack.translate(renderX, renderY, renderZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            // Standard see-through render pass
            VertexConsumer bufferSeeThrough = context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH);
            drawMarker(poseStack, bufferSeeThrough, r, g, b, 255, markerSize, false);
            context.bufferSource().endBatch(WAYPOINT_SEE_THROUGH);

            // Shaderpack fallback render pass
            if (isShaderActive) {
                VertexConsumer bufferShader = context.bufferSource().getBuffer(WAYPOINT_SHADER_COMPAT);
                drawMarker(poseStack, bufferShader, r, g, b, 255, markerSize, true);
                context.bufferSource().endBatch(WAYPOINT_SHADER_COMPAT);
            }

            // Text label
            poseStack.pushPose();
            poseStack.translate(0.0f, markerSize * 1.50f, 0.0f);
            float textScale = 0.035f * markerSize / 0.7f;
            poseStack.scale(-textScale, -textScale, textScale);
            float xOffset = -font.width(nameText) / 2.0f + 1.0f;

            // Background shadow pass
            font.drawInBatch(
                    nameText, xOffset, 0.0f,
                    0x00FFFFFF, false,
                    poseStack.last().pose(), context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0x40000000, 0xF000F0
            );

            // Foreground text pass
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
            poseStack.popPose();
            poseStack.popPose();
        }

        context.bufferSource().endBatch();
    }

    private static void drawMarker(PoseStack poseStack, VertexConsumer buffer, int r, int g, int b, int a, float size, boolean hasOverlayAndNormal) {
        Matrix4f poseMatrix = poseStack.last().pose();
        float halfWidth = size * MARKER_ASPECT_FACTOR;
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
