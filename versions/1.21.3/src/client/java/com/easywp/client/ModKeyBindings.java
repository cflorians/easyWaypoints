package com.easywp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static KeyMapping toggleWaypointsKey;
    public static KeyMapping createWaypointKey;
    public static KeyMapping listWaypointsKey;
    public static KeyMapping.Category EASYWP_CATEGORY;
    public static WaypointDisplayMode displayMode = WaypointDisplayMode.WORLD_MARKERS;

    public static void register() {
        EASYWP_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("easywp", "easywp_controls"));

        toggleWaypointsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
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
            while (toggleWaypointsKey.consumeClick()) {
                displayMode = displayMode.next();
                if (client.player != null) {
                    client.gui.setOverlayMessage(
                        I18nHelper.getComponent(displayMode.getTranslationKey()),
                        true
                    );
                }
            }

            while (createWaypointKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    BlockPos targetPos = client.player.blockPosition();
                    client.setScreen(new WaypointCreateScreen(targetPos));
                }
            }

            while (listWaypointsKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    client.setScreen(new WaypointListScreen(null));
                }
            }
        });
    }
}
