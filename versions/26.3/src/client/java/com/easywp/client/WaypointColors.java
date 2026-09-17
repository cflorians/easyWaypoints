package com.easywp.client;

import java.util.Random;

/**
 * The waypoint color picker's data: a small bar of mutually distinct family colors, and - for
 * whichever one is currently active - a longer bar of that same color's shades, dark to light.
 *
 * <p>This is the third shape this file has been. The first was a single grid dense enough that
 * neighboring cells were near-duplicate shades of each other (60 colors, then 196) - good for
 * "similar colors sit near each other," bad for "any two swatches are easy to tell apart," which
 * is the thing a picker actually needs. The second split that into 20 base colors plus a 7-shade
 * panel for whichever one was active - better, but the bases still doubled up visually at 18
 * hues 20° apart, and 7 shades was thin for a proper dark-to-light ramp. This version narrows the
 * family bar to 12 hues, spaced a full 30° apart (12 * 30 = 360, so - like the 18-hue version
 * before it - the spacing divides evenly with none left over), plus white and black; and widens
 * each family's own shade bar to 15, running strictly dark (index 0) to light (the last index),
 * with the familiar "vivid" tone landing exactly in the middle.
 *
 * <p>No Minecraft imports, so it stays trivially identical across versions.
 */
public final class WaypointColors {

    private WaypointColors() {
    }

    /** How many shades each family (a row of {@link #HUE_FAMILIES}, or {@link #GRAYSCALE_FAMILY})
     *  has, dark (index 0) to light (index {@code SHADE_COUNT - 1}). */
    public static final int SHADE_COUNT = 15;

