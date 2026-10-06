package com.easywp.client;

import com.easywp.JsonStore;
import com.easywp.XaeroWaypointFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two manual, explicit operations that sync waypoints between easyWp and Xaero's Minimap:
 * {@link #importNow()} and {@link #exportNow()}, driven by the two buttons in the bottom-right corner
 * of {@code WaypointListScreen}.
 *
 * <p><b>Nothing here runs on its own.</b> An earlier version of this class pushed on every save,
 * imported on every world join, and polled {@code WaypointSet.size()} every client tick to react to
 * Xaero-side edits within ~50ms. All of that is gone on purpose: two lists that silently rewrite each
 * other are hard to reason about, and the tick poll existed only because Xaero exposes no add/remove
 * event to hook (still true - confirmed against its own bytecode, and against {@code way2wayfabric},
 * the one existing Fabric mod doing something comparable). What replaces it is a button press, which
 * needs no event.
 *
 * <p><b>Each button makes the target match the source, but only for waypoints the two have actually
 * exchanged.</b> A sync adds what the source has and the target lacks, and removes what the target
 * still has but the source has deleted - and that removal is deliberately narrow: it only ever
 * touches a waypoint recorded in {@link XaeroLinkLedger} as having existed on <em>both</em> sides. A
 * waypoint only one list ever had is never removed by syncing the other, so the player's own
 * Xaero-only and easyWp-only waypoints survive any number of clicks in either direction. That
 * narrowness is the whole reason the ledger has to exist: once a waypoint is deleted from one side,
 * nothing in the current contents of either list can still tell "the copy of a waypoint that was just
 * deleted" apart from "a waypoint the other list simply never had".
 *
 * <p>A useful consequence of identity being name + position: moving or renaming a waypoint in the
 * source looks like a delete plus an add, so a sync propagates edits too, not just creations and
 * deletions.
 *
 * <p><b>Not knowing is never treated as "empty".</b> Every read of the other side's contents can come
 * back {@code null} - no minimap session, or an unreadable {@code waypoints.txt} - and a dimension
 * that reads {@code null} is skipped entirely: no additions, no removals, and its ledger entries left
 * exactly as they were. Collapsing that into an empty list would turn a failed read into a mass
 * deletion of everything the two sides had ever shared, which is the one way this feature could
 * genuinely lose a player's data.
 *
 * <p><b>The file is the backup, never a second writer.</b> Each operation decides <em>per dimension</em>
 * whether the live bridge could actually do the work; only if it couldn't (Xaero missing, a
 * binary-incompatible version, no active session, or its own save failing) does this write the file
 * directly - never both for the same dimension, which is what avoids the real failure mode this split
 * exists to dodge: Xaero rewriting its own {@code waypoints.txt} on save and clobbering a concurrent
 * write from this mod.
 *
 * <p>Every easyWp waypoint has exactly one identity on Xaero's side, in its own dimension. Until
 * 1.4.0 a {@code shared} waypoint was exported into both halves of the Overworld/Nether pair and
 * had two; loading now splits every such waypoint into the original plus a copy at the converted
 * position (see {@code WaypointStore}), which carries exactly the identity its second image had -
 * so what an earlier Export wrote into Xaero, and what the ledger recorded, still match.
 */
public final class XaeroSync {

    private static final String WAYPOINTS_FILE_NAME = "waypoints.txt";

    private XaeroSync() {
    }

    /** What a sync actually did. {@code added} and {@code removed} count waypoints, not dimensions, so
     *  the two directions report comparable numbers. */
    public record SyncResult(int added, int removed) {
    }

