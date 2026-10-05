package com.easywp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.easywp.XaeroWaypointFormat.XaeroWaypoint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Xaero {@code waypoints.txt} line format and its chat-share counterpart.
 *
 * <p>Held to the same bar as {@link JsonStoreTest}: this is the one other piece of the mod that can
 * be verified without a running client, and it is the backup persistence path for Xaero sync (see
 * CLAUDE.md), so its guarantees are pinned here rather than re-derived from the implementation.
 */
class XaeroWaypointFormatTest {

    private static XaeroWaypoint sample() {
        return new XaeroWaypoint("Base", "B", 100, 64, -200, 2, false, 0, "Easy Waypoints", List.of());
    }

    @Test
    void serializeThenParseRoundTripsEveryField() {
        XaeroWaypoint original = new XaeroWaypoint("Base", "B", 100, 64, -200, 6, true, 3, "My Set", List.of());

        String line = XaeroWaypointFormat.serialize(original);
        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(line);

        assertEquals(1, parsed.waypoints().size());
        assertEquals(original, parsed.waypoints().get(0));
    }

    @Test
    void yNullRoundTripsAsTheLiteralTildeNotANumber() {
        XaeroWaypoint noY = new XaeroWaypoint("Base", "B", 100, null, -200, 0, false, 0, "Easy Waypoints", List.of());

        String line = XaeroWaypointFormat.serialize(noY);

        assertTrue(line.contains(":~:"), "a y-excluded waypoint must write the literal ~, not a number");
        assertNull(XaeroWaypointFormat.parse(line).waypoints().get(0).y());
    }

    @Test
    void unknownTrailingFieldsSurviveARoundTrip() {
        // A future Xaero version adding a 15th field must not have it silently dropped by a round
        // trip through this class.
        String line = "waypoint:Base:B:1:2:3:0:false:0:Easy Waypoints:true:90:1:false:SOME_FUTURE_FIELD";

        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(line);
        XaeroWaypoint wp = parsed.waypoints().get(0);
        String reserialized = XaeroWaypointFormat.serialize(wp);

        assertEquals(
                List.of("true", "90", "1", "false", "SOME_FUTURE_FIELD"),
                wp.trailing()
        );
        assertEquals(line, reserialized);
    }

    @Test
    void aLineShorterThanXaerosOwnMinimumIsSkippedNotGuessed() {
        // Xaero's own reader indexes field 9 unconditionally once the tag matches, so anything with
        // fewer than 10 fields (0-9) is exactly as unreadable to Xaero as it is here.
        String tooShort = "waypoint:Base:B:1:2:3:0:false:0";

        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(tooShort);

        assertTrue(parsed.waypoints().isEmpty());
        assertEquals(List.of(tooShort), parsed.otherLines(),
                "an unparseable line must be preserved verbatim, not silently dropped");
    }

    @Test
    void aLineWithNonNumericCoordinatesIsSkippedNotGuessed() {
        String malformed = "waypoint:Base:B:not-a-number:2:3:0:false:0:Easy Waypoints";

        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(malformed);

        assertTrue(parsed.waypoints().isEmpty());
        assertEquals(List.of(malformed), parsed.otherLines());
    }

    @Test
    void commentsAndSetsDirectivesArePreservedAsOtherLines() {
        String contents = "#\n" + "#waypoint:name:initials:...\n" + "sets:Default\n" + XaeroWaypointFormat.serialize(sample()) + "\n";

        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(contents);

        assertEquals(1, parsed.waypoints().size());
        assertEquals(List.of("#", "#waypoint:name:initials:...", "sets:Default"), parsed.otherLines());
    }

    @Test
    void mergeAddsANewWaypointWithoutTouchingExistingLines() {
        String existing = "sets:Default\n" + XaeroWaypointFormat.serialize(sample()) + "\n";
        XaeroWaypoint toAdd = new XaeroWaypoint("New", "N", 5, 6, 7, 1, false, 0, "Easy Waypoints", List.of());

        String merged = XaeroWaypointFormat.merge(existing, List.of(toAdd));
        XaeroWaypointFormat.ParsedFile parsed = XaeroWaypointFormat.parse(merged);

        assertEquals(List.of("sets:Default"), parsed.otherLines());
        assertEquals(2, parsed.waypoints().size());
        assertTrue(parsed.waypoints().contains(sample()));
        assertTrue(parsed.waypoints().contains(toAdd));
    }

