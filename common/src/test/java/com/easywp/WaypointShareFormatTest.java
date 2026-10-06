package com.easywp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaypointShareFormatTest {

    private static final String NETHER = "minecraft:the_nether";
    private static final String TWILIGHT = "twilightforest:twilight_forest";

    @Test
    void formatsVanillaDimensionsByNameAndOthersById() {
        assertEquals("Base principal >>> Nether 12 64 -30",
                WaypointShareFormat.format("Base principal", NETHER, 12, 64, -30));
        assertEquals("Casa >>> Overworld 0 70 0",
                WaypointShareFormat.format("Casa", "minecraft:overworld", 0, 70, 0));
        assertEquals("Portal >>> The End 100 50 0",
                WaypointShareFormat.format("Portal", "minecraft:the_end", 100, 50, 0));
        assertEquals("Torre >>> twilightforest:twilight_forest 100 70 200",
                WaypointShareFormat.format("Torre", TWILIGHT, 100, 70, 200));
    }

    @Test
    void theNameKeepsItsCase() {
        assertEquals("mina de Hierro >>> Overworld 1 2 3",
                WaypointShareFormat.format("mina de Hierro", "minecraft:overworld", 1, 2, 3));
    }

    @Test
    void roundTripsEveryKindOfDimension() {
        for (String dim : new String[]{"minecraft:overworld", NETHER, "minecraft:the_end", TWILIGHT}) {
            String line = WaypointShareFormat.format("Sitio", dim, -5, 64, 7);
            assertEquals(new WaypointShareFormat.Shared("Sitio", dim, -5, 64, 7), WaypointShareFormat.parse(line));
        }
    }

    @Test
    void aNameMayContainTheSeparatorSpacesAndAccents() {
        String name = "Flecha >>> norte, añil";
        assertEquals(new WaypointShareFormat.Shared(name, NETHER, 1, 2, 3),
                WaypointShareFormat.parse(WaypointShareFormat.format(name, NETHER, 1, 2, 3)));
    }

    @Test
    void aNameThatLooksLikeALineStillReadsAsAName() {
        String name = "A >>> Nether 1 2 3";
        assertEquals(new WaypointShareFormat.Shared(name, "minecraft:overworld", 4, 5, 6),
                WaypointShareFormat.parse(WaypointShareFormat.format(name, "minecraft:overworld", 4, 5, 6)));
    }

    @Test
    void readsLinesFrom13x() {
        assertEquals(new WaypointShareFormat.Shared("BASE", NETHER, 12, 64, -30),
                WaypointShareFormat.parse("BASE >>> Nether 12 64 -30"));
        // 1.3.x prettified a modded dimension's id: nothing to resolve it back to.
        assertEquals(new WaypointShareFormat.Shared("TORRE", null, 100, 70, 200),
                WaypointShareFormat.parse("TORRE >>> Twilight Forest 100 70 200"));
    }

    @Test
    void vanillaNamesAreReadInAnyCase() {
        assertEquals("minecraft:the_end", WaypointShareFormat.dimensionFromLabel("THE END"));
        assertEquals(NETHER, WaypointShareFormat.dimensionFromLabel("nether"));
        assertEquals(NETHER, WaypointShareFormat.dimensionFromLabel(NETHER));
        assertNull(WaypointShareFormat.dimensionFromLabel("Twilight:Forest"));
    }

    @Test
    void toleratesExtraWhitespace() {
        assertEquals(new WaypointShareFormat.Shared("Casa", NETHER, 1, -2, 3),
                WaypointShareFormat.parse("  Casa   >>>  Nether  1   -2 3  "));
    }

    @Test
    void rejectsWhatIsNotAShareLine() {
        assertNull(WaypointShareFormat.parse(null));
        assertNull(WaypointShareFormat.parse(""));
        assertNull(WaypointShareFormat.parse("hola a todos"));
        assertNull(WaypointShareFormat.parse("Casa Nether 1 2 3"));          // no separator
        assertNull(WaypointShareFormat.parse("Casa >>> Nether 1 2"));        // two coordinates
        assertNull(WaypointShareFormat.parse("Casa >>> 1 2 3"));             // no dimension
        assertNull(WaypointShareFormat.parse(" >>> Nether 1 2 3"));          // no name
        assertNull(WaypointShareFormat.parse("Casa >>> Nether 1 2.5 3"));    // not a block
        assertNull(WaypointShareFormat.parse("Casa >>> Nether 1 2 3 y más")); // trailing text
        assertNull(WaypointShareFormat.parse("Casa >>> Nether 1 2 3000000000")); // past int range
    }

    @Test
    void findsTheLineAfterASenderPrefix() {
        WaypointShareFormat.Shared base = new WaypointShareFormat.Shared("Base principal", NETHER, 12, 64, -30);
        assertEquals(base, WaypointShareFormat.findIn("<Steve> Base principal >>> Nether 12 64 -30"));
        assertEquals(base, WaypointShareFormat.findIn("Steve whispers to you: Base principal >>> Nether 12 64 -30"));
        assertEquals(base, WaypointShareFormat.findIn("[Steve -> me] Base principal >>> Nether 12 64 -30"));
        assertEquals(base, WaypointShareFormat.findIn("Steve » Base principal >>> Nether 12 64 -30"));
    }

    @Test
    void aPluginPrefixWithSeveralPartsEndsAtTheLastOne() {
        assertEquals(new WaypointShareFormat.Shared("Casa", "minecraft:overworld", 1, 2, 3),
                WaypointShareFormat.findIn("[Admin] Steve: Casa >>> Overworld 1 2 3"));
    }

    @Test
    void aSeparatorInsideTheNameIsNotAPrefix() {
        assertEquals(new WaypointShareFormat.Shared("A >>> B", NETHER, 1, 2, 3),
                WaypointShareFormat.findIn("<Steve> A >>> B >>> Nether 1 2 3"));
    }

    @Test
    void aBareLineIsFoundToo() {
        assertEquals(new WaypointShareFormat.Shared("Casa", NETHER, 1, 2, 3),
                WaypointShareFormat.findIn("Casa >>> Nether 1 2 3"));
    }

    @Test
    void findsNothingInAnOrdinaryMessage() {
        assertNull(WaypointShareFormat.findIn(null));
        assertNull(WaypointShareFormat.findIn("<Steve> hola a todos"));
        assertNull(WaypointShareFormat.findIn("<Steve> vamos >>> ahora"));
    }

    @Test
    void chatSafeDropsWhatVanillaRefusesInChat() {
        assertEquals("cBase roja", WaypointShareFormat.chatSafe("§cBase roja"));
        assertEquals("ab", WaypointShareFormat.chatSafe("a\tb\n"));
        assertEquals("ab", WaypointShareFormat.chatSafe("a\u007Fb"));
        assertEquals("Mina ñandú 😀", WaypointShareFormat.chatSafe("Mina ñandú 😀"));
        assertEquals("", WaypointShareFormat.chatSafe(null));
    }

    @Test
    void fitLineCutsOnlyTheNameAndStillParses() {
        String line = WaypointShareFormat.fitLine("x".repeat(300), WaypointShareFormat.CHAT_MAX_LENGTH,
                n -> WaypointShareFormat.format(n, NETHER, 12, 64, -30));
        assertEquals(WaypointShareFormat.CHAT_MAX_LENGTH, line.length());
        WaypointShareFormat.Shared shared = WaypointShareFormat.parse(line);
        assertEquals(NETHER, shared.dimension());
        assertEquals(-30, shared.z());
        assertEquals(line.length() - " >>> Nether 12 64 -30".length(), shared.name().length());
    }

    @Test
    void fitLineLeavesAFittingLineAlone() {
        assertEquals("Casa >>> Nether 1 2 3", WaypointShareFormat.fitLine("Casa", 256,
                n -> WaypointShareFormat.format(n, NETHER, 1, 2, 3)));
    }

    @Test
    void fitLineNeverSplitsACharacter() {
        String line = WaypointShareFormat.fitLine("😀".repeat(200), 100, n -> n + "|");
        assertEquals(true, line.length() <= 100);
        assertEquals(false, Character.isHighSurrogate(line.charAt(line.length() - 2)));
    }

    @Test
    void fitLineGivesUpWhenNothingFits() {
        assertNull(WaypointShareFormat.fitLine("Casa", 5, n -> n + " >>> Nether 1 2 3"));
    }
}
