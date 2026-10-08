package com.easywp.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * A small hand-drawn menu over a screen: the list's "More options" and group filter, and the
 * group suggestions on the form's "More options" page. Not a widget - the screen that owns it
 * builds it each frame from its own state, draws it last in a stratum of its own, and hands it
 * clicks and wheel turns first. That keeps its position computed from live data (a row that moved,
 * a field that was retyped) instead of a widget that would have to be kept in sync.
 *
 * <p>It is placed inside bounds the caller gives (the modal), pulled back in rather than drawn
 * past them, and when it has more items than {@code maxVisible} it scrolls with the wheel; the
 * caller keeps the scroll offset between frames, since the menu itself does not survive one.
 */
final class PopupMenu {
    static final int ITEM_H = 16;
    private static final int PAD = 2;
    /** Text start when some item has an icon, which sits in front of it. */
    private static final int ICON_INSET = 17;
    private static final int PLAIN_INSET = 5;

    /** Draws an item's icon from the item's top-left corner. */
    interface IconPainter {
        void draw(GuiGraphicsExtractor g, int x, int y);
    }

    /** One entry; {@code action} runs only when {@code enabled}. */
    record Item(Component label, boolean enabled, IconPainter icon, Runnable action) {
        static Item of(Component label, Runnable action) {
            return new Item(label, true, null, action);
        }

        static Item disabled(Component label) {
            return new Item(label, false, null, null);
        }
    }

    private final List<Item> items;
    private final int inset;
    private final int width;
    private final int height;
    private final int visibleCount;
    private final int scroll;
    private int x;
    private int y;

    /**
     * Sizes the menu: as wide as its widest label (at least {@code minWidth}, at most
     * {@code maxWidth}, a longer label cut), and tall enough for up to {@code maxVisible} items.
     * Place it with {@link #placeAt} before drawing.
     */
    PopupMenu(Font font, List<Item> items, int scroll, int maxVisible, int minWidth, int maxWidth) {
        this.items = items;
        boolean anyIcon = false;
        int textWidth = 0;
        for (Item item : items) {
            anyIcon |= item.icon() != null;
            textWidth = Math.max(textWidth, font.width(item.label()));
        }
        this.inset = anyIcon ? ICON_INSET : PLAIN_INSET;
        this.visibleCount = Math.max(1, Math.min(items.size(), maxVisible));
        this.scroll = clampScroll(scroll, items.size(), this.visibleCount);
        int scrollbar = items.size() > this.visibleCount ? 4 : 0;
        this.width = Math.max(minWidth, Math.min(maxWidth, this.inset + textWidth + 6 + scrollbar + PAD * 2));
        this.height = this.visibleCount * ITEM_H + PAD * 2;
    }

    /** Puts the top-left corner at {@code (x, y)}, pulled back inside the bounds if it would leave them. */
    PopupMenu placeAt(int x, int y, int minX, int minY, int maxX, int maxY) {
        this.x = Math.max(minX, Math.min(x, maxX - this.width));
        this.y = Math.max(minY, Math.min(y, maxY - this.height));
        return this;
    }

    int getWidth() {
        return this.width;
    }

    int getHeight() {
        return this.height;
    }

    /**
     * Where the last item's row is drawn - {@code {x, y, width}}, {@link #ITEM_H} tall - or
     * {@code null} while it is scrolled out. For an owner that puts a text field over that row.
     */
    int[] lastRowBounds() {
        int row = this.items.size() - 1 - this.scroll;
        if (row < 0 || row >= this.visibleCount) return null;
        boolean scrolls = this.items.size() > this.visibleCount;
        return new int[] {this.x + PAD, this.y + PAD + row * ITEM_H, this.width - PAD * 2 - (scrolls ? 4 : 0)};
    }

    /** The scroll offset as clamped here - what the owner should keep for the next frame. */
    int getScroll() {
        return this.scroll;
    }

    boolean contains(double mx, double my) {
        return mx >= this.x && mx < this.x + this.width && my >= this.y && my < this.y + this.height;
    }

    /**
     * A click: runs the item under it if that item is enabled. @return whether the click was inside
     * the menu at all - the owner decides what a click outside does (usually close it).
     */
    boolean click(double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        int index = itemAt(my);
        if (button == ClientCompat.MOUSE_LEFT && index >= 0) {
            Item item = this.items.get(index);
            if (item.enabled() && item.action() != null) {
                item.action().run();
            }
        }
        return true;
    }

    /** The scroll offset after one wheel step; unchanged when everything fits. */
    int scrolled(double verticalAmount) {
        int next = this.scroll + (verticalAmount < 0 ? 1 : verticalAmount > 0 ? -1 : 0);
        return clampScroll(next, this.items.size(), this.visibleCount);
    }

    void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (this.items.isEmpty()) return;
        // A stratum of its own, so it lands above the text under it as well as the buttons.
        graphics.nextStratum();
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, UiPalette.MODAL_BORDER);
        graphics.fill(this.x + 1, this.y + 1, this.x + this.width - 1, this.y + this.height - 1, UiPalette.MENU_BG);

        boolean scrolls = this.items.size() > this.visibleCount;
        int itemWidth = this.width - PAD * 2 - (scrolls ? 4 : 0);
        int hovered = contains(mouseX, mouseY) ? itemAt(mouseY) : -1;
        for (int row = 0; row < this.visibleCount; row++) {
            int index = this.scroll + row;
            Item item = this.items.get(index);
            int ix = this.x + PAD;
            int iy = this.y + PAD + row * ITEM_H;
            boolean hover = index == hovered && mouseX < ix + itemWidth;
            if (hover && item.enabled()) {
                graphics.fill(ix, iy, ix + itemWidth, iy + ITEM_H, UiPalette.BTN_BG_HOVER);
            }
            if (item.icon() != null) {
                item.icon().draw(graphics, ix, iy);
            }
            int color = !item.enabled() ? UiPalette.TEXT_DIM : (hover ? UiPalette.BTN_TEXT_HOVER : UiPalette.BTN_TEXT);
            int textRoom = itemWidth - this.inset - 3;
            FormattedCharSequence text = font.width(item.label()) <= textRoom
                    ? item.label().getVisualOrderText()
                    : Language.getInstance().getVisualOrder(font.substrByWidth(item.label(), textRoom));
            graphics.text(font, text, ix + this.inset, iy + 4, color);
        }

        if (scrolls) {
            int trackX = this.x + this.width - PAD - 2;
            int trackTop = this.y + PAD;
            int trackH = this.height - PAD * 2;
            graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackH, UiPalette.SCROLL_TRACK);
            int thumbH = Math.max(6, trackH * this.visibleCount / this.items.size());
            int maxScroll = this.items.size() - this.visibleCount;
            int thumbY = trackTop + (trackH - thumbH) * this.scroll / maxScroll;
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, UiPalette.SCROLL_THUMB);
        }
    }

    /** The index of the item under {@code my}, or -1 in the padding. */
    private int itemAt(double my) {
        int row = (int) Math.floor((my - this.y - PAD) / ITEM_H);
        if (row < 0 || row >= this.visibleCount || this.scroll + row >= this.items.size()) return -1;
        return this.scroll + row;
    }

    private static int clampScroll(int scroll, int total, int visible) {
        return Math.max(0, Math.min(scroll, total - visible));
    }
}
