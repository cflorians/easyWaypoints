package com.easywp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                ClientCompat.KEYBOARD,
                ClientCompat.KEY_K,
                EASYWP_CATEGORY
        ));

        createWaypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.create",
                ClientCompat.KEYBOARD,
                ClientCompat.KEY_N,
                EASYWP_CATEGORY
        ));

        listWaypointsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.list",
                ClientCompat.KEYBOARD,
                ClientCompat.KEY_J,
                EASYWP_CATEGORY
        ));

        // H, not V: V moved to Fast Ping below, since it's the one that now gets the frequent,
        // no-menu press - freeing V for it and bumping classic Ping to its neighbor on the keyboard.
        pingWaypointKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.ping",
                ClientCompat.KEYBOARD,
                ClientCompat.KEY_H,
                EASYWP_CATEGORY
        ));

        // Replaces the old no-menu "quick create" key (was G) with the same instant-creation
        // behavior, but targeting the crosshair ray like classic Ping above instead of the
        // player's own feet - see the handler below.
        fastPingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easywp.fast_ping",
                ClientCompat.KEYBOARD,
                ClientCompat.KEY_V,
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
                            String.valueOf(nextFreeFastPingNumber())).getString();

                    Waypoint wp = new Waypoint(name, pos, WaypointColors.random(), dimension);
                    wp.setFastPing(true);
                    WaypointStore.waypoints.add(wp);
                    WaypointStore.saveToFile();

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

    /** The last run of digits in a name - "Ping #12" gives 12, in any locale's wording. */
    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)\\D*$");

    /**
     * The number for the next Fast Ping's "Ping #N": the lowest positive number no existing Fast
     * Ping is using. Deleting pings frees their numbers for reuse, so after clearing out old pings
     * numbering starts again from #1 instead of continuing past the highest one ever made, and it
     * can never produce a name that is still in use.
     *
     * <p>Only waypoints still carrying the {@code fastPing} flag are considered - an edited ping
     * loses it (see {@code WaypointCreateScreen.saveWaypoint()}), and a player renaming an
     * unrelated waypoint to "Ping #3" never counts. The number is read back from the name's last
     * digit run, so it works whatever the locale's wording of "Ping #N" is.
     */
    private static int nextFreeFastPingNumber() {
        Set<Integer> used = new HashSet<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp == null || !wp.isFastPing() || wp.getName() == null) continue;
            Matcher m = LAST_NUMBER.matcher(wp.getName());
            if (m.find()) {
                try {
                    used.add(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException tooLong) {
                    // A digit run past int range is not a number this mod ever wrote.
                }
            }
        }
        int next = 1;
        while (used.contains(next)) {
            next++;
        }
        return next;
    }
}
