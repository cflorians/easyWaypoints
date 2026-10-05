package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativeCoordinateTest {

    private static final double PLAYER = 100.7;

    private static double read(String text) {
        OptionalDouble value = RelativeCoordinate.parse(text, PLAYER);
        assertTrue(value.isPresent(), "should read: \"" + text + "\"");
        return value.getAsDouble();
    }

    private static void unreadable(String text, double base) {
        assertTrue(RelativeCoordinate.parse(text, base).isEmpty(), "should not read: \"" + text + "\"");
    }

    @Test
    void aPlainNumberIsAbsolute() {
        assertEquals(12.0, read("12"));
        assertEquals(-3.5, read("-3.5"));
        assertEquals(7.0, read("  7  "));
        assertEquals(0.0, read("0"));
    }

    @Test
    void aTildeAloneIsThePlayersPosition() {
        assertEquals(PLAYER, read("~"));
        assertEquals(PLAYER, read(" ~ "));
    }

    @Test
    void aTildeWithANumberOffsetsFromThePlayer() {
        assertEquals(PLAYER + 5, read("~5"));
        assertEquals(PLAYER - 3, read("~-3"));
        assertEquals(PLAYER + 0.5, read("~0.5"));
        assertEquals(PLAYER + 2, read("~+2"));
        assertEquals(PLAYER + 0.5, read("~.5"));
    }

    @Test
    void textThatIsNotACoordinateIsUnreadable() {
        unreadable("", PLAYER);
        unreadable("   ", PLAYER);
        unreadable(null, PLAYER);
        unreadable("abc", PLAYER);
        unreadable("~abc", PLAYER);
        unreadable("~~", PLAYER);
        unreadable("5~", PLAYER);
        unreadable("1.2.3", PLAYER);
        unreadable("~5~", PLAYER);
    }

    @Test
    void aSpaceRightAfterTheTildeIsUnreadable() {
        // As in vanilla commands: "~ 5" is not "~5". Double.parseDouble would have trimmed it.
        unreadable("~ 5", PLAYER);
    }

    @Test
    void valuesThatAreNotFiniteAreUnreadable() {
        unreadable("NaN", PLAYER);
        unreadable("Infinity", PLAYER);
        unreadable("-Infinity", PLAYER);
        unreadable("1e400", PLAYER);
        unreadable("~Infinity", PLAYER);
    }

    @Test
    void withoutAPlayerOnlyPlainNumbersRead() {
        unreadable("~", Double.NaN);
        unreadable("~5", Double.NaN);
        assertEquals(42.0, RelativeCoordinate.parse("42", Double.NaN).getAsDouble());
    }
}
