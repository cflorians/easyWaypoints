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

        for (Waypoint wp : waypoints) {
            if (wp.getDimension() != null && !wp.getDimension().equals(currentDimension)) {
                continue;
            }
            BlockPos wpPos = wp.getPos();
            
            // Tip of the arrow will point at the bottom center of the block (Y + 0.0, at the feet level)
            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY();
            double targetZ = wpPos.getZ() + 0.5;

            double relX = targetX - cameraPos.x;
            double relY = targetY - cameraPos.y;
            double relZ = targetZ - cameraPos.z;

            double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
            // Use the chunk render distance dynamically as the projection limit.
            // This ensures pixel-perfect native GPU depth clipping works at all loaded distances,
            // while preventing waypoints from disappearing beyond the render fog.
            double renderDistanceBlocks = client.options.renderDistance().get() * 16.0;
            double maxRenderDistance = renderDistanceBlocks;

            double renderX = relX;
            double renderY = relY;
            double renderZ = relZ;
            float markerSize;

            boolean isFarDistance = distance >= 115.0;
            boolean forceSeeThroughOnly = distance > maxRenderDistance;

            if (forceSeeThroughOnly || isFarDistance) {
                // Project waypoint to exactly 32.0 blocks from the camera to bypass far clipping plane culling and shader fog
                double projectionDistance = 32.0;
                double scaleFactor = projectionDistance / distance;
                renderX = relX * scaleFactor;
                renderY = relY * scaleFactor;
                renderZ = relZ * scaleFactor;
                // Constant visual size relative to the 32.0 blocks projection distance
                markerSize = (float) (projectionDistance / 16.0) * 0.7f;
            } else {
                // Render at exact world coordinates with dynamic size scaling (enables pixel-perfect native GPU occlusion)
                markerSize = (float) Math.max(0.4, distance / 16.0) * 0.7f;
            }

            // Raycast check: Used ONLY when the waypoint is far (>= 115 blocks) to handle shader fog bypass.
            // When close (< 115 blocks), we use the GPU depth buffer (pixel-perfect) naturally.
            boolean isObstructed = false;
            if (isFarDistance && client.level != null) {
                try {
                    Vec3 start = cameraPos;
                    // Aim at the visual center of the waypoint (0.8 blocks above the base Y coordinate)
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
                        // Ignore collision if it is with the waypoint's base block coordinate itself
                        if (hitPos != null && !hitPos.equals(wpPos)) {
                            isObstructed = true;
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            // Force see-through transparency if:
            // 1. The waypoint is beyond active chunk render distance.
            // 2. The waypoint is far (>= 100m) and the line of sight is obstructed.
            forceSeeThroughOnly = (distance > renderDistanceBlocks) || (isFarDistance && isObstructed);

            // Determine if the visible pass (solid) should be rendered.
            int seeThroughAlpha = 160;
            boolean drawVisiblePass = !forceSeeThroughOnly;
            int textSeeThroughColor = 0xA0FFFFFF;

            // Format: NAME (123m)
            String nameText = wp.getName().toUpperCase() + " (" + (int)distance + "m)";

            poseStack.pushPose();
            poseStack.translate(renderX, renderY, renderZ);

            // Billboard rotation (make the billboard face the camera)
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            // Extract waypoint colors
            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8) & 0xFF;
            int b = wpColor & 0xFF;

            // Render textured teardrop pin:
            // 1. See-through pass (translucent original color, always passes depth testing)
            VertexConsumer bufferSeeThrough = context.bufferSource().getBuffer(WAYPOINT_SEE_THROUGH);
            drawMarker(poseStack, bufferSeeThrough, r, g, b, seeThroughAlpha, markerSize, false);
            context.bufferSource().endBatch(WAYPOINT_SEE_THROUGH);

            // 2. Visible pass (full waypoint color, normal depth testing, slightly offset in Z to avoid Z-fighting/double-blend white color)
            // Rendered only when the waypoint is within the active chunk render distance.
            // When partially covered by blocks, the GPU depth buffer will clip only the covered pixels, revealing the see-through pass underneath.
            // Note: Since WAYPOINT_VISIBLE is now a Text RenderType, we build it with hasOverlayAndNormal = false.
            if (drawVisiblePass) {
                poseStack.pushPose();
                poseStack.translate(0.0f, 0.0f, -0.05f);
                VertexConsumer bufferVisible = context.bufferSource().getBuffer(WAYPOINT_VISIBLE);
                drawMarker(poseStack, bufferVisible, r, g, b, 255, markerSize, false);
                context.bufferSource().endBatch(WAYPOINT_VISIBLE);
                poseStack.popPose();
            }

            // Render name tag:
            poseStack.pushPose();
            // Position text tag above the marker (shifted up to prevent overlap with the smaller marker pin)
            poseStack.translate(0.0f, markerSize * 1.50f, 0.0f);
            float textScale = 0.035f * markerSize / 0.7f;
            if (distance > 100.0) {
                // Scale boost for readability at long distance to combat shader blurring
                textScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
            }
            poseStack.scale(-textScale, -textScale, textScale); // Scale down text and preserve winding order
            
            // Visual offset correction
            float xOffset = -font.width(nameText) / 2.0f + 1.0f;

            // Pass 1: See-through text (visible through walls, translucent fallback background)
            font.drawInBatch(
                    nameText,
                    xOffset,
                    0.0f,
                    textSeeThroughColor,
                    false,
                    poseStack.last().pose(),
                    context.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH,
                    0,
                    0xF000F0
            );
            context.bufferSource().endBatch();

            // Pass 2: Normal text (visible directly, perfect background, slightly offset in Z to draw on top of see-through pass)
            // Rendered only when the waypoint is within the active chunk render distance.
            if (drawVisiblePass) {
                poseStack.pushPose();
                poseStack.translate(0.0f, 0.0f, -0.05f);
                
                int borderCol = 0xFF000000;
                int textCol = 0xFFFFFFFF;

                // Render a high-visibility black border around the text (4 offset black shadows)
                // We use Font.DisplayMode.NORMAL for the black border to force a separate buffer/draw call from the center white text
                float outlineOffset = 1.0f; // 1-pixel font-space offset
                // Top border
                font.drawInBatch(nameText, xOffset, -outlineOffset, borderCol, false, poseStack.last().pose(), context.bufferSource(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
                // Bottom border
                font.drawInBatch(nameText, xOffset, outlineOffset, borderCol, false, poseStack.last().pose(), context.bufferSource(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
                // Left border
                font.drawInBatch(nameText, xOffset - outlineOffset, 0.0f, borderCol, false, poseStack.last().pose(), context.bufferSource(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
                // Right border
                font.drawInBatch(nameText, xOffset + outlineOffset, 0.0f, borderCol, false, poseStack.last().pose(), context.bufferSource(), Font.DisplayMode.NORMAL, 0, 0xF000F0);
                context.bufferSource().endBatch(); // Flush borders before drawing the center text

                // Central white text pass (no shadow, so it renders cleanly within the border)
                // We use Font.DisplayMode.POLYGON_OFFSET which applies a hardware-level depth offset in OpenGL.
                // This guarantees the white text is rendered in front of the black borders, preventing Z-fighting completely.
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        textCol, // Completely white color
                        false,      // Disable default drop shadow (handled manually above for a full border)
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.POLYGON_OFFSET, // Disable block lighting shading to prevent it from looking black
                        0,          // Remove the grey background box (set to transparent)
                        0xF000F0
                );
                context.bufferSource().endBatch();
                poseStack.popPose();
            }
            
            poseStack.popPose();
            poseStack.popPose();

            // Force immediate flush of all elements for this waypoint to avoid delayed rendering or chunk/entity culling issues
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
        
        public WaypointData(Waypoint wp) {
            this.name = wp.getName();
            this.x = wp.getPos().getX();
            this.y = wp.getPos().getY();
            this.z = wp.getPos().getZ();
            this.color = wp.getColor();
            this.dimension = wp.getDimension();
        }
        
        public Waypoint toWaypoint() {
            return new Waypoint(name, new BlockPos(x, y, z), color, dimension);
        }
    }
}