    /**
     * Makes easyWp's list match Xaero's: adds every real Xaero waypoint easyWp lacks, and removes
     * every easyWp waypoint that was previously shared with Xaero and has since been deleted there.
     * Waypoints easyWp has that Xaero never did are left alone.
     *
     * @return what it did, or {@code null} if the integration is switched off.
     */
    public static SyncResult importNow() {
        if (!ModConfig.get().xaeroIntegration.enabled) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return new SyncResult(0, 0);
        }
        // A deletion still waiting out its undo is final for a sync: it is already gone from the
        // world and the file, and a sync acts on the list as it is about to be.
        WaypointStore.commitPendingRemovals();
        boolean live = XaeroBridge.isAvailable();
        Set<String> ledger = XaeroLinkLedger.load();

        // Read every dimension first, before mutating anything: the removal pass below judges each
        // easyWp waypoint against what was read, and a dimension that could not be read must not
        // count as "empty" anywhere.
        Map<String, List<XaeroBridge.RealWaypoint>> readable = new HashMap<>();
        for (String dimension : knownDimensions(client)) {
            List<XaeroBridge.RealWaypoint> current = currentXaeroWaypoints(client, dimension, live);
            if (current != null) {
                readable.put(dimension, current);
            }
        }

        int added = 0;
        Set<String> existing = existingIdentities();
        for (Map.Entry<String, List<XaeroBridge.RealWaypoint>> entry : readable.entrySet()) {
            String dimension = entry.getKey();
            for (XaeroBridge.RealWaypoint rw : entry.getValue()) {
                // Added as it goes, the way the list itself grows, so a name repeated at the same
                // spot in Xaero is still imported only once.
                if (!existing.add(keyOf(dimension, rw))) {
                    continue;
                }
                BlockPos pos = new BlockPos(rw.x(), rw.y(), rw.z());
                WaypointStore.waypoints.add(new Waypoint(rw.name(), pos,
                        XaeroWaypointFormat.rgbForColorOrdinal(rw.colorOrdinal()), dimension));
                added++;
            }
        }

        Map<String, Set<String>> presentByDimension = new HashMap<>();
        for (Map.Entry<String, List<XaeroBridge.RealWaypoint>> entry : readable.entrySet()) {
            presentByDimension.put(entry.getKey(), identityKeys(entry.getKey(), entry.getValue()));
        }

