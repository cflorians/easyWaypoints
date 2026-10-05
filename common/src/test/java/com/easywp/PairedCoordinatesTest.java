package com.easywp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairedCoordinatesTest {

    @Test
    void intoTheNetherDividesByEightAndRounds() {
        assertEquals(100, PairedCoordinates.toNether(800));
        assertEquals(-20, PairedCoordinates.toNether(-160));
        assertEquals(-2, PairedCoordinates.toNether(-15));
        assertEquals(2, PairedCoordinates.toNether(12));   // 1.5 rounds up
        assertEquals(-1, PairedCoordinates.toNether(-12)); // -1.5 rounds up, as Math.round does
        assertEquals(0, PairedCoordinates.toNether(3));
    }

    @Test
    void outOfTheNetherMultipliesByEight() {
        assertEquals(800, PairedCoordinates.toOverworld(100));
        assertEquals(-16, PairedCoordinates.toOverworld(-2));
    }

    @Test
    void anOverworldPointAndItsNetherCopyAreCopies() {
        assertTrue(PairedCoordinates.areCopies(800, -160, 100, -20));
        assertTrue(PairedCoordinates.areCopies(-15, 12, -2, 2));
    }

    @Test
    void aNetherPointAndItsOverworldCopyAreCopies() {
        int netherX = -2;
        int netherZ = 7;
        assertTrue(PairedCoordinates.areCopies(
                PairedCoordinates.toOverworld(netherX), PairedCoordinates.toOverworld(netherZ), netherX, netherZ));
    }

    @Test
    void aCopyOfACopyIsStillRecognised() {
        // -15 in the Overworld duplicates to -2 in the Nether, and that back to -16: all three
        // belong together, so duplicating either way again must find the existing one.
        assertTrue(PairedCoordinates.areCopies(-15, 0, -2, 0));
        assertTrue(PairedCoordinates.areCopies(-16, 0, -2, 0));
    }

    @Test
    void pointsThatDoNotCorrespondAreNotCopies() {
        assertFalse(PairedCoordinates.areCopies(800, -160, 101, -20));
        assertFalse(PairedCoordinates.areCopies(800, -160, 100, -21));
        assertFalse(PairedCoordinates.areCopies(-15, 0, -1, 0));
    }
}
