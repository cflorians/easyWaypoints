package com.easywp.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;

/**
 * Mod settings screen, built entirely from vanilla widgets (sliders, cycle
 * button, plain buttons) laid out with vanilla's own header/footer layout so
 * it matches the look of Minecraft's own options screens, instead of the
 * mod's custom dark-blue modal style used by the waypoint list/create screens.
 * Each setting has its own reset-to-default button next to it. Keybindings
 * are not edited here - the mod's keys are already registered under their
 * own vanilla category and are rebound from Minecraft's own Controls screen.
 */
public class ModConfigScreen extends Screen {
    private final WaypointListScreen parentScreen;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    private static final int ROW_WIDTH = 250;
    private static final int ROW_HEIGHT = 20;
    private static final int CONTROL_WIDTH = 194;
    private static final int RESET_WIDTH = 50;
    private static final int ROW_SPACING = 6;

    // Matches vanilla's own per-keybind reset button (see KeyBindsList) so the label reads
    // exactly the way Minecraft's own Controls screen does, in whatever language it's set to.
    private static final Component RESET_LABEL = Component.translatable("controls.reset");

    private static final double SIZE_PERCENT_FLOOR = 10.0;
    private static final double SIZE_PERCENT_CEIL = 300.0;

    private static final double RADIUS_FLOOR = 1.0;
    private static final double RADIUS_CEIL = 50.0;

    private static final double GRACE_FLOOR = 0.5;
    private static final double GRACE_CEIL = 30.0;

    public ModConfigScreen(WaypointListScreen parentScreen) {
        super(I18nHelper.getComponent("config.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(8));
        header.addChild(new StringWidget(this.getTitle(), this.font), LayoutSettings::alignHorizontallyCenter);

        ModConfig cfg = ModConfig.get();
        LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));

        content.addChild(row(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, SIZE_PERCENT_FLOOR, SIZE_PERCENT_CEIL, 1.0,
                        cfg.waypointSize.sizePercent, "config.size_row", this::applySizePercent),
                this::resetSizePercent
        ));

        content.addChild(row(
                CycleButton.onOffBuilder(cfg.deathWaypoints.enabled)
                        .create(0, 0, CONTROL_WIDTH, ROW_HEIGHT, I18nHelper.getComponent("config.section.death_waypoint"),
                                (button, value) -> {
                                    ModConfig.get().deathWaypoints.enabled = value;
                                    ModConfig.save();
                                }),
                this::resetDeathEnabled
        ));

        content.addChild(row(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, RADIUS_FLOOR, RADIUS_CEIL, 1.0,
                        cfg.deathWaypoints.radius, "config.radius_row", this::applyRadius),
                this::resetRadius
        ));

        content.addChild(row(
                new ConfigSlider(CONTROL_WIDTH, ROW_HEIGHT, GRACE_FLOOR, GRACE_CEIL, 0.5,
                        cfg.deathWaypoints.graceSeconds, "config.grace_row", this::applyGrace),
                this::resetGrace
        ));

        this.layout.addToFooter(Button.builder(I18nHelper.getComponent("menu.back"), btn -> {
            this.minecraft.setScreenAndShow(this.parentScreen);
        }).width(ROW_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Pairs a control widget with a small reset-to-default button to its right. */
    private LinearLayout row(LayoutElement control, Runnable onReset) {
        LinearLayout rowLayout = LinearLayout.horizontal().spacing(ROW_SPACING);
        rowLayout.addChild(control);
        rowLayout.addChild(Button.builder(RESET_LABEL, btn -> onReset.run())
                .width(RESET_WIDTH)
                .build());
        return rowLayout;
    }

    private void applySizePercent(double v) {
        ModConfig cfg = ModConfig.get();
        cfg.waypointSize.sizePercent = clamp(v, SIZE_PERCENT_FLOOR, SIZE_PERCENT_CEIL);
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

    private void resetSizePercent() {
        ModConfig.get().waypointSize.sizePercent = new ModConfig.WaypointSize().sizePercent;
        ModConfig.save();
        reopen();
    }

    private void resetDeathEnabled() {
        ModConfig.get().deathWaypoints.enabled = new ModConfig.DeathWaypoints().enabled;
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

    /** Rebuilds the screen from scratch so every widget reflects the freshly-reset config value. */
    private void reopen() {
        this.minecraft.setScreenAndShow(new ModConfigScreen(this.parentScreen));
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
