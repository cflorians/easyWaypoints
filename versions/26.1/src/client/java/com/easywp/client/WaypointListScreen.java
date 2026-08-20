package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * Centered modal screen for browsing, filtering, and managing waypoints.
 */
public class WaypointListScreen extends Screen {
    private final Screen parentScreen;
    private int scrollIndex = 0;
    private static final int ITEMS_PER_PAGE = 6;
    private String selectedDimension = "minecraft:overworld";
    private WaypointSortMode sortMode = WaypointSortMode.CREATED;
    private EditBox searchField;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();

    // Cached state fields for zero-allocation render loop
    private List<WaypointEntry> cachedFilteredWaypoints = new ArrayList<>();
    private boolean cachedHasAnyFocused = false;
    private boolean cachedShowTP = false;

    /** The three vanilla dimensions, always offered so the tab order never shifts under the player. */
    private static final List<String> VANILLA_DIMENSIONS = List.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );

    /**
     * The tabs the < and > buttons cycle through: the vanilla three in their fixed order, then
     * every other dimension that actually holds waypoints.
     *
     * <p>Before this was rebuilt from the waypoints, the cycle was hardcoded to the vanilla three,
     * so a modded dimension could only be reached by opening the list while standing in it (the
     * constructor seeds the selection from the current level) - and it was labelled THE END, since
     * the header fell through to the last branch. One press of < or > left the tab, and nothing
     * could ever navigate back to it, stranding those waypoints with no way to edit or delete them.
     */
    private List<String> dimensions = VANILLA_DIMENSIONS;

    /**
     * Name column runs from just past the marker swatch (centerX - 117) up to just short of the
     * TP icon (centerX + 20), regardless of whether TP is actually shown for this player, so the
     * layout doesn't shift with permissions. Leaves a small gap so the text never touches the icon.
     * The focus toggle used to occupy this slot; merging it into the swatch on the left freed 20px,
     * and shifting the action-button row right (to equalize the left/right modal margins) freed 12 more.
     */
    private static final int NAME_MAX_WIDTH = 133;

    /**
     * Search box and sort button share the 260px the search box used to occupy on its own, at the
     * top of the modal: the box keeps the left {@code SEARCH_WIDTH}, the button the right
     * {@code SORT_BUTTON_WIDTH}, with a 4px gap between them.
     */
    private static final int SORT_BUTTON_WIDTH = 66;
    private static final int SEARCH_WIDTH = 260 - SORT_BUTTON_WIDTH - 4;

    /** Size of the hand-drawn sort glyph and the gap it keeps from the label it sits beside. */
    private static final int SORT_ICON_WIDTH = 3;
    private static final int SORT_ICON_GAP = 4;

    /**
     * One row's worth of data. An entry is built for every waypoint in the selected dimension on
     * every search keystroke, but only the six on screen are ever drawn, so the two Components -
     * the only part that costs anything, since laying out the name means measuring it against the
     * font - are built on demand instead of in the constructor.
     */
    private static class WaypointEntry {
        final Waypoint waypoint;
        final BlockPos displayPos;
        final boolean converted;
        private Component nameComponent;
        private Component coordsComponent;

        WaypointEntry(Waypoint waypoint, BlockPos displayPos, boolean converted) {
            this.waypoint = waypoint;
            this.displayPos = displayPos;
            this.converted = converted;
        }

        Component name(net.minecraft.client.gui.Font font) {
            if (nameComponent == null) {
                String name = waypoint.getName();
                if (converted) {
                    String prefix = waypoint.getDimension().equals("minecraft:overworld") ? "[OW] " : "[N] ";
                    name = prefix + name;
                }
                nameComponent = Component.literal(truncateToWidth(font, name, NAME_MAX_WIDTH));
            }
            return nameComponent;
        }

        Component coords() {
            if (coordsComponent == null) {
                coordsComponent = Component.literal(String.format("%d, %d, %d", displayPos.getX(), displayPos.getY(), displayPos.getZ()));
            }
            return coordsComponent;
        }
    }

    /**
     * Clips {@code text} to {@code maxWidth}, appending an ellipsis.
     *
     * <p>Walks the string once, keeping a running total, rather than re-measuring the whole prefix
     * for every character: the previous version asked the font to lay out a string one character
     * longer on each iteration, which made a long name cost O(n^2) measurements on every keystroke
     * in the search box.
     */
    private static String truncateToWidth(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;

        String ellipsis = "...";
        int budget = maxWidth - font.width(ellipsis);
        if (budget <= 0) return ellipsis;

        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int next = text.offsetByCodePoints(end, 1);
            int width = font.width(text.substring(end, next));
            if (used + width > budget) break;
            used += width;
            end = next;
        }
        return text.substring(0, end) + ellipsis;
    }

    public WaypointListScreen(Screen parentScreen) {
        super(I18nHelper.getComponent("menu.title"));
        this.parentScreen = parentScreen;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            this.selectedDimension = mc.level.dimension().identifier().toString();
        } else {
            this.selectedDimension = "minecraft:overworld";
        }

        this.sortMode = WaypointSortMode.fromName(ModConfig.get().list.sortMode);
    }

    private List<WaypointEntry> getWaypointsForDimension(String dimension) {
        List<WaypointEntry> result = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp == null) continue;

            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";

            if (wpDim.equals(dimension)) {
                result.add(new WaypointEntry(wp, wp.getPos(), false));
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && dimension.equals("minecraft:the_nether")) {
                    BlockPos netherPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                    result.add(new WaypointEntry(wp, netherPos, true));
                } else if (wpDim.equals("minecraft:the_nether") && dimension.equals("minecraft:overworld")) {
                    BlockPos overworldPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                    result.add(new WaypointEntry(wp, overworldPos, true));
                }
            }
        }
        return result;
    }

    private List<WaypointEntry> getFilteredWaypoints() {
        List<WaypointEntry> base = getWaypointsForDimension(selectedDimension);
        if (searchField == null) {
            sortEntries(base);
            return base;
        }
        String query = searchField.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            sortEntries(base);
            return base;
        }

        List<WaypointEntry> filtered = new ArrayList<>();
        for (WaypointEntry entry : base) {
            if (entry != null && entry.waypoint != null && entry.waypoint.getName() != null) {
                if (entry.waypoint.getName().toLowerCase(Locale.ROOT).contains(query)) {
                    filtered.add(entry);
                }
            }
        }
        sortEntries(filtered);
        return filtered;
    }

    /**
     * Applies the current {@link WaypointSortMode} in place. Runs when the list is rebuilt, not
     * per frame, so a DISTANCE ordering settles as you open or interact with the screen instead
     * of reshuffling under the cursor while you walk.
     */
    private void sortEntries(List<WaypointEntry> entries) {
        switch (sortMode) {
            case NAME -> entries.sort(Comparator.comparing(
                    entry -> entry.waypoint.getName() == null ? "" : entry.waypoint.getName(),
                    String.CASE_INSENSITIVE_ORDER));
            case DISTANCE -> {
                // Measured against the coordinates the row actually shows, so a shared waypoint
                // sorts by where it is in the dimension being viewed, not where it was placed.
                BlockPos from = this.minecraft != null && this.minecraft.player != null
                        ? this.minecraft.player.blockPosition()
                        : BlockPos.ZERO;
                entries.sort(Comparator.comparingDouble(entry -> squaredDistance(entry.displayPos, from)));
            }
            // CREATED leaves the insertion order alone, which is already the creation order.
            case CREATED -> { }
        }
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = (double) a.getX() - b.getX();
        double dy = (double) a.getY() - b.getY();
        double dz = (double) a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Recomputes {@link #dimensions}. The current dimension and the selected one are always kept,
     * so the tab being viewed cannot disappear mid-navigation - not even after deleting the last
     * waypoint in it.
     */
    private void updateDimensionTabs() {
        List<String> extra = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp == null) continue;
            String dim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            if (!VANILLA_DIMENSIONS.contains(dim) && !extra.contains(dim)) {
                extra.add(dim);
            }
        }
        if (this.minecraft != null && this.minecraft.level != null) {
            String current = this.minecraft.level.dimension().identifier().toString();
            if (!VANILLA_DIMENSIONS.contains(current) && !extra.contains(current)) {
                extra.add(current);
            }
        }
        if (!VANILLA_DIMENSIONS.contains(selectedDimension) && !extra.contains(selectedDimension)) {
            extra.add(selectedDimension);
        }

        if (extra.isEmpty()) {
            this.dimensions = VANILLA_DIMENSIONS;
            return;
        }

        // Sorted, so the extra tabs keep the same order every time the screen is opened instead
        // of following whatever order the waypoint list happens to be in.
        extra.sort(null);
        List<String> tabs = new ArrayList<>(VANILLA_DIMENSIONS);
        tabs.addAll(extra);
        this.dimensions = tabs;
    }

    /** Header label for a dimension tab. */
    private static String dimensionTitle(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld"  -> "OVERWORLD";
            case "minecraft:the_nether" -> "NETHER";
            case "minecraft:the_end"    -> "THE END";
            default -> prettyName(dimension).toUpperCase(Locale.ROOT);
        };
    }

    /** Dimension name used in the chat message the share button sends. */
    private static String dimensionChatName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld"  -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end"    -> "The End";
            default -> prettyName(dimension);
        };
    }

    /** Turns an identifier into something readable: "twilightforest:dark_forest" -> "Dark Forest". */
    private static String prettyName(String dimension) {
        int colon = dimension.indexOf(':');
        String path = colon >= 0 ? dimension.substring(colon + 1) : dimension;

        StringBuilder sb = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return sb.length() == 0 ? dimension : sb.toString();
    }

    private static int dimensionColor(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld"  -> UiPalette.DIM_OVERWORLD;
            case "minecraft:the_nether" -> UiPalette.DIM_NETHER;
            case "minecraft:the_end"    -> UiPalette.DIM_END;
            default -> UiPalette.TEXT_PRIMARY;
        };
    }

    /**
     * Whether waypoints in this dimension can be mirrored into another one. Only the Overworld and
     * the Nether have the 1:8 relationship the renderer knows how to convert, which is the same
     * rule WaypointCreateScreen already applies to its own share toggle. For the vanilla three
     * this is exactly the old {@code !equals("minecraft:the_end")} test.
     */
    private static boolean isConvertible(String dimension) {
        return dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_nether");
    }

    private void updateWaypointsCache() {
        updateDimensionTabs();
        this.cachedFilteredWaypoints = getFilteredWaypoints();
        this.cachedHasAnyFocused = false;
        for (WaypointEntry entry : cachedFilteredWaypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                this.cachedHasAnyFocused = true;
                break;
            }
        }
        this.cachedShowTP = this.minecraft != null && this.minecraft.player != null &&
                            this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        if (this.searchField == null) {
            this.searchField = new EditBox(this.font, centerX - 130, centerY - 90, SEARCH_WIDTH, 16, I18nHelper.getComponent("menu.search"));
            this.searchField.setHint(I18nHelper.getComponent("menu.search_hint"));
            this.searchField.setResponder(text -> {
                this.scrollIndex = 0;
                refreshItemWidgets();
            });
        } else {
            this.searchField.setPosition(centerX - 130, centerY - 90);
            this.searchField.setWidth(SEARCH_WIDTH);
        }

        this.addRenderableWidget(this.searchField);

        this.addRenderableWidget(
            ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.minecraft.setScreen(new ModConfigScreen(this));
            }).pos(8, this.height - 28).size(20, 20).build()
        );

        refreshItemWidgets();
    }

    private void refreshItemWidgets() {
        updateWaypointsCache();
        for (var widget : dynamicWidgets) {
            this.removeWidget(widget);
        }
        dynamicWidgets.clear();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int totalWaypoints = waypoints.size();
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean showTP = this.cachedShowTP;
        boolean finalHasAnyFocused = this.cachedHasAnyFocused;

        for (int i = startIndex; i < endIndex; i++) {
            WaypointEntry entry = waypoints.get(i);
            if (entry == null || entry.waypoint == null) continue;
            Waypoint wp = entry.waypoint;
            BlockPos pos = entry.displayPos;
            int offsetIndex = i - startIndex;
            int itemY = centerY - 60 + offsetIndex * 22;

            int buttonX = centerX + 120;

            var delBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (ModConfig.get().confirmations.confirmBeforeDelete) {
                    this.minecraft.setScreen(new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                WaypointRenderer.waypoints.remove(wp);
                                WaypointRenderer.saveToFile();
                            }
                            this.minecraft.setScreen(this);
                        },
                        I18nHelper.getComponent("menu.confirm_delete_title"),
                        I18nHelper.getComponent("menu.confirm_delete_text", wp.getName())
                    ));
                } else {
                    WaypointRenderer.waypoints.remove(wp);
                    WaypointRenderer.saveToFile();
                    refreshItemWidgets();
                }
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(delBtn);
            dynamicWidgets.add(delBtn);
            buttonX -= 20;

            var dimBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                wp.setShared(!wp.isShared());
                WaypointRenderer.saveToFile();
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18).build();
            dimBtn.active = isConvertible(selectedDimension);
            this.addRenderableWidget(dimBtn);
            dynamicWidgets.add(dimBtn);
            buttonX -= 20;

            var shareBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                    String dimName = dimensionChatName(selectedDimension);
                    String msg = String.format("%s -> [%d, %d, %d] at %s",
                        wp.getName().toUpperCase(Locale.ROOT), pos.getX(), pos.getY(), pos.getZ(), dimName);
                    this.minecraft.player.connection.sendChat(msg);
                    this.onClose();
                }
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(shareBtn);
            dynamicWidgets.add(shareBtn);
            buttonX -= 20;

            var editBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.minecraft.setScreen(new WaypointCreateScreen(wp));
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(editBtn);
            dynamicWidgets.add(editBtn);
            buttonX -= 20;

            var visBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (finalHasAnyFocused) {
                    if (!wp.isFocused()) {
                        wp.setForceVisible(!wp.isForceVisible());
                        wp.setVisible(true);
                    } else {
                        wp.setVisible(!wp.isVisible());
                    }
                } else {
                    wp.setVisible(!wp.isVisible());
                }
                WaypointRenderer.saveToFile();
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(visBtn);
            dynamicWidgets.add(visBtn);

            if (showTP) {
                buttonX -= 20;
                var tpBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                    if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                        String targetDim = wp.isShared() ? selectedDimension : (wp.getDimension() != null ? wp.getDimension() : selectedDimension);
                        String tpCommand = String.format("execute in %s run tp @s %d %d %d", targetDim, pos.getX(), pos.getY(), pos.getZ());
                        this.minecraft.player.connection.sendCommand(tpCommand);
                        this.onClose();
                    }
                }).pos(buttonX, itemY).size(18, 18).build();
                this.addRenderableWidget(tpBtn);
                dynamicWidgets.add(tpBtn);
            }

            // Focus toggle now lives on the marker swatch itself, at the left of the name.
            var focusBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (wp.isFocused()) {
                    wp.setFocused(false);
                    wp.setForceVisible(false);
                } else {
                    for (Waypoint w : WaypointRenderer.waypoints) {
                        if (w != null) {
                            w.setFocused(false);
                            w.setForceVisible(false);
                        }
                    }
                    wp.setFocused(true);
                    wp.setVisible(true);
                }
                WaypointRenderer.saveToFile();
                refreshItemWidgets();
            }).pos(centerX - 138, itemY).size(18, 18).build();
            this.addRenderableWidget(focusBtn);
            dynamicWidgets.add(focusBtn);
        }

        // Rebuilt here rather than in init() so its label tracks the mode it cycles.
        var sortBtn = ModernButton.modernBuilder(I18nHelper.getComponent(sortMode.getTranslationKey()), btn -> {
            this.sortMode = this.sortMode.next();
            ModConfig.get().list.sortMode = this.sortMode.name();
            ModConfig.save();
            this.scrollIndex = 0;
            refreshItemWidgets();
        }).pos(centerX - 130 + SEARCH_WIDTH + 4, centerY - 90).size(SORT_BUTTON_WIDTH, 16).build();
        this.addRenderableWidget(sortBtn);
        dynamicWidgets.add(sortBtn);

        var prevDimBtn = ModernButton.modernBuilder(Component.literal("<"), btn -> {
            int currentIndex = Math.max(0, dimensions.indexOf(selectedDimension));
            int prevIndex = (currentIndex - 1 + dimensions.size()) % dimensions.size();
            selectedDimension = dimensions.get(prevIndex);
            scrollIndex = 0;
            refreshItemWidgets();
        }).pos(centerX - 130, centerY + 86).size(20, 20).build();
        this.addRenderableWidget(prevDimBtn);
        dynamicWidgets.add(prevDimBtn);

        var newBtn = ModernButton.modernBuilder(I18nHelper.getComponent("menu.new"), btn -> {
            if (this.minecraft.player != null) {
                this.minecraft.setScreen(new WaypointCreateScreen(this.minecraft.player.blockPosition()));
            }
        }).pos(centerX - 105, centerY + 86).size(102, 20).build();
        this.addRenderableWidget(newBtn);
        dynamicWidgets.add(newBtn);

        var backBtn = ModernButton.modernBuilder(I18nHelper.getComponent("menu.back"), btn -> {
            if (parentScreen != null) {
                this.minecraft.setScreen(parentScreen);
            } else {
                this.onClose();
            }
        }).pos(centerX + 2, centerY + 86).size(103, 20).build();
        this.addRenderableWidget(backBtn);
        dynamicWidgets.add(backBtn);

        var nextDimBtn = ModernButton.modernBuilder(Component.literal(">"), btn -> {
            int currentIndex = Math.max(0, dimensions.indexOf(selectedDimension));
            int nextIndex = (currentIndex + 1) % dimensions.size();
            selectedDimension = dimensions.get(nextIndex);
            scrollIndex = 0;
            refreshItemWidgets();
        }).pos(centerX + 110, centerY + 86).size(20, 20).build();
        this.addRenderableWidget(nextDimBtn);
        dynamicWidgets.add(nextDimBtn);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int total = waypoints.size();
        if (total > ITEMS_PER_PAGE) {
            if (verticalAmount < 0) {
                if (scrollIndex < total - ITEMS_PER_PAGE) {
                    scrollIndex++;
                    refreshItemWidgets();
                    return true;
                }
            } else if (verticalAmount > 0) {
                if (scrollIndex > 0) {
                    scrollIndex--;
                    refreshItemWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int headerCenterX = centerX;

        int bgW = 290;
        int bgH = 226;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;

        graphics.fill(minX, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BG);
        graphics.fill(minX, minY, minX + bgW, minY + 1, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY + bgH - 1, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY, minX + 1, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX + bgW - 1, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);

        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int totalWaypoints = waypoints.size();

        String headerDimText = dimensionTitle(selectedDimension) + " (" + totalWaypoints + ")";
        graphics.centeredText(this.font, Component.literal(headerDimText), headerCenterX, centerY - 106,
                dimensionColor(selectedDimension));

        super.extractRenderState(graphics, mouseX, mouseY, a);

        graphics.blit(pipeline, Icons.CONFIG, 9, this.height - 27, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_IDLE);

        // Sort icon: drawn to the left of the button's own centered label instead of being baked
        // into ModernButton, so the label keeps its normal hover-reactive colour and the icon
        // just follows it. Position mirrors the sortBtn built in refreshItemWidgets().
        int sortBtnX = centerX - 130 + SEARCH_WIDTH + 4;
        int sortBtnY = centerY - 90;
        boolean sortHovered = mouseX >= sortBtnX && mouseX < sortBtnX + SORT_BUTTON_WIDTH
                && mouseY >= sortBtnY && mouseY < sortBtnY + 16;
        int sortIconColor = sortHovered ? UiPalette.BTN_TEXT_HOVER : UiPalette.BTN_TEXT;
        int sortLabelWidth = this.font.width(I18nHelper.getComponent(sortMode.getTranslationKey()));
        int sortTextLeftX = sortBtnX + SORT_BUTTON_WIDTH / 2 - sortLabelWidth / 2;
        drawSortIcon(graphics, sortTextLeftX - SORT_ICON_GAP - SORT_ICON_WIDTH, sortBtnY + 5, sortIconColor);

        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean hasAnyFocused = this.cachedHasAnyFocused;
        boolean showTP = this.cachedShowTP;

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, I18nHelper.getComponent("menu.no_waypoints"), headerCenterX, centerY + 5, UiPalette.TEXT_SECONDARY);
        } else {
            int arrowColor = UiPalette.SCROLL_ARROW;
            if (scrollIndex > 0) {
                drawUpTriangle(graphics, headerCenterX, centerY - 68, arrowColor);
            }

            if (startIndex + ITEMS_PER_PAGE < totalWaypoints) {
                drawDownTriangle(graphics, headerCenterX, centerY + 72, arrowColor);
            }

            for (int i = startIndex; i < endIndex; i++) {
                WaypointEntry entry = waypoints.get(i);
                if (entry == null || entry.waypoint == null) continue;
                Waypoint wp = entry.waypoint;
                int offsetIndex = i - startIndex;
                int itemY = centerY - 60 + offsetIndex * 22;

                boolean isDimmed = !wp.isVisible() || (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible());
                boolean focused = wp.isFocused();

                // Marker swatch doubles as the focus toggle: always filled with the waypoint's own
                // color, black border normally, gold border while focused.
                int swatchFill = isDimmed ? dim(wp.getColor()) : wp.getColor();
                int swatchOutlineBase = focused ? UiPalette.FOCUS_OUTLINE_ON : UiPalette.MARKER_OUTLINE;
                int swatchOutline = isDimmed ? dim(swatchOutlineBase) : swatchOutlineBase;
                Icons.drawMarkerIcon(graphics, pipeline, centerX - 138, itemY, swatchFill, swatchOutline, wp.isDeath());

                int nameColor = isDimmed ? UiPalette.TEXT_DIM : (entry.converted ? UiPalette.TEXT_CONVERTED : UiPalette.TEXT_PRIMARY);
                graphics.text(this.font, entry.name(this.font), centerX - 117, itemY + 1, nameColor);

                int coordsColor = isDimmed ? UiPalette.TEXT_DIM_SOFT : UiPalette.TEXT_SECONDARY;
                graphics.text(this.font, entry.coords(), centerX - 117, itemY + 10, coordsColor);

                if (showTP) {
                    graphics.blit(pipeline, Icons.TP, centerX + 20, itemY, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_ACCENT);
                }

                boolean isVisible = wp.isVisible();
                if (hasAnyFocused) {
                    isVisible = wp.isFocused() || wp.isForceVisible();
                }
                Identifier visIcon = isVisible ? Icons.SHOW : Icons.HIDE;
                int visColor = isVisible ? UiPalette.ICON_ACTIVE : UiPalette.ICON_DISABLED;
                graphics.blit(pipeline, visIcon, centerX + 40, itemY, 0.0f, 0.0f, 18, 18, 18, 18, visColor);

                graphics.blit(pipeline, Icons.EDIT, centerX + 60, itemY, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_IDLE);
                graphics.blit(pipeline, Icons.SHARE, centerX + 80, itemY, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_IDLE);

                Icons.drawPortalIcon(graphics, pipeline, centerX + 100, itemY,
                        wp.isShared(), isConvertible(selectedDimension));

                graphics.blit(pipeline, Icons.DELETE, centerX + 120, itemY, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_DESTRUCTIVE);
            }

            if (totalWaypoints > ITEMS_PER_PAGE) {
                int scrollbarX = centerX + 140;
                int scrollbarY = centerY - 60;
                int scrollbarHeight = 128;

                graphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, UiPalette.SCROLL_TRACK);

                float visibleRatio = (float) ITEMS_PER_PAGE / totalWaypoints;
                int thumbHeight = Math.max(12, (int) (scrollbarHeight * visibleRatio));

                float scrollRatio = (float) scrollIndex / (totalWaypoints - ITEMS_PER_PAGE);
                int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * scrollRatio);

                graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, UiPalette.SCROLL_THUMB);
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ModKeyBindings.listWaypointsKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Darkens an ARGB color for rows hidden or filtered out by an active focus. */
    private static int dim(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0x60 << 24) | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
    }

    /**
     * Stacked up/down chevrons - a compact "sort" glyph - drawn the same hand-pixel way as the
     * scroll arrows below instead of as a texture asset, since it is only ever used at this one
     * size and colour pair. {@code x, y} is the top-left corner of the 3x5 icon.
     */
    private void drawSortIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 1, y, x + 2, y + 1, color);
        graphics.fill(x, y + 1, x + 3, y + 2, color);
        graphics.fill(x, y + 3, x + 3, y + 4, color);
        graphics.fill(x + 1, y + 4, x + 2, y + 5, color);
    }

    private void drawUpTriangle(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        graphics.fill(centerX, startY, centerX + 1, startY + 1, color);
        graphics.fill(centerX - 1, startY + 1, centerX + 2, startY + 2, color);
        graphics.fill(centerX - 2, startY + 2, centerX + 3, startY + 3, color);
        graphics.fill(centerX - 3, startY + 3, centerX + 4, startY + 4, color);
    }

    private void drawDownTriangle(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        graphics.fill(centerX - 3, startY, centerX + 4, startY + 1, color);
        graphics.fill(centerX - 2, startY + 1, centerX + 3, startY + 2, color);
        graphics.fill(centerX - 1, startY + 2, centerX + 2, startY + 3, color);
        graphics.fill(centerX, startY + 3, centerX + 1, startY + 4, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}


