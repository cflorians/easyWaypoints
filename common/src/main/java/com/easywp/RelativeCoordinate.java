package com.easywp;

import java.util.OptionalDouble;

/**
 * Reads one coordinate the way vanilla commands do: a plain number, or {@code ~} for the player's
 * own position on that axis, optionally followed by an offset ({@code ~5}, {@code ~-3},
 * {@code ~0.5}).
 *
 * <p>Pure text, so it lives in {@code common/} with its own tests; the create screen's X/Y/Z
 * fields use it today and the {@code /easywp} commands will reuse it.
 *
 * <p>A plain number is read with {@link Double#parseDouble}, exactly as the create screen always
 * read its fields, so nothing that was accepted before stops being accepted - except values that
 * are not finite ({@code NaN}, {@code Infinity}, or a number too large to be a double), which used
 * to turn into a coordinate of 0 or of the largest int and now count as unreadable.
 */
public final class RelativeCoordinate {
    private RelativeCoordinate() {
    }

    /**
     * @param text the field's contents; surrounding spaces are ignored, a space right after
     *             {@code ~} is not ({@code "~ 5"} is unreadable, as in vanilla)
     * @param base the player's position on this axis, or {@code NaN} if there is no player - then
     *             every {@code ~} form is unreadable, while a plain number still reads
     * @return the coordinate, or empty if the text cannot be read; the caller keeps whatever it
     *         had before in that case
     */
    public static OptionalDouble parse(String text, double base) {
        if (text == null) {
            return OptionalDouble.empty();
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (trimmed.charAt(0) != '~') {
            return number(trimmed);
        }
        String offset = trimmed.substring(1);
        if (offset.isEmpty()) {
            return finite(base);
        }
        if (Character.isWhitespace(offset.charAt(0))) {
            return OptionalDouble.empty();
        }
        OptionalDouble delta = number(offset);
        return delta.isPresent() ? finite(base + delta.getAsDouble()) : OptionalDouble.empty();
    }

    private static OptionalDouble number(String text) {
        try {
            return finite(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    private static OptionalDouble finite(double value) {
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }
}