        List<Waypoint> doomed = new ArrayList<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null && deletedOnXaerosSide(wp, ledger, presentByDimension)) {
                doomed.add(wp);
            }
        }
        WaypointStore.removeAll(doomed);

        if (added > 0 || !doomed.isEmpty()) {
            WaypointStore.saveToFile();
        }

        // easyWp now holds every Xaero waypoint in every dimension that could be read, so those
        // dimensions' shared set is exactly what Xaero has. Unreadable dimensions keep what they had.
        for (Map.Entry<String, Set<String>> entry : presentByDimension.entrySet()) {
            replaceDimension(ledger, entry.getKey(), entry.getValue());
        }
        XaeroLinkLedger.save(ledger);

        return new SyncResult(added, doomed.size());
    }

    /**
     * Whether {@code wp} should be removed because Xaero no longer has it: the two had exchanged it
     * (it is in the ledger), and its dimension was read and no longer holds it. A dimension that
     * could not be read makes this false outright: the waypoint might still be there and simply
     * unseen.
     */
    private static boolean deletedOnXaerosSide(Waypoint wp, Set<String> ledger, Map<String, Set<String>> presentByDimension) {
        String key = identityOf(wp);
        if (!ledger.contains(key)) {
            return false;
        }
        Set<String> present = presentByDimension.get(wp.dimensionOrOverworld());
        return present != null && !present.contains(key);
    }

    /**
     * Makes Xaero's list match easyWp's: adds every easyWp waypoint Xaero lacks, and removes every
     * Xaero waypoint that was previously shared with easyWp and has since been deleted here.
     * Waypoints Xaero has that easyWp never did are left alone.
     *
     * @return what it did, or {@code null} if the integration is switched off.
     */
    public static SyncResult exportNow() {
        if (!ModConfig.get().xaeroIntegration.enabled) {
            return null;
        }
        Minecraft client = Minecraft.getInstance();
        // See importNow: pending deletions are final for a sync.
        WaypointStore.commitPendingRemovals();
        boolean live = XaeroBridge.isAvailable();
        Set<String> ledger = XaeroLinkLedger.load();

        Map<String, List<Waypoint>> byDimension = groupByDimension();

        // Every dimension easyWp has waypoints in, plus every dimension the ledger mentions. That
        // second half matters: after the last easyWp waypoint in a dimension is deleted, that
        // dimension is absent from byDimension entirely, and visiting only byDimension would leave its
        // linked Xaero copies behind forever - exactly the deletion the player asked this button to
        // propagate.
        Set<String> dimensions = new HashSet<>(byDimension.keySet());
        for (String key : ledger) {
            dimensions.add(XaeroLinkLedger.dimensionOf(key));
        }

        int added = 0;
        int removed = 0;
        for (String dimension : dimensions) {
            List<XaeroBridge.RealWaypoint> wanted = new ArrayList<>();
            for (Waypoint wp : byDimension.getOrDefault(dimension, List.of())) {
                BlockPos pos = wp.getPos();
                wanted.add(new XaeroBridge.RealWaypoint(
                        wp.getName(), pos.getX(), pos.getY(), pos.getZ(),
                        XaeroWaypointFormat.nearestColorOrdinal(wp.getColor())));
            }
            Set<String> wantedKeys = identityKeys(dimension, wanted);

            List<XaeroBridge.RealWaypoint> current = currentXaeroWaypoints(client, dimension, live);
            if (current == null) {
                continue; // unreadable: change nothing here, and leave this dimension's ledger alone
            }
            Set<String> currentKeys = identityKeys(dimension, current);

            List<XaeroBridge.RealWaypoint> toAdd = new ArrayList<>();
            for (XaeroBridge.RealWaypoint rw : wanted) {
                if (!currentKeys.contains(keyOf(dimension, rw))) {
                    toAdd.add(rw);
                }
            }
            List<XaeroBridge.RealWaypoint> toRemove = new ArrayList<>();
            for (XaeroBridge.RealWaypoint rw : current) {
                String key = keyOf(dimension, rw);
                if (ledger.contains(key) && !wantedKeys.contains(key)) {
                    toRemove.add(rw);
                }
            }

            if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
                boolean applied = live && XaeroBridge.applyRealWaypoints(dimension, toAdd, toRemove);
                if (!applied) {
                    applied = applyToFile(client, dimension, toAdd, toRemove);
                }
                if (!applied) {
                    continue; // nothing changed for this dimension, so its ledger must not change either
                }
                added += toAdd.size();
                removed += toRemove.size();
            }

            // Xaero now holds everything easyWp wanted here; anything else it holds was never shared.
            replaceDimension(ledger, dimension, wantedKeys);
        }
        XaeroLinkLedger.save(ledger);

        return new SyncResult(added, removed);
    }

    // ---- reading the other side ----------------------------------------------------------------

    /** One dimension's current Xaero contents, live if the bridge can serve it and from
     *  {@code waypoints.txt} otherwise. @return {@code null} if it could not be read at all - never an
     *  empty list for that case, see the class javadoc. */
    private static List<XaeroBridge.RealWaypoint> currentXaeroWaypoints(Minecraft client, String dimension, boolean live) {
        if (live) {
            List<XaeroBridge.RealWaypoint> fromBridge = XaeroBridge.readRealWaypoints(dimension);
            if (fromBridge != null) {
                return fromBridge;
            }
        }
        return fileWaypoints(client, dimension);
    }

    /** @return the waypoints in {@code dimension}'s guessed {@code waypoints.txt}, or {@code null} if
     *          that file is absent or unreadable - which is "unknown", not "none". */
    private static List<XaeroBridge.RealWaypoint> fileWaypoints(Minecraft client, String dimension) {
        Path file = guessedFile(client, dimension);
        String contents = file == null ? null : JsonStore.read(file);
        if (contents == null) {
            return null;
        }
        List<XaeroBridge.RealWaypoint> result = new ArrayList<>();
        for (XaeroWaypointFormat.XaeroWaypoint xwp : XaeroWaypointFormat.parse(contents).waypoints()) {
            int y = xwp.y() != null ? xwp.y()
                    : (client.player != null ? (int) client.player.getY() : 64);
            result.add(new XaeroBridge.RealWaypoint(xwp.name(), xwp.x(), y, xwp.z(), xwp.colorOrdinal()));
        }
        return result;
    }

    /** The {@code waypoints.txt} fallback for one dimension's export: remove, then add, in one write.
     *  {@link XaeroWaypointFormat#removeAll} and {@link XaeroWaypointFormat#merge} both preserve every
     *  line they were not asked about, including the {@code sets:} directives that are not waypoints at
     *  all. @return whether the file was actually written. */
    private static boolean applyToFile(Minecraft client, String dimension,
                                       List<XaeroBridge.RealWaypoint> toAdd, List<XaeroBridge.RealWaypoint> toRemove) {
        Path file = guessedFile(client, dimension);
        if (file == null) {
            return false;
        }
        String existing = JsonStore.read(file);
        if (existing == null) {
            existing = "";
        }
        String updated = XaeroWaypointFormat.removeAll(existing, asFileLines(toRemove));
        updated = XaeroWaypointFormat.merge(updated, asFileLines(toAdd));
        try {
            JsonStore.write(file, updated);
            return true;
        } catch (Exception e) {
            com.easywp.EasyWp.LOGGER.warn("Failed to write the Xaero waypoints fallback file for {}", dimension, e);
            return false;
        }
    }

    private static List<XaeroWaypointFormat.XaeroWaypoint> asFileLines(List<XaeroBridge.RealWaypoint> waypoints) {
        List<XaeroWaypointFormat.XaeroWaypoint> result = new ArrayList<>();
        for (XaeroBridge.RealWaypoint rw : waypoints) {
            result.add(new XaeroWaypointFormat.XaeroWaypoint(
                    rw.name(), rw.x(), rw.y(), rw.z(), rw.colorOrdinal(),
                    XaeroWaypointFormat.DEFAULT_SET_NAME));
        }
        return result;
    }

    // ---- identity ------------------------------------------------------------------------------

    private static String keyOf(String dimension, XaeroBridge.RealWaypoint rw) {
        return XaeroLinkLedger.keyFor(dimension, rw.x(), rw.z(), rw.name());
    }

    private static Set<String> identityKeys(String dimension, List<XaeroBridge.RealWaypoint> waypoints) {
        Set<String> keys = new HashSet<>();
        for (XaeroBridge.RealWaypoint rw : waypoints) {
            keys.add(keyOf(dimension, rw));
        }
        return keys;
    }

    /** The identity an easyWp waypoint has on Xaero's side: its dimension, X, Z and name. */
    private static String identityOf(Waypoint wp) {
        return XaeroLinkLedger.keyFor(wp.dimensionOrOverworld(), wp.getPos().getX(), wp.getPos().getZ(), wp.getName());
    }

    /** Swaps out every ledger entry for {@code dimension}, leaving other dimensions untouched - the
     *  per-dimension granularity that lets one unreadable dimension keep its history while the rest
     *  of the sync proceeds. */
    private static void replaceDimension(Set<String> ledger, String dimension, Set<String> keys) {
        ledger.removeIf(key -> XaeroLinkLedger.isInDimension(key, dimension));
        ledger.addAll(keys);
    }

    /**
     * Every identity easyWp already shows a waypoint under - name, dimension, X and Z - which an
     * Import checks each Xaero waypoint against before adding it. Built once per Import rather than
     * rescanning the whole list (and rebuilding each waypoint's identities) for every Xaero
     * waypoint, which made an Import cost easyWp's count times Xaero's.
     *
     * <p>Y is deliberately not part of it: Xaero reports a synthetic {@code y = 64} for a waypoint
     * saved without one, which would otherwise never match. A waypoint with no name is left out, as
     * it could never equal a Xaero waypoint's name.
     */
    private static Set<String> existingIdentities() {
        Set<String> keys = new HashSet<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null && wp.getName() != null) {
                keys.add(identityOf(wp));
            }
        }
        return keys;
    }

    /** Every current waypoint, grouped by its dimension. */
    private static Map<String, List<Waypoint>> groupByDimension() {
        Map<String, List<Waypoint>> result = new HashMap<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null) {
                result.computeIfAbsent(wp.dimensionOrOverworld(), d -> new ArrayList<>()).add(wp);
            }
        }
        return result;
    }

    // ---- best-effort path guessing (only used when the live bridge is unavailable) ------------

    /** Every dimension worth checking for Xaero waypoints to import: the vanilla three, plus
     *  anything easyWp already knows about, plus wherever the player currently is - the same
     *  reasoning {@code WaypointListScreen.updateDimensionTabs()} uses for its own tab list. */
    private static List<String> knownDimensions(Minecraft client) {
        List<String> dims = new ArrayList<>(List.of(DimensionNames.OVERWORLD, DimensionNames.NETHER, DimensionNames.END));
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null && wp.getDimension() != null && !dims.contains(wp.getDimension())) {
                dims.add(wp.getDimension());
            }
        }
        if (client.level != null) {
            String current = client.level.dimension().identifier().toString();
            if (!dims.contains(current)) {
                dims.add(current);
            }
        }
        return dims;
    }

    /**
     * Best-effort {@code waypoints.txt} path for {@code dimension}, used only as the fallback when
     * {@link XaeroBridge} cannot supply the real one. The per-dimension folder name
     * ({@code namespace%path}, slashes in the path replaced with {@code %}) was confirmed from
     * Xaero's own bytecode (see {@code XaeroWaypointFormat}); the world root folder name
     * ({@code Singleplayer_<name>}/{@code Multiplayer_<ip>}) is not - this mod has no way to read
     * Xaero's own world-identity logic without the bridge that is, by definition, unavailable on
     * this path - so it is a standard, widely-documented guess rather than a confirmed one. A wrong
     * guess here costs nothing worse than a missed sync: the file simply won't be found, which reads
     * as "unknown" and leaves both sides and the ledger untouched.
     */
    private static Path guessedFile(Minecraft client, String dimension) {
        String worldFolder;
        if (client.getSingleplayerServer() != null) {
            String name = client.getSingleplayerServer().getWorldData() != null
                    ? client.getSingleplayerServer().getWorldData().getLevelName()
                    : "world";
            worldFolder = "Singleplayer_" + sanitize(name);
        } else if (client.getCurrentServer() != null) {
            worldFolder = "Multiplayer_" + sanitize(client.getCurrentServer().ip);
        } else {
            worldFolder = "Multiplayer_lan";
        }
        return new File(client.gameDirectory, "XaeroWaypoints/" + worldFolder + "/" + dimensionFolderName(dimension) + "/" + WAYPOINTS_FILE_NAME).toPath();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** {@code <namespace>%<path>}, with any {@code /} in the path (a namespaced/nested dimension
     *  path) replaced by {@code %} too, and trailing dots replaced by commas - read from
     *  {@code MinimapDimensionHelper}'s own bytecode, not guessed. */
    private static String dimensionFolderName(String dimension) {
        int colon = dimension.indexOf(':');
        String namespace = colon >= 0 ? dimension.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? dimension.substring(colon + 1) : dimension;
        path = path.replace('/', '%');
        path = replaceTrailingDots(path);
        return namespace + "%" + path;
    }

    private static String replaceTrailingDots(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        if (end == s.length()) {
            return s;
        }
        return s.substring(0, end) + ",".repeat(s.length() - end);
    }
}
