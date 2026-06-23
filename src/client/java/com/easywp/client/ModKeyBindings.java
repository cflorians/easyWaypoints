package com.easywp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static KeyMapping toggleWaypointsKey;
    public static boolean showWaypoints = true;

    public static void register(){
        toggleWaypointsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleWaypointsKey.consumeClick()){
                showWaypoints = !showWaypoints;
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("Waypoints: " + (showWaypoints ? "Visibles" : "Ocultos")));
                }
            }
        });
    }
}