    @Test
    void mergeCalledTwiceWithTheSameInputIsIdempotent() {
        String existing = XaeroWaypointFormat.serialize(sample()) + "\n";
        XaeroWaypoint toAdd = new XaeroWaypoint("New", "N", 5, 6, 7, 1, false, 0, "Easy Waypoints", List.of());

        String once = XaeroWaypointFormat.merge(existing, List.of(toAdd));
        String twice = XaeroWaypointFormat.merge(once, List.of(toAdd));

        assertEquals(2, XaeroWaypointFormat.parse(twice).waypoints().size(),
                "merging the same candidate again must not duplicate it");
        assertEquals(XaeroWaypointFormat.parse(once).waypoints(), XaeroWaypointFormat.parse(twice).waypoints());
    }

    @Test
    void mergePreservesAUserWaypointEvenWithTheSameNameInADifferentSet() {
        // merge()'s own dedup key deliberately does not include the set name - this test documents
        // that choice: a coincidental name+position match is treated as "already present" regardless
        // of which set it is in, because merge() only ever adds and has no basis to prefer one set
        // over another. Erring towards "already there" is the safe direction for an add-only export:
        // the worst case is one waypoint not exported, never a duplicate or an overwrite.
        XaeroWaypoint userOwn = new XaeroWaypoint("Base", "B", 100, 64, -200, 5, false, 0, "My Own Set", List.of());
        String existing = XaeroWaypointFormat.serialize(userOwn) + "\n";

        String merged = XaeroWaypointFormat.merge(existing, List.of(sample()));

        assertEquals(1, XaeroWaypointFormat.parse(merged).waypoints().size());
    }

    @Test
    void mergeNeverRemovesAWaypointTheCallerDidNotMention() {
        // The guarantee the manual "Export to Xaero" button rests on: the player's own waypoints, in
        // any set, are still there afterwards. merge() has no delete path at all, and this pins that
        // rather than leaving it to be re-derived from the implementation.
        XaeroWaypoint userOwn = new XaeroWaypoint("Home", "H", 0, 70, 0, 9, false, 0, "My Own Set", List.of());
        XaeroWaypoint userOther = new XaeroWaypoint("Mine", "M", 1, 2, 3, 0, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());
        String existing = XaeroWaypointFormat.serialize(userOwn) + "\n" + XaeroWaypointFormat.serialize(userOther) + "\n";
        XaeroWaypoint exported = new XaeroWaypoint("New", "N", 4, 5, 6, 1, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());

        List<XaeroWaypoint> waypoints = XaeroWaypointFormat.parse(
                XaeroWaypointFormat.merge(existing, List.of(exported))).waypoints();

        assertTrue(waypoints.contains(userOwn), "a waypoint in a different set must survive untouched");
        assertTrue(waypoints.contains(userOther), "a waypoint in the set being exported into must survive untouched");
        assertTrue(waypoints.contains(exported));
        assertEquals(3, waypoints.size());
    }

    @Test
    void removeAllDropsOnlyTheWaypointsNamedAndKeepsEverythingElse() {
        XaeroWaypoint doomed = new XaeroWaypoint("Old", "O", 1, 2, 3, 0, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());
        XaeroWaypoint sameNameElsewhere = new XaeroWaypoint("Old", "O", 50, 2, 60, 0, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());
        XaeroWaypoint userOwn = new XaeroWaypoint("Home", "H", 0, 70, 0, 9, false, 0, "My Own Set", List.of());
        String existing = "sets:Default\n"
                + XaeroWaypointFormat.serialize(doomed) + "\n"
                + XaeroWaypointFormat.serialize(sameNameElsewhere) + "\n"
                + XaeroWaypointFormat.serialize(userOwn) + "\n";

        XaeroWaypointFormat.ParsedFile result = XaeroWaypointFormat.parse(
                XaeroWaypointFormat.removeAll(existing, List.of(doomed)));

        assertEquals(List.of("sets:Default"), result.otherLines(), "non-waypoint lines must survive a removal");
        assertFalse(result.waypoints().contains(doomed));
        assertTrue(result.waypoints().contains(sameNameElsewhere), "a same-named waypoint at another position is a different waypoint");
        assertTrue(result.waypoints().contains(userOwn));
        assertEquals(2, result.waypoints().size());
    }

    @Test
    void removeAllMatchesOnNameAndColumnIgnoringY() {
        // The live bridge identifies waypoints by name + X + Z because Xaero reports a synthetic Y for
        // one saved without any, so the file path has to agree or the same click would remove
        // different things depending on whether the live bridge happened to be usable.
        XaeroWaypoint inFile = new XaeroWaypoint("Base", "B", 10, 64, 20, 0, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());
        XaeroWaypoint sameColumnOtherY = new XaeroWaypoint("Base", "B", 10, 200, 20, 0, false, 0, XaeroWaypointFormat.DEFAULT_SET_NAME, List.of());

        String result = XaeroWaypointFormat.removeAll(
                XaeroWaypointFormat.serialize(inFile) + "\n", List.of(sameColumnOtherY));

        assertTrue(XaeroWaypointFormat.parse(result).waypoints().isEmpty(),
                "a differing Y must not save a waypoint from removal");
    }

