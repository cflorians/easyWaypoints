package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;

/**
 * Mod settings screen, built on vanilla's own {@link OptionsSubScreen} +
 * {@link OptionsList} - the exact same scrollable widget Minecraft's own
 * Video Settings/Controls/etc. screens use - so it both looks and scrolls
 * like a native options screen instead of the mod's custom dark-blue modal
 * style used by the waypoint list/create screens. One setting per row
 * (control + its own "Reset" button), grouped under section headers.
 * Keybindings are not edited here - the mod's keys are already registered
 * under their own vanilla category and are rebound from Minecraft's own
 * Controls screen.
 */
public class ModConfigScreen extends OptionsSubScreen {
    private static final int CONTROL_WIDTH = 150;
    private static final int RESET_WIDTH = 90;
    private static final int ROW_HEIGHT = 20;

    // OptionsList.getRowWidth() is hardcoded to 310 by vanilla and the scrollbar is placed at
    // getRowRight() + margin, where getRowRight() derives from the width passed to the list's own
    // constructor. Passing the full screen width (as OptionsSubScreen's default addContents() does)
    // pins the scrollbar near the true right edge of the window, far from our much narrower rows.
    // Passing a width just past the row width instead keeps the list (and its scrollbar) centered
    // right next to the actual row content.
    private static final int LIST_WIDTH = 330;

    // Matches vanilla's own per-keybind reset button (see KeyBindsList) so the label reads
    // exactly the way Minecraft's own Controls screen does, in whatever language it's set to.
    private static final Component RESET_LABEL = Component.translatable("controls.reset");

    private static final double SIZE_PERCENT_FLOOR = 10.0;
    private static final double SIZE_PERCENT_CEIL = 300.0;

    private static final double OPACITY_FLOOR = 0.0;
    private static final double OPACITY_CEIL = 100.0;

    private static final double MAX_DEATH_FLOOR = 1.0;
    private static final double MAX_DEATH_CEIL = 10.0;

    private static final double RADIUS_FLOOR = 1.0;
    private static final double RADIUS_CEIL = 50.0;

    private static final double GRACE_FLOOR = 0.5;
    private static final double GRACE_CEIL = 30.0;

    private static final double PING_DIST_FLOOR = 16.0;
    private static final double PING_DIST_CEIL = 512.0;

    public ModConfigScreen(Screen parentScreen) {
        super(parentScreen, Minecraft.getInstance().options, I18nHelper.getComponent("config.title"));
    }

    @Override
    protected void addContents() {
        this.list = this.layout.addToContents(new OptionsList(this.minecraft, LIST_WIDTH, this));
        this.addOptions();
    }

