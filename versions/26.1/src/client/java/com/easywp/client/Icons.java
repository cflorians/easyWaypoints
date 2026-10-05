package com.easywp.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Shared icon identifiers and layered blit helpers for the mod's dark UI. Icons are white masks tinted by UiPalette. */
public final class Icons {
    private Icons() {}

    /** The in-world waypoint marker, a 5x7 white mask tinted with the waypoint colour. */
    public static final Identifier WAYPOINT_MARKER = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    public static final Identifier MARKER_FILL    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_fill.png");
    public static final Identifier MARKER_OUTLINE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_outline.png");
    public static final Identifier MARKER_SKULL   = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_skull.png");

    public static final Identifier CANCEL  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/cancelbutton.png");
    public static final Identifier LIST    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/listbutton.png");
    public static final Identifier CONFIG  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/configicon.png");
    public static final Identifier DELETE  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/delete.png");
    public static final Identifier EDIT    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/edit.png");
    public static final Identifier SHOW    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/show.png");
    public static final Identifier HIDE    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/hide.png");
    public static final Identifier TP      = Identifier.fromNamespaceAndPath("easywp", "textures/gui/tp.png");

    /** Marker in layers: fill tinted with the waypoint's own color (skip if 0), then outline, then an optional skull badge. */
    public static void drawMarkerIcon(GuiGraphicsExtractor g, int x, int y,
                                      int fillColor, int outlineColor, boolean skull) {
        if (fillColor != 0) {
            ClientCompat.blitTinted(g, MARKER_FILL, x, y, 18, 18, fillColor);
        }
        ClientCompat.blitTinted(g, MARKER_OUTLINE, x, y, 18, 18, outlineColor);
        if (skull) {
            ClientCompat.blitTinted(g, MARKER_SKULL, x, y, 18, 18, UiPalette.MARKER_SKULL);
        }
    }

    /**
     * Rows of the 9x9 favorite star, as [first column, past-the-last column] pairs per row - drawn
     * with {@code fill} like the list's other hand-drawn glyphs rather than shipped as a texture,
     * since it is one small size in one colour.
     */
    private static final int[][] STAR_ROWS = {
            {4, 5}, {4, 5}, {3, 6}, {0, 9}, {1, 8}, {2, 7}, {2, 4, 5, 7}, {1, 3, 6, 8}, {1, 2, 7, 8}
    };

    /** The 9x9 favorite star with its top-left corner at {@code x, y}. */
    public static void drawStar(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int row = 0; row < STAR_ROWS.length; row++) {
            int[] runs = STAR_ROWS[row];
            for (int i = 0; i < runs.length; i += 2) {
                g.fill(x + runs[i], y + row, x + runs[i + 1], y + row + 1, color);
            }
        }
    }

    /** Three dots, the usual "more options" glyph, centred on {@code cx, cy} - the list's row
     *  button and the create screen's page button. Hand-drawn like the star. */
    public static void drawMoreGlyph(GuiGraphicsExtractor g, int cx, int cy, int color) {
        for (int dx = -5; dx <= 3; dx += 4) {
            g.fill(cx + dx, cy - 1, cx + dx + 2, cy + 1, color);
        }
    }
}
