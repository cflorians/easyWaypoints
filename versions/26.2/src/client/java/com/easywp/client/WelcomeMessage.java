package com.easywp.client;

import com.easywp.WelcomeVersion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * A short message in the chat the first time a world is entered after installing or updating: the
 * key that leads to the settings, as it is bound <em>now</em> (the player may have changed it), and
 * the release's main news - nothing else. Once per version, compared in full (see {@link WelcomeVersion}),
 * remembered in {@code config.json}'s {@code onboarding.lastSeenVersion}.
 *
 * <p>The news are lang keys {@code easywp.welcome.news.<release>.1}, {@code .2}, ... read until the
 * first one missing, so a release adds its lines to the lang files and nothing else; a release
 * without any shows the keys and settings alone.
 *
 * <p>Sent {@link #DELAY_TICKS} after entering the world rather than at once, so it lands after what
 * the server prints on join instead of being buried under it.
 */
final class WelcomeMessage {

    private static final int DELAY_TICKS = 40;
    private static final String MOD_ID = "easywp";

    /** Ticks spent in the current world; -1 once this world has been dealt with. */
    private static int ticksInWorld = 0;

    private WelcomeMessage() {
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                ticksInWorld = 0;
                return;
            }
            if (ticksInWorld < 0 || ++ticksInWorld < DELAY_TICKS) return;
            ticksInWorld = -1;
            showIfNew(client);
        });
    }

    private static void showIfNew(Minecraft client) {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        ModConfig config = ModConfig.get();
        if (!WelcomeVersion.isNew(config.onboarding.lastSeenVersion, version)) return;
        config.onboarding.lastSeenVersion = version;
        ModConfig.save();

        ClientCompat.addChatMessage(client, I18nHelper.getComponent("welcome.title", version)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        boolean modMenu = FabricLoader.getInstance().isModLoaded("modmenu");
        ClientCompat.addChatMessage(client, I18nHelper.getComponent(
                modMenu ? "welcome.settings_modmenu" : "welcome.settings",
                ModKeyBindings.listWaypointsKey.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.GRAY));

        String prefix = "easywp.welcome.news." + WelcomeVersion.release(version) + ".";
        Language language = Language.getInstance();
        if (language.has(prefix + "1")) {
            ClientCompat.addChatMessage(client, I18nHelper.getComponent("welcome.news_title").withStyle(ChatFormatting.GOLD));
            for (int i = 1; language.has(prefix + i); i++) {
                ClientCompat.addChatMessage(client, Component.literal(" • ").withStyle(ChatFormatting.GOLD)
                        .append(Component.translatable(prefix + i).withStyle(ChatFormatting.WHITE)));
            }
        }
    }
}
