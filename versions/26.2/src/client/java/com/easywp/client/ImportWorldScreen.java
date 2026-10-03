package com.easywp.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-world waypoint import picker, reached from {@code ModConfigScreen}'s own "Import" section -
 * see the Persistence section of CLAUDE.md for why this exists: a Realm's session address can
 * change between joins and orphan a whole world's own {@code waypoints_*.json} under its previous
 * address, and this screen is the manual escape hatch a player reaches for once
 * {@code loadFromFile()}'s log line has told them which file to look for.
 *
 * <p>Lists every {@code waypoints_*.json} under the config directory except the current world's
 * own (see {@code WaypointStore.listOtherWorldFiles()}), each as one vanilla button naming the
 * world/server it belongs to and how many waypoints it holds. Clicking a row opens
 * {@link ImportConfirmScreen}, which previews the waypoints and does the actual import; either
 * answer returns here, so a second file can be imported in the same visit.
 *
 * <p>Styled as a vanilla screen (default background, white title, vanilla {@link Button}s) rather
 * than with this mod's own dark modal, since it is reached from {@code ModConfigScreen}, which is
 * itself a native-looking options screen.
 */
public class ImportWorldScreen extends Screen {
    private static final int ROW_WIDTH = 250;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 24;
    private static final int TITLE_Y = 15;
    private static final int LIST_TOP = 40;
    private static final int FOOTER_HEIGHT = 36;

    private final Screen parentScreen;
    private final List<Path> files = new ArrayList<>();
    private int scrollIndex = 0;
    private int itemsPerPage = 1;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();

    /**
     * Each file's waypoint count, read the first time its row is shown and kept for as long as this
     * screen is. Counting means flushing pending saves and parsing the whole file, and the rows are
     * rebuilt on every scroll step and every return from {@link ImportConfirmScreen}. Nothing can
     * change these files meanwhile: the one file an import writes to is the current world's own,
     * which is never listed here.
     */
    private final Map<Path, Integer> waypointCounts = new HashMap<>();

    public ImportWorldScreen(Screen parentScreen) {
        super(I18nHelper.getComponent("import.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        this.files.clear();
        this.files.addAll(WaypointStore.listOtherWorldFiles());
        this.itemsPerPage = Math.max(1, (this.height - LIST_TOP - FOOTER_HEIGHT) / ROW_STEP);
        this.scrollIndex = Math.max(0, Math.min(this.scrollIndex, this.files.size() - this.itemsPerPage));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, btn -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());

        refreshItemWidgets();
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(this.minecraft, this.parentScreen);
    }

    private void refreshItemWidgets() {
        for (var widget : dynamicWidgets) {
            this.removeWidget(widget);
        }
        dynamicWidgets.clear();

        int rowX = this.width / 2 - ROW_WIDTH / 2;
        int endIndex = Math.min(scrollIndex + itemsPerPage, files.size());

        for (int i = scrollIndex; i < endIndex; i++) {
            Path file = files.get(i);
            int itemY = LIST_TOP + (i - scrollIndex) * ROW_STEP;
            Component label = I18nHelper.getComponent("import.row",
                    WaypointStore.prettyWorldName(file),
                    waypointCounts.computeIfAbsent(file, WaypointStore::countWaypointsIn));

            Button row = Button.builder(label, btn ->
                    ClientCompat.setScreen(this.minecraft, new ImportConfirmScreen(this, file)))
                    .bounds(rowX, itemY, ROW_WIDTH, ROW_HEIGHT)
                    .build();
            this.addRenderableWidget(row);
            dynamicWidgets.add(row);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, files.size() - itemsPerPage);
        int next = scrollIndex + (verticalAmount < 0 ? 1 : verticalAmount > 0 ? -1 : 0);
        next = Math.max(0, Math.min(next, maxScroll));
        if (next != scrollIndex) {
            scrollIndex = next;
            refreshItemWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, TITLE_Y, 0xFFFFFFFF);

        if (files.isEmpty()) {
            graphics.centeredText(this.font, I18nHelper.getComponent("import.no_files"),
                    this.width / 2, this.height / 2 - 4, 0xFFA0A0A0);
        }
    }

    // isPauseScreen() is left at the default (true), matching ModConfigScreen - this screen is
    // only reachable through it, and toggling pause on the way in and back out would be jarring.
}
