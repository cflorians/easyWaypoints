package com.easywp.client;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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

            double renderX = relX;
            double renderY = relY;
            double renderZ = relZ;
            float markerSize;

            // Project all waypoints beyond 12.0 blocks to exactly 12.0 blocks from the camera
            // to completely bypass volumetric shader fog and preserve absolute visibility and pixel precision
            if (distance > 12.0) {
                double projectionDistance = 12.0;
                double scaleFactor = projectionDistance / distance;
                renderX = relX * scaleFactor;
                renderY = relY * scaleFactor;
                renderZ = relZ * scaleFactor;
                markerSize = (float) (Math.max(0.4, distance / 16.0) * 0.7f * (projectionDistance / distance));
            } else {
                markerSize = (float) Math.max(0.4, distance / 16.0) * 0.7f;
            }

            // Raycast check performed only if the waypoint is within the active chunk render distance
            boolean isObstructed = false;
            if (distance <= renderDistanceBlocks && client.level != null 
                    && Double.isFinite(targetX) && Double.isFinite(targetY) && Double.isFinite(targetZ)) {
                try {
                    Vec3 start = cameraPos;
                    Vec3 end = new Vec3(targetX, targetY + 0.8, targetZ);
                    BlockHitResult hitResult = client.level.clip(new ClipContext(
                        start,
                        end,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        client.player
                    ));
                    if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                        BlockPos hitPos = hitResult.getBlockPos();
                        if (hitPos != null && !hitPos.equals(wpPos)) {
                            // Check if the obstructing block is transparent/pass-through (like portals, glass, water)
                            net.minecraft.world.level.block.state.BlockState state = client.level.getBlockState(hitPos);
                            boolean isTransparentObstacle = state.isAir()
                                    || state.getCollisionShape(client.level, hitPos).isEmpty()
                                    || state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)
                                    || state.is(net.minecraft.world.level.block.Blocks.GLASS)
                                    || state.is(net.minecraft.world.level.block.Blocks.TINTED_GLASS)
                                    || state.getBlock().getClass().getSimpleName().toLowerCase().contains("glass")
                                    || state.getBlock().getClass().getSimpleName().toLowerCase().contains("portal");
                            
                            if (!isTransparentObstacle) {
                                isObstructed = true;
                            }
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            // Draw visible pass only if it is in render range AND not obstructed by terrain/blocks
            boolean forceSeeThroughOnly = (distance > renderDistanceBlocks) || isObstructed;
            boolean drawVisiblePass = !forceSeeThroughOnly;

            int seeThroughAlpha = 160;
            int textSeeThroughColor = 0xA0FFFFFF;
            String wpName = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = wpName.toUpperCase() + " (" + (int)distance + "m)";

            poseStack.pushPose();
            poseStack.translate(renderX, renderY, renderZ);

            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8) & 0xFF;
            int b = wpColor & 0xFF;

            // RENDER PASS: Render EXACTLY one pass (either Solid/Visible or See-Through)
            // This prevents double blending of backgrounds and Z-fighting flickering.
            if (drawVisiblePass) {
                // 1. Solid Visible Pass (normal depth test, full opacity)
                VertexConsumer bufferVisible = context.bufferSource().getBuffer(WAYPOINT_VISIBLE);
                drawMarker(poseStack, bufferVisible, r, g, b, 255, markerSize, false);
                context.bufferSource().endBatch(WAYPOINT_VISIBLE);

                // Render name tag for solid pass
                poseStack.pushPose();
                poseStack.translate(0.0f, markerSize * 1.50f, 0.0f);
                float textScale = 0.035f * markerSize / 0.7f;
                if (distance > 100.0) {
                    textScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
                }
                poseStack.scale(-textScale, -textScale, textScale);
                float xOffset = -font.width(nameText) / 2.0f + 1.0f;

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
                poseStack.popPose();
            } else {
                // 2. See-Through Pass (ignored depth test, see through walls)
                VertexConsumer bufferSeeThrough = context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH);
                drawMarker(poseStack, bufferSeeThrough, r, g, b, seeThroughAlpha, markerSize, false);
                context.bufferSource().endBatch(WAYPOINT_SEE_THROUGH);

                // Render name tag for see-through pass
                poseStack.pushPose();
                poseStack.translate(0.0f, markerSize * 1.50f, 0.0f);
                float textScale = 0.035f * markerSize / 0.7f;
                if (distance > 100.0) {
                    textScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
                }
                poseStack.scale(-textScale, -textScale, textScale);
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
                poseStack.popPose();
            }
            
            poseStack.popPose();

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
