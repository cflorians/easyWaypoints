package com.easywp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Durable read/write for the mod's JSON stores.
 *
 * <p>Writing straight to the destination truncates it the moment the stream opens, so a crash
 * anywhere between that and the last byte leaves a half-written file - and for the per-world
 * waypoint store that file is the only copy those waypoints exist in. Everything here is written
 * to a sibling {@code .tmp} first, flushed to disk, and then moved onto the destination in one
 * atomic step, so a reader only ever sees the complete old file or the complete new one.
 *
 * <p>Lives in {@code common/} on purpose: it touches nothing but {@code java.nio}, so unlike the
 * client classes it compiles standalone against this module's Minecraft-free dependency set, and
 * both versions pick it up from the shared source directory instead of keeping a copy each.
 */
public final class JsonStore {

    private JsonStore() { }

    /** The previous good revision of {@code target}, refreshed on every successful write. */
    public static Path backupOf(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    private static Path tempOf(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    /**
     * Replaces {@code target} with {@code content}, atomically.
     *
     * @throws IOException if the content could not be written; in that case {@code target} is
     *                     left exactly as it was.
     */
    public static void write(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = tempOf(target);
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            // Without this the rename below can land while the bytes are still only in the page
            // cache, which is exactly the power-loss case the temp file is meant to survive.
            channel.force(true);
        }

        // Snapshot the last good revision before it is replaced. Done in this order on purpose:
        // dying midway through the copy damages only the backup, and the real file - the one
        // read() is asked for first - is still intact.
        if (Files.exists(target)) {
            try {
                Files.copy(target, backupOf(target), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // A missing backup is a lost safety net, not a reason to refuse to save.
                EasyWp.LOGGER.warn("Could not refresh the backup of {}", target, e);
            }
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Only reachable if temp and target somehow straddle two filesystems, which they
            // cannot here since temp is a sibling. Kept so an exotic setup degrades to the
            // previous behaviour instead of losing the save outright.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Reads a file written by {@link #write}.
     *
     * @return its contents, or {@code null} if it is absent, empty, or unreadable - all of which
     *         callers handle the same way, by falling back to {@link #backupOf} or to defaults.
     */
    public static String read(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            EasyWp.LOGGER.warn("Could not read {}", path, e);
            return null;
        }
    }
}
