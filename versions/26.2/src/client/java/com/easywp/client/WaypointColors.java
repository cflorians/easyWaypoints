package com.easywp.client;

import java.util.Random;

/**
 * The 60-color waypoint palette, extracted out of {@code WaypointCreateScreen} once a second
 * caller ({@code ModKeyBindings}' Fast Ping, which has no picker UI to offer and just wants one at
 * random) needed the same grid - the same reasoning {@link DimensionNames} documents for its own
 * extraction. No Minecraft imports, so it stays trivially identical across versions.
 */
public final class WaypointColors {

    private WaypointColors() {
    }

    /**
     * 15 columns (grayscale, then 14 hues in spectrum order) x 4 rows (vivid / light / deep / dark
     * shade of each hue), so scanning either direction lands on similar colors: down a column for
     * shades of the same hue, across a row for neighboring hues at the same shade.
     */
    public static final int[][] COLOR_GRID = {
        // Row 1: Vivid — bright, saturated tones across the spectrum
        { 0xFFFFFFFF, 0xFFFF3B30, 0xFFFF9500, 0xFFFFC107, 0xFFFFCC00, 0xFFAEEA00, 0xFF28CD41, 0xFF00C7BE, 0xFF00E5FF, 0xFF29B6F6, 0xFF007AFF, 0xFF5856D6, 0xFF7C4DFF, 0xFFAF52DE, 0xFFFF2D92 },
        // Row 2: Light — pastel tints of the same hues
        { 0xFFC0C0C0, 0xFFFF8A80, 0xFFFFB74D, 0xFFFFE082, 0xFFFFF176, 0xFFDCE775, 0xFF81C784, 0xFF4DB6AC, 0xFF4DD0E1, 0xFF81D4FA, 0xFF64B5F6, 0xFF7986CB, 0xFFB39DDB, 0xFFBA68C8, 0xFFF48FB1 },
        // Row 3: Deep — saturated dark shades of the same hues
        { 0xFF4A4A4A, 0xFFB71C1C, 0xFFE65100, 0xFFFF8F00, 0xFFC7A600, 0xFF7CB342, 0xFF1B5E20, 0xFF004D40, 0xFF006064, 0xFF0277BD, 0xFF0D47A1, 0xFF1A237E, 0xFF4A148C, 0xFF6A1B9A, 0xFFAD1457 },
        // Row 4: Dark — near-black shades of the same hues
        { 0xFF000000, 0xFF4A0000, 0xFF7A3C00, 0xFF7A4F00, 0xFF5C4B00, 0xFF33691E, 0xFF0D3010, 0xFF00251A, 0xFF00363A, 0xFF01315C, 0xFF052863, 0xFF0B1247, 0xFF260A4A, 0xFF380E52, 0xFF5C0B2C }
    };

    /** One of the 60 grid colors, picked uniformly at random - the manual picker's own starting
     *  selection, and the only color Fast Ping ever gets since it has no picker to offer one. */
    public static int random() {
        Random rand = new Random();
        int r = rand.nextInt(COLOR_GRID.length);
        int c = rand.nextInt(COLOR_GRID[r].length);
        return COLOR_GRID[r][c];
    }
}
