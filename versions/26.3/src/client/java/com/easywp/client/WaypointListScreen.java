package com.easywp.client;

import com.easywp.XaeroWaypointFormat;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
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
    private String selectedDimension = DimensionNames.OVERWORLD;
    private WaypointSortMode sortMode = WaypointSortMode.CREATED;
    private EditBox searchField;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();

    // Cached state fields for zero-allocation render loop
    private List<WaypointEntry> cachedFilteredWaypoints = new ArrayList<>();
    private boolean cachedHasAnyFocused = false;
    private boolean cachedShowTP = false;

    /**
     * Whether to offer the Xaero Import/Export pair at all. Resolved once per {@link #init()} rather
     * than per frame, and read by both the widget creation and the glyph drawing so the two can never
     * disagree about whether the buttons exist. Gated on Xaero merely being <em>installed</em>, not on
     * {@code XaeroBridge.isAvailable()}: if the live bridge is unusable (an incompatible Xaero
     * release) the two actions still work through the {@code waypoints.txt} fallback, and hiding the
     * buttons there would remove the only way to reach it.
     */
    private boolean showXaeroButtons = false;


    /** The three vanilla dimensions, always offered so the tab order never shifts under the player. */
    private static final List<String> VANILLA_DIMENSIONS = List.of(
        DimensionNames.OVERWORLD,
        DimensionNames.NETHER,
        DimensionNames.END
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
     * The Xaero pair sits in the screen's bottom-right corner, mirroring the config gear in the
     * bottom-left: same 20x20 size, same 8px margin from its own edge, same anchoring to the
     * <em>screen</em> rather than to the modal, so neither moves when the modal's contents change.
     * 24px of pitch puts a 4px gap between the two.
     */
    private static final int CORNER_BUTTON_SIZE = 20;
    private static final int CORNER_BUTTON_MARGIN = 8;
    private static final int CORNER_BUTTON_PITCH = 24;

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
                    String prefix = waypoint.dimensionOrOverworld().equals(DimensionNames.OVERWORLD) ? "[OW] " : "[N] ";
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
            this.selectedDimension = DimensionNames.OVERWORLD;
        }

        this.sortMode = WaypointSortMode.fromName(ModConfig.get().list.sortMode);
    }

    private List<WaypointEntry> getWaypointsForDimension(String dimension) {
        List<WaypointEntry> result = new ArrayList<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp == null) continue;
            BlockPos pos = wp.positionIn(dimension);
            if (pos != null) {
                // Shown here but belonging elsewhere: a shared waypoint's converted copy.
                result.add(new WaypointEntry(wp, pos, !wp.dimensionOrOverworld().equals(dimension)));
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
            // By creation time. List.sort is stable, so waypoints from before 1.4.0 - all at 0, their
            // real time never recorded - keep their insertion order among themselves and come
            // first, which is exactly where the old "no sort" put them. A waypoint imported from
            // another world sorts by when it was originally created, not when it was imported.
            case CREATED -> entries.sort(Comparator.comparingLong(entry -> entry.waypoint.getCreatedAtMillis()));
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
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp == null) continue;
            String dim = wp.dimensionOrOverworld();
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

    private void updateWaypointsCache() {
        updateDimensionTabs();
        this.cachedFilteredWaypoints = getFilteredWaypoints();
        // Over every waypoint, as the renderer counts it - not just the rows on this tab or matching
        // the search: a focus in another dimension, or filtered out by the search, still hides
        // these rows' waypoints in the world, so the rows have to show them hidden too.
        this.cachedHasAnyFocused = WaypointStore.hasAnyFocused();
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
                ClientCompat.setScreen(this.minecraft, new ModConfigScreen(this));
            }).pos(CORNER_BUTTON_MARGIN, this.height - 28).size(CORNER_BUTTON_SIZE, CORNER_BUTTON_SIZE)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.CONFIG, b.getX() + 1, b.getY() + 1, 18, 18, UiPalette.ICON_IDLE))
                    .build()
        );

        this.showXaeroButtons = ModConfig.get().xaeroIntegration.enabled && XaeroBridge.isModInstalled();
        if (this.showXaeroButtons) {
            var importBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                reportSync("xaero.imported", XaeroSync.importNow());
                // Rebuild the rows: imported and removed waypoints are already applied to the list,
                // but this screen only rereads it when something tells it to, so the change would
                // otherwise stay invisible until the next keystroke or dimension switch.
                refreshItemWidgets();
            }).pos(xaeroButtonX(1), this.height - 28).size(CORNER_BUTTON_SIZE, CORNER_BUTTON_SIZE)
                    .icon((g, b, hovered) -> drawImportGlyph(g, b.getX() + b.getWidth() / 2, cornerGlyphY(b), UiPalette.ICON_IDLE))
                    .build();
            importBtn.setTooltip(Tooltip.create(I18nHelper.getComponent("list.xaero_import")));
            this.addRenderableWidget(importBtn);

            var exportBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                reportSync("xaero.exported", XaeroSync.exportNow());
            }).pos(xaeroButtonX(0), this.height - 28).size(CORNER_BUTTON_SIZE, CORNER_BUTTON_SIZE)
                    .icon((g, b, hovered) -> drawExportGlyph(g, b.getX() + b.getWidth() / 2, cornerGlyphY(b), UiPalette.ICON_IDLE))
                    .build();
            exportBtn.setTooltip(Tooltip.create(I18nHelper.getComponent("list.xaero_export")));
            this.addRenderableWidget(exportBtn);
        }

        refreshItemWidgets();
    }

    /** Left edge of the Xaero corner button {@code slotFromRight} places in from the screen's right
     *  edge: 0 is the outermost (Export), 1 the one beside it (Import). */
    private int xaeroButtonX(int slotFromRight) {
        return this.width - CORNER_BUTTON_MARGIN - CORNER_BUTTON_SIZE - slotFromRight * CORNER_BUTTON_PITCH;
    }

    /** The Xaero glyphs are 7px tall (4 of triangle, a 2px gap, 1 of bar), centred in the button. */
    private static int cornerGlyphY(ModernButton button) {
        return button.getY() + (button.getHeight() - 7) / 2;
    }

    /** Reports a sync in the same transient action-bar overlay every other mutating action here uses.
     *  Both counts are always shown, including zeroes: a sync that removes waypoints should say so
     *  plainly, and "0 added, 0 removed" is a meaningful answer rather than a reason to stay silent. */
    private void reportSync(String messageKey, XaeroSync.SyncResult result) {
        ClientCompat.setOverlayMessage(this.minecraft, result == null
                ? I18nHelper.getComponent("xaero.unavailable")
                : I18nHelper.getComponent(messageKey, String.valueOf(result.added()), String.valueOf(result.removed())), true);
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
        // The list can shrink under the current scroll position (a delete on the last page, a Xaero
        // import removing rows): without this the page would start past the new end, hiding rows
        // above it that mouseScrolled could no longer reach once the total dropped to one page.
        scrollIndex = Math.max(0, Math.min(scrollIndex, totalWaypoints - ITEMS_PER_PAGE));
        // Same reason: a half-finished double-click names its row by index, which may now be a
        // different waypoint's.
        lastCoordsClickIndex = -1;
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
                    ClientCompat.setScreen(this.minecraft, new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                WaypointStore.waypoints.remove(wp);
                                WaypointStore.saveToFile();
                            }
                            ClientCompat.setScreen(this.minecraft, this);
                        },
                        I18nHelper.getComponent("menu.confirm_delete_title"),
                        I18nHelper.getComponent("menu.confirm_delete_text", wp.getName())
                    ));
                } else {
                    WaypointStore.waypoints.remove(wp);
                    WaypointStore.saveToFile();
                    refreshItemWidgets();
                }
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.DELETE, b.getX(), b.getY(), 18, 18, UiPalette.ICON_DESTRUCTIVE))
                    .build();
            this.addRenderableWidget(delBtn);
            dynamicWidgets.add(delBtn);
            buttonX -= 20;

            var dimBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                wp.setShared(!wp.isShared());
                WaypointStore.saveToFile();
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> Icons.drawPortalIcon(g, b.getX(), b.getY(), wp.isShared(), b.active))
                    .build();
            dimBtn.active = DimensionNames.isConvertible(selectedDimension);
            this.addRenderableWidget(dimBtn);
            dynamicWidgets.add(dimBtn);
            buttonX -= 20;

            var shareBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                    String msg;
                    if (ModConfig.get().xaeroIntegration.xaeroChatFormat) {
                        // Xaero's own chat listener recognizes this exact prefix and offers
                        // recipients who run it an [Add] button - see XaeroWaypointFormat.
                        // The set name is irrelevant to chatShareLine (the chat format carries no set
                        // field at all - see its javadoc) but the convenience constructor requires one.
                        XaeroWaypointFormat.XaeroWaypoint xwp = new XaeroWaypointFormat.XaeroWaypoint(
                                wp.getName(), pos.getX(), pos.getY(), pos.getZ(),
                                XaeroWaypointFormat.nearestColorOrdinal(wp.getColor()),
                                XaeroWaypointFormat.DEFAULT_SET_NAME
                        );
                        msg = XaeroWaypointFormat.chatShareLine(xwp);
                    } else {
                        // Plain text, no color codes: vanilla strips the section sign from any
                        // chat message a player sends before broadcasting it (a long-standing
                        // anti-spoofing measure), so a client mod cannot color another player's
                        // view of this message - only the in-game list itself can use DimensionNames'
                        // per-dimension colors, which this line can't carry over the network.
                        String dimName = DimensionNames.chatName(selectedDimension);
                        msg = I18nHelper.getComponent("share.chat",
                                wp.getName().toUpperCase(Locale.ROOT), dimName,
                                String.valueOf(pos.getX()), String.valueOf(pos.getY()), String.valueOf(pos.getZ())
                        ).getString();
                    }
                    this.minecraft.player.connection.sendChat(msg);
                    this.onClose();
                }
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.SHARE, b.getX(), b.getY(), 18, 18, UiPalette.ICON_IDLE))
                    .build();
            this.addRenderableWidget(shareBtn);
            dynamicWidgets.add(shareBtn);
            buttonX -= 20;

            var editBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                // Opened from here, the form comes back here - see WaypointCreateScreen's returnScreen.
                ClientCompat.setScreen(this.minecraft, new WaypointCreateScreen(wp, this));
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.EDIT, b.getX(), b.getY(), 18, 18, UiPalette.ICON_IDLE))
                    .build();
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
                WaypointStore.saveToFile();
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> {
                        // The renderer's own rule: a hidden focused waypoint used to keep an open
                        // eye here, since with a focus active only focused/forceVisible was checked.
                        boolean shown = wp.isShown(this.cachedHasAnyFocused);
                        ClientCompat.blitTinted(g, shown ? Icons.SHOW : Icons.HIDE, b.getX(), b.getY(), 18, 18,
                                shown ? UiPalette.ICON_IDLE : UiPalette.ICON_DISABLED);
                    })
                    .build();
            this.addRenderableWidget(visBtn);
            dynamicWidgets.add(visBtn);

            if (showTP) {
                buttonX -= 20;
                var tpBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                    if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                        String targetDim = wp.isShared() ? selectedDimension : (wp.getDimension() != null ? wp.getDimension() : selectedDimension);
                        // Block centre on X/Z (x + 0.5, not "%d.5", which is wrong for negatives),
                        // so the player lands in the middle of the block rather than on its corner.
                        String tpCommand = String.format(Locale.ROOT, "execute in %s run tp @s %.1f %d %.1f",
                                targetDim, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        this.minecraft.player.connection.sendCommand(tpCommand);
                        this.onClose();
                    }
                }).pos(buttonX, itemY).size(18, 18)
                        .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.TP, b.getX(), b.getY(), 18, 18, UiPalette.ICON_ACCENT))
                        .build();
                this.addRenderableWidget(tpBtn);
                dynamicWidgets.add(tpBtn);
            }

            // Focus toggle now lives on the marker swatch itself, at the left of the name.
            var focusBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (wp.isFocused()) {
                    // Every "kept visible" goes with the focus it was kept beside. Only this one's
                    // flag used to be cleared, the rest waiting for the next focus to clear them:
                    // invisible while nothing is focused, but now that forceVisible is saved,
                    // leaving them would write stale flags into the file.
                    wp.setFocused(false);
                    for (Waypoint w : WaypointStore.waypoints) {
                        if (w != null) {
                            w.setForceVisible(false);
                        }
                    }
                } else {
                    for (Waypoint w : WaypointStore.waypoints) {
                        if (w != null) {
                            w.setFocused(false);
                            w.setForceVisible(false);
                        }
                    }
                    wp.setFocused(true);
                    wp.setVisible(true);
                }
                WaypointStore.saveToFile();
                refreshItemWidgets();
            }).pos(centerX - 138, itemY).size(18, 18)
                    .icon((g, b, hovered) -> {
                        // The marker swatch doubles as the focus toggle: always filled with the
                        // waypoint's own color, black border normally, gold border while focused.
                        boolean dimmed = isDimmed(wp);
                        int fill = dimmed ? dim(wp.getColor()) : wp.getColor();
                        int outlineBase = wp.isFocused() ? UiPalette.FOCUS_OUTLINE_ON : UiPalette.MARKER_OUTLINE;
                        Icons.drawMarkerIcon(g, b.getX(), b.getY(), fill, dimmed ? dim(outlineBase) : outlineBase, wp.isDeath());
                    })
                    .build();
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
        }).pos(centerX - 130 + SEARCH_WIDTH + 4, centerY - 90).size(SORT_BUTTON_WIDTH, 16)
                .icon((g, b, hovered) -> {
                    // Sort glyph just left of the button's own centred label, following its
                    // hover colour, rather than baked into the label text.
                    int labelLeft = b.getX() + b.getWidth() / 2 - this.font.width(b.getMessage()) / 2;
                    drawSortIcon(g, labelLeft - SORT_ICON_GAP - SORT_ICON_WIDTH, b.getY() + 5,
                            hovered ? UiPalette.BTN_TEXT_HOVER : UiPalette.BTN_TEXT);
                })
                .build();
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
                ClientCompat.setScreen(this.minecraft, new WaypointCreateScreen(this.minecraft.player.blockPosition(), this));
            }
        }).pos(centerX - 105, centerY + 86).size(102, 20).build();
        this.addRenderableWidget(newBtn);
        dynamicWidgets.add(newBtn);

        var backBtn = ModernButton.modernBuilder(I18nHelper.getComponent("menu.back"), btn -> {
            if (parentScreen != null) {
                ClientCompat.setScreen(this.minecraft, parentScreen);
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

    /** Window for the double-click-to-copy gesture below, hand-timed rather than trusting the
     *  engine's own {@code doubleClick} parameter: confirmed from {@code MouseHandler}'s own
     *  bytecode that the one call site reaching a {@code Screen} subclass's {@code mouseClicked}
     *  override always passes a literal {@code false} - real double-click detection happens deeper
     *  inside {@code Screen}'s default widget-dispatch logic, which overriding this method bypasses
     *  entirely. 250ms matches the feel of double-click elsewhere in vanilla's own UI. */
    private static final long COORDS_DOUBLE_CLICK_MS = 250;
    private long lastCoordsClickTime = 0L;
    private int lastCoordsClickIndex = -1;

    /** How long the inline "Copied!" feedback (drawn beside the coordinates, see
     *  {@code extractRenderState}) stays up after a successful copy. */
    private static final long COPIED_FEEDBACK_MS = 1000;
    /** The waypoint whose row shows the feedback - the waypoint, not its row index, which would
     *  point at another row if the list changed meanwhile (a delete within that second). */
    private Waypoint copiedFeedbackWaypoint = null;
    private long copiedFeedbackUntilMillis = 0L;

    /**
     * Double-clicking a row's name or coordinates text copies the coordinates to the clipboard -
     * replaces what used to be a dedicated button in the action strip, which had no room left once
     * the strip already ran from the swatch to within 7px of the modal's own edge. There is no
     * per-region callback for a plain drawn text area (unlike e.g. {@code EditBox}'s own
     * word-select), so the hit test below (mirroring where {@code extractRenderState} actually
     * draws the text) is the only custom part needed besides the timing above.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int hitIndex = nameOrCoordsRowAt(event.x(), event.y());
            if (hitIndex >= 0) {
                long now = System.currentTimeMillis();
                if (hitIndex == lastCoordsClickIndex && now - lastCoordsClickTime <= COORDS_DOUBLE_CLICK_MS) {
                    WaypointEntry hit = this.cachedFilteredWaypoints.get(hitIndex);
                    BlockPos displayPos = hit.displayPos;
                    String coords = String.format("%d, %d, %d", displayPos.getX(), displayPos.getY(), displayPos.getZ());
                    this.minecraft.keyboardHandler.setClipboard(coords);
                    // Inline feedback beside the coordinates themselves, not a HUD overlay message -
                    // see extractRenderState, which draws it for exactly COPIED_FEEDBACK_MS.
                    copiedFeedbackWaypoint = hit.waypoint;
                    copiedFeedbackUntilMillis = now + COPIED_FEEDBACK_MS;
                    lastCoordsClickTime = 0L;
                    lastCoordsClickIndex = -1;
                    return true;
                }
                lastCoordsClickTime = now;
                lastCoordsClickIndex = hitIndex;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** @return the index into {@link #cachedFilteredWaypoints} (i.e. the currently visible page)
     *  whose name or coordinates text sits under {@code (mouseX, mouseY)}, or -1. Mirrors the row
     *  layout {@code extractRenderState} actually draws with - the full name+coords column, both
     *  lines, rather than a separate widget's own bounds - so a double-click lands the same whether
     *  it's on the row's name or its coordinates. */
    private int nameOrCoordsRowAt(double mouseX, double mouseY) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, this.cachedFilteredWaypoints.size());
        for (int i = startIndex; i < endIndex; i++) {
            int itemY = centerY - 60 + (i - startIndex) * 22;
            if (mouseX >= centerX - 117 && mouseX < centerX - 117 + NAME_MAX_WIDTH
                    && mouseY >= itemY && mouseY < itemY + 21) {
                return i;
            }
        }
        return -1;
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

        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int totalWaypoints = waypoints.size();

        String headerDimText = DimensionNames.title(selectedDimension) + " (" + totalWaypoints + ")";
        graphics.centeredText(this.font, Component.literal(headerDimText), centerX, centerY - 106,
                DimensionNames.color(selectedDimension));

        super.extractRenderState(graphics, mouseX, mouseY, a);

        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, I18nHelper.getComponent("menu.no_waypoints"), centerX, centerY + 5, UiPalette.TEXT_SECONDARY);
        } else {
            int arrowColor = UiPalette.SCROLL_ARROW;
            if (scrollIndex > 0) {
                drawUpTriangle(graphics, centerX, centerY - 68, arrowColor);
            }

            if (startIndex + ITEMS_PER_PAGE < totalWaypoints) {
                drawDownTriangle(graphics, centerX, centerY + 72, arrowColor);
            }

            for (int i = startIndex; i < endIndex; i++) {
                WaypointEntry entry = waypoints.get(i);
                if (entry == null || entry.waypoint == null) continue;
                Waypoint wp = entry.waypoint;
                int offsetIndex = i - startIndex;
                int itemY = centerY - 60 + offsetIndex * 22;

                boolean isDimmed = isDimmed(wp);

                int nameColor = isDimmed ? UiPalette.TEXT_DIM : (entry.converted ? UiPalette.TEXT_CONVERTED : UiPalette.TEXT_PRIMARY);
                graphics.text(this.font, entry.name(this.font), centerX - 117, itemY + 1, nameColor);

                int coordsColor = isDimmed ? UiPalette.TEXT_DIM_SOFT : UiPalette.TEXT_SECONDARY;
                Component coordsComponent = entry.coords();
                graphics.text(this.font, coordsComponent, centerX - 117, itemY + 10, coordsColor);

                // Inline "Copied!" feedback right after the coordinates text, for exactly
                // COPIED_FEEDBACK_MS after a double-click copy - see mouseClicked/nameOrCoordsRowAt.
                if (wp == copiedFeedbackWaypoint && System.currentTimeMillis() < copiedFeedbackUntilMillis) {
                    int copiedX = centerX - 117 + this.font.width(coordsComponent) + 4;
                    graphics.text(this.font, I18nHelper.getComponent("list.coords_copied"), copiedX, itemY + 10, UiPalette.TEXT_SUCCESS);
                }
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

    /**
     * The list key closes the list again - except while a text field has the focus, where it is
     * just a letter being typed. Checking the key first, as this used to, made the search box
     * unable to take that letter at all: typing "jungle" with the default J closed the screen.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!(this.getFocused() instanceof EditBox) && ModKeyBindings.listWaypointsKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Whether a row is shown dimmed: its waypoint is not drawn in the world - hidden, or filtered
     *  out by another waypoint's focus. */
    private boolean isDimmed(Waypoint wp) {
        return !wp.isShown(this.cachedHasAnyFocused);
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

    /**
     * Arrow-onto-a-bar, the universal download glyph, for the Xaero Import button - hand-drawn with
     * {@code fill} rather than added as a texture asset, the same choice {@link #drawSortIcon} already
     * documents: it is only ever used at this one size and colour. The bar is what distinguishes it
     * from the bare scroll arrows above, which have none.
     */
    private void drawImportGlyph(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        drawDownTriangle(graphics, centerX, startY, color);
        graphics.fill(centerX - 4, startY + 6, centerX + 5, startY + 7, color);
    }

    /** Arrow-off-a-bar, the counterpart to {@link #drawImportGlyph}, for the Xaero Export button. */
    private void drawExportGlyph(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        drawUpTriangle(graphics, centerX, startY, color);
        graphics.fill(centerX - 4, startY + 6, centerX + 5, startY + 7, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}