    /**
     * 12 hues, 30° apart around the color wheel, x {@link #SHADE_COUNT} shades per hue. Each row
     * is built as two halves meeting at the middle index (7): darkening from index 0 up to the
     * vivid tone at full saturation and rising brightness, then lightening from that same vivid
     * tone up to index 14 at falling saturation and full brightness - shades by mixing in black,
     * tints by mixing in white, the vivid tone shared as the one point both halves pass through.
     */
    public static final int[][] HUE_FAMILIES = {
        { 0xFF240000, 0xFF430000, 0xFF620000, 0xFF820000, 0xFFA10000, 0xFFC00000, 0xFFE00000, 0xFFFF0000, 0xFFFF2020, 0xFFFF4040, 0xFFFF6060, 0xFFFF8080, 0xFFFFA0A0, 0xFFFFC0C0, 0xFFFFE0E0 }, // H=0
        { 0xFF241200, 0xFF432200, 0xFF623100, 0xFF824100, 0xFFA15100, 0xFFC06000, 0xFFE07000, 0xFFFF8000, 0xFFFF9020, 0xFFFFA040, 0xFFFFB060, 0xFFFFC080, 0xFFFFD0A0, 0xFFFFE0C0, 0xFFFFF0E0 }, // H=30
        { 0xFF242400, 0xFF434300, 0xFF626200, 0xFF828200, 0xFFA1A100, 0xFFC0C000, 0xFFE0E000, 0xFFFFFF00, 0xFFFFFF20, 0xFFFFFF40, 0xFFFFFF60, 0xFFFFFF80, 0xFFFFFFA0, 0xFFFFFFC0, 0xFFFFFFE0 }, // H=60
        { 0xFF122400, 0xFF224300, 0xFF316200, 0xFF418200, 0xFF51A100, 0xFF60C000, 0xFF70E000, 0xFF80FF00, 0xFF90FF20, 0xFFA0FF40, 0xFFB0FF60, 0xFFC0FF80, 0xFFD0FFA0, 0xFFE0FFC0, 0xFFF0FFE0 }, // H=90
        { 0xFF002400, 0xFF004300, 0xFF006200, 0xFF008200, 0xFF00A100, 0xFF00C000, 0xFF00E000, 0xFF00FF00, 0xFF20FF20, 0xFF40FF40, 0xFF60FF60, 0xFF80FF80, 0xFFA0FFA0, 0xFFC0FFC0, 0xFFE0FFE0 }, // H=120
        { 0xFF002412, 0xFF004322, 0xFF006231, 0xFF008241, 0xFF00A151, 0xFF00C060, 0xFF00E070, 0xFF00FF80, 0xFF20FF90, 0xFF40FFA0, 0xFF60FFB0, 0xFF80FFC0, 0xFFA0FFD0, 0xFFC0FFE0, 0xFFE0FFF0 }, // H=150
        { 0xFF002424, 0xFF004343, 0xFF006262, 0xFF008282, 0xFF00A1A1, 0xFF00C0C0, 0xFF00E0E0, 0xFF00FFFF, 0xFF20FFFF, 0xFF40FFFF, 0xFF60FFFF, 0xFF80FFFF, 0xFFA0FFFF, 0xFFC0FFFF, 0xFFE0FFFF }, // H=180
        { 0xFF001224, 0xFF002243, 0xFF003162, 0xFF004182, 0xFF0051A1, 0xFF0060C0, 0xFF0070E0, 0xFF0080FF, 0xFF2090FF, 0xFF40A0FF, 0xFF60B0FF, 0xFF80C0FF, 0xFFA0D0FF, 0xFFC0E0FF, 0xFFE0F0FF }, // H=210
        { 0xFF000024, 0xFF000043, 0xFF000062, 0xFF000082, 0xFF0000A1, 0xFF0000C0, 0xFF0000E0, 0xFF0000FF, 0xFF2020FF, 0xFF4040FF, 0xFF6060FF, 0xFF8080FF, 0xFFA0A0FF, 0xFFC0C0FF, 0xFFE0E0FF }, // H=240
        { 0xFF120024, 0xFF220043, 0xFF310062, 0xFF410082, 0xFF5100A1, 0xFF6000C0, 0xFF7000E0, 0xFF8000FF, 0xFF9020FF, 0xFFA040FF, 0xFFB060FF, 0xFFC080FF, 0xFFD0A0FF, 0xFFE0C0FF, 0xFFF0E0FF }, // H=270
        { 0xFF240024, 0xFF430043, 0xFF620062, 0xFF820082, 0xFFA100A1, 0xFFC000C0, 0xFFE000E0, 0xFFFF00FF, 0xFFFF20FF, 0xFFFF40FF, 0xFFFF60FF, 0xFFFF80FF, 0xFFFFA0FF, 0xFFFFC0FF, 0xFFFFE0FF }, // H=300
        { 0xFF240012, 0xFF430022, 0xFF620031, 0xFF820041, 0xFFA10051, 0xFFC00060, 0xFFE00070, 0xFFFF0080, 0xFFFF2090, 0xFFFF40A0, 0xFFFF60B0, 0xFFFF80C0, 0xFFFFA0D0, 0xFFFFC0E0, 0xFFFFE0F0 }  // H=330
    };

    /** Black-to-white ramp - the "family" shown when either the white or the black swatch in the
     *  family bar is active, since neither has a hue of its own to shade or tint. */
    public static final int[] GRAYSCALE_FAMILY = {
        0xFF080808, 0xFF191919, 0xFF2B2B2B, 0xFF3D3D3D, 0xFF4E4E4E, 0xFF606060, 0xFF727272, 0xFF838383, 0xFF959595, 0xFFA7A7A7, 0xFFB8B8B8, 0xFFCACACA, 0xFFDCDCDC, 0xFFEDEDED, 0xFFFFFFFF
    };

    /**
     * The family bar: 14 entries, flat (it is drawn and clicked as one touching row, not a grid)
     * - the 12 hues (spectrum order, same order as {@link #HUE_FAMILIES}), then white, then
     * black. {@link #WHITE_INDEX}/{@link #BLACK_INDEX} are those last two flat indices.
     *
     * <p>Each hue entry is {@code HUE_FAMILIES[i][6]}, one step darker than that hue's fully
     * saturated, fully bright vivid tone at index {@code SHADE_COUNT / 2} (7) - full brightness
     * at full saturation is the single most visually "loud" point on the whole ramp, and a bar of
     * 12 of them side by side read as garish rather than as a calm set of options to choose a
     * family from. Pulling back by exactly one step keeps the hue fully saturated and still
     * clearly identifiable, without pulling back far enough to look muddy or dim - white and
     * black are left at their own extremes, since neither is a saturated color with a "loud"
     * version to soften in the first place.
     */
    public static final int[] FAMILY_BAR = {
        0xFFE00000, 0xFFE07000, 0xFFE0E000, 0xFF70E000, 0xFF00E000, 0xFF00E070, 0xFF00E0E0, 0xFF0070E0, 0xFF0000E0, 0xFF7000E0, 0xFFE000E0, 0xFFE00070, 0xFFFFFFFF, 0xFF000000
    };

