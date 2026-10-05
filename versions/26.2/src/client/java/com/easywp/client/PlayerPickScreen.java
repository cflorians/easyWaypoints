package com.easywp.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who a private share goes to: the other players online, one button each, with a search box for a
 * full server. Picking one sends the waypoint with {@code /msg} ({@link WaypointShare#sendPrivate})
 * and closes to wherever the list would have closed to, as a public share does; Back returns to
 * the list. Vanilla-styled, like the import screens, since it is a plain chooser.
 *
 * <p>The player list is read once, on open: someone who joins meanwhile appears on reopening, and
 * someone who left simply makes {@code /msg} answer that they are not online.
 */
public class PlayerPickScreen extends Screen {
    private static final int ROW_WIDTH = 200;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_STEP = 22;
    private static final int TITLE_Y = 12;
    private static final int SEARCH_Y = 28;
    private static final int LIST_TOP = 54;
    private static final int FOOTER_HEIGHT = 36;

    private final WaypointListScreen listScreen;
    private final Waypoint waypoint;
    private final List<String> players;
    private final List<String> filtered = new ArrayList<>();
    private final List<GuiEventListener> rowWidgets = new ArrayList<>();
    private EditBox searchField;
    private int scrollIndex = 0;
    private int itemsPerPage = 1;

    public PlayerPickScreen(WaypointListScreen listScreen, Waypoint waypoint, List<String> players) {
        super(I18nHelper.getComponent("share.private_title", waypoint.getName()));
        this.listScreen = listScreen;
        this.waypoint = waypoint;
        this.players = players;
    }

    @Override
    protected void init() {
        this.itemsPerPage = Math.max(1, (this.height - LIST_TOP - FOOTER_HEIGHT) / ROW_STEP);
        String query = this.searchField != null ? this.searchField.getValue() : "";
        this.searchField = new EditBox(this.font, this.width / 2 - ROW_WIDTH / 2, SEARCH_Y, ROW_WIDTH, 18,
                I18nHelper.getComponent("share.search_hint"));
        this.searchField.setHint(I18nHelper.getComponent("share.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        this.searchField.setValue(query);
        this.searchField.setResponder(text -> {
            this.scrollIndex = 0;
            refreshRows();
        });
        this.addRenderableWidget(this.searchField);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, btn -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 27, 200, 20)
                .build());
        refreshRows();
    }

    /** The search box takes the focus, so a name can be typed straight away. An override rather than
     *  a call in init(), which vanilla's own no-argument call right after init() would undo. */
    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.searchField);
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(this.minecraft, this.listScreen);
    }

    private void refreshRows() {
        for (var widget : rowWidgets) {
            this.removeWidget(widget);
        }
        rowWidgets.clear();

        String query = this.searchField.getValue().trim().toLowerCase(Locale.ROOT);
        this.filtered.clear();
        for (String name : players) {
            if (name.toLowerCase(Locale.ROOT).contains(query)) {
                this.filtered.add(name);
            }
        }
        this.scrollIndex = Math.max(0, Math.min(this.scrollIndex, this.filtered.size() - this.itemsPerPage));

        int rowX = this.width / 2 - ROW_WIDTH / 2;
        int end = Math.min(scrollIndex + itemsPerPage, filtered.size());
        for (int i = scrollIndex; i < end; i++) {
            String name = filtered.get(i);
            Button row = Button.builder(Component.literal(name), btn -> {
                if (WaypointShare.sendPrivate(this.minecraft, this.waypoint, name)) {
                    ClientCompat.setOverlayMessage(this.minecraft,
                            I18nHelper.getComponent("share.private_sent", this.waypoint.getName(), name), false);
                }
                this.listScreen.onClose();
            }).bounds(rowX, LIST_TOP + (i - scrollIndex) * ROW_STEP, ROW_WIDTH, ROW_HEIGHT).build();
            this.addRenderableWidget(row);
            rowWidgets.add(row);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxScroll = Math.max(0, filtered.size() - itemsPerPage);
        int next = scrollIndex + (verticalAmount < 0 ? 1 : verticalAmount > 0 ? -1 : 0);
        next = Math.max(0, Math.min(next, maxScroll));
        if (next != scrollIndex) {
            scrollIndex = next;
            refreshRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, TITLE_Y, 0xFFFFFFFF);
        if (filtered.isEmpty()) {
            graphics.centeredText(this.font, I18nHelper.getComponent("share.private_none"),
                    this.width / 2, LIST_TOP + 6, 0xFFA0A0A0);
        }
    }
}
