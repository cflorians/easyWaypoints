package com.easywp.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    // Define the custom marker texture path
    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    public static final RenderType WAYPOINT_SEE_THROUGH = RenderTypes.textSeeThrough(MARKER_TEXTURE);
    public static final RenderType WAYPOINT_VISIBLE = RenderTypes.text(MARKER_TEXTURE);

    public static void init(){
        if (initialized) return;
        initialized = true;
    }

    public static void render(LevelRenderContext context){
        if (!ModKeyBindings.showWaypoints) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        // Automatically load world-specific waypoints if we switched worlds/servers
        checkAndLoadWorldWaypoints();

        Font font = client.font;
        CameraRenderState cameraState = context.levelState().cameraRenderState;
        if (cameraState == null || !cameraState.initialized) return;

        Vec3 cameraPos = cameraState.pos;
        PoseStack poseStack = context.poseStack();

        String currentDimension = client.level.dimension().identifier().toString();

        // Focus Mode check: see if there is at least one focused waypoint
        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        for (Waypoint wp : waypoints) {
            if (wp == null) {
                continue;
            }
            // Apply Mute/Hide and Focus filters
            if (!wp.isVisible()) {
                continue;
            }
            if (hasAnyFocused) {
                if (!wp.isFocused() && !wp.isForceVisible()) {
                    continue;
                }
            }

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

            if (wpPos == null) {
                continue;
            }
            
            // Tip of the arrow will point at the bottom center of the block (Y + 0.0, at the feet level)
            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY();
            double targetZ = wpPos.getZ() + 0.5;

            double relX = targetX - cameraPos.x;
            double relY = targetY - cameraPos.y;
            double relZ = targetZ - cameraPos.z;

            double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
            double renderDistanceBlocks = client.options.renderDistance().get() * 16.0;

            float realMarkerSize = (float) (0.5 + distance / 20.0) * 0.7f;

            // For waypoints beyond 12 blocks, we compute a projected position at 12 blocks
            // along the same direction vector to bypass volumetric shader fog.
            // The visible pass always renders at the REAL position for GPU pixel-perfect depth testing.
            double projRenderX = relX;
            double projRenderY = relY;
            double projRenderZ = relZ;
            float projMarkerSize = realMarkerSize;

            if (distance > 12.0) {
                double projectionDistance = 12.0;
                double scaleFactor = projectionDistance / distance;
                projRenderX = relX * scaleFactor;
                projRenderY = relY * scaleFactor;
                projRenderZ = relZ * scaleFactor;
                projMarkerSize = (float) (realMarkerSize * scaleFactor);
            }

            // The visible pass draws at the real position only if chunks are loaded there
            boolean drawVisiblePass = (distance <= renderDistanceBlocks);

            int seeThroughAlpha = 160;
            int textSeeThroughColor = 0xA0FFFFFF;
            String wpName = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = wpName.toUpperCase() + " (" + (int)distance + "m)";

            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8) & 0xFF;
            int b = wpColor & 0xFF;

            // RENDER PASS 1: See-Through (translucent base, at projected position for fog bypass)
            poseStack.pushPose();
            poseStack.translate(projRenderX, projRenderY, projRenderZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            VertexConsumer bufferSeeThrough = context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH);
            drawMarker(poseStack, bufferSeeThrough, r, g, b, seeThroughAlpha, projMarkerSize, false);
            context.bufferSource().endBatch(WAYPOINT_SEE_THROUGH);

            // Render name tag for see-through pass
            poseStack.pushPose();
            poseStack.translate(0.0f, projMarkerSize * 1.50f, 0.0f);
            float projTextScale = 0.035f * projMarkerSize / 0.7f;
            if (distance > 100.0) {
                projTextScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
            }
            poseStack.scale(-projTextScale, -projTextScale, projTextScale);
            float xOffset = -font.width(nameText) / 2.0f + 1.0f;

            // Pass A: Background plate see-through (transparent text)
            font.drawInBatch(
                    nameText,
                    xOffset,
                    0.0f,
                    0x00FFFFFF,  // Completely transparent text
                    false,
                    poseStack.last().pose(),
                    context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH,
                    0x40000000,  // Translucent black background
                    0xF000F0
            );

            // Push text slightly forward in Z to eliminate Z-fighting
            poseStack.pushPose();
            poseStack.translate(0.0f, 0.0f, -0.03f);

            // Pass B: See-through text on top without background
            font.drawInBatch(
                    nameText,
                    xOffset,
                    0.0f,
                    textSeeThroughColor, // Translucent white text
                    false,
                    poseStack.last().pose(),
                    context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH,
                    0,           // No background plate
                    0xF000F0
            );
            poseStack.popPose();
            
            context.bufferSource().endBatch();
            poseStack.popPose(); // text pose
            poseStack.popPose(); // projected position pose

            // RENDER PASS 2: Solid/Visible (at REAL position for pixel-perfect GPU depth testing)
            // The GPU depth buffer naturally occludes pixels behind blocks, producing
            // pixel-perfect partial translucency at any distance.
            if (drawVisiblePass) {
                poseStack.pushPose();
                poseStack.translate(relX, relY, relZ);
                poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
                poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

                VertexConsumer bufferVisible = context.bufferSource().getBuffer(WAYPOINT_VISIBLE);
                drawMarker(poseStack, bufferVisible, r, g, b, 255, realMarkerSize, false);
                context.bufferSource().endBatch(WAYPOINT_VISIBLE);

                // Render name tag for solid pass
                poseStack.pushPose();
                poseStack.translate(0.0f, realMarkerSize * 1.50f, 0.0f);
                float realTextScale = 0.035f * realMarkerSize / 0.7f;
                if (distance > 100.0) {
                    realTextScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
                }
                poseStack.scale(-realTextScale, -realTextScale, realTextScale);

                // Pass A: Background plate (using transparent text color to prevent duplicate letters)
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        0x00FFFFFF,  // Completely transparent text (alpha 0)
                        false,
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.POLYGON_OFFSET,
                        0x40000000,  // Translucent black background
                        0xF000F0
                );
                
                // Push text slightly forward in Z to eliminate Z-fighting and grey blending
                poseStack.pushPose();
                poseStack.translate(0.0f, 0.0f, -0.03f);
                
                // Pass B: Solid white text on top without background
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        0xFFFFFFFF,  // Solid white text color
                        false,       // No drop shadow
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.POLYGON_OFFSET,
                        0,           // No background plate
                        0xF000F0
                );
                poseStack.popPose();
                
                context.bufferSource().endBatch();
                poseStack.popPose(); // text pose
                poseStack.popPose(); // real position pose
            }

            context.bufferSource().endBatch();
        }

        // Flush render buffer as required by END_MAIN event
        context.bufferSource().endBatch();
    }

    private static void drawMarker(PoseStack poseStack, VertexConsumer buffer, int r, int g, int b, int a, float size, boolean hasOverlayAndNormal) {
        Matrix4f poseMatrix = poseStack.last().pose();
        // Maintain 5:7 texture aspect ratio for the 5x7px marker
        float halfWidth = size * (5.0f / 7.0f) / 2.0f;
        int lightmap = 240; // 240 is 15 * 16 (full block & sky light) to make the marker fullbright and immune to shading changes

        if (hasOverlayAndNormal) {
            // Vertex format layout for entities: POSITION_COLOR_TEX_OVERLAY_LIGHTMAP_NORMAL
            // Vertex 1: Top-Left
            buffer.addVertex(poseMatrix, -halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 0.0f)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            // Vertex 2: Top-Right
            buffer.addVertex(poseMatrix, halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 0.0f)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            // Vertex 3: Bottom-Right
            buffer.addVertex(poseMatrix, halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 1.0f)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
            // Vertex 4: Bottom-Left
            buffer.addVertex(poseMatrix, -halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 1.0f)
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                    .setUv2(lightmap, lightmap)
                    .setNormal(0.0f, 0.0f, 1.0f);
        } else {
            // Vertex format layout for text: POSITION_COLOR_TEX_LIGHTMAP
            // Vertex 1: Top-Left
            buffer.addVertex(poseMatrix, -halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 0.0f)
                    .setUv2(lightmap, lightmap);
            // Vertex 2: Top-Right
            buffer.addVertex(poseMatrix, halfWidth, size, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 0.0f)
                    .setUv2(lightmap, lightmap);
            // Vertex 3: Bottom-Right
            buffer.addVertex(poseMatrix, halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(1.0f, 1.0f)
                    .setUv2(lightmap, lightmap);
            // Vertex 4: Bottom-Left
            buffer.addVertex(poseMatrix, -halfWidth, 0.0f, 0.0f)
                    .setColor(r, g, b, a)
                    .setUv(0.0f, 1.0f)
                    .setUv2(lightmap, lightmap);
        }
    }

    // World and server independent persistence logic
    private static String lastWorldId = "";
    private static final Object lock = new Object();

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
        
        java.io.File configFile = new java.io.File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        try {
            configFile.getParentFile().mkdirs();
            java.util.List<WaypointData> dataList = new java.util.ArrayList<>();
            for (Waypoint wp : waypoints) {
                dataList.add(new WaypointData(wp));
            }
            
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            try (java.io.FileWriter writer = new java.io.FileWriter(configFile)) {
                gson.toJson(dataList, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void loadFromFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;
        
        java.io.File configFile = new java.io.File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        waypoints.clear();
        if (!configFile.exists()) return;
        
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            try (java.io.FileReader reader = new java.io.FileReader(configFile)) {
                java.lang.reflect.Type listType = new com.google.gson.reflect.TypeToken<java.util.List<WaypointData>>(){}.getType();
                java.util.List<WaypointData> dataList = gson.fromJson(reader, listType);
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

    // Waypoint DTO for safe JSON serialization using Gson
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
