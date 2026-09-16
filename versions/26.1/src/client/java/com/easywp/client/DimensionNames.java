package com.easywp.client;

import java.util.Locale;

/**
 * Turns a dimension identifier into the various human-readable forms the mod needs, plus the one
 * piece of domain knowledge those forms share: which dimensions the renderer's {@code shared} 1:8
 * conversion actually understands.
 *
 * <p>Extracted from {@code WaypointListScreen}, which used to be the only place any of this lived,
 * once a second caller needed the same name/color for the same reason (labeling and coloring by
 * dimension): having two copies of a vanilla/modded-dimension naming switch is exactly the kind of
 * drift {@code checkSharedSources} exists to catch for files, not logic duplicated inside one. No
 * Minecraft imports, so - like {@link UiPalette} and {@link Icons} - it stays trivially identical
 * across versions.
 */
public final class DimensionNames {

    private DimensionNames() {
    }

    /** Header label for a dimension tab. */
    public static String title(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "OVERWORLD";
            case "minecraft:the_nether" -> "NETHER";
            case "minecraft:the_end" -> "THE END";
            default -> prettyName(dimension).toUpperCase(Locale.ROOT);
        };
    }

    /** Natural-case dimension name, used in the share-to-chat message. */
    public static String chatName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
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

    public static int color(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> UiPalette.DIM_OVERWORLD;
            case "minecraft:the_nether" -> UiPalette.DIM_NETHER;
            case "minecraft:the_end" -> UiPalette.DIM_END;
            default -> UiPalette.TEXT_PRIMARY;
        };
    }

    /**
     * Whether waypoints in this dimension can be mirrored into another one. Only the Overworld and
     * the Nether have the 1:8 relationship the renderer knows how to convert, which is the same rule
     * {@code WaypointCreateScreen} already applies to its own share toggle. For the vanilla three
     * this is exactly the old {@code !equals("minecraft:the_end")} test.
     */
    public static boolean isConvertible(String dimension) {
        return dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_nether");
    }
}
