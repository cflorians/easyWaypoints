package com.easywp.client;

import com.easywp.AsyncJsonWriter;
import com.easywp.EasyWp;
import com.easywp.JsonStore;
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
import java.util.List;
import java.util.Objects;
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
    }

    /** Whether there is a world open for the waypoints to belong to - {@link #getWorldId()} is
     *  {@code "unknown"} exactly when there is not, and nothing can be saved then. */
    public static boolean isInWorld() {
        return Minecraft.getInstance().level != null;
    }

    /** Whether any waypoint of the current world is focused, in any dimension. A focus anywhere
     *  hides every waypoint that is neither focused nor kept visible beside it, in every dimension -
     *  see {@link Waypoint#isShown}. */
    public static boolean hasAnyFocused() {
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
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
    }

    public static void loadFromFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        Path configFile = waypointsFile(worldId);
        waypoints.clear();

        List<WaypointData> dataList = readWaypointData(configFile, worldId);
        if (dataList != null) {
            for (WaypointData data : dataList) {
                if (data != null) {
                    waypoints.add(data.toWaypoint());
                }
            }
        } else {
            warnAboutOrphanedRealmFiles(worldId, configFile);
        }
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
        List<Waypoint> result = new ArrayList<>();
        List<WaypointData> dataList = readWaypointData(file, null);
        if (dataList != null) {
            for (WaypointData data : dataList) {
                if (data != null) {
                    result.add(data.toWaypoint());
                }
            }
        }
        return result;
    }

    /** How many waypoints a file holds, for the import picker's per-row count - reads the file
     *  without materializing full {@link Waypoint} objects. */
    public static int countWaypointsIn(Path file) {
        List<WaypointData> dataList = readWaypointData(file, null);
        return dataList == null ? 0 : dataList.size();
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
                    candidate.isShared(),
                    candidate.isVisible(),
                    false,
                    false,
                    candidate.getCreatedAtMillis()
            );
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
            if (existing == null) continue;
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

    private static class WaypointData {
        String name;
        int x;
        int y;
        int z;
        int color;
        String dimension;
        boolean shared;
        Boolean visible;
        Boolean focused;
        Boolean death;
        Long createdAt;
        Boolean fastPing;
        /** Since 1.4.0, so the waypoints kept visible beside a focused one survive a restart the
         *  way the focus itself always did. Optional: a file from before reads it as absent, and
         *  older versions simply ignore it. */
        Boolean forceVisible;

        public WaypointData(Waypoint wp, boolean anyFocused) {
            this.name = wp.getName();
            this.x = wp.getPos().getX();
            this.y = wp.getPos().getY();
            this.z = wp.getPos().getZ();
            this.color = wp.getColor();
            this.dimension = wp.getDimension();
            this.shared = wp.isShared();
            this.visible = wp.isVisible();
            this.focused = wp.isFocused();
            this.death = wp.isDeath();
            this.createdAt = wp.getCreatedAtMillis();
            this.fastPing = wp.isFastPing();
            this.forceVisible = anyFocused && wp.isForceVisible();
        }

        public Waypoint toWaypoint() {
            Waypoint wp = new Waypoint(
                name,
                new BlockPos(x, y, z),
                color,
                dimension,
                shared,
                visible == null ? true : visible,
                focused == null ? false : focused,
                death == null ? false : death,
                createdAt == null ? 0L : createdAt
            );
            wp.setFastPing(fastPing != null && fastPing);
            wp.setForceVisible(forceVisible != null && forceVisible);
            return wp;
        }
    }
}
