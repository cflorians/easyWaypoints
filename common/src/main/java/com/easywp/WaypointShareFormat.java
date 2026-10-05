package com.easywp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line a waypoint is shared as in chat: {@code NAME >>> DIMENSION X Y Z}, e.g.
 * {@code Base principal >>> Nether 12 64 -30}.
 *
 * <p>Fixed here in code rather than in the lang files, where it used to live as
 * {@code easywp.share.chat}: it is read back by a parser (the chat {@code [+]}, clipboard import),
 * and a translation that reordered or reworded it would silently break that for everyone receiving
 * it. The name goes as the player wrote it (1.3.x forced it to upper case); the three vanilla
 * dimensions by their readable name, any other by its full id, which 1.3.x dropped in favour of a
 * prettified name nothing could resolve back.
 *
 * <p>{@link #parse} reads from the right: the last three numbers are the coordinates, the last
 * {@code >>>} before them ends the name. So a name may itself contain {@code >>>}, and a dimension
 * may contain spaces ({@code The End}). Lines from 1.3.x parse too; their prettified modded
 * dimension comes back as {@code null}, which the receiver replaces with its own dimension.
 */
public final class WaypointShareFormat {

    public static final String SEPARATOR = " >>> ";

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final String END = "minecraft:the_end";

    /** Greedy name up to the last {@code >>>}, then the dimension, then three integers at the end. */
    private static final Pattern LINE = Pattern.compile(
            "^(.*\\S)\\s*>>>\\s*(\\S.*?)\\s+(-?\\d{1,10})\\s+(-?\\d{1,10})\\s+(-?\\d{1,10})\\s*$");

    /** A namespaced id, as {@code Identifier} accepts it: lower-case namespace and path. */
    private static final Pattern DIMENSION_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private WaypointShareFormat() {
    }

    /** A waypoint read back from a share line. {@code dimension} is an id, or {@code null} when the
     *  line named one this format cannot resolve (a 1.3.x prettified modded dimension). */
    public record Shared(String name, String dimension, int x, int y, int z) {
    }

    public static String format(String name, String dimension, int x, int y, int z) {
        return name + SEPARATOR + dimensionLabel(dimension) + " " + x + " " + y + " " + z;
    }

    /** {@code Overworld}, {@code Nether}, {@code The End}, or the id itself for any other dimension. */
    public static String dimensionLabel(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "The End";
            default -> dimension;
        };
    }

    /** The inverse of {@link #dimensionLabel}: a vanilla name (any case) or a namespaced id gives the
     *  id; anything else - such as 1.3.x's {@code Twilight Forest} - gives {@code null}. */
    public static String dimensionFromLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "overworld":
                return OVERWORLD;
            case "nether":
                return NETHER;
            case "the end":
                return END;
            default:
                return DIMENSION_ID.matcher(trimmed).matches() ? trimmed : null;
        }
    }

    /** The waypoint in {@code line}, or {@code null} if it is not a share line. The whole line must
     *  be one: text before or after it makes it unreadable. */
    public static Shared parse(String line) {
        if (line == null) return null;
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        try {
            int x = Integer.parseInt(m.group(3));
            int y = Integer.parseInt(m.group(4));
            int z = Integer.parseInt(m.group(5));
            // Never empty: the pattern's name group ends in a non-space character.
            return new Shared(m.group(1).trim(), dimensionFromLabel(m.group(2)), x, y, z);
        } catch (NumberFormatException outOfRange) {
            return null;
        }
    }
}