    @Test
    void removeAllWithNothingToRemoveLeavesTheFileIntact() {
        String existing = "sets:Default\n" + XaeroWaypointFormat.serialize(sample()) + "\n";

        assertEquals(existing, XaeroWaypointFormat.removeAll(existing, List.of()));
    }

    @Test
    void exportedWaypointsLandInXaerosOwnDefaultSet() {
        // "gui.xaero_default" is Xaero's MinimapWorld.DEFAULT_SET, read from its bytecode. A set name
        // Xaero doesn't have would still parse, but the waypoints would sit in a set the player has to
        // go looking for before they see any of them - a silent failure from the user's side.
        assertEquals("gui.xaero_default", XaeroWaypointFormat.DEFAULT_SET_NAME);

        XaeroWaypoint exported = new XaeroWaypoint("Base", 1, 2, 3, 0, XaeroWaypointFormat.DEFAULT_SET_NAME);

        assertEquals(XaeroWaypointFormat.DEFAULT_SET_NAME,
                XaeroWaypointFormat.parse(XaeroWaypointFormat.serialize(exported)).waypoints().get(0).set());
    }

    @Test
    void nameAndSetWithAColonAreSanitizedNotLeftToDesyncTheLine() {
        XaeroWaypoint wp = new XaeroWaypoint("Base: North", "B", 1, 2, 3, 0, false, 0, "A:Set", List.of());

        String line = XaeroWaypointFormat.serialize(wp);
        XaeroWaypoint parsedBack = XaeroWaypointFormat.parse(line).waypoints().get(0);

        assertFalse(line.contains("Base: North"), "a literal colon in the name must not reach the file");
        assertEquals("Base- North", parsedBack.name());
        assertEquals("A-Set", parsedBack.set());
    }

    @Test
    void nonAsciiNamesSurviveTheRoundTrip() {
        XaeroWaypoint accented = new XaeroWaypoint("Montaña ⛏ 洞窟", "M", 1, 2, 3, 0, false, 0, "Easy Waypoints", List.of());

        String line = XaeroWaypointFormat.serialize(accented);

        assertEquals(accented, XaeroWaypointFormat.parse(line).waypoints().get(0));
    }

    @Test
    void chatShareLineMatchesXaeroOwnReceivingParserExactly() {
        // This is NOT the same field layout as serialize()'s file line past field 6, despite an
        // earlier version of this method assuming it was - confirmed against
        // WaypointSharingHandler's own disassembled bytecode (see chatShareLine's own javadoc).
        // Pinned here field-by-field against the real onWaypointAdd(String[]) index mapping:
        // arr[1]=name arr[2]=initials arr[3]=x arr[4]=y arr[5]=z arr[6]=color arr[7]=rotation
        // arr[8]=yaw, with no 9th field at all.
        XaeroWaypoint wp = sample();

        String chatLine = XaeroWaypointFormat.chatShareLine(wp);

        assertEquals("xaero-waypoint:Base:B:100:64:-200:2:false:0", chatLine);
    }

    @Test
    void chatShareLineOmitsTheDestinationFieldEntirely() {
        // Critical, not cosmetic: onWaypointAdd only even tries to resolve a destination when the
        // split array has more than 9 elements (i.e. a real 9th field is present), falling back to
        // "the receiving player's current world" otherwise - exactly what a shared waypoint should
        // do. A 9th field present but wrong (this mod's own set name, or even an empty string) makes
        // that resolution fail and Xaero's [Add] silently add nothing - the actual bug this pins.
        String chatLine = XaeroWaypointFormat.chatShareLine(sample());

        assertEquals(9, chatLine.split(":", -1).length,
                "exactly 9 elements after split (the tag plus 8 real fields) - a 10th means a " +
                        "destination field crept back in, which breaks the receiving [Add] button");
    }

    @Test
    void chatShareLineUsesTheLiteralTildeForAYExcludedWaypoint() {
        XaeroWaypoint noY = new XaeroWaypoint("Base", "B", 100, null, -200, 0, false, 0, "Easy Waypoints", List.of());

        String chatLine = XaeroWaypointFormat.chatShareLine(noY);

        assertEquals("xaero-waypoint:Base:B:100:~:-200:0:false:0", chatLine);
    }

