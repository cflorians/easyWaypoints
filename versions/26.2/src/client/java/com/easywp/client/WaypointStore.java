package com.easywp.client;

import com.easywp.AsyncJsonWriter;
import com.easywp.EasyWp;
import com.easywp.JsonStore;
import com.easywp.PairedCoordinates;
import com.easywp.PendingRemovals;
import com.easywp.WaypointGroups;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The current world's waypoints and everything about keeping them: which world/server they belong
 * to ({@link #getWorldId()}), loading them when that changes, saving them after every change, and
 * the cross-world import helpers.
 *
 * <p>The live list is a plain static {@code List} that the screens, the renderer and the managers
 * read and mutate directly, all on the client thread - there is no further abstraction on
 * purpose. Split out of {@code WaypointRenderer} in 1.3.4, which until then both drew the
 * waypoints and owned this; the renderer now only reads {@link #waypoints} and
 * {@link #getCurrentDimension()}.
 */
public final class WaypointStore {
    public static final List<Waypoint> waypoints = new ArrayList<>();

    /** Reused across loads and saves - Gson instances are safe to share and hold no per-call state. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static String lastWorldId = "";
    private static Object lastCheckedLevel = null;

    /** Refreshed only alongside {@link #lastCheckedLevel} - see {@link #checkAndLoadWorldWaypoints()}. */
    private static String cachedDimension = DimensionNames.OVERWORLD;

    /** How long a deletion from the list can be undone, each on its own clock. */
    private static final long UNDO_LIFETIME_MS = 7_000;

    /**
     * The list's deletions still within their seconds. Kept here, not on the screen, so closing and
     * reopening the list meanwhile still shows them, and so they run out with the list closed (see
     * {@link #tickPendingRemovals()}).
     */
    private static final PendingRemovals<Waypoint> pendingRemovals =
            new PendingRemovals<>(UNDO_LIFETIME_MS, System::currentTimeMillis);

    /**
     * The groups of the current world hidden from the list's eye, by exact name: hidden in the world
     * too ({@link Waypoint#isShown}). Saved apart from the waypoints, in
     * {@code groups_<worldId>.json}, so the waypoints file keeps the shape older versions read.
     */
    private static Set<String> hiddenGroups = new LinkedHashSet<>();

    /** Bumped whenever waypoints leave the list without the list having asked - a pending deletion
     *  running out, or all of them being made final - so an open list knows to rebuild its rows. */
    private static int removalsVersion = 0;

    private WaypointStore() { }

    /** Characters that are unsafe in a file name, stripped when deriving the world id. Hoisted to a
     *  constant rather than inlined as String.replaceAll, which recompiles the pattern on every
     *  call - getWorldId() is now gated behind the level-identity check in
     *  checkAndLoadWorldWaypoints(), but it still runs on every save. */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>| ]");

    public static String getWorldId() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "unknown";
        }
        if (client.getSingleplayerServer() != null) {
            if (client.getSingleplayerServer().getWorldData() != null) {
                return "sp_" + UNSAFE_FILENAME_CHARS.matcher(client.getSingleplayerServer().getWorldData().getLevelName()).replaceAll("_");
            }
            return "sp_world";
        }
        ServerData server = client.getCurrentServer();
        if (server != null) {
            // A Realm is named after the Realm, not after where it answered from. ServerData.ip is
            // the only address the client has, and for a direct server it is a stable identity - but
            // for a Realm it is the session address Mojang's backend resolves per join attempt
            // (RealmsJoinInformation.address(), region-routed via RegionData), which
            // RealmsServer.toServerData drops straight into that same field. It changes on its own:
            // a player whose Realm was updated between two sessions came back to a different
            // address, a different worldId, and an empty waypoint list - the old file still sat
            // intact under the previous address. ServerData.Type.REALM (via isRealm(), which is
            // exactly that comparison) is what tells the two cases apart.
            //
            // The name is the best identity reachable from here, not a perfect one, and it is worth
            // being precise about which: RealmsServer carries a genuinely stable long id, but
            // toServerData does not pass it on, and ServerData is all this method ever sees. So the
            // name it is - stable unless the owner renames the Realm, which is rare, deliberate, and
            // recoverable by renaming the file to match. It does NOT change when the Realm switches
            // to a minigame: that name is RealmsServer.minigameName, a separate field, and
            // RealmsConnect sets it on the packet listener rather than on this ServerData.
            if (server.isRealm()) {
                String realmName = realmFileName(server);
                if (realmName != null) {
                    return "mp_realm_" + realmName;
                }
                // Fall through to the address. It brings back the very instability above for this
                // one Realm, which is the lesser evil: see realmFileName().
            }
            return "mp_" + UNSAFE_FILENAME_CHARS.matcher(server.ip.replace(':', '_')).replaceAll("_");
        }
        return "mp_lan";
    }

    /**
     * Vanilla's placeholder for a Realm whose name the Realms API did not return.
     *
     * <p>{@code RealmsServer.toServerData} is
     * {@code new ServerData(Objects.requireNonNullElse(this.name, "unknown server"), address, REALM)},
     * so {@code ServerData.name} is never null on a Realm - a null-check here would never fire and
     * this literal would sail straight through as if it were a name.
     */
    private static final String UNNAMED_REALM = "unknown server";

    /**
     * @return the sanitized name to file this Realm under, or {@code null} if it has none usable.
     *
     * <p>Rejecting {@link #UNNAMED_REALM} matters more than it looks: it is a constant, so every
     * unnamed Realm would otherwise share one {@code waypoints_mp_realm_unknown_server.json} and
     * show each other's waypoints. Mixing two Realms' lists together is a worse outcome than the
     * address instability this whole branch exists to fix - that one only makes waypoints look
     * missing, with the real file intact and recoverable - so an unnamed Realm falls back to the
     * address rather than into a shared file.
     *
     * <p>The remaining hole is accepted rather than solved, because {@code ServerData} carries
     * nothing else to disambiguate with: two <em>differently</em> named Realms never collide, but
     * two Realms that happen to share a name do, and the fix is for the player to rename one.
     */
    private static String realmFileName(ServerData server) {
        String name = server.name;
        if (name == null || name.isBlank() || name.equals(UNNAMED_REALM)) {
            return null;
        }
        return UNSAFE_FILENAME_CHARS.matcher(name).replaceAll("_");
    }

    public static void checkAndLoadWorldWaypoints() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        // client.level is a new instance on every dimension change as well as on (re)join, and
        // unchanged on every other frame, so gating getWorldId() on it turns this from a per-frame
        // call (string formatting, singleplayer/server lookups) into one that only runs on those
        // transitions - identical result, since worldId never depends on which dimension the
        // already-known server/world is currently in. cachedDimension piggybacks on the same gate:
        // dimension().identifier().toString() allocates too, and it can only change on the same
        // transitions that change client.level's identity.
        if (client.level == lastCheckedLevel) return;
        lastCheckedLevel = client.level;
        cachedDimension = client.level.dimension().identifier().toString();

        // Not synchronized: every caller of this - the HUD renderer, DeathWaypointManager.tick() and the
        // screens - runs on the client thread, and so does every mutation of the waypoint list, so
        // there is no second thread for a monitor to exclude. The lock that used to sit here
        // guarded this one block while the rest of the list was mutated freely without it, which
        // bought nothing.
        String currentWorld = getWorldId();
        if (!currentWorld.equals("unknown") && !currentWorld.equals(lastWorldId)) {
            lastWorldId = currentWorld;
            loadFromFile();
        }
    }

    /**
     * Lets go of the world just left: its level, its waypoint list and its id. Called every client
     * tick with no level, so it returns at once once there is nothing left to drop.
     *
     * <p>{@link #lastCheckedLevel} used to keep the old {@code ClientLevel} - every loaded chunk
     * and entity - reachable from the main menu until another world was joined, and the list stayed
     * in memory with it, which is what let the import picker (reachable from the title screen
     * through Mod Menu) merge a file into a world that was no longer open. Rejoining reloads from
     * disk, and {@link #readWaypointData} flushes pending saves first, so nothing written is lost.
     *
     * <p>Only reached on an actual exit (or a server reconfiguring, e.g. a proxy moving the player
     * to another backend): a dimension change swaps the level without ever passing through null.
     */
    public static void forgetWorld() {
        if (lastCheckedLevel == null && lastWorldId.isEmpty() && waypoints.isEmpty()) return;
        lastCheckedLevel = null;
        lastWorldId = "";
        cachedDimension = DimensionNames.OVERWORLD;
        waypoints.clear();
        pendingRemovals.takeAll();
        hiddenGroups = new LinkedHashSet<>();
    }

    /**
     * Deletes {@code wp} the way the list's delete button does: for {@link #UNDO_LIFETIME_MS} it
     * stays in the list, dimmed, with Undo and Delete in place of its buttons; it is gone from the
     * world ({@link Waypoint#isShown}), from any focus count and from the file at once - the save
     * below already leaves it out, so closing the game meanwhile keeps it deleted, as a deletion
     * always was. When its time runs out {@link #tickPendingRemovals()} drops it from the list.
     * Only the list deletes through here; deaths reached, Xaero imports and evictions remove
     * directly and cannot be undone.
     */
    public static void markForRemoval(Waypoint wp) {
        if (!waypoints.contains(wp)) return;
        wp.setPendingRemoval(true);
        pendingRemovals.mark(wp);
        saveToFile();
    }

    /** Takes back a pending deletion - the row's Undo. The waypoint never left its place in the
     *  list, so it is simply back, focus and all. @return whether it was still pending */
    public static boolean undoRemoval(Waypoint wp) {
        if (!pendingRemovals.cancel(wp)) return false;
        wp.setPendingRemoval(false);
        saveToFile();
        return true;
    }

    /** Makes a pending deletion final now instead of after its seconds - the row's Delete. */
    public static void removeNow(Waypoint wp) {
        pendingRemovals.cancel(wp);
        waypoints.remove(wp);
        saveToFile();
    }

    /** Drops from the list every pending deletion whose time is up. Every client tick, in a world,
     *  so it happens with the list closed too. */
    public static void tickPendingRemovals() {
        if (pendingRemovals.size() == 0) return;
        List<Waypoint> expired = pendingRemovals.takeExpired();
        if (!expired.isEmpty()) {
            waypoints.removeAll(expired);
            removalsVersion++;
        }
    }

    /** Makes every pending deletion final at once - before a Xaero sync, which must act on the list
     *  as it is about to be, not on rows that are already gone everywhere else. */
    public static void commitPendingRemovals() {
        List<Waypoint> all = pendingRemovals.takeAll();
        if (!all.isEmpty()) {
            waypoints.removeAll(all);
            removalsVersion++;
        }
    }

    /** Milliseconds until {@code wp}'s pending deletion becomes final; -1 if it is not pending. */
    public static long remainingRemovalMillis(Waypoint wp) {
        return pendingRemovals.remainingMillis(wp);
    }

    /** See {@link #removalsVersion}. */
    public static int removalsVersion() {
        return removalsVersion;
    }

    /**
     * The copy {@code wp} already has in the other half of the Overworld/Nether pair, or
     * {@code null}: a waypoint there with the same name whose X/Z correspond to {@code wp}'s at 1:8
     * ({@link PairedCoordinates#areCopies}), in whichever direction it was made. Y is not compared,
     * so a copy whose height the player adjusted to its own dimension is still the copy. A copy
     * that is itself pending deletion does not count - it is gone as far as the player can tell -
     * and neither, of course, does one deleted for good: duplicating then makes a new one.
     */
    public static Waypoint findCopyInPairedDimension(Waypoint wp) {
        String paired = wp.pairedDimension();
        if (paired == null || wp.getName() == null) return null;
        boolean wpInOverworld = wp.dimensionOrOverworld().equals(DimensionNames.OVERWORLD);
        for (Waypoint other : waypoints) {
            if (other == null || other == wp || other.isPendingRemoval()) continue;
            if (!paired.equals(other.dimensionOrOverworld()) || !wp.getName().equals(other.getName())) continue;
            BlockPos ow = wpInOverworld ? wp.getPos() : other.getPos();
            BlockPos nether = wpInOverworld ? other.getPos() : wp.getPos();
            if (PairedCoordinates.areCopies(ow.getX(), ow.getZ(), nether.getX(), nether.getZ())) {
                return other;
            }
        }
        return null;
    }

    /**
     * Adds an independent copy of {@code wp} to the other half of the Overworld/Nether pair: same
     * name and colour, at {@link Waypoint#convertedPosition()}, with its own creation time - from
     * then on the two are separate waypoints that can be moved, renamed or deleted apart. Nothing
     * else is carried over. Used by the list's "More options" menu and the create screen's "Save
     * and duplicate"; it is what replaced the shared waypoints drawn in both dimensions until 1.4.0.
     *
     * @return the copy, or {@code null} if none was made: outside the pair, or because
     *         {@link #findCopyInPairedDimension} already finds one - duplicating twice never
     *         leaves two copies
     */
    public static Waypoint duplicateIntoPairedDimension(Waypoint wp) {
        String paired = wp.pairedDimension();
        BlockPos at = wp.convertedPosition();
        if (paired == null || at == null || findCopyInPairedDimension(wp) != null) return null;

        Waypoint copy = new Waypoint(wp.getName(), at, wp.getColor(), paired);
        waypoints.add(copy);
        saveToFile();
        return copy;
    }

    /** Whether there is a world open for the waypoints to belong to - {@link #getWorldId()} is
     *  {@code "unknown"} exactly when there is not, and nothing can be saved then. */
    public static boolean isInWorld() {
        return Minecraft.getInstance().level != null;
    }

    /**
     * Focuses {@code wp}, the only one: focus is exclusive, and every "kept visible" beside the old
     * focus goes with it. {@code wp} is made visible too, since a hidden focus would hide everything.
     * The list's focus swatch and {@code /easywp focus}.
     */
    public static void focus(Waypoint wp) {
        for (Waypoint w : waypoints) {
            if (w != null) {
                w.setFocused(false);
                w.setForceVisible(false);
            }
        }
        wp.setFocused(true);
        wp.setVisible(true);
        saveToFile();
    }

    /**
     * Ends the focus. Every "kept visible" goes with the focus it was kept beside: only the focused
     * one's flag used to be cleared, the rest waiting for the next focus to clear them - invisible
     * while nothing is focused, but forceVisible is saved, so they were written out stale.
     */
    public static void clearFocus() {
        for (Waypoint w : waypoints) {
            if (w != null) {
                w.setFocused(false);
                w.setForceVisible(false);
            }
        }
        saveToFile();
    }

    /**
     * Every group the current world has: the union of its waypoints' groups, sorted. A group lives
     * only as long as some waypoint carries it. Waypoints pending deletion still count, so undoing
     * the deletion of a group's last waypoint brings the group back as it was.
     */
    public static List<String> allGroups() {
        List<List<String>> each = new ArrayList<>(waypoints.size());
        for (Waypoint wp : waypoints) {
            if (wp != null) {
                each.add(wp.getGroups());
            }
        }
        return WaypointGroups.union(each);
    }

    public static boolean isGroupHidden(String group) {
        return hiddenGroups.contains(group);
    }

    /** Hides or shows a whole group, in the list and in the world, and saves that. */
    public static void setGroupHidden(String group, boolean hidden) {
        boolean changed = hidden ? hiddenGroups.add(group) : hiddenGroups.remove(group);
        if (changed) {
            saveGroups();
        }
    }

    /** Whether one of {@code wp}'s groups is hidden. Called for every waypoint every frame, so the
     *  usual case - nothing hidden - returns at once. */
    static boolean isInHiddenGroup(Waypoint wp) {
        return !hiddenGroups.isEmpty() && WaypointGroups.anyHidden(wp.getGroups(), hiddenGroups);
    }

    /** Whether any waypoint of the current world is focused, in any dimension. A focus anywhere
     *  hides every waypoint that is neither focused nor kept visible beside it, in every dimension -
     *  see {@link Waypoint#isShown}. */
    public static boolean hasAnyFocused() {
        for (Waypoint wp : waypoints) {
            // A focused waypoint pending deletion is already gone from the world, and so is its focus.
            if (wp != null && wp.isFocused() && !wp.isPendingRemoval()) {
                return true;
            }
        }
        return false;
    }

    /** The current dimension id, cached alongside {@link #checkAndLoadWorldWaypoints()}. Callers
     *  that already trigger that check first (the HUD renderer, DeathWaypointManager.tick()) always see a
     *  value fresh for the current frame/tick. */
    public static String getCurrentDimension() {
        return cachedDimension;
    }

    private static Path waypointsFile(String worldId) {
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json").toPath();
    }

    private static Path configDir() {
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp").toPath();
    }

    public static void saveToFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        try {
            // forceVisible only means something beside a focused waypoint. Deleting the focused one
            // (from the list, by reaching a death waypoint, by a Xaero import) leaves the others'
            // flags behind; they are ignored in memory until the next focus clears them, but they
            // are not written out as if they still meant something.
            boolean anyFocused = hasAnyFocused();
            List<WaypointData> dataList = new ArrayList<>();
            for (Waypoint wp : waypoints) {
                // Deleted from the list and only waiting out its undo: already deleted on disk.
                if (wp.isPendingRemoval()) continue;
                dataList.add(new WaypointData(wp, anyFocused));
            }

            // Serialised in full, here on the client thread, before anything touches the disk:
            // JsonStore needs the whole document up front to write it in one atomic replacement,
            // and building it now pins exactly this state even though the write itself happens
            // later, on AsyncJsonWriter's thread - the fsync is what used to stall the frame.
            AsyncJsonWriter.SHARED.write(waypointsFile(worldId), GSON.toJson(dataList));
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to save waypoints", e);
        }

        // A hidden group whose last waypoint just went (deleted, regrouped) is forgotten with it.
        if (!hiddenGroups.isEmpty()) {
            Set<String> kept = WaypointGroups.prune(hiddenGroups, allGroups());
            if (kept.size() != hiddenGroups.size()) {
                hiddenGroups = kept;
                saveGroups();
            }
        }
    }

    /** Writes the hidden groups to {@code groups_<worldId>.json}, the same durable way as the
     *  waypoints. A world with none hidden writes an empty list rather than deleting the file. */
    private static void saveGroups() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;
        GroupsData data = new GroupsData();
        data.hidden = new ArrayList<>(WaypointGroups.prune(hiddenGroups, allGroups()));
        AsyncJsonWriter.SHARED.write(groupsFile(worldId), GSON.toJson(data));
    }

    /** The hidden groups of {@code worldId}, or none if the file is missing or unreadable - which
     *  only shows a group, never hides one. Reads after a flush, like the waypoints. */
    private static Set<String> loadHiddenGroups(String worldId) {
        AsyncJsonWriter.SHARED.flush();
        Path file = groupsFile(worldId);
        GroupsData data = parseGroups(JsonStore.read(file));
        if (data == null) {
            data = parseGroups(JsonStore.read(JsonStore.backupOf(file)));
        }
        Set<String> hidden = new LinkedHashSet<>();
        if (data != null && data.hidden != null) {
            for (String group : data.hidden) {
                if (group != null) hidden.add(group);
            }
        }
        return hidden;
    }

    private static GroupsData parseGroups(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return GSON.fromJson(json, GroupsData.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path groupsFile(String worldId) {
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp/groups_" + worldId + ".json").toPath();
    }

    public static void loadFromFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        Path configFile = waypointsFile(worldId);
        waypoints.clear();
        pendingRemovals.takeAll();
        hiddenGroups = loadHiddenGroups(worldId);

        List<WaypointData> dataList = readWaypointData(configFile, worldId);
        if (dataList != null) {
            Loaded loaded = toWaypoints(dataList);
            waypoints.addAll(loaded.waypoints());
            if (loaded.splitShared() > 0) {
                EasyWp.LOGGER.info("Turned {} shared waypoint(s) of {} into a separate copy in each dimension",
                        loaded.splitShared(), worldId);
                // Written straight back, so the file holds the new shape (and the .bak the old
                // one) and the split is not redone on every join.
                saveToFile();
            }
        } else {
            warnAboutOrphanedRealmFiles(worldId, configFile);
        }
    }

    /** What a file turned into: its waypoints, and how many old shared ones were split to get them. */
    private record Loaded(List<Waypoint> waypoints, int splitShared) {
    }

    /**
     * Turns a file's entries into waypoints, splitting every one saved as {@code shared}.
     *
     * <p>Until 1.4.0 a shared waypoint lived in one dimension and was also drawn, converted 1:8, in
     * the other half of the Overworld/Nether pair. That mirroring is gone, replaced by an
     * independent copy (the create screen's "Save and duplicate"). So an old shared waypoint
     * becomes exactly that: itself, plus a copy at {@link Waypoint#convertedPosition()} - where its
     * mirror was drawn - right after it, with the same name, colour, visibility, favorite and
     * creation time. The player keeps seeing what they saw, and a copy Xaero's Export had already
     * written into the other dimension keeps matching (same name, X and Z), so the next Export does
     * not delete it from Xaero.
     *
     * <p>The copy is never focused (focus is one waypoint) but is kept visible beside the original's
     * focus, as the mirror was; it is never a death waypoint or a Fast Ping, whose rules belong to
     * the one that was really created. It is skipped when the file already holds a waypoint of that
     * name at that exact spot, or when the shared one was not in the Overworld or the Nether.
     */
    private static Loaded toWaypoints(List<WaypointData> dataList) {
        List<Waypoint> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (WaypointData data : dataList) {
            if (data != null) {
                identities.add(identityOf(data.toWaypoint()));
            }
        }
        int split = 0;
        for (WaypointData data : dataList) {
            if (data == null) continue;
            Waypoint wp = data.toWaypoint();
            result.add(wp);
            if (!Boolean.TRUE.equals(data.shared)) continue;

            String paired = wp.pairedDimension();
            BlockPos at = wp.convertedPosition();
            if (paired == null || at == null) continue;
            Waypoint copy = new Waypoint(wp.getName(), at, wp.getColor(), paired,
                    wp.isVisible(), false, false, wp.getCreatedAtMillis());
            copy.setForceVisible(wp.isFocused() || wp.isForceVisible());
            copy.setFavorite(wp.isFavorite());
            copy.setGroups(wp.getGroups());
            if (identities.add(identityOf(copy))) {
                result.add(copy);
                split++;
            }
        }
        return new Loaded(result, split);
    }

    /** Name, exact position and dimension - the same identity {@link #mergeFrom} dedups by. */
    private static String identityOf(Waypoint wp) {
        BlockPos pos = wp.getPos();
        return wp.dimensionOrOverworld() + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + wp.getName();
    }

    /**
     * Every OTHER world/server's waypoint file this player has used the mod on - the manual
     * cross-world import in {@code ModConfigScreen}'s picker screen reads this, so a Realm whose
     * session address changed (see {@link #getWorldId()}) or any other world's waypoints stay
     * reachable by hand instead of only through {@link #warnAboutOrphanedRealmFiles}'s log line.
     *
     * @return every {@code waypoints_*.json} under the config directory except the current
     *         world's own file, sorted by file name. Never {@code null}; empty if the directory
     *         does not exist yet or holds nothing else.
     */
    public static List<Path> listOtherWorldFiles() {
        String currentWorldId = getWorldId();
        Path currentFile = currentWorldId.equals("unknown") ? null : waypointsFile(currentWorldId);

        File[] all = configDir().toFile().listFiles((dir, name) ->
                name.startsWith("waypoints_") && name.endsWith(".json"));
        if (all == null) return new ArrayList<>();

        List<Path> others = new ArrayList<>();
        for (File f : all) {
            Path p = f.toPath();
            if (currentFile == null || !p.equals(currentFile)) {
                others.add(p);
            }
        }
        others.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return others;
    }

    /**
     * Reads one waypoints file without touching the live, in-memory list - unlike
     * {@link #loadFromFile()}, which always targets and mutates the current world's own. Shares
     * that method's fallback to {@code .bak} on a missing/truncated main file, for the same
     * reason: a file this old could predate atomic writes.
     *
     * @return the parsed waypoints, or an empty list if {@code file} is absent or unreadable.
     */
    public static List<Waypoint> loadWaypointsFrom(Path file) {
        List<WaypointData> dataList = readWaypointData(file, null);
        // Old shared waypoints split the same way as in this world's own file - but the other
        // world's file is only read here, never written back.
        return dataList == null ? new ArrayList<>() : toWaypoints(dataList).waypoints();
    }

    /** How many waypoints a file holds, for the import picker's per-row count - counted after
     *  splitting old shared ones, so it matches what the confirmation screen then lists. */
    public static int countWaypointsIn(Path file) {
        return loadWaypointsFrom(file).size();
    }

    /**
     * Reads {@code file}, falling back to its {@code .bak} if the main copy is missing, empty or
     * unparseable - shared by {@link #loadFromFile()} and the import picker's own reads.
     *
     * @param worldIdForLog when non-null, a successful fallback to the backup is logged as
     *                      "recovered" under this world id - used only by {@link #loadFromFile()}
     *                      for the current world. The import picker passes {@code null}: reading
     *                      an arbitrary other file recovering from its own backup is not this
     *                      world's problem to log.
     */
    private static List<WaypointData> readWaypointData(Path file, String worldIdForLog) {
        // Saves are written in the background; a read must not see a revision older than the last
        // one saved (e.g. rejoining a world right after leaving it, or the import picker's counts).
        AsyncJsonWriter.SHARED.flush();
        List<WaypointData> dataList = parseWaypoints(JsonStore.read(file));
        if (dataList == null) {
            dataList = parseWaypoints(JsonStore.read(JsonStore.backupOf(file)));
            if (dataList != null && worldIdForLog != null) {
                EasyWp.LOGGER.warn("Recovered the waypoints of {} from the backup file", worldIdForLog);
            }
        }
        return dataList;
    }

    /**
     * Result of a cross-world import merge: how many waypoints were actually added, and how many
     * were already present under the same identity and left untouched.
     */
    public record MergeResult(int added, int skipped) {
    }

    /**
     * Merges {@code incoming} into the current world's live waypoint list, additively - the only
     * mode the import picker in {@code ModConfigScreen} offers, on purpose: this file format is
     * the only copy of some player's waypoints that may exist, so importing can only ever add to
     * the current world's list, never replace or remove from it.
     *
     * <p>A waypoint already present under the same identity - name, exact position, and
     * dimension - is left alone rather than duplicated. This is a stricter identity than the
     * name+X+Z (no Y) rule {@code XaeroSync}/{@code XaeroWaypointFormat} use for their own
     * dedup: that rule exists only because an Xaero waypoint saved without a Y coordinate reports
     * a synthetic {@code y = 64}, which would never match on Y even for the exact same waypoint.
     * Nothing here comes from Xaero's file format - both sides of this merge are this mod's own
     * JSON, where Y is always real - so there is no reason to throw that precision away.
     *
     * <p>{@code focused} is always cleared on the copy too: focus is exclusive (one waypoint at a
     * time, see WaypointListScreen's focus toggle), and an imported focused waypoint would sit
     * focused beside whichever one the current world already had. {@code forceVisible} goes with
     * it - it only means "kept visible beside the focused one", and the copy has no focus to be
     * beside; the fresh {@link Waypoint} below starts without it.
     *
     * <p>{@code death} and {@code fastPing} are always cleared on the copy that gets added, the
     * same way {@code WaypointCreateScreen.saveWaypoint()} already clears them the moment either
     * kind of waypoint is edited by hand: a death waypoint's dwell-timer deletion and a Fast
     * Ping's numbering both belong to the world that created them, and would either delete
     * themselves for no reason or miscount in a world where no matching death/ping ever happened.
     */
    public static MergeResult mergeFrom(List<Waypoint> incoming) {
        int added = 0;
        int skipped = 0;
        for (Waypoint candidate : incoming) {
            if (hasSameIdentity(candidate)) {
                skipped++;
                continue;
            }
            Waypoint copy = new Waypoint(
                    candidate.getName(),
                    candidate.getPos(),
                    candidate.getColor(),
                    candidate.getDimension(),
                    candidate.isVisible(),
                    false,
                    false,
                    candidate.getCreatedAtMillis()
            );
            // Unlike focus, a favorite is the player's own mark on the waypoint itself, not state
            // tied to the world it came from.
            copy.setFavorite(candidate.isFavorite());
            // Groups are the player's own labels too, and come along; whether one is hidden is
            // this world's choice, kept in this world's own groups file.
            copy.setGroups(candidate.getGroups());
            waypoints.add(copy);
            added++;
        }
        if (added > 0) {
            saveToFile();
        }
        return new MergeResult(added, skipped);
    }

    private static boolean hasSameIdentity(Waypoint candidate) {
        for (Waypoint existing : waypoints) {
            // One pending deletion is about to be gone: it must not keep its twin from being imported.
            if (existing == null || existing.isPendingRemoval()) continue;
            if (Objects.equals(existing.getName(), candidate.getName())
                    && existing.getPos().equals(candidate.getPos())
                    && Objects.equals(existing.getDimension(), candidate.getDimension())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A human-readable label for a {@code waypoints_<worldId>.json} file, for the import picker.
     * The inverse of {@link #getWorldId()}'s four prefixes - kept beside it for the same reason a
     * future prefix added there needs a matching case added here.
     *
     * <p>Lossy by construction: {@link #getWorldId()} already replaced every unsafe filename
     * character (including spaces) with {@code _} before this ever sees the string, so the
     * original name cannot always be recovered exactly. Underscores are turned back into spaces
     * on a best-effort basis; nothing here is used for anything but display.
     */
    public static Component prettyWorldName(Path file) {
        String fileName = file.getFileName().toString();
        String worldId = fileName.startsWith("waypoints_") && fileName.endsWith(".json")
                ? fileName.substring("waypoints_".length(), fileName.length() - ".json".length())
                : fileName;

        if (worldId.equals("mp_lan")) {
            return I18nHelper.getComponent("import.world_lan");
        }
        if (worldId.startsWith("mp_realm_")) {
            return I18nHelper.getComponent("import.world_realm", humanize(worldId.substring("mp_realm_".length())));
        }
        if (worldId.startsWith("sp_")) {
            return I18nHelper.getComponent("import.world_singleplayer", humanize(worldId.substring("sp_".length())));
        }
        if (worldId.startsWith("mp_")) {
            return I18nHelper.getComponent("import.world_server", humanize(worldId.substring("mp_".length())));
        }
        return Component.literal(worldId);
    }

    private static String humanize(String sanitized) {
        return sanitized.replace('_', ' ');
    }

    /**
     * Points a Realm player at the file their waypoints are actually in, once.
     *
     * <p>Only ever runs for a Realm whose own file is absent, and only logs. Before Realms were
     * filed under their name, they were filed under the session address the backend happened to
     * hand out, so a player who has used this mod on a Realm before has their waypoints sitting in
     * a waypoints_mp_&lt;address&gt;.json that nothing will open again. Which one is not decidable
     * from here - a session address is opaque and carries nothing tying it back to a Realm, so a
     * rename done automatically could just as easily adopt some unrelated server's waypoints. The
     * list of candidates plus the name to rename one to is the most this can do without guessing.
     */
    private static void warnAboutOrphanedRealmFiles(String worldId, Path expected) {
        if (!worldId.startsWith("mp_realm_")) return;

        File[] candidates = expected.getParent().toFile().listFiles((dir, name) ->
                name.startsWith("waypoints_mp_") && name.endsWith(".json") && !name.startsWith("waypoints_mp_realm_"));
        if (candidates == null || candidates.length == 0) return;

        List<String> names = new ArrayList<>();
        for (File candidate : candidates) {
            names.add(candidate.getName());
        }
        EasyWp.LOGGER.info(
                "No waypoints for this Realm yet. Older versions filed a Realm under its session address, "
                + "which changes between joins; if this Realm's waypoints are missing, one of {} is them - "
                + "rename it to {} to bring it back.",
                names, expected.getFileName());
    }

    /** @return the parsed list, or {@code null} if {@code json} is absent or not readable as one. */
    private static List<WaypointData> parseWaypoints(String json) {
        if (json == null) return null;
        try {
            Type listType = new TypeToken<List<WaypointData>>(){}.getType();
            return GSON.fromJson(json, listType);
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to parse waypoints", e);
            return null;
        }
    }

    /** {@code groups_<worldId>.json}: the hidden groups of a world. An object, not a bare array,
     *  so anything else about groups can be added later without changing its shape. */
    private static class GroupsData {
        List<String> hidden;
    }

    private static class WaypointData {
        String name;
        int x;
        int y;
        int z;
        int color;
        String dimension;
        /** Read only: until 1.4.0, a waypoint also drawn in the other half of the Overworld/Nether
         *  pair. Loading splits it into two (see {@link #toWaypoints}); saving leaves it out
         *  (null, which Gson skips), and versions before 1.4.0 read the missing key as false. */
        Boolean shared;
        Boolean visible;
        Boolean focused;
        Boolean death;
        Long createdAt;
        Boolean fastPing;
        /** Since 1.4.0, so the waypoints kept visible beside a focused one survive a restart the
         *  way the focus itself always did. Optional: a file from before reads it as absent, and
         *  older versions simply ignore it. */
        Boolean forceVisible;
        /** Since 1.4.0. Optional, like the rest: absent reads as not a favorite. */
        Boolean favorite;
        /** Since 1.4.0: the waypoint's groups. Written only when it has any (null otherwise,
         *  which Gson skips), so a waypoint in no group saves exactly as before. */
        List<String> groups;

        public WaypointData(Waypoint wp, boolean anyFocused) {
            this.name = wp.getName();
            this.x = wp.getPos().getX();
            this.y = wp.getPos().getY();
            this.z = wp.getPos().getZ();
            this.color = wp.getColor();
            this.dimension = wp.getDimension();
            this.visible = wp.isVisible();
            this.focused = wp.isFocused();
            this.death = wp.isDeath();
            this.createdAt = wp.getCreatedAtMillis();
            this.fastPing = wp.isFastPing();
            this.forceVisible = anyFocused && wp.isForceVisible();
            this.favorite = wp.isFavorite();
            this.groups = wp.getGroups().isEmpty() ? null : new ArrayList<>(wp.getGroups());
        }

        public Waypoint toWaypoint() {
            Waypoint wp = new Waypoint(
                name,
                new BlockPos(x, y, z),
                color,
                dimension,
                visible == null ? true : visible,
                focused == null ? false : focused,
                death == null ? false : death,
                createdAt == null ? 0L : createdAt
            );
            wp.setFastPing(fastPing != null && fastPing);
            wp.setForceVisible(forceVisible != null && forceVisible);
            wp.setFavorite(favorite != null && favorite);
            if (groups != null) {
                // Cleaned as the form would have, so a hand-edited file cannot put a blank or
                // duplicate group on a waypoint.
                List<String> clean = new ArrayList<>();
                for (String group : groups) {
                    String name = WaypointGroups.normalize(group);
                    if (name != null && WaypointGroups.canAdd(clean, name)) {
                        clean.add(name);
                    }
                }
                wp.setGroups(clean);
            }
            return wp;
        }
    }
}
