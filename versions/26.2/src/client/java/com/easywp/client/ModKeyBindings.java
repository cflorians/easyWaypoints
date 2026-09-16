package com.easywp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKeyBindings {
    public static KeyMapping toggleWaypointsKey;
    public static KeyMapping createWaypointKey;
    public static KeyMapping listWaypointsKey;
    public static KeyMapping pingWaypointKey;
    public static KeyMapping fastPingKey;
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

        // H, not V: V moved to Fast Ping below, since it's the one that now gets the frequent,
        // no-menu press - freeing V for it and bumping classic Ping to its neighbor on the keyboard.
        pingWaypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.ping",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                EASYWP_CATEGORY
        ));

        // Replaces the old no-menu "quick create" key (was G) with the same instant-creation
        // behavior, but targeting the crosshair ray like classic Ping above instead of the
        // player's own feet - see the handler below.
        fastPingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.fast_ping",
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
                    ClientCompat.setOverlayMessage(
                        client,
                        I18nHelper.getComponent(displayMode.getTranslationKey()),
                        true
                    );
                }
            }

            while (createWaypointKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    BlockPos targetPos = client.player.blockPosition();
                    ClientCompat.setScreen(client, new WaypointCreateScreen(targetPos));
                }
            }

            while (listWaypointsKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    ClientCompat.setScreen(client, new WaypointListScreen(null));
                }
            }

            while (pingWaypointKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    ClientCompat.setScreen(client, new WaypointCreateScreen(WaypointPing.target(client)));
                }
            }

            while (fastPingKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    // Same crosshair targeting and ModConfig.Ping settings (range, fluids,
                    // follow-render-distance) as classic Ping above - the two are meant to share
                    // one set of config, not grow a second copy of the same raycast logic.
                    BlockPos pos = WaypointPing.target(client);
                    String dimension = client.level.dimension().identifier().toString();
                    String name = I18nHelper.getComponent("create.ping_name",
                            String.valueOf(countFastPings() + 1)).getString();

                    Waypoint wp = new Waypoint(name, pos, WaypointColors.random(), dimension);
                    wp.setFastPing(true);
                    WaypointRenderer.waypoints.add(wp);
                    WaypointRenderer.saveToFile();

                    ClientCompat.setOverlayMessage(client, I18nHelper.getComponent(
                            "create.feedback_created", name, pos.getX(), pos.getY(), pos.getZ()
                    ).withStyle(ChatFormatting.GRAY), true);

                    // Same restore-visibility reasoning as WaypointCreateScreen.saveWaypoint(): a
                    // waypoint just created while display is off would otherwise stay invisible.
                    if (displayMode == WaypointDisplayMode.DISABLED) {
                        displayMode = WaypointDisplayMode.WORLD_MARKERS;
                        if (ModConfig.get().visibility.rememberOnExit) {
                            ModConfig.get().visibility.lastVisible = true;
                            ModConfig.save();
                        }
                    }
                }
            }
        });
    }

    /** How many Fast Ping waypoints currently exist, for the next one's "Ping #N" name. Counts the
     *  {@code fastPing} flag rather than matching the rendered name text, so it stays correct
     *  regardless of locale and isn't confused by a player renaming an unrelated waypoint to
     *  something that happens to look like "Ping #3". Deleting pings lowers this count, so a
     *  number can be reused after a deletion - the same trade-off the old quick-create name always
     *  made by counting the whole waypoint list. */
    private static int countFastPings() {
        int count = 0;
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp != null && wp.isFastPing()) {
                count++;
            }
        }
        return count;
    }
}
