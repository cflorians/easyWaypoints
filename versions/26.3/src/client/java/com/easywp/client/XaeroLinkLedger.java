package com.easywp.client;

import com.easywp.EasyWp;
import com.easywp.JsonStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which waypoints easyWp and Xaero's Minimap have actually exchanged, per world, at
 * {@code config/easywp/xaero_links_<worldId>.json}.
 *
 * <p><b>Why this has to be persisted rather than derived.</b> {@code XaeroSync}'s two buttons
 * propagate deletions, and the rule the player asked for is that a deletion only ever removes a
 * waypoint that <em>existed on both sides</em> - a waypoint only one list ever had must survive the
 * other list being synced over it. Deciding that needs to know "these two were the same waypoint"
 * at a moment when one of them is already gone, which is information the current contents of either
 * list no longer contain: after the player deletes a waypoint in easyWp, nothing distinguishes
 * "Xaero's copy of a waypoint I just deleted" from "a waypoint of Xaero's that easyWp never had".
 * This ledger is exactly that distinction, written down before it is lost.
 *
 * <p>An entry is one {@linkplain #keyFor identity}, the same dimension + name + X + Z identity
 * {@code XaeroSync} dedups by in both directions. Y is excluded for the reason documented there:
 * Xaero reports a synthetic Y for a waypoint saved without one.
 *
 * <p>Read and written fresh on each button press rather than cached. A sync is a manual action a
 * handful of times per session, so two small file operations cost nothing, and not caching means
 * there is no per-world invalidation to get wrong on a dimension change or a server switch - the
 * class simply has no state to go stale.
 *
 * <p><b>Losing this file fails safe.</b> An absent, empty, or unparseable ledger reads as "nothing
 * has ever been synced", which makes both buttons purely additive again - the behaviour they had
 * before deletion propagation existed. A missing ledger can therefore leave a stale copy behind,
 * but it can never delete something it shouldn't.
 */
public final class XaeroLinkLedger {

    /** Reused across loads and saves - Gson instances are safe to share and hold no per-call state. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private XaeroLinkLedger() {
    }

    /** Wrapper object rather than a bare JSON array so a later field can be added without reshaping
     *  the document, the same reasoning {@code ModConfig}'s grouping follows. */
    private static class Document {
        List<String> linked;
    }

    /**
     * One waypoint's cross-mod identity. The name goes last on purpose: it is the only field that can
     * contain the separator, and being last makes that harmless, since these keys are only ever
     * compared for equality and prefix-matched on the dimension, never parsed field by field.
     */
    public static String keyFor(String dimension, int x, int z, String name) {
        return dimension + "|" + x + "|" + z + "|" + name;
    }

    /** The dimension a {@link #keyFor} key belongs to. Safe because a dimension id cannot contain
     *  {@code |} (resource locations are restricted to {@code [a-z0-9_.-/:]}). */
    public static String dimensionOf(String key) {
        int bar = key.indexOf('|');
        return bar < 0 ? key : key.substring(0, bar);
    }

    /** Every key in {@code ledger} belonging to {@code dimension}. Prefix-matching the dimension plus
     *  its separator is exact for the same reason {@link #dimensionOf} is. */
    public static boolean isInDimension(String key, String dimension) {
        return key.startsWith(dimension + "|");
    }

    /** @return a mutable set of the identities last known to exist on both sides; empty if this world
     *          has no ledger yet, or if the file and its backup are both unreadable. */
    public static Set<String> load() {
        Path file = ledgerFile();
        if (file == null) {
            return new HashSet<>();
        }
        Set<String> result = parse(JsonStore.read(file));
        if (result == null) {
            result = parse(JsonStore.read(JsonStore.backupOf(file)));
            if (result != null) {
                EasyWp.LOGGER.warn("Recovered the Xaero link ledger from the backup file");
            }
        }
        return result != null ? result : new HashSet<>();
    }

    public static void save(Set<String> linked) {
        Path file = ledgerFile();
        if (file == null) {
            return;
        }
        Document doc = new Document();
        // Sorted so a hand-inspected file and a diff of two revisions both stay readable - a HashSet's
        // own order would reshuffle the whole document on every save for no reason.
        doc.linked = new ArrayList<>(linked);
        doc.linked.sort(null);
        try {
            JsonStore.write(file, GSON.toJson(doc));
        } catch (Exception e) {
            EasyWp.LOGGER.warn("Failed to save the Xaero link ledger", e);
        }
    }

    /** @return the parsed identities, or {@code null} if {@code json} is absent or not readable as a
     *          ledger - which callers treat as "nothing synced yet", never as an error. */
    private static Set<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Document doc = GSON.fromJson(json, Document.class);
            if (doc == null || doc.linked == null) {
                return null;
            }
            Set<String> result = new HashSet<>();
            for (String key : doc.linked) {
                if (key != null && !key.isBlank()) {
                    result.add(key);
                }
            }
            return result;
        } catch (Exception e) {
            EasyWp.LOGGER.warn("Could not read the Xaero link ledger; treating it as empty", e);
            return null;
        }
    }

    /** @return {@code null} while there is no identified world to keep a ledger for (main menu, or
     *          between loads), matching how {@code WaypointStore} skips its own files there. */
    private static Path ledgerFile() {
        String worldId = WaypointStore.getWorldId();
        if (worldId.equals("unknown")) {
            return null;
        }
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp/xaero_links_" + worldId + ".json").toPath();
    }
}
