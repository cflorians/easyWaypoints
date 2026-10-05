package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameNumberingTest {

    private static final IntFunction<String> WAYPOINT = n -> "WAYPOINT #" + n;

    @Test
    void anEmptyListStartsAtOne() {
        assertEquals(1, NameNumbering.lowestUnused(WAYPOINT, Set.of()));
    }

    @Test
    void consecutiveNamesContinueAfterTheLast() {
        assertEquals(3, NameNumbering.lowestUnused(WAYPOINT, Set.of("WAYPOINT #1", "WAYPOINT #2")));
    }

    @Test
    void aDeletedNumberIsHandedOutAgain() {
        assertEquals(2, NameNumbering.lowestUnused(WAYPOINT, Set.of("WAYPOINT #1", "WAYPOINT #3")));
        assertEquals(1, NameNumbering.lowestUnused(WAYPOINT, Set.of("WAYPOINT #2", "WAYPOINT #3")));
    }

    @Test
    void otherNamesDoNotTakeANumber() {
        // Fast Ping's names share the #N shape and must not count; neither do renamed waypoints.
        assertEquals(1, NameNumbering.lowestUnused(WAYPOINT,
                Set.of("Ping #1", "Ping #2", "Base", "WAYPOINT #1 old", "waypoint #1")));
    }

    @Test
    void theCountOfNamesIsNotTheNumber() {
        // Three waypoints, none of them numbered: still #1, where "count + 1" would give #4.
        assertEquals(1, NameNumbering.lowestUnused(WAYPOINT, Set.of("Casa", "Mina", "Portal")));
    }
}
