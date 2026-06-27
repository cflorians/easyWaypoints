package com.easywp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static KeyMapping toggleWaypointsKey;
    public static KeyMapping createWaypointKey;
    public static KeyMapping listWaypointsKey;
    public static KeyMapping.Category EASYWP_CATEGORY;
    public static boolean showWaypoints = true;

    public static void register(){
        // Register custom keybind category
        EASYWP_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("easywp", "easywp_controls"));

        toggleWaypointsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                EASYWP_CATEGORY
        ));

        createWaypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.create",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                EASYWP_CATEGORY
        ));

        listWaypointsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.list",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                EASYWP_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Toggle visibility
            while (toggleWaypointsKey.consumeClick()){
                showWaypoints = !showWaypoints;
                if (client.player != null) {
                    client.gui.setOverlayMessage(Component.literal("Waypoints: " + (showWaypoints ? "Visibles" : "Ocultos")), true);
                }
            }

            // Create waypoint
            while (createWaypointKey.consumeClick()){
                if (client.player != null && client.level != null){
                    BlockPos targetPos = null;
                    if (client.hitResult != null && client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        net.minecraft.world.phys.BlockHitResult blockHit = (net.minecraft.world.phys.BlockHitResult) client.hitResult;
                        targetPos = blockHit.getBlockPos().relative(blockHit.getDirection());
                    } else {
                        targetPos = client.player.blockPosition();
                    }
                    client.setScreen(new WaypointCreateScreen(targetPos));
                }
            }

            // List waypoints
            while (listWaypointsKey.consumeClick()){
                if (client.player != null && client.level != null){
                    client.setScreen(new WaypointListScreen(null));
                }
            }
        });
    }
}