    @Test
    void chatShareLineTruncatesNameAndInitialsToXaerosOwnLimits() {
        // onWaypointAdd rejects the whole message (same silent failure as the destination-field
        // bug) if name is empty or over 32 chars, or initials is empty or over 3 - these are
        // Xaero's own hard limits, not a style choice of this class.
        XaeroWaypoint longName = new XaeroWaypoint(
                "A".repeat(40), "WXYZ", 1, 2, 3, 0, false, 0, "Easy Waypoints", List.of());

        String chatLine = XaeroWaypointFormat.chatShareLine(longName);
        String[] fields = chatLine.split(":", -1);

        assertEquals("A".repeat(32), fields[1]);
        assertEquals("WXY", fields[2]);
    }

    @Test
    void colorOrdinalRoundTripsForEveryPaletteEntry() {
        for (int i = 0; i < XaeroWaypointFormat.colorCount(); i++) {
            int argb = XaeroWaypointFormat.rgbForColorOrdinal(i);
            assertEquals(i, XaeroWaypointFormat.nearestColorOrdinal(argb),
                    "palette entry " + i + " must map back to itself, not just to something close");
        }
    }

    @Test
    void rgbForColorOrdinalIsOpaque() {
        int argb = XaeroWaypointFormat.rgbForColorOrdinal(0);

        assertEquals(0xFF, (argb >>> 24) & 0xFF, "imported colors must be fully opaque");
    }

    @Test
    void rgbForColorOrdinalClampsAnOutOfRangeIndexInsteadOfThrowing() {
        assertEquals(XaeroWaypointFormat.rgbForColorOrdinal(0), XaeroWaypointFormat.rgbForColorOrdinal(999));
        assertEquals(XaeroWaypointFormat.rgbForColorOrdinal(0), XaeroWaypointFormat.rgbForColorOrdinal(-1));
    }

    @Test
    void nearestColorOrdinalPicksAVisiblyDifferentEntryForAVeryDifferentColor() {
        int pureBlue = 0xFF0000FF;
        int pureRed = 0xFFFF0000;

        assertNotEquals(
                XaeroWaypointFormat.nearestColorOrdinal(pureBlue),
                XaeroWaypointFormat.nearestColorOrdinal(pureRed)
        );
    }

    @Test
    void initialsForUsesTheUppercasedFirstCharacter() {
        assertEquals("B", XaeroWaypointFormat.initialsFor("base"));
        assertEquals("M", XaeroWaypointFormat.initialsFor("Montaña"));
    }

    @Test
    void initialsForFallsBackToXForAnEmptyName() {
        assertEquals("X", XaeroWaypointFormat.initialsFor(""));
        assertEquals("X", XaeroWaypointFormat.initialsFor(null));
    }

    @Test
    void parsesItsOwnChatShareLineBack() {
        XaeroWaypoint parsed = XaeroWaypointFormat.parseChatShareLine(
                XaeroWaypointFormat.chatShareLine(new XaeroWaypoint("Base norte", 100, 64, -200, 2, "x")));
        assertEquals("Base norte", parsed.name());
        assertEquals(100, parsed.x());
        assertEquals(64, parsed.y());
        assertEquals(-200, parsed.z());
        assertEquals(2, parsed.colorOrdinal());
    }

    @Test
    void findsTheChatLineAfterASenderPrefixAndIgnoresADestination() {
        XaeroWaypoint parsed = XaeroWaypointFormat.parseChatShareLine(
                "<Steve> xaero-waypoint:Mina:M:-5:12:7:14:false:0:Internal-overworld-waypoints");
        assertEquals("Mina", parsed.name());
        assertEquals(-5, parsed.x());
        assertEquals(12, parsed.y());
        assertEquals(7, parsed.z());
        assertEquals(14, parsed.colorOrdinal());
    }

    @Test
    void aTildeYIsNoY() {
        assertNull(XaeroWaypointFormat.parseChatShareLine("xaero-waypoint:Base:B:100:~:-200:0:false:0").y());
    }

    @Test
    void anOutOfRangeChatColorFallsBackToTheFirst() {
        assertEquals(0, XaeroWaypointFormat.parseChatShareLine("xaero-waypoint:Base:B:1:2:3:99:false:0").colorOrdinal());
    }

    @Test
    void rejectsWhatIsNotAChatLine() {
        assertNull(XaeroWaypointFormat.parseChatShareLine(null));
        assertNull(XaeroWaypointFormat.parseChatShareLine("hola"));
        assertNull(XaeroWaypointFormat.parseChatShareLine("xaero-waypoint:Base:B:1:2:3:0:false"));  // seven fields
        assertNull(XaeroWaypointFormat.parseChatShareLine("xaero-waypoint:Base:B:x:2:3:0:false:0"));
        assertNull(XaeroWaypointFormat.parseChatShareLine("xaero-waypoint:Base:B:1:2:3:red:false:0"));
        assertNull(XaeroWaypointFormat.parseChatShareLine("xaero-waypoint: :B:1:2:3:0:false:0"));
    }
}
