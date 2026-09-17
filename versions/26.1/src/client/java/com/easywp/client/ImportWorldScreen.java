package com.easywp.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-world waypoint import picker, reached from {@code ModConfigScreen}'s own new "Import"
 * section - see the Persistence section of CLAUDE.md for why this exists: a Realm's session
 * address can change between joins and orphan a whole world's own {@code waypoints_*.json} under
 * its previous address, and this screen is the manual escape hatch a player reaches for once
 * {@code loadFromFile()}'s log line has told them which file to look for.
 *
 * <p>Lists every {@code waypoints_*.json} under the config directory except the current world's
 * own (see {@code WaypointRenderer.listOtherWorldFiles()}), each as one full-width button naming
 * the world/server it belongs to and how many waypoints it holds. Clicking a row imports
 * immediately - see {@code WaypointRenderer.mergeFrom()} for exactly what "import" means here
 * (additive, deduplicated, never destructive) - and the screen stays open afterward so a second
 * file can be imported in the same visit without navigating back and forth.
 */
public class ImportWorldScreen extends Screen {
    private static final int ROW_WIDTH = 250;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 24;
    private static final int ITEMS_PER_PAGE = 6;

    private final Screen parentScreen;
    private final List<Path> files = new ArrayList<>();
    private int scrollIndex = 0;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();

    public ImportWorldScreen(Screen parentScreen) {
        super(I18nHelper.getComponent("import.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        this.files.clear();
        this.files.addAll(WaypointRenderer.listOtherWorldFiles());
        this.scrollIndex = 0;

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        this.addRenderableWidget(
            ModernButton.modernBuilder(I18nHelper.getComponent("menu.back"), btn -> {
                if (parentScreen != null) {
                    ClientCompat.setScreen(this.minecraft, parentScreen);
                } else {
                    this.onClose();
                }
            }).pos(centerX - 90, centerY + 85).size(180, 20).build()
        );

        refreshItemWidgets();
    }

    private void refreshItemWidgets() {
        for (var widget : dynamicWidgets) {
            this.removeWidget(widget);
        }
        dynamicWidgets.clear();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, files.size());

        for (int i = startIndex; i < endIndex; i++) {
            Path file = files.get(i);
            int itemY = centerY - 88 + (i - startIndex) * ROW_STEP;
            int count = WaypointRenderer.countWaypointsIn(file);
            Component label = I18nHelper.getComponent("import.row", WaypointRenderer.prettyWorldName(file), count);

            var importBtn = ModernButton.modernBuilder(label, btn -> {
                List<Waypoint> incoming = WaypointRenderer.loadWaypointsFrom(file);
                WaypointRenderer.MergeResult result = WaypointRenderer.mergeFrom(incoming);
                ClientCompat.setOverlayMessage(this.minecraft,
                        I18nHelper.getComponent("import.result", result.added(), result.skipped()), false);
            }).pos(centerX - ROW_WIDTH / 2, itemY).size(ROW_WIDTH, ROW_HEIGHT).build();
            this.addRenderableWidget(importBtn);
            dynamicWidgets.add(importBtn);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int total = files.size();
        if (total > ITEMS_PER_PAGE) {
            if (verticalAmount < 0 && scrollIndex < total - ITEMS_PER_PAGE) {
                scrollIndex++;
                refreshItemWidgets();
                return true;
            } else if (verticalAmount > 0 && scrollIndex > 0) {
                scrollIndex--;
                refreshItemWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int bgW = 290;
        int bgH = 226;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        graphics.fill(minX, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BG);
        graphics.fill(minX, minY, minX + bgW, minY + 1, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY + bgH - 1, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY, minX + 1, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX + bgW - 1, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);

        graphics.centeredText(this.font, this.getTitle(), centerX, minY + 6, UiPalette.TEXT_PRIMARY);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        if (files.isEmpty()) {
            graphics.centeredText(this.font, I18nHelper.getComponent("import.no_files"), centerX, centerY - 8, UiPalette.TEXT_SECONDARY);
        }
    }

    // isPauseScreen() is left at the default (true), matching ModConfigScreen - this screen is
    // only reachable through it, and toggling pause on the way in and back out would be jarring.
}
