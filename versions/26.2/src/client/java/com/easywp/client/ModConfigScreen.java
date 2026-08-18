package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.OptionsList;
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
                        cfg.waypointSize.sizePercent, "config.size_row", this::applySizePercent),
                resetButton(this::resetSizePercent)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, OPACITY_FLOOR, OPACITY_CEIL, 1.0,
                        cfg.waypointSize.opacityPercent, "config.opacity_row", this::applyOpacityPercent),
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
                        cfg.deathWaypoints.maxCount, "config.max_death_row", this::applyMaxDeathCount),
                resetButton(this::resetMaxDeathCount)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, RADIUS_FLOOR, RADIUS_CEIL, 1.0,
                        cfg.deathWaypoints.radius, "config.radius_row", this::applyRadius),
                resetButton(this::resetRadius)
        );
        this.list.addSmall(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, GRACE_FLOOR, GRACE_CEIL, 0.5,
                        cfg.deathWaypoints.graceSeconds, "config.grace_row", this::applyGrace),
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
                cfg.ping.maxDistance, "config.ping_distance", this::applyPingDistance);
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

    /** Rebuilds the screen from scratch so every widget reflects the freshly-reset config value. */
    private void reopen() {
        this.minecraft.setScreenAndShow(new ModConfigScreen(this.lastScreen));
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
        private final DoubleConsumer onChange;

        ConfigSlider(int width, int height, double min, double max, double step, double initial,
                     String i18nKey, DoubleConsumer onChange) {
            super(0, 0, width, height, Component.empty(), Mth.clamp((initial - min) / (max - min), 0.0, 1.0));
            this.min = min;
            this.max = max;
            this.step = step;
            this.i18nKey = i18nKey;
            this.onChange = onChange;
            this.updateMessage();
        }

        private double currentValue() {
            double raw = min + this.value * (max - min);
            double stepped = step > 0 ? Math.round(raw / step) * step : raw;
            return Mth.clamp(stepped, min, max);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(I18nHelper.getComponent(i18nKey, currentValue()));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentValue());
        }
    }
}
