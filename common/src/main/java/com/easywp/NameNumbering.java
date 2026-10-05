package com.easywp;

import java.util.Set;
import java.util.function.IntFunction;

/**
 * Picks the number for a numbered default name like {@code WAYPOINT #N}.
 *
 * <p>Pure, so it lives in {@code common/} with its own tests. The numbers are tried against the
 * finished name rather than read back out of the existing ones, on purpose: {@code WAYPOINT #N}
 * and Fast Ping's {@code Ping #N} share the {@code #N} shape, and reading digits out of names
 * would count the pings as well.
 */
public final class NameNumbering {
    private NameNumbering() {
    }

    /**
     * @param nameFor the name number {@code n} would produce, exactly as it will be saved
     * @param taken   the names already in use, compared exactly (case included)
     * @return the lowest {@code n >= 1} whose name is not taken - so a deleted waypoint's number
     *         is handed out again, and a name still in use never is
     */
    public static int lowestUnused(IntFunction<String> nameFor, Set<String> taken) {
        int n = 1;
        while (taken.contains(nameFor.apply(n))) {
            n++;
        }
        return n;
    }
}
