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
    public static KeyMapping pingWaypointKey;
    public static KeyMapping.Category EASYWP_CATEGORY;
    public static WaypointDisplayMode displayMode = WaypointDisplayMode.WORLD_MARKERS;
    private static boolean appliedRememberedVisibility = false;

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

        pingWaypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.ping",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                EASYWP_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Deferred to the first tick (rather than the static field initializer above) because
            // ModConfig.get() needs Minecraft.getInstance().gameDirectory, which isn't guaranteed
            // to be ready yet during ClientModInitializer.onInitializeClient().
            if (!appliedRememberedVisibility) {
                appliedRememberedVisibility = true;
                ModConfig.Visibility visibilityCfg = ModConfig.get().visibility;
                if (visibilityCfg.rememberOnExit) {
                    displayMode = visibilityCfg.lastVisible ? WaypointDisplayMode.WORLD_MARKERS : WaypointDisplayMode.DISABLED;
                }
            }

            while (toggleWaypointsKey.consumeClick()) {
                displayMode = displayMode.next();
                if (ModConfig.get().visibility.rememberOnExit) {
                    ModConfig.get().visibility.lastVisible = (displayMode == WaypointDisplayMode.WORLD_MARKERS);
                    ModConfig.save();
                }
                if (client.player != null) {
                    client.gui.hud.setOverlayMessage(
                        I18nHelper.getComponent(displayMode.getTranslationKey()),
                        true
                    );
                }
            }

            while (createWaypointKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    BlockPos targetPos = client.player.blockPosition();
                    client.setScreenAndShow(new WaypointCreateScreen(targetPos));
                }
            }

            while (listWaypointsKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    client.setScreenAndShow(new WaypointListScreen(null));
                }
            }

            while (pingWaypointKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    client.setScreenAndShow(new WaypointCreateScreen(WaypointPing.target(client)));
                }
            }
        });
    }
}
