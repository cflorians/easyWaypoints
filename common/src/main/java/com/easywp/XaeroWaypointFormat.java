package com.easywp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Parser/serializer for Xaero's Minimap {@code waypoints.txt} format, and the matching
 * {@code xaero-waypoint:} chat-share line.
 *
 * <p>Lives in {@code common/} for the same reason as {@link JsonStore}: it is pure text handling,
 * nothing {@code net.minecraft.*}, so it compiles standalone against this module's Minecraft-free
 * dependency set and both version modules share it unmodified - and it is the only part of Xaero
 * integration that can be verified without launching the game.
 *
 * <p>The field layout below is not published anywhere Xaero documents for developers - it was read
 * directly from the bytecode of {@code xaero.hud.minimap.waypoint.io.WaypointIO} inside
 * {@code xaerominimap-fabric-26.2-26.5.0.jar} (disassembling {@code checkLine}/{@code saveWaypoints}),
 * because third-party descriptions of this format online are for older Xaero versions and disagree
 * with each other on the field count. That class's own writer literally emits this line as a file
 * comment, which matches its real read/write code field-for-field:
 * <pre>#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination</pre>
 *
 * <p>Fields 0-9 (through {@code set}) are required: Xaero's own reader indexes {@code arr[9]}
 * unconditionally the moment a line's first field is {@code waypoint}, so a shorter line throws on
 * Xaero's side - this class treats it the same way, as unparseable rather than guessed. Fields 10-13
 * are read only if present ({@code arr.length > 10/11/12/13} in the real reader, one guard per field)
 * and are always written by this version of Xaero; a short line round-trips with Xaero's own defaults
 * for the missing tail (disabled, yaw 0, etc. via {@link XaeroWaypoint#trailing()} being empty), and a
 * full line's trailing fields are preserved verbatim rather than assumed to be the last ones Xaero
 * will ever add.
 */
public final class XaeroWaypointFormat {

    private XaeroWaypointFormat() {
    }

    private static final Pattern COLON = Pattern.compile(":");
    private static final String TAG = "waypoint:";

    /**
     * The name of Xaero's own default waypoint set - the one a waypoint lands in unless the player has
     * switched sets. Taken from {@code MinimapWorld.DEFAULT_SET} in Xaero's bytecode rather than
     * invented, because a {@code set} field naming a set Xaero doesn't have would put the written
     * waypoints in a set the player has to go find before they can see them.
     */
    public static final String DEFAULT_SET_NAME = "gui.xaero_default";

    /**
     * One waypoint line. {@code y} is {@code null} for a {@code ~} (altitude-independent) waypoint -
     * Xaero writes the literal character {@code ~} instead of a number when
     * {@code Waypoint.isYIncluded()} is false, and expects the same back on read.
     *
     * <p>{@code colorOrdinal} indexes {@link #colorCount()} palette entries - Xaero's own 21-entry
     * {@code WaypointColor} enum, in declaration order. That is the same ordinal the live bridge's
     * {@code WaypointColor.ordinal()}/{@code fromIndex(int)} use, so one palette (see
     * {@link #nearestColorOrdinal}/{@link #rgbForColorOrdinal}) serves both this file format and the
     * live API.
     *
     * <p>{@code trailing} holds every field from index 10 onward verbatim. This class understands all
     * four of them in the current format, but keeps them as opaque strings rather than typed fields so
     * a future Xaero version's 15th field survives a round-trip through here unexamined instead of
     * being silently dropped.
     */
    public record XaeroWaypoint(
            String name,
            String initials,
            int x,
            Integer y,
            int z,
            int colorOrdinal,
            boolean disabled,
            int type,
            String set,
            List<String> trailing
    ) {
        public XaeroWaypoint {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(initials, "initials");
            Objects.requireNonNull(set, "set");
            trailing = trailing == null ? List.of() : List.copyOf(trailing);
        }

        /** Convenience constructor for a waypoint this mod is creating fresh: always Y-included,
         *  enabled, the default marker shape, and no trailing fields to preserve. */
        public XaeroWaypoint(String name, int x, int y, int z, int colorOrdinal, String set) {
            this(name, initialsFor(name), x, y, z, colorOrdinal, false, 0, set, List.of());
        }
    }

    /**
     * The result of {@link #parse}: the file's waypoint lines, and every other line (comments,
     * {@code sets:} directives, blank lines, anything else) verbatim and in order, so the file can be
     * rebuilt without losing content this class doesn't specifically understand.
     */
    public record ParsedFile(List<XaeroWaypoint> waypoints, List<String> otherLines) {
        public ParsedFile {
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
            otherLines = otherLines == null ? List.of() : List.copyOf(otherLines);
        }
    }

    public static ParsedFile parse(String contents) {
        List<XaeroWaypoint> waypoints = new ArrayList<>();
        List<String> other = new ArrayList<>();
        if (contents == null || contents.isEmpty()) {
            return new ParsedFile(waypoints, other);
        }
        String[] lines = contents.split("\n", -1);
        // split(..., -1) keeps a trailing empty element for content ending in '\n', which every
        // file this class writes does. Dropping exactly one trailing empty line here keeps
        // serialize-then-parse-then-serialize stable instead of growing the file by one blank
        // line on every round-trip.
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        for (int i = 0; i < lineCount; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            XaeroWaypoint wp = parseLine(line);
            if (wp != null) {
                waypoints.add(wp);
            } else {
                other.add(line);
            }
        }
        return new ParsedFile(waypoints, other);
    }

    private static XaeroWaypoint parseLine(String line) {
        if (!line.regionMatches(true, 0, TAG, 0, TAG.length())) {
            return null;
        }
        String[] f = COLON.split(line, -1);
        // Xaero's own reader indexes f[9] unconditionally once the tag matches; a shorter line is
        // exactly as unreadable to Xaero as it is here, so it is skipped rather than guessed.
        if (f.length < 10) {
            return null;
        }
        try {
            int x = Integer.parseInt(f[3]);
            Integer y = "~".equals(f[4]) ? null : Integer.valueOf(Integer.parseInt(f[4]));
            int z = Integer.parseInt(f[5]);
            int colorOrdinal = Integer.parseInt(f[6]);
            boolean disabled = Boolean.parseBoolean(f[7]);
            int type = Integer.parseInt(f[8]);
            List<String> trailing = f.length > 10 ? List.of(f).subList(10, f.length) : List.of();
            return new XaeroWaypoint(f[1], f[2], x, y, z, colorOrdinal, disabled, type, f[9], trailing);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String serialize(XaeroWaypoint wp) {
        StringBuilder sb = new StringBuilder(TAG);
        sb.append(sanitize(wp.name())).append(':')
                .append(sanitize(wp.initials())).append(':')
                .append(wp.x()).append(':')
                .append(wp.y() == null ? "~" : wp.y().toString()).append(':')
                .append(wp.z()).append(':')
                .append(wp.colorOrdinal()).append(':')
                .append(wp.disabled()).append(':')
                .append(wp.type()).append(':')
                .append(sanitize(wp.set()));
        for (String extra : wp.trailing()) {
            sb.append(':').append(extra);
        }
        return sb.toString();
    }

    /**
     * Xaero's format has no escape for {@code :} - it is the field separator - so a name, initials,
     * or set name carrying one would desync every field after it when Xaero itself reads the line.
     * Replaced with {@code -} rather than dropped, so the text stays recognizable; newlines are
     * flattened to a space for the same reason, one field must stay one line.
     */
    private static String sanitize(String value) {
        return value.replace(':', '-').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Appends {@code toAdd} to {@code existingContents}, preserving every line this class doesn't
     * parse as a waypoint (comments, {@code sets:} directives, anything else) and every waypoint
     * already present, exactly as found. A candidate whose name, position, and y-inclusion already
     * matches an existing waypoint is skipped, so calling this twice with the same input is
     * idempotent rather than duplicating.
     *
     * <p>Add-only is the whole contract, not a limitation: this backs easyWp's manual "Export to
     * Xaero" button, which must never delete or overwrite a waypoint the player made by hand in the
     * same file. An edit or delete on easyWp's side is therefore not reflected here at all.
     */
    public static String merge(String existingContents, List<XaeroWaypoint> toAdd) {
        ParsedFile existing = parse(existingContents);
        List<XaeroWaypoint> merged = new ArrayList<>(existing.waypoints());
        for (XaeroWaypoint candidate : toAdd) {
            boolean alreadyPresent = false;
            for (XaeroWaypoint present : merged) {
                if (sameSpot(present, candidate)) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) {
                merged.add(candidate);
            }
        }
        return render(existing.otherLines(), merged);
    }

    private static boolean sameSpot(XaeroWaypoint a, XaeroWaypoint b) {
        return a.x() == b.x() && a.z() == b.z() && Objects.equals(a.y(), b.y()) && a.name().equals(b.name());
    }

    /**
     * Drops every waypoint matching one of {@code toRemove} by name, X and Z, preserving every other
     * waypoint and every line this class doesn't parse as one. The counterpart to {@link #merge} for
     * the delete half of a sync, and the file-path equivalent of what the live bridge does by pulling
     * entries out of a {@code WaypointSet}.
     *
     * <p>Y is deliberately <em>not</em> part of the match, unlike {@link #merge}'s own dedup: the live
     * path compares name + X + Z because Xaero reports a synthetic Y for a waypoint saved without one,
     * and the two paths removing different things for the same request would be worse than either
     * rule on its own.
     */
    public static String removeAll(String existingContents, List<XaeroWaypoint> toRemove) {
        ParsedFile existing = parse(existingContents);
        if (toRemove.isEmpty()) {
            return render(existing.otherLines(), existing.waypoints());
        }
        List<XaeroWaypoint> kept = new ArrayList<>();
        for (XaeroWaypoint wp : existing.waypoints()) {
            boolean doomed = false;
            for (XaeroWaypoint target : toRemove) {
                if (sameColumn(wp, target)) {
                    doomed = true;
                    break;
                }
            }
            if (!doomed) {
                kept.add(wp);
            }
        }
        return render(existing.otherLines(), kept);
    }

    private static boolean sameColumn(XaeroWaypoint a, XaeroWaypoint b) {
        return a.x() == b.x() && a.z() == b.z() && a.name().equals(b.name());
    }

    private static String render(List<String> otherLines, List<XaeroWaypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        for (String line : otherLines) {
            sb.append(line).append('\n');
        }
        for (XaeroWaypoint wp : waypoints) {
            sb.append(serialize(wp)).append('\n');
        }
        return sb.toString();
    }

    private static final String CHAT_TAG = "xaero-waypoint:";

    /**
     * The {@code xaero-waypoint:} line Xaero's own chat listener recognizes and renders with a
     * clickable {@code [Add]} button. <b>This is a different field layout from {@link #serialize}'s
     * file line, not the same one with a different tag</b> - the two happened to share a field count
     * in an earlier version of this method, which produced a line that parsed without throwing but
     * behaved wrong from field 7 onward, so {@code [Add]} silently did nothing. Confirmed from
     * {@code WaypointSharingHandler}'s own disassembled bytecode (the outgoing
     * {@code StringConcatFactory} recipe building the message, and the validating parser -
     * {@code onWaypointAdd(String[])} - on the receiving end): after the tag, the real fields are
     * {@code name:initials:x:y:z:color:rotation:yaw}, optionally followed by a 9th
     * "destination details" field describing which of the receiving player's own worlds to add the
     * waypoint to.
     *
     * <p>That 9th field is deliberately omitted here, not sent empty: {@code onWaypointAdd} only
     * even attempts to resolve a destination when the split array has more than 9 elements, and
     * falls back to "the receiving player's current world" otherwise - exactly what a shared
     * waypoint should do. Sending it empty (or, as the earlier version of this method did, with
     * this mod's own Xaero waypoint-set name in that slot) makes the receiver's parser try to
     * resolve a destination from a value that is neither the literal {@code "External"} nor an
     * {@code "Internal-<matching path>"} token it recognizes, fail, log one line on Xaero's own
     * client log, and silently add nothing - no exception, no chat error, nothing visible to either
     * player. Name and initials are truncated to the receiving parser's own hard limits (32 and 3
     * characters; {@code onWaypointAdd} rejects the message outright, same silent failure, otherwise)
     * and sanitized the same way a file line's fields are, since a literal {@code :} would desync
     * fields here exactly as it would there.
     */
    public static String chatShareLine(XaeroWaypoint wp) {
        String name = sanitize(truncate(wp.name(), 32));
        String initials = sanitize(truncate(wp.initials(), 3));
        String y = wp.y() == null ? "~" : wp.y().toString();
        return CHAT_TAG + name + ":" + initials + ":" + wp.x() + ":" + y + ":" + wp.z()
                + ":" + wp.colorOrdinal() + ":false:0";
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * Xaero's own 21 named waypoint colors, in {@code WaypointColor} declaration order - this array's
     * index is both the file format's {@code color} field and the live bridge's
     * {@code WaypointColor.ordinal()}/{@code fromIndex(int)}. Read from the enum's static initializer
     * in the same jar: the first 16 (through {@code WHITE}) matched vanilla {@code ChatFormatting}'s
     * RGB values exactly except {@code RED}, which Xaero sets to pure {@code 0xFF0000} rather than
     * vanilla's muted {@code 0xFF5555} - confirmed from the bytecode constant, not assumed. The last
     * five (Xaero-only names with no vanilla equivalent) use standard named-color RGB. This mapping
     * is approximate by design past what was confirmed byte-for-byte: the goal is a recognizable
     * same-ish color on Xaero's map, not exact fidelity.
     */
    private static final int[] COLOR_PALETTE = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF0000, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
            0xFF00FF, 0xADD8E6, 0x00FF00, 0xFFC0CB, 0x8B4513,
    };

    /**
     * The waypoint in a received {@code xaero-waypoint:} chat line - the inverse of
     * {@link #chatShareLine}, found anywhere in {@code text} so a sender prefix
     * ({@code <Steve> }) does not hide it. At least the eight fields Xaero's own receiving parser
     * requires ({@code name:initials:x:y:z:color:rotation:yaw}); anything after them is ignored. A Y
     * of {@code ~} (a waypoint saved without one) comes back as {@code null}, and an out-of-range
     * colour as {@code 0}. Returns {@code null} for anything else: too few fields, a non-numeric
     * coordinate or colour, an empty name.
     */
    public static XaeroWaypoint parseChatShareLine(String text) {
        if (text == null) return null;
        int at = text.indexOf(CHAT_TAG);
        if (at < 0) return null;
        String[] fields = text.substring(at + CHAT_TAG.length()).split(":", -1);
        if (fields.length < 8) return null;
        String name = fields[0].trim();
        if (name.isEmpty()) return null;
        try {
            int x = Integer.parseInt(fields[2].trim());
            String yText = fields[3].trim();
            Integer y = yText.equals("~") ? null : Integer.valueOf(yText);
            int z = Integer.parseInt(fields[4].trim());
            int color = Integer.parseInt(fields[5].trim());
            if (color < 0 || color >= COLOR_PALETTE.length) {
                color = 0;
            }
            return new XaeroWaypoint(name, fields[1].trim(), x, y, z, color, false, 0, DEFAULT_SET_NAME, List.of());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    public static int colorCount() {
        return COLOR_PALETTE.length;
    }

    /** Nearest of the {@link #colorCount()} palette entries to {@code argb} by squared RGB distance
     *  (alpha ignored - Xaero's palette has none). */
    public static int nearestColorOrdinal(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            int entry = COLOR_PALETTE[i];
            long dr = r - ((entry >> 16) & 0xFF);
            long dg = g - ((entry >> 8) & 0xFF);
            long db = b - (entry & 0xFF);
            long dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** Inverse of {@link #nearestColorOrdinal}: an opaque easyWp ARGB for a Xaero palette index,
     *  clamped to a valid index so a file hand-edited with an out-of-range color doesn't throw. */
    public static int rgbForColorOrdinal(int ordinal) {
        int safe = (ordinal < 0 || ordinal >= COLOR_PALETTE.length) ? 0 : ordinal;
        return 0xFF000000 | COLOR_PALETTE[safe];
    }

    /** The 1-2 letters Xaero shows on the marker itself, mirroring {@code Waypoint.getInitials()}'s
     *  own "X" fallback for an empty name (the same fallback Xaero's bundled Waystones-compat shim
     *  uses for an unnamed waystone). */
    public static String initialsFor(String name) {
        if (name == null || name.isEmpty()) {
            return "X";
        }
        return String.valueOf(name.charAt(0)).toUpperCase(Locale.ROOT);
    }
}
