package com.easywp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the durable read/write behind both JSON stores.
 *
 * <p>This is the one piece of the mod worth testing in isolation and the one piece that can be:
 * everything else needs a running client, while {@link JsonStore} touches nothing but
 * {@code java.nio} (see CLAUDE.md). It is also where a bug costs the most - the per-world waypoint
 * file is the only copy those waypoints exist in - so the guarantees it makes are pinned here
 * rather than left to be re-derived by reading the implementation.
 */
class JsonStoreTest {

    private static final String DOC = "[{\"name\":\"Base\"}]";

    @Test
    void writeThenReadRoundTripsTheContent(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("waypoints.json");

        JsonStore.write(target, DOC);

        assertEquals(DOC, JsonStore.read(target));
    }

    @Test
    void writeCreatesMissingParentDirectories(@TempDir Path dir) throws IOException {
        // The real target is config/easywp/<file>, which does not exist on a fresh install.
        Path target = dir.resolve("config").resolve("easywp").resolve("waypoints.json");

        JsonStore.write(target, DOC);

        assertTrue(Files.isRegularFile(target));
        assertEquals(DOC, JsonStore.read(target));
    }

    @Test
    void writeLeavesNoTempFileBehind(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("waypoints.json");

        JsonStore.write(target, DOC);

        try (Stream<Path> entries = Files.list(dir)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("waypoints.json"), names,
                    "the .tmp file must be moved onto the target, not left next to it");
        }
    }

    @Test
    void firstWriteCreatesNoBackup(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("waypoints.json");

        JsonStore.write(target, DOC);

        assertFalse(Files.exists(JsonStore.backupOf(target)),
                "there is no previous revision to snapshot on the very first write");
    }

    @Test
    void secondWriteSnapshotsThePreviousRevision(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("waypoints.json");
        String updated = "[{\"name\":\"Base\"},{\"name\":\"Mine\"}]";

        JsonStore.write(target, DOC);
        JsonStore.write(target, updated);

        assertEquals(updated, JsonStore.read(target));
        assertEquals(DOC, JsonStore.read(JsonStore.backupOf(target)),
                "the backup must hold the revision the write replaced, not the new one");
    }

    @Test
    void backupTracksOnlyTheImmediatelyPrecedingRevision(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("waypoints.json");

        JsonStore.write(target, "1");
        JsonStore.write(target, "2");
        JsonStore.write(target, "3");

        assertEquals("3", JsonStore.read(target));
        assertEquals("2", JsonStore.read(JsonStore.backupOf(target)));
    }

    @Test
    void backupOfNamesASiblingOfTheTarget(@TempDir Path dir) {
        Path target = dir.resolve("waypoints_sp_world.json");

        Path backup = JsonStore.backupOf(target);

        assertEquals(dir, backup.getParent());
        assertEquals("waypoints_sp_world.json.bak", backup.getFileName().toString());
    }

    @Test
    void readReturnsNullForAMissingFile(@TempDir Path dir) {
        assertNull(JsonStore.read(dir.resolve("absent.json")));
    }

    @Test
    void readReturnsNullForAnEmptyFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("empty.json");
        Files.createFile(target);

        // Callers treat "empty" exactly like "absent" - both mean fall back to the backup or to
        // defaults, never "this world legitimately has zero waypoints".
        assertNull(JsonStore.read(target));
    }

    @Test
    void readReturnsNullForABlankFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("blank.json");
        Files.writeString(target, "  \n\t\n ", StandardCharsets.UTF_8);

        assertNull(JsonStore.read(target));
    }

    @Test
    void readReturnsNullForADirectory(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("notafile");
        Files.createDirectory(target);

        assertNull(JsonStore.read(target));
    }

    @Test
    void contentIsStoredAsUtf8(@TempDir Path dir) throws IOException {
        // Waypoint names are player-typed and routinely non-ASCII; the encoding has to survive the
        // round trip independently of the platform default charset.
        Path target = dir.resolve("waypoints.json");
        String accented = "[{\"name\":\"Montaña ⛏ 洞窟\"}]";

        JsonStore.write(target, accented);

        assertEquals(accented, JsonStore.read(target));
        assertEquals(accented, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void writeReplacesAShorterDocumentCompletely(@TempDir Path dir) throws IOException {
        // Guards the failure the temp-file dance exists to avoid: writing in place would leave the
        // tail of the longer previous revision behind after a shorter one.
        Path target = dir.resolve("waypoints.json");

        JsonStore.write(target, "[{\"name\":\"a very long first revision\"}]");
        JsonStore.write(target, "[]");

        assertEquals("[]", JsonStore.read(target));
    }

    @Test
    void aTruncatedTargetStillLeavesTheBackupReadable(@TempDir Path dir) throws IOException {
        // Simulates what a crash under the pre-atomic write left behind: a target that parses to
        // nothing, with the previous good revision still sitting in the backup slot. This is the
        // exact state WaypointRenderer.loadFromFile() and ModConfig.load() recover from.
        Path target = dir.resolve("waypoints.json");
        JsonStore.write(target, DOC);
        JsonStore.write(target, "[{\"name\":\"newer\"}]");
        Files.writeString(target, "", StandardCharsets.UTF_8);

        assertNull(JsonStore.read(target));
        assertEquals(DOC, JsonStore.read(JsonStore.backupOf(target)));
    }
}
