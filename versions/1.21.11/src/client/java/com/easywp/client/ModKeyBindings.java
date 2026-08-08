package com.easywp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static KeyBinding toggleWaypointsKey;
    public static KeyBinding createWaypointKey;
    public static KeyBinding listWaypointsKey;
    public static final String EASYWP_CATEGORY = "key.categories.easywp";
    public static WaypointDisplayMode displayMode = WaypointDisplayMode.WORLD_MARKERS;

    public static void register() {
        toggleWaypointsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.easywp.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                EASYWP_CATEGORY
        ));

        createWaypointKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.easywp.create",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                EASYWP_CATEGORY
        ));

        listWaypointsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.easywp.list",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                EASYWP_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleWaypointsKey.wasPressed()) {
                displayMode = displayMode.next();
                if (client.player != null) {
                    client.inGameHud.setOverlayMessage(
                        I18nHelper.getComponent(displayMode.getTranslationKey()),
                        true
                    );
                }
            }

            while (createWaypointKey.wasPressed()) {
                if (client.player != null && client.world != null) {
                    BlockPos targetPos = client.player.getBlockPos();
                    client.setScreen(new WaypointCreateScreen(targetPos));
                }
            }

            while (listWaypointsKey.wasPressed()) {
                if (client.player != null && client.world != null) {
                    client.setScreen(new WaypointListScreen(null));
                }
            }
        });
    }
}
