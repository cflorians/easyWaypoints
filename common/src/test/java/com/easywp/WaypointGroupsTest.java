package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.easywp.WaypointGroups.Filter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaypointGroupsTest {

    @Test
    void normalizeTrimsCollapsesAndCuts() {
        assertEquals("Minas de oro", WaypointGroups.normalize("  Minas   de\toro "));
        assertEquals("Añil", WaypointGroups.normalize("Añil"));
        assertEquals("abcdefghijklmnop", WaypointGroups.normalize("abcdefghijklmnopqrstu"));
        assertNull(WaypointGroups.normalize("   "));
        assertNull(WaypointGroups.normalize(null));
    }

    @Test
    void resolveReusesAnExistingGroupIgnoringCase() {
        List<String> existing = List.of("Minas", "Casas");
        assertEquals("Minas", WaypointGroups.resolve(existing, " minas "));
        assertEquals("Granjas", WaypointGroups.resolve(existing, "Granjas"));
        assertNull(WaypointGroups.resolve(existing, ""));
    }

    @Test
    void unionIsEveryGroupOnceSortedIgnoringCase() {
        assertEquals(List.of("aldea", "Casas", "Minas"),
                WaypointGroups.union(List.of(List.of("Minas", "Casas"), List.of(), List.of("aldea", "Minas"))));
        assertEquals(List.of(), WaypointGroups.union(List.of()));
    }

    @Test
    void theFilterKeepsWhatItNames() {
        assertTrue(WaypointGroups.matches(List.of(), Filter.ALL, null));
        assertTrue(WaypointGroups.matches(List.of(), Filter.NONE, null));
        assertFalse(WaypointGroups.matches(List.of("Minas"), Filter.NONE, null));
        assertTrue(WaypointGroups.matches(List.of("Casas", "Minas"), Filter.GROUP, "Minas"));
        assertFalse(WaypointGroups.matches(List.of("Casas"), Filter.GROUP, "Minas"));
        assertFalse(WaypointGroups.matches(List.of(), Filter.GROUP, "Minas"));
    }

    @Test
    void oneHiddenGroupIsEnoughToHide() {
        assertTrue(WaypointGroups.anyHidden(List.of("Casas", "Minas"), Set.of("Minas")));
        assertFalse(WaypointGroups.anyHidden(List.of("Casas"), Set.of("Minas")));
        assertFalse(WaypointGroups.anyHidden(List.of(), Set.of("Minas")));
        assertFalse(WaypointGroups.anyHidden(List.of("Minas"), Set.of()));
    }

    @Test
    void pruneDropsHiddenGroupsThatNoLongerExist() {
        assertEquals(Set.of("Minas"), WaypointGroups.prune(Set.of("Minas", "Viejo"), List.of("Minas", "Casas")));
    }

    @Test
    void canAddRefusesADuplicateAndAFullWaypoint() {
        assertTrue(WaypointGroups.canAdd(List.of("Casas"), "Minas"));
        assertFalse(WaypointGroups.canAdd(List.of("Minas"), "minas"));
        assertFalse(WaypointGroups.canAdd(List.of("a", "b", "c", "d"), "e"));
        assertTrue(WaypointGroups.canAdd(List.of("a", "b", "c"), "e"));
        assertFalse(WaypointGroups.canAdd(List.of(), null));
    }

    @Test
    void suggestionsMatchTheTextAndSkipWhatIsAssigned() {
        List<String> existing = List.of("Casas", "Minas", "Minas viejas");
        assertEquals(List.of("Minas", "Minas viejas"), WaypointGroups.suggestions(existing, List.of(), "min"));
        assertEquals(List.of("Minas viejas"), WaypointGroups.suggestions(existing, List.of("minas"), "MIN"));
        assertEquals(List.of("Casas", "Minas viejas"), WaypointGroups.suggestions(existing, List.of("Minas"), ""));
    }
}