    @Override
    protected void addOptions() {
        ModConfig cfg = ModConfig.get();

        this.list.addHeader(I18nHelper.getComponent("config.section.appearance"));
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, SIZE_PERCENT_FLOOR, SIZE_PERCENT_CEIL, 1.0,
                        cfg.waypointSize.sizePercent, "config.size_row", 0, this::applySizePercent),
                resetButton(this::resetSizePercent)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, OPACITY_FLOOR, OPACITY_CEIL, 1.0,
                        cfg.waypointSize.opacityPercent, "config.opacity_row", 0, this::applyOpacityPercent),
                resetButton(this::resetOpacityPercent)
        );
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.labelDisplay.showDistance)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.show_distance"),
                                (button, value) -> {
                                    ModConfig.get().labelDisplay.showDistance = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetShowDistance)
        );
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.labelDisplay.uppercase)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.uppercase_labels"),
                                (button, value) -> {
                                    ModConfig.get().labelDisplay.uppercase = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetUppercaseLabels)
        );

        this.list.addHeader(I18nHelper.getComponent("config.section.death_waypoint"));
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.deathWaypoints.enabled)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.death_enabled_toggle"),
                                (button, value) -> {
                                    ModConfig.get().deathWaypoints.enabled = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetDeathEnabled)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, MAX_DEATH_FLOOR, MAX_DEATH_CEIL, 1.0,
                        cfg.deathWaypoints.maxCount, "config.max_death_row", 0, this::applyMaxDeathCount),
                resetButton(this::resetMaxDeathCount)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, RADIUS_FLOOR, RADIUS_CEIL, 1.0,
                        cfg.deathWaypoints.radius, "config.radius_row", 0, this::applyRadius),
                resetButton(this::resetRadius)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, GRACE_FLOOR, GRACE_CEIL, 0.5,
                        cfg.deathWaypoints.graceSeconds, "config.grace_row", 1, this::applyGrace),
                resetButton(this::resetGrace)
        );

        this.list.addHeader(I18nHelper.getComponent("config.section.ping"));
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.ping.followRenderDistance)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.ping_follow_render_distance"),
                                (button, value) -> {
                                    ModConfig.get().ping.followRenderDistance = value;
                                    ModConfig.save();
                                    // The distance slider's locked/unlocked state is set once at
                                    // construction time in addOptions(), so it needs the screen
                                    // rebuilt to pick up the new value - same as every reset button.
                                    reopen();
                                }),
                resetButton(this::resetPingFollowRenderDistance)
        );
        ConfigSlider pingDistanceSlider = new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, PING_DIST_FLOOR, PING_DIST_CEIL, 1.0,
                cfg.ping.maxDistance, "config.ping_distance", 0, this::applyPingDistance);
        pingDistanceSlider.active = !cfg.ping.followRenderDistance;
        this.list.addSmall(
                pingDistanceSlider,
                resetButton(this::resetPingDistance)
        );
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.ping.hitFluids)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.ping_fluids"),
                                (button, value) -> {
                                    ModConfig.get().ping.hitFluids = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetPingFluids)
        );

        this.list.addHeader(I18nHelper.getComponent("config.section.behavior"));
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.confirmations.confirmBeforeDelete)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.confirm_delete_toggle"),
                                (button, value) -> {
                                    ModConfig.get().confirmations.confirmBeforeDelete = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetConfirmBeforeDelete)
        );
        this.list.addSmall(
                CycleButton.onOffBuilder(cfg.visibility.rememberOnExit)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.remember_visibility"),
                                (button, value) -> {
                                    ModConfig.get().visibility.rememberOnExit = value;
                                    ModConfig.save();
                                }),
                resetButton(this::resetRememberVisibility)
        );

        // Both Xaero rows are greyed out when Xaero's Minimap isn't installed: neither setting can do
        // anything without it, and a live toggle that silently changes nothing is worse than a
        // visibly unavailable one - the section stays listed so the integration is discoverable.
        boolean xaeroPresent = XaeroBridge.isModInstalled();
        this.list.addHeader(I18nHelper.getComponent("config.section.xaero"));
        this.list.addSmall(
                gated(xaeroPresent, CycleButton.onOffBuilder(cfg.xaeroIntegration.enabled)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.xaero_enabled_toggle"),
                                (button, value) -> {
                                    ModConfig.get().xaeroIntegration.enabled = value;
                                    ModConfig.save();
                                })),
                gated(xaeroPresent, resetButton(this::resetXaeroEnabled))
        );
        this.list.addSmall(
                gated(xaeroPresent, CycleButton.onOffBuilder(cfg.xaeroIntegration.xaeroChatFormat)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.xaero_chat_format"),
                                (button, value) -> {
                                    ModConfig.get().xaeroIntegration.xaeroChatFormat = value;
                                    ModConfig.save();
                                })),
                gated(xaeroPresent, resetButton(this::resetXaeroChatFormat))
        );
        // No Import/Export action row here: those two live in WaypointListScreen's bottom-right
        // corner, next to the list they act on, and duplicating them here would mean two places to
        // click for the same thing with no way to tell which one the player will reach for.
    }

    /** Disables {@code widget} unless {@code available}, and explains why on hover. Returns the same
     *  widget so it can wrap an {@code addSmall} argument inline. Vanilla's own options widgets draw
     *  their label greyed out when inactive, so nothing else is needed to make the state visible. */
    private AbstractWidget gated(boolean available, AbstractWidget widget) {
        if (!available) {
            widget.active = false;
            widget.setTooltip(Tooltip.create(I18nHelper.getComponent("config.xaero_missing")));
        }
        return widget;
    }

    @Override
    protected void addFooter() {
        this.layout.addToFooter(Button.builder(I18nHelper.getComponent("menu.back"), btn -> this.onClose())
                .width(CONTROL_WIDTH + RESET_WIDTH)
                .build());
    }

    @Override
    public void removed() {
        // Deliberately does not call super.removed(): OptionsSubScreen's default re-saves
        // vanilla's own options.txt on close, which this screen has no reason to trigger.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private AbstractWidget resetButton(Runnable onReset) {
        return Button.builder(RESET_LABEL, btn -> onReset.run())
                .width(RESET_WIDTH)
                .build();
    }

    private void applySizePercent(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.waypointSize.sizePercent = clamp(v, SIZE_PERCENT_FLOOR, SIZE_PERCENT_CEIL);
        ModConfig.save();
    }

    private void applyOpacityPercent(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.waypointSize.opacityPercent = clamp(v, OPACITY_FLOOR, OPACITY_CEIL);
        ModConfig.save();
    }

    private void applyMaxDeathCount(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.deathWaypoints.maxCount = (int) Math.round(clamp(v, MAX_DEATH_FLOOR, MAX_DEATH_CEIL));
        ModConfig.save();
    }

    private void applyRadius(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.deathWaypoints.radius = clamp(v, RADIUS_FLOOR, RADIUS_CEIL);
        ModConfig.save();
    }

    private void applyGrace(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.deathWaypoints.graceSeconds = clamp(v, GRACE_FLOOR, GRACE_CEIL);
        ModConfig.save();
    }

    private void applyPingDistance(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.ping.maxDistance = clamp(v, PING_DIST_FLOOR, PING_DIST_CEIL);
        ModConfig.save();
    }

    private void resetSizePercent() {
        ModConfig.get().waypointSize.sizePercent = new ModConfig.WaypointSize().sizePercent;
        ModConfig.save();
        reopen();
    }

    private void resetOpacityPercent() {
        ModConfig.get().waypointSize.opacityPercent = new ModConfig.WaypointSize().opacityPercent;
        ModConfig.save();
        reopen();
    }

    private void resetShowDistance() {
        ModConfig.get().labelDisplay.showDistance = new ModConfig.LabelDisplay().showDistance;
        ModConfig.save();
        reopen();
    }

    private void resetUppercaseLabels() {
        ModConfig.get().labelDisplay.uppercase = new ModConfig.LabelDisplay().uppercase;
        ModConfig.save();
        reopen();
    }

    private void resetDeathEnabled() {
        ModConfig.get().deathWaypoints.enabled = new ModConfig.DeathWaypoints().enabled;
        ModConfig.save();
        reopen();
    }

    private void resetMaxDeathCount() {
        ModConfig.get().deathWaypoints.maxCount = new ModConfig.DeathWaypoints().maxCount;
        ModConfig.save();
        reopen();
    }

    private void resetRadius() {
        ModConfig.get().deathWaypoints.radius = new ModConfig.DeathWaypoints().radius;
        ModConfig.save();
        reopen();
    }

    private void resetGrace() {
        ModConfig.get().deathWaypoints.graceSeconds = new ModConfig.DeathWaypoints().graceSeconds;
        ModConfig.save();
        reopen();
    }

    private void resetPingFollowRenderDistance() {
        ModConfig.get().ping.followRenderDistance = new ModConfig.Ping().followRenderDistance;
        ModConfig.save();
        reopen();
    }

    private void resetPingDistance() {
        ModConfig.get().ping.maxDistance = new ModConfig.Ping().maxDistance;
        ModConfig.save();
        reopen();
    }

    private void resetPingFluids() {
        ModConfig.get().ping.hitFluids = new ModConfig.Ping().hitFluids;
        ModConfig.save();
        reopen();
    }

    private void resetConfirmBeforeDelete() {
        ModConfig.get().confirmations.confirmBeforeDelete = new ModConfig.Confirmations().confirmBeforeDelete;
        ModConfig.save();
        reopen();
    }

    private void resetRememberVisibility() {
        ModConfig.get().visibility.rememberOnExit = new ModConfig.Visibility().rememberOnExit;
        ModConfig.save();
        reopen();
    }

    private void resetXaeroEnabled() {
        ModConfig.get().xaeroIntegration.enabled = new ModConfig.XaeroIntegration().enabled;
        ModConfig.save();
        reopen();
    }

    private void resetXaeroChatFormat() {
        ModConfig.get().xaeroIntegration.xaeroChatFormat = new ModConfig.XaeroIntegration().xaeroChatFormat;
        ModConfig.save();
        reopen();
    }

    /**
     * Rebuilds the screen from scratch so every widget reflects the freshly-reset config value.
     * Carries the current scroll position over to the new {@link OptionsList} - it would otherwise
     * start back at 0 on every reset/reopen, jerking the player back to the top of a scrolled list.
     *
     * <p>The scroll is restored <em>after</em> the screen is set, not before: {@code list} is
     * declared nullable by {@link OptionsSubScreen} and is only assigned in {@link #addContents()},
     * which runs from {@code init()} - and {@code init()} is what {@link ClientCompat#setScreen}
     * ends up calling. Touching {@code newScreen.list} first therefore dereferences null. Setting
     * the screen first also means the list is populated by the time {@code setScrollAmount} clamps
     * against its max scroll, instead of being clamped to 0 against an empty one.
     */
    private void reopen() {
        double scrollAmount = this.list != null ? this.list.scrollAmount() : 0.0;
        ModConfigScreen newScreen = new ModConfigScreen(this.lastScreen);
        ClientCompat.setScreen(this.minecraft, newScreen);
        if (newScreen.list != null) {
            newScreen.list.setScrollAmount(scrollAmount);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Vanilla-styled slider bound directly to a {@link ModConfig} field via a write-back callback. */
    private static class ConfigSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final double step;
        private final String i18nKey;
        private final int decimals;
        private final DoubleConsumer onChange;

        /**
         * @param decimals how many decimals the value is rendered with. It has to be stated here
         *                 rather than living in the translation string, because Minecraft's
         *                 translation formatter understands only {@code %s} - see {@link I18nHelper}.
         */
        ConfigSlider(int width, int height, double min, double max, double step, double initial,
                     String i18nKey, int decimals, DoubleConsumer onChange) {
            super(0, 0, width, height, Component.empty(), Mth.clamp((initial - min) / (max - min), 0.0, 1.0));
            this.min = min;
            this.max = max;
            this.step = step;
            this.i18nKey = i18nKey;
            this.decimals = decimals;
            this.onChange = onChange;
            this.updateMessage();
        }

        private double currentValue() {
            double raw = min + this.value * (max - min);
            double stepped = step > 0 ? Math.round(raw / step) * step : raw;
            return Mth.clamp(stepped, min, max);
        }

        /**
         * The number reaches the translation already formatted. Handing a raw double to a
         * {@code %s} placeholder would print "85.0" where the old {@code String.format("%.0f")}
         * printed "85". No explicit Locale, so the decimal separator keeps following the system
         * locale exactly as it did before the strings moved into the lang files.
         */
        @Override
        protected void updateMessage() {
            this.setMessage(I18nHelper.getComponent(i18nKey, String.format("%." + decimals + "f", currentValue())));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentValue());
        }
    }
}
