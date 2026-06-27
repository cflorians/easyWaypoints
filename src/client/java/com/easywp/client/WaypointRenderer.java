package com.easywp.client;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
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

            // Project waypoints at 32 blocks max to bypass horizon fog and keep them readable
            double maxRenderDistance = 32.0;

            double renderX = relX;
            double renderY = relY;
            double renderZ = relZ;
            float markerSize;

            if (distance > maxRenderDistance) {
                double scaleFactor = maxRenderDistance / distance;
                renderX = relX * scaleFactor;
                renderY = relY * scaleFactor;
                renderZ = relZ * scaleFactor;
                // Constant visual size at max projection distance
                markerSize = (float) (maxRenderDistance / 10.0);
            } else {
                // Dynamic scale based on distance
                markerSize = (float) Math.max(1.0, distance / 10.0);
            }

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
            drawMarker(poseStack, bufferSeeThrough, r, g, b, 160, markerSize);

            // 2. Visible pass (full waypoint color, normal depth testing, slightly offset in Z to avoid Z-fighting/double-blend white color)
            poseStack.pushPose();
            poseStack.translate(0.0f, 0.0f, -0.01f);
            VertexConsumer bufferVisible = context.bufferSource().getBuffer(WAYPOINT_VISIBLE);
            drawMarker(poseStack, bufferVisible, r, g, b, 255, markerSize);
            poseStack.popPose();

            // Render name tag:
            poseStack.pushPose();
            // Position text tag above the marker
            poseStack.translate(0.0f, markerSize * 1.15f, 0.0f);
            float textScale = 0.025f * markerSize;
            if (distance > 100.0) {
                // Scale boost for readability at long distance to combat shader blurring
                textScale *= (float) Math.min(1.4, 1.0 + (distance - 100.0) * 0.002);
            }
            poseStack.scale(-textScale, -textScale, textScale); // Scale down text and preserve winding order
            
            // Visual offset correction
            float xOffset = -font.width(nameText) / 2.0f + 1.0f;

            if (distance < 20.0) {
                // Near: render white text with background box as see-through to avoid occlusion
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        0xFFFFFFFF,
                        false,
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.SEE_THROUGH,
                        0x90000000,
                        0xF000F0
                );
            } else {
                // Far: dual-pass rendering for see-through rays effect
                // Pass 1: translucent text (see-through) without background box
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        0xA0FFFFFF,
                        false,
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.SEE_THROUGH,
                        0,
                        0xF000F0
                );

                // Pass 2: opaque text (visible) with background box, slightly offset to avoid z-fighting
                poseStack.pushPose();
                poseStack.translate(0.0f, 0.0f, -0.1f);
                font.drawInBatch(
                        nameText,
                        xOffset,
                        0.0f,
                        0xFFFFFFFF,
                        false,
                        poseStack.last().pose(),
                        context.bufferSource(),
                        Font.DisplayMode.NORMAL,
                        0x90000000,
                        0xF000F0
                );
                poseStack.popPose();
            }

            poseStack.popPose();
            poseStack.popPose();
        }

        // Flush render buffer as required by END_MAIN event
        context.bufferSource().endBatch();
    }

    private static void drawMarker(PoseStack poseStack, VertexConsumer buffer, int r, int g, int b, int a, float size) {
        Matrix4f poseMatrix = poseStack.last().pose();
        // Maintain 5:7 texture aspect ratio for the 5x7px marker
        float halfWidth = size * (5.0f / 7.0f) / 2.0f;
        int lightmap = 240; // 240 is 15 * 16 (full block & sky light) to make the marker fullbright and immune to shading changes

        // Vertex format layout: POSITION_COLOR_TEX_LIGHTMAP
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
