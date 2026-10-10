package com.easywp.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import com.easywp.WaypointGroups;

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

    /**
     * The waypoint whose "More options" menu is open, or {@code null}. The menu is a small panel
     * beside that row, drawn and clicked by hand on top of everything else rather than built from
     * widgets (see {@link #openMenu()}), and it follows the waypoint, not a row index: toggling a
     * favorite moves the row to the top and the menu goes with it, and if the row leaves the page
     * the menu closes.
     */
    private Waypoint optionsFor = null;

    /** Whether the groups page's last row is the field for typing a new group's name. See
     *  {@link #typingGroup()}. */
    private boolean newGroupTyping = false;
    /** The field behind that row: a widget of this screen (so it takes keys and focus), drawn by hand
     *  over the menu's row in {@link #extractRenderState}, since the menu is drawn above the widgets. */
    private EditBox newGroupField;

    /** Whether that menu shows its groups page (see {@link #groupItemsFor}) instead of its main one. */
    private boolean optionsGroupsPage = false;

    /** Whether the group filter's menu is open, below its button. At most one menu is open: opening
     *  either closes the other. */
    private boolean groupMenuOpen = false;

    /** The open menu's scroll offset, kept here since the menu is rebuilt every frame. */
    private int menuScroll = 0;

    /** What the group filter keeps - all waypoints, those in no group, or one group's - and that
     *  group's name. Not saved: the list opens on "All" every time. */
    private WaypointGroups.Filter groupFilter = WaypointGroups.Filter.ALL;
    private String groupFilterName = null;

    /** {@link WaypointStore#removalsVersion()} when the rows were last built - see {@link #tick()}. */
    private int seenRemovalsVersion = WaypointStore.removalsVersion();


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
     * Name column runs from just past the marker swatch (centerX - 117) up to just short of the TP
     * icon (centerX + 40), whether or not TP is shown for this player, so the layout doesn't shift
     * with permissions. The focus toggle used to occupy this slot; merging it into the swatch on the
     * left freed 20px, shifting the action-button row right (to equalize the left/right modal
     * margins) freed 12 more, and the share button moving into "More options" (1.4.0) 20 more.
     */
    private static final int NAME_MAX_WIDTH = 155;

    /** A row pending deletion keeps the narrower column: its Undo and Delete start at centerX + 20
     *  (see PENDING_BUTTON_W), where the name used to end. */
    private static final int PENDING_NAME_WIDTH = 133;

    /**
     * A favorite's name starts after its 9px star and a 2px gap, and gives up that width. Only
     * favorites move: a list without any looks exactly as it did, rather than every row losing
     * the space to a star slot that stays empty.
     */
    private static final int STAR_SLOT = 11;

    private static int nameIndent(Waypoint wp) {
        return wp.isFavorite() ? STAR_SLOT : 0;
    }

    /**
     * The widest name a player can give a waypoint: what fits in a row before the TP icon even with
     * a favorite's star in front, so a name typed in the form or with {@code /easywp add} is always
     * shown whole, never cut with an ellipsis. A width, not a character count: the font is
     * proportional, so "iiii" and "WWWW" take very different room. Names from elsewhere (another
     * world, Xaero, the clipboard) keep theirs and may still be cut here.
     */
    static final int NAME_FIT_WIDTH = NAME_MAX_WIDTH - STAR_SLOT;

    /** The longest start of {@code name} within {@link #NAME_FIT_WIDTH}. */
    static String fitName(net.minecraft.client.gui.Font font, String name) {
        return font.plainSubstrByWidth(name, NAME_FIT_WIDTH);
    }

    /**
     * A row pending deletion shows Undo and Delete across the whole action strip, from the end of
     * the name column (centerX + 20) to the delete icon's right edge (centerX + 138): two buttons
     * with a 4px gap.
     */
    private static final int PENDING_BUTTON_W = 57;

    /** The modal panel, centred on the screen - see extractRenderState. The row menu stays inside it. */
    private static final int MODAL_WIDTH = 290;
    private static final int MODAL_HEIGHT = 226;

    /**
     * The 260px header row at the top of the modal, left to right with 4px gaps: the search box
     * ({@code SEARCH_WIDTH}), the group filter ({@code GROUP_BUTTON_WIDTH}), the selected group's
     * show/hide eye ({@code GROUP_EYE_SIZE}) and the sort button ({@code SORT_BUTTON_WIDTH}). With no
     * single group selected there is no eye, and the filter takes its place, so the search box and
     * the sort button never move.
     */
    private static final int SORT_BUTTON_WIDTH = 66;
    private static final int GROUP_BUTTON_WIDTH = 64;
    private static final int GROUP_EYE_SIZE = 16;
    private static final int SEARCH_WIDTH = 260 - SORT_BUTTON_WIDTH - GROUP_BUTTON_WIDTH - GROUP_EYE_SIZE - 3 * 4;
    /** How many groups the filter's menu shows before it scrolls. */
    private static final int GROUP_MENU_ROWS = 8;

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
        private Component nameComponent;
        private Component coordsComponent;

        WaypointEntry(Waypoint waypoint) {
            this.waypoint = waypoint;
            this.displayPos = waypoint.getPos();
        }

        Component name(net.minecraft.client.gui.Font font) {
            if (nameComponent == null) {
                int column = waypoint.isPendingRemoval() ? PENDING_NAME_WIDTH : NAME_MAX_WIDTH;
                nameComponent = Component.literal(truncateToWidth(font, waypoint.getName(), column - nameIndent(waypoint)));
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
            if (wp != null && wp.dimensionOrOverworld().equals(dimension)
                    && WaypointGroups.matches(wp.getGroups(), this.groupFilter, this.groupFilterName)) {
                result.add(new WaypointEntry(wp));
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
        // Favorites first, whatever the order. A second, stable sort, so within each half the
        // mode's own order stands.
        entries.sort(Comparator.comparing(entry -> !entry.waypoint.isFavorite()));
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
        this.cachedShowTP = this.minecraft != null && EasyWpCommands.canTeleport(this.minecraft.player);
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

        if (this.newGroupField == null) {
            this.newGroupField = new EditBox(this.font, -1000, -1000, 100, PopupMenu.ITEM_H,
                    I18nHelper.getComponent("list.group_new"));
            this.newGroupField.setMaxLength(WaypointGroups.MAX_NAME_LENGTH);
            this.newGroupField.visible = false;
            this.newGroupField.active = false;
            this.newGroupField.setHint(I18nHelper.getComponent("list.group_new_hint").withStyle(ChatFormatting.DARK_GRAY));
        }
        // Not renderable: drawn over the menu's row, after the menu. Only a child, to get the keys.
        this.addWidget(this.newGroupField);

        this.addRenderableWidget(
            ModernButton.modernBuilder(Component.literal(""), btn -> {
                ClientCompat.setScreen(this.minecraft, new ModConfigScreen(this));
            }).pos(CORNER_BUTTON_MARGIN, this.height - 28).size(CORNER_BUTTON_SIZE, CORNER_BUTTON_SIZE)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.CONFIG, b.getX() + 1, b.getY() + 1, 18, 18, UiPalette.ICON_IDLE))
                    .build()
        );

        // Beside the gear, anchored to the screen like it: what the list shows right now - this tab,
        // narrowed by the search - as share lines on the clipboard. Ctrl+V reads them back.
        var copyAllBtn = ModernButton.modernBuilder(Component.literal(""), btn -> exportShownToClipboard())
                .pos(CORNER_BUTTON_MARGIN + CORNER_BUTTON_PITCH, this.height - 28).size(CORNER_BUTTON_SIZE, CORNER_BUTTON_SIZE)
                .icon((g, b, hovered) -> drawCopyGlyph(g, b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2,
                        hovered ? UiPalette.BTN_TEXT_HOVER : UiPalette.ICON_IDLE))
                .build();
        copyAllBtn.setTooltip(Tooltip.create(I18nHelper.getComponent("list.export_tooltip")));
        this.addRenderableWidget(copyAllBtn);

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

    /** The clipboard export: every waypoint the list is showing, pending deletions left out. */
    private void exportShownToClipboard() {
        List<Waypoint> shown = new ArrayList<>();
        for (WaypointEntry entry : this.cachedFilteredWaypoints) {
            if (entry != null && entry.waypoint != null && !entry.waypoint.isPendingRemoval()) {
                shown.add(entry.waypoint);
            }
        }
        if (shown.isEmpty()) {
            ClientCompat.setOverlayMessage(this.minecraft, I18nHelper.getComponent("list.export_empty"), false);
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(WaypointShare.exportLines(shown));
        ClientCompat.setOverlayMessage(this.minecraft,
                I18nHelper.getComponent("list.exported", String.valueOf(shown.size())), false);
    }

    /** The paste: the waypoints in the clipboard, previewed and confirmed before they are merged
     *  (additive and deduplicated, as a world import). */
    private void importFromClipboard() {
        List<Waypoint> incoming = WaypointShare.parseLines(this.minecraft, this.minecraft.keyboardHandler.getClipboard());
        if (incoming.isEmpty()) {
            ClientCompat.setOverlayMessage(this.minecraft, I18nHelper.getComponent("list.paste_empty"), false);
            return;
        }
        ClientCompat.setScreen(this.minecraft, new ImportConfirmScreen(this, incoming));
    }

    /** Two overlapping squares, the usual "copy" glyph, centred on {@code (cx, cy)}: the back one
     *  showing only its top and left edges, the front one outlined. Hand-drawn like the Xaero pair's. */
    private static void drawCopyGlyph(GuiGraphicsExtractor g, int cx, int cy, int color) {
        int x = cx - 5;
        int y = cy - 5;
        g.fill(x, y, x + 7, y + 1, color);
        g.fill(x, y, x + 1, y + 7, color);
        int fx = x + 3;
        int fy = y + 3;
        g.fill(fx, fy, fx + 8, fy + 1, color);
        g.fill(fx, fy + 7, fx + 8, fy + 8, color);
        g.fill(fx, fy, fx + 1, fy + 8, color);
        g.fill(fx + 7, fy, fx + 8, fy + 8, color);
    }

    /** Rebuilds the rows when deletions ran out (or were all made final) while the list was open:
     *  those waypoints left the list from outside it, and their rows would otherwise linger. */
    @Override
    public void tick() {
        super.tick();
        // Outside its menu the field must not exist for Tab navigation or clicks: it sits off screen,
        // and focus on an invisible box would swallow typing.
        boolean typing = typingGroup();
        this.newGroupField.visible = typing;
        this.newGroupField.active = typing;
        if (!typing && this.getFocused() == this.newGroupField) {
            this.setFocused(null);
        }
        if (this.seenRemovalsVersion != WaypointStore.removalsVersion()) {
            refreshItemWidgets();
        }
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
        this.seenRemovalsVersion = WaypointStore.removalsVersion();

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
            int offsetIndex = i - startIndex;
            int itemY = centerY - 60 + offsetIndex * 22;

            if (wp.isPendingRemoval()) {
                addPendingRemovalButtons(wp, centerX, itemY);
                continue;
            }

            int buttonX = centerX + 120;

            // No confirmation: the row stays for a few seconds with Undo, which covers a slip.
            var delBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                WaypointStore.markForRemoval(wp);
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> ClientCompat.blitTinted(g, Icons.DELETE, b.getX(), b.getY(), 18, 18, UiPalette.ICON_DESTRUCTIVE))
                    .build();
            this.addRenderableWidget(delBtn);
            dynamicWidgets.add(delBtn);
            buttonX -= 20;

            // Opens this row's "More options" menu (favorite, duplicate in the other dimension, and the
            // three ways to share) without leaving the list, in the slot the shared-waypoint portal
            // toggle had until 1.4.0. While a menu is open mouseClicked handles every click itself, so
            // this only ever opens one.
            var moreBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.optionsFor = wp;
                this.optionsGroupsPage = false;
                this.menuScroll = 0;
                this.groupMenuOpen = false;
            }).pos(buttonX, itemY).size(18, 18)
                    .icon((g, b, hovered) -> Icons.drawMoreGlyph(g, b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2,
                            hovered ? UiPalette.BTN_TEXT_HOVER : UiPalette.ICON_IDLE))
                    .build();
            moreBtn.setTooltip(Tooltip.create(I18nHelper.getComponent("create.more_options")));
            this.addRenderableWidget(moreBtn);
            dynamicWidgets.add(moreBtn);
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
                        this.minecraft.player.connection.sendCommand(EasyWpCommands.teleportCommand(wp));
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
                    WaypointStore.clearFocus();
                } else {
                    WaypointStore.focus(wp);
                }
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
        }).pos(centerX + 130 - SORT_BUTTON_WIDTH, centerY - 90).size(SORT_BUTTON_WIDTH, 16)
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

        addGroupFilterWidgets(centerX, centerY);

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

    /**
     * The row of a waypoint deleted and still within its seconds: its swatch stays (dimmed, not
     * clickable) and its whole action strip becomes Undo and Delete - Undo takes the deletion back,
     * Delete makes it final now instead of waiting. The waypoint itself is already gone from the
     * world and from the file; see WaypointStore.markForRemoval.
     */
    private void addPendingRemovalButtons(Waypoint wp, int centerX, int itemY) {
        var undoBtn = ModernButton.modernBuilder(I18nHelper.getComponent("list.undo"), btn -> {
            WaypointStore.undoRemoval(wp);
            refreshItemWidgets();
        }).pos(centerX + 20, itemY).size(PENDING_BUTTON_W, 18).build();
        this.addRenderableWidget(undoBtn);
        dynamicWidgets.add(undoBtn);

        var deleteNowBtn = ModernButton.modernBuilder(I18nHelper.getComponent("list.delete_now"), btn -> {
            WaypointStore.removeNow(wp);
            refreshItemWidgets();
        }).pos(centerX + 20 + PENDING_BUTTON_W + 4, itemY).size(PENDING_BUTTON_W, 18).build();
        this.addRenderableWidget(deleteNowBtn);
        dynamicWidgets.add(deleteNowBtn);

        var swatch = ModernButton.modernBuilder(Component.literal(""), btn -> { })
                .pos(centerX - 138, itemY).size(18, 18)
                .icon((g, b, hovered) -> Icons.drawMarkerIcon(g, b.getX(), b.getY(),
                        dim(wp.getColor()), dim(UiPalette.MARKER_OUTLINE), wp.isDeath()))
                .build();
        swatch.active = false;
        this.addRenderableWidget(swatch);
        dynamicWidgets.add(swatch);
    }

    /**
     * The group filter between the search box and the sort button, and - while one group is
     * selected - the eye beside it that hides or shows that whole group, here and in the world.
     * Rebuilt with the rows, so the label follows the filter. A group that stopped existing (its
     * last waypoint deleted or regrouped) drops the filter back to "All".
     */
    private void addGroupFilterWidgets(int centerX, int centerY) {
        if (this.groupFilter == WaypointGroups.Filter.GROUP && !WaypointStore.allGroups().contains(this.groupFilterName)) {
            this.groupFilter = WaypointGroups.Filter.ALL;
            this.groupFilterName = null;
        }
        boolean oneGroup = this.groupFilter == WaypointGroups.Filter.GROUP;
        int filterX = groupFilterX();
        int filterW = groupFilterWidth();

        var filterBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
            this.groupMenuOpen = true;
            this.optionsFor = null;
            this.menuScroll = 0;
        }).pos(filterX, centerY - 90).size(filterW, 16)
                .icon((g, b, hovered) -> {
                    int color = hovered ? UiPalette.BTN_TEXT_HOVER : UiPalette.BTN_TEXT;
                    int room = b.getWidth() - 6 - 9;
                    String label = this.font.plainSubstrByWidth(groupFilterLabel().getString(), room);
                    g.text(this.font, Component.literal(label), b.getX() + 5, b.getY() + 4, color);
                    drawDropGlyph(g, b.getX() + b.getWidth() - 9, b.getY() + 7, color);
                })
                .build();
        filterBtn.setTooltip(Tooltip.create(I18nHelper.getComponent("list.group_filter_tooltip")));
        this.addRenderableWidget(filterBtn);
        dynamicWidgets.add(filterBtn);

        if (oneGroup) {
            String group = this.groupFilterName;
            boolean hidden = WaypointStore.isGroupHidden(group);
            var eyeBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                WaypointStore.setGroupHidden(group, !WaypointStore.isGroupHidden(group));
                refreshItemWidgets();
            }).pos(filterX + filterW + 4, centerY - 90).size(GROUP_EYE_SIZE, 16)
                    .icon((g, b, hovered) -> {
                        boolean isHidden = WaypointStore.isGroupHidden(group);
                        ClientCompat.blitTinted(g, isHidden ? Icons.HIDE : Icons.SHOW, b.getX(), b.getY(), 16, 16,
                                isHidden ? UiPalette.ICON_DISABLED : UiPalette.ICON_IDLE);
                    })
                    .build();
            eyeBtn.setTooltip(Tooltip.create(I18nHelper.getComponent(hidden ? "list.group_show" : "list.group_hide", group)));
            this.addRenderableWidget(eyeBtn);
            dynamicWidgets.add(eyeBtn);
        }
    }

    private int groupFilterX() {
        return this.width / 2 - 130 + SEARCH_WIDTH + 4;
    }

    /** The filter button's width: its own, or its own plus the eye's slot when there is no eye. */
    private int groupFilterWidth() {
        return this.groupFilter == WaypointGroups.Filter.GROUP
                ? GROUP_BUTTON_WIDTH
                : GROUP_BUTTON_WIDTH + 4 + GROUP_EYE_SIZE;
    }

    private Component groupFilterLabel() {
        return switch (this.groupFilter) {
            case ALL -> I18nHelper.getComponent("list.group_all");
            case NONE -> I18nHelper.getComponent("list.group_none");
            case GROUP -> Component.literal(this.groupFilterName);
        };
    }

    /** A small downward triangle, "this opens a menu", 5px wide with its top-left at {@code (x, y)}. */
    private static void drawDropGlyph(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y, x + 5, y + 1, color);
        g.fill(x + 1, y + 1, x + 4, y + 2, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
    }

    /** The group filter's menu: All, No group, then every group of the world - a hidden one says
     *  so - with a mark on the current choice. Picking one closes the menu and refilters. */
    private List<PopupMenu.Item> groupMenuItems() {
        List<PopupMenu.Item> items = new ArrayList<>();
        items.add(groupChoice(I18nHelper.getComponent("list.group_all"), WaypointGroups.Filter.ALL, null));
        items.add(groupChoice(I18nHelper.getComponent("list.group_none"), WaypointGroups.Filter.NONE, null));
        for (String group : WaypointStore.allGroups()) {
            Component label = WaypointStore.isGroupHidden(group)
                    ? Component.literal(group).append(I18nHelper.getComponent("list.group_hidden_suffix").withStyle(ChatFormatting.DARK_GRAY))
                    : Component.literal(group);
            items.add(groupChoice(label, WaypointGroups.Filter.GROUP, group));
        }
        return items;
    }

    private PopupMenu.Item groupChoice(Component label, WaypointGroups.Filter filter, String group) {
        boolean current = this.groupFilter == filter
                && (filter != WaypointGroups.Filter.GROUP || group.equals(this.groupFilterName));
        return new PopupMenu.Item(label, true,
                current ? (g, x, y) -> drawCheckGlyph(g, x + 4, y + 4, UiPalette.TEXT_SUCCESS) : null,
                () -> {
                    this.groupFilter = filter;
                    this.groupFilterName = group;
                    this.groupMenuOpen = false;
                    this.scrollIndex = 0;
                    refreshItemWidgets();
                });
    }

    /** A 7x6 tick, the current choice in the group filter's menu, top-left at {@code (x, y)}. */
    private static void drawCheckGlyph(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y + 3, x + 1, y + 4, color);
        g.fill(x + 1, y + 4, x + 2, y + 5, color);
        g.fill(x + 2, y + 5, x + 3, y + 6, color);
        g.fill(x + 3, y + 4, x + 4, y + 5, color);
        g.fill(x + 4, y + 3, x + 5, y + 4, color);
        g.fill(x + 5, y + 2, x + 6, y + 3, color);
        g.fill(x + 6, y + 1, x + 7, y + 2, color);
    }

    /**
     * The open menu's items: the favorite toggle (the menu stays open, and follows the row to the
     * top of the list), and duplicating into the other half of the Overworld/Nether pair - greyed
     * out, saying why, outside the pair or once the copy exists (WaypointStore's own rule), so a
     * second press cannot leave two copies.
     */
    private List<PopupMenu.Item> menuItems(Waypoint wp) {
        List<PopupMenu.Item> items = new ArrayList<>(5);
        items.add(new PopupMenu.Item(
                I18nHelper.getComponent(wp.isFavorite() ? "create.favorite_on" : "create.favorite_off"), true,
                (g, x, y) -> Icons.drawStar(g, x + 4, y + 3, wp.isFavorite() ? UiPalette.FAVORITE_STAR : UiPalette.ICON_DISABLED),
                () -> {
                    wp.setFavorite(!wp.isFavorite());
                    WaypointStore.saveToFile();
                    refreshItemWidgets();
                }));

        items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.group_menu"), () -> {
            this.optionsGroupsPage = true;
            this.newGroupTyping = false;
            this.menuScroll = 0;
        }));

        String paired = wp.pairedDimension();
        if (paired == null) {
            items.add(PopupMenu.Item.disabled(I18nHelper.getComponent("create.duplicate_unsupported")));
        } else if (WaypointStore.findCopyInPairedDimension(wp) != null) {
            items.add(PopupMenu.Item.disabled(I18nHelper.getComponent("list.duplicate_exists", DimensionNames.chatName(paired))));
        } else {
            items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.duplicate", DimensionNames.chatName(paired)), () -> {
                WaypointCreateScreen.announceDuplicate(this.minecraft, wp, WaypointStore.duplicateIntoPairedDimension(wp));
                this.optionsFor = null;
                refreshItemWidgets();
            }));
        }
        addShareItems(items, wp);
        return items;
    }

    /**
     * The row menu's groups page: back to the main page, every group of the world with a tick on the
     * ones this waypoint is in - a click adds or removes it, and the menu stays open for the next -
     * and "New group...", which opens the form on its "More options" page with the group field
     * focused, since a new name has to be typed. A group the waypoint cannot take any more (it has
     * {@link WaypointGroups#MAX_PER_WAYPOINT}) is greyed out.
     */
    private List<PopupMenu.Item> groupItemsFor(Waypoint wp) {
        List<PopupMenu.Item> items = new ArrayList<>();
        items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.group_back"), () -> {
            this.optionsGroupsPage = false;
            stopTypingGroup();
            this.menuScroll = 0;
        }));
        for (String group : WaypointStore.allGroups()) {
            boolean assigned = wp.getGroups().contains(group);
            if (!assigned && !WaypointGroups.canAdd(wp.getGroups(), group)) {
                items.add(PopupMenu.Item.disabled(Component.literal(group)));
                continue;
            }
            items.add(new PopupMenu.Item(Component.literal(group), true,
                    assigned ? (g, x, y) -> drawCheckGlyph(g, x + 4, y + 4, UiPalette.TEXT_SUCCESS) : null,
                    () -> {
                        List<String> groups = new ArrayList<>(wp.getGroups());
                        if (assigned) {
                            groups.remove(group);
                        } else {
                            groups.add(group);
                        }
                        wp.setGroups(groups);
                        WaypointStore.saveToFile();
                        refreshItemWidgets();
                    }));
        }
        if (wp.getGroups().size() >= WaypointGroups.MAX_PER_WAYPOINT) {
            items.add(PopupMenu.Item.disabled(I18nHelper.getComponent("create.group_full",
                    String.valueOf(WaypointGroups.MAX_PER_WAYPOINT))));
        } else if (typingGroup()) {
            // The row the field is drawn over; nothing to read or click in the menu itself.
            items.add(PopupMenu.Item.disabled(Component.empty()));
        } else {
            items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.group_new"), () -> {
                this.newGroupTyping = true;
                this.newGroupField.visible = true;
                this.newGroupField.active = true;
                this.newGroupField.setValue("");
                this.menuScroll = Integer.MAX_VALUE; // clamped to the end, where the field is
                this.setFocused(this.newGroupField);
            }));
        }
        return items;
    }

    /** Whether the groups page is asking for a new group's name. */
    private boolean typingGroup() {
        return this.newGroupTyping && this.optionsGroupsPage && this.optionsFor != null;
    }

    private void stopTypingGroup() {
        this.newGroupTyping = false;
        if (this.getFocused() == this.newGroupField) {
            this.setFocused(null);
        }
    }

    /** Enter in the field: the typed group goes onto the waypoint (an existing one differing only in
     *  case is reused), and the page shows it ticked. Blank text keeps the field open. */
    private void submitNewGroup() {
        String name = WaypointGroups.resolve(WaypointStore.allGroups(), this.newGroupField.getValue());
        if (name == null) return;
        Waypoint wp = this.optionsFor;
        if (WaypointGroups.canAdd(wp.getGroups(), name)) {
            List<String> groups = new ArrayList<>(wp.getGroups());
            groups.add(name);
            wp.setGroups(groups);
            WaypointStore.saveToFile();
            refreshItemWidgets();
        }
        stopTypingGroup();
    }

    /**
     * The menu's three ways to share, where the row's own share button used to be: the public chat
     * line (what that button sent directly), a private one to a player picked from those online -
     * offered only when anyone else is - and a copy of the share line to the clipboard. The two that
     * send close the list, as sharing always did; copying stays.
     */
    private void addShareItems(List<PopupMenu.Item> items, Waypoint wp) {
        items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.share_public"), () -> {
            this.optionsFor = null;
            if (WaypointShare.send(this.minecraft, wp)) {
                this.onClose();
            }
        }));
        List<String> others = WaypointShare.otherPlayers(this.minecraft);
        if (!others.isEmpty()) {
            items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.share_private"), () -> {
                this.optionsFor = null;
                ClientCompat.setScreen(this.minecraft, new PlayerPickScreen(this, wp, others));
            }));
        }
        items.add(PopupMenu.Item.of(I18nHelper.getComponent("list.share_copy"), () -> {
            this.optionsFor = null;
            WaypointShare.copy(this.minecraft, wp);
            ClientCompat.setOverlayMessage(this.minecraft, I18nHelper.getComponent("list.share_copied", wp.getName()), false);
        }));
    }

    /**
     * The open menu, placed: the group filter's below its button, or a row's "More options" inside
     * the modal over its row's own buttons, its top-right corner on the delete button's
     * (centerX + 138, the row's top), so it opens right where it was asked for and lines up with the
     * action strip. Near the bottom of the page it is pushed up to end at the modal's inner edge,
     * and a translation too wide for the modal starts at its left inner edge - it never sticks out
     * of the panel. {@code null} - and the row menu closed - when no menu is open or its row is not
     * on the page any more.
     */
    private PopupMenu openMenu() {
        if (this.groupMenuOpen) {
            // Below the filter button, at least as wide as it, inside the modal.
            int top = this.height / 2 - 90 + 16 + 1;
            PopupMenu menu = new PopupMenu(this.font, groupMenuItems(), this.menuScroll, GROUP_MENU_ROWS,
                    GROUP_BUTTON_WIDTH + 4 + GROUP_EYE_SIZE, MODAL_WIDTH - 2);
            this.menuScroll = menu.getScroll();
            return menu.placeAt(groupFilterX(), top, modalLeft(), modalTop(), modalRight(), modalBottom());
        }
        if (this.optionsFor == null) return null;
        int row = -1;
        int endIndex = Math.min(scrollIndex + ITEMS_PER_PAGE, this.cachedFilteredWaypoints.size());
        for (int i = scrollIndex; i < endIndex; i++) {
            if (this.cachedFilteredWaypoints.get(i).waypoint == this.optionsFor) {
                row = i - scrollIndex;
                break;
            }
        }
        if (row < 0 || this.optionsFor.isPendingRemoval()) {
            this.optionsFor = null;
            return null;
        }

        int itemY = this.height / 2 - 60 + row * 22;
        PopupMenu menu = this.optionsGroupsPage
                ? new PopupMenu(this.font, groupItemsFor(this.optionsFor), this.menuScroll, 8, 120, MODAL_WIDTH - 2)
                : new PopupMenu(this.font, menuItems(this.optionsFor), 0, 8, 0, MODAL_WIDTH - 2);
        this.menuScroll = menu.getScroll();
        return menu.placeAt(this.width / 2 + 138 - menu.getWidth(), itemY,
                modalLeft(), modalTop(), modalRight(), modalBottom());
    }

    /** Closes whichever menu is open. */
    private void closeMenus() {
        stopTypingGroup();
        this.optionsFor = null;
        this.optionsGroupsPage = false;
        this.groupMenuOpen = false;
        this.menuScroll = 0;
    }

    private int modalLeft() {
        return this.width / 2 - MODAL_WIDTH / 2 + 1;
    }

    private int modalRight() {
        return this.width / 2 + MODAL_WIDTH / 2 - 1;
    }

    private int modalTop() {
        return this.height / 2 - MODAL_HEIGHT / 2 + 1;
    }

    private int modalBottom() {
        return this.height / 2 + MODAL_HEIGHT / 2 - 1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // The wheel over an open menu scrolls the menu, when it has more than it shows.
        PopupMenu menu = openMenu();
        if (menu != null && menu.contains(mouseX, mouseY)) {
            this.menuScroll = menu.scrolled(verticalAmount);
            return true;
        }
        // Elsewhere the rows move under the menu; close it rather than let it point at another row.
        closeMenus();
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
        // An open menu takes every click: an item runs (if enabled), anywhere else just closes it,
        // so a click meant to dismiss the menu can never land on a delete button beneath it.
        if (typingGroup() && this.newGroupField.isMouseOver(event.x(), event.y())) {
            this.setFocused(this.newGroupField);
            this.newGroupField.mouseClicked(event, doubleClick);
            return true;
        }
        PopupMenu menu = openMenu();
        if (menu != null) {
            if (!menu.click(event.x(), event.y(), event.button())) {
                closeMenus();
            }
            return true;
        }
        if (event.button() == ClientCompat.MOUSE_LEFT) {
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
            // A row pending deletion shows its countdown instead of coordinates: nothing to copy.
            if (this.cachedFilteredWaypoints.get(i).waypoint.isPendingRemoval()) continue;
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

        int bgW = MODAL_WIDTH;
        int bgH = MODAL_HEIGHT;
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

                int nameColor = isDimmed ? UiPalette.TEXT_DIM : UiPalette.TEXT_PRIMARY;
                if (wp.isFavorite()) {
                    Icons.drawStar(graphics, centerX - 117, itemY, isDimmed ? dim(UiPalette.FAVORITE_STAR) : UiPalette.FAVORITE_STAR);
                }
                graphics.text(this.font, entry.name(this.font), centerX - 117 + nameIndent(wp), itemY + 1, nameColor);

                if (wp.isPendingRemoval()) {
                    // In place of the coordinates: how long until the deletion is final.
                    long seconds = Math.max(1, (WaypointStore.remainingRemovalMillis(wp) + 999) / 1000);
                    graphics.text(this.font, I18nHelper.getComponent("list.pending_delete", String.valueOf(seconds)),
                            centerX - 117, itemY + 10, UiPalette.ICON_DESTRUCTIVE);
                    continue;
                }

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

        PopupMenu menu = openMenu();
        if (menu != null) {
            menu.draw(graphics, this.font, mouseX, mouseY);
        }
        // The new group's name field, over the last row of the groups page.
        int[] row = typingGroup() && menu != null ? menu.lastRowBounds() : null;
        if (row == null) {
            this.newGroupField.setPosition(-1000, -1000);
        } else {
            this.newGroupField.setPosition(row[0], row[1]);
            this.newGroupField.setWidth(row[2]);
            graphics.nextStratum();
            this.newGroupField.extractRenderState(graphics, mouseX, mouseY, a);
        }
    }

    /**
     * The list key closes the list again - except while a text field has the focus, where it is
     * just a letter being typed. Checking the key first, as this used to, made the search box
     * unable to take that letter at all: typing "jungle" with the default J closed the screen.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (typingGroup() && this.getFocused() == this.newGroupField
                && (event.key() == ClientCompat.KEY_ENTER || event.key() == ClientCompat.KEY_KP_ENTER)) {
            submitNewGroup();
            return true;
        }
        // Esc closes an open menu first, and only a second Esc the list.
        if ((this.optionsFor != null || this.groupMenuOpen) && event.isEscape()) {
            closeMenus();
            return true;
        }
        if (!(this.getFocused() instanceof EditBox) && ModKeyBindings.listWaypointsKey.matches(event)) {
            this.onClose();
            return true;
        }
        // Ctrl+V imports - unless the search box has the focus, where it pastes into the search.
        if (!(this.getFocused() instanceof EditBox) && event.isPaste()) {
            importFromClipboard();
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


