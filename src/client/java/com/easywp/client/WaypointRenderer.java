package com.easywp.client;

import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    public static void init(){
        if (initialized) return;

        waypoints.add(new Waypoint("Prueba", new BlockPos(0,80,0)));
        initialized = true;
    }

    public static void render(PoseStack poseStack, float parcialTick, long limitTime){
        if (!ModKeyBindings.showWaypoints) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        Font font = client.font;
        EntityRenderDispatcher renderDispatcher = client.getEntityRenderDispatcher();

        // Camera pos
        double camX = renderDispatcher.camera.position().x;
        double camY = renderDispatcher.camera.position().y;
        double camZ = renderDispatcher.camera.position().z;

        for (Waypoint wp : waypoints){
            BlockPos pos = wp.getPos();

            float x = (float) (pos.getX() - camX) + 0.5f;
            float y = (float) (pos.getY() - camY) + 1.0f;
            float z = (float) (pos.getZ() - camZ) + 0.5f;

            poseStack.pushPose();
            poseStack.translate(x, y, z);

            poseStack.mulPose(client.gameRenderer.getMainCamera().rotation());
            poseStack.scale(-0.025f, -0.025f, 0.025f);

            Matrix4f matrix = poseStack.last().pose();
            Component text = Component.literal(wp.getName());
            float textWidth = (float) -font.width(text) / 2;

            font.drawInBatch(text, textWidth, 0, 0xFFFFFF, false, matrix,
                    client.renderBuffers().bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, 15728880);

            poseStack.popPose();
        }
    }
}