    private static final int HUE_COUNT = HUE_FAMILIES.length;
    public static final int WHITE_INDEX = HUE_COUNT;
    public static final int BLACK_INDEX = HUE_COUNT + 1;

    /** @return the shade bar a family-bar entry owns: its own hue's row for one of the 12 hues,
     *  or the shared grayscale ramp for white/black. */
    public static int[] familyOf(int baseIndex) {
        return baseIndex < HUE_COUNT ? HUE_FAMILIES[baseIndex] : GRAYSCALE_FAMILY;
    }

    /**
     * Reconstructs picker state for an arbitrary starting color - a waypoint's own saved value,
     * or {@link #random()}'s pick - that need not be exactly one of the 14 family-bar entries.
     *
     * @return {@code {baseIndex, shadeIndex}}: the closest family's index (for highlighting it in
     *         the bar and choosing which shades the lower bar shows), and which shade within that
     *         family (0..{@code SHADE_COUNT - 1}) is nearest (for highlighting it there too).
     *         Found by nearest RGB distance across every shade of every family, not just the 14
     *         family-bar entries themselves - a color from deep within a hue's dark end is still
     *         that hue's family, even though pure black is numerically closer to it in plain RGB
     *         terms than that hue's own vivid tone is.
     */
    public static int[] nearestMatch(int argb) {
        int bestHue = 0;
        int bestHueShade = 0;
        long bestHueDist = Long.MAX_VALUE;
        for (int h = 0; h < HUE_COUNT; h++) {
            int[] family = HUE_FAMILIES[h];
            for (int s = 0; s < family.length; s++) {
                long d = distance(family[s], argb);
                if (d < bestHueDist) {
                    bestHueDist = d;
                    bestHue = h;
                    bestHueShade = s;
                }
            }
        }

        int bestGrayShade = 0;
        long bestGrayDist = Long.MAX_VALUE;
        for (int s = 0; s < GRAYSCALE_FAMILY.length; s++) {
            long d = distance(GRAYSCALE_FAMILY[s], argb);
            if (d < bestGrayDist) {
                bestGrayDist = d;
                bestGrayShade = s;
            }
        }

        if (bestGrayDist < bestHueDist) {
            // GRAYSCALE_FAMILY runs dark (index 0) to light (the last index), same as every hue
            // row, so the lighter half belongs to white and the darker half to black.
            int baseIndex = bestGrayShade >= GRAYSCALE_FAMILY.length / 2 ? WHITE_INDEX : BLACK_INDEX;
            return new int[] { baseIndex, bestGrayShade };
        }
        return new int[] { bestHue, bestHueShade };
    }

    private static long distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return (long) dr * dr + (long) dg * dg + (long) db * db;
    }

    /** One color picked uniformly at random from every shade of every family (12 * 15 + 15 = 195
     *  in total, not just the 14 family-bar entries) - the manual picker's own starting
     *  selection, and the only color Fast Ping ever gets since it has no picker to offer one. */
    public static int random() {
        Random rand = new Random();
        int totalHueShades = HUE_COUNT * SHADE_COUNT;
        int totalShades = totalHueShades + GRAYSCALE_FAMILY.length;
        int pick = rand.nextInt(totalShades);
        if (pick < totalHueShades) {
            return HUE_FAMILIES[pick / SHADE_COUNT][pick % SHADE_COUNT];
        }
        return GRAYSCALE_FAMILY[pick - totalHueShades];
    }
}
