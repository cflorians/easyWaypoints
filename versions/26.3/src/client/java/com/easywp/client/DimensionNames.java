package com.easywp.client;

import java.util.Locale;

/**
 * Turns a dimension identifier into the various human-readable forms the mod needs.
 *
 * <p>Extracted from {@code WaypointListScreen}, which used to be the only place any of this lived,
 * once a second caller needed the same name/color for the same reason (labeling and coloring by
 * dimension): having two copies of a vanilla/modded-dimension naming switch is exactly the kind of
 * drift {@code checkSharedSources} exists to catch for files, not logic duplicated inside one. No
 * Minecraft imports, so - like {@link UiPalette} and {@link Icons} - it stays trivially identical
 * across versions.
 */
public final class DimensionNames {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    private DimensionNames() {
    }

    /** Header label for a dimension tab. */
    public static String title(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> "OVERWORLD";
            case NETHER -> "NETHER";
            case END -> "THE END";
            default -> prettyName(dimension).toUpperCase(Locale.ROOT);
        };
    }

    /** Natural-case dimension name, for messages shown to this player. The share line has its own,
     *  fixed rule ({@code WaypointShareFormat.dimensionLabel}), which a modded dimension's
     *  prettified name here would not survive. */
    public static String chatName(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "The End";
            default -> prettyName(dimension);
        };
    }

    /** Turns an identifier into something readable: "twilightforest:dark_forest" -> "Dark Forest". */
    public static String prettyName(String dimension) {
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

    /** The other half of the Overworld/Nether pair, or {@code null} for any other dimension - the
     *  only pair with a coordinate relationship (1:8) to place a copy across. */
    public static String paired(String dimension) {
        if (OVERWORLD.equals(dimension)) {
            return NETHER;
        }
        if (NETHER.equals(dimension)) {
            return OVERWORLD;
        }
        return null;
    }

    public static int color(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> UiPalette.DIM_OVERWORLD;
            case NETHER -> UiPalette.DIM_NETHER;
            case END -> UiPalette.DIM_END;
            default -> UiPalette.TEXT_PRIMARY;
        };
    }
}
