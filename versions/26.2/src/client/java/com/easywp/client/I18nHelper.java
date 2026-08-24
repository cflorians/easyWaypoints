package com.easywp.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Bridge between the mod's short UI keys and the translation files under
 * {@code assets/easywp/lang/}.
 *
 * <p>This used to be a hand-written {@code switch} returning one of two hardcoded strings per key,
 * which meant a new language was a Java change duplicated across both version trees. The strings
 * now live in the lang JSONs, which are real shared resources in {@code common/}, so adding a
 * language is a single new file and no code at all.
 *
 * <p>The class survives the migration for two reasons: it keeps the {@code easywp.} namespace in
 * one place - unprefixed keys like {@code menu.title} would collide with vanilla and other mods in
 * the global translation registry - and it lets {@link WaypointSortMode} and
 * {@link WaypointDisplayMode} keep returning short, readable keys.
 *
 * <p><strong>Format specifiers in the lang files are not {@code String.format}'s.</strong>
 * Minecraft's translation formatter accepts only {@code %s} (and positional {@code %1$s}), plus
 * {@code %%} for a literal percent sign. A {@code %d} or {@code %.1f} raises
 * {@code TranslatableFormatException}, and a bare {@code %} raises an {@code IllegalArgumentException}
 * that vanilla does not catch. Numbers that need a specific number of decimals must therefore be
 * formatted by the caller and passed as strings - see {@code ModConfigScreen.ConfigSlider}.
 */
public final class I18nHelper {
    private static final String NAMESPACE = "easywp.";

    private I18nHelper() { }

    /**
     * Resolves a short key against the current language, substituting {@code %s} placeholders.
     *
     * <p>Returns the {@link MutableComponent} that {@code translatable} already builds, so a caller
     * can still restyle it - which matters more than it looks: a translated string is split into one
     * part per placeholder and each part is rendered starting from the component's own style, so a
     * legacy section-sign colour code stops applying at the next {@code %s}. Text that has to stay one
     * colour across a placeholder sets that colour here rather than in the translation.
     */
    public static MutableComponent getComponent(String key, Object... args) {
        return Component.translatable(NAMESPACE + key, args);
    }
}
