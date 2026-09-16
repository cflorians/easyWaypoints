package com.easywp.client;

import com.easywp.XaeroWaypointFormat;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.MinimapWorldManager;
import xaero.hud.minimap.world.container.MinimapWorldContainer;
import xaero.hud.minimap.world.container.MinimapWorldRootContainer;
import xaero.hud.path.XaeroPath;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The only class in this mod that imports {@code xaero.*} - see {@link XaeroBridge} for why every
 * other class goes through that facade instead of here directly.
 *
 * <p>Reads and writes the player's <em>own real</em> waypoints, the ones Xaero persists and shows in
 * its own waypoint list - not the {@code ThirdPartyWaypointManager} layer an earlier version of this
 * mod used. That layer was the wrong tool for what easyWp's Export button now means: it is pure
 * runtime state Xaero never writes to disk, so an exported waypoint vanished on the next restart and
 * was drawn as a separate overlay rather than appearing in the player's list at all.
 *
 * <p><b>Writing is strictly additive.</b> {@link #addRealWaypoints} only ever appends; nothing here
 * removes, replaces, or reorders a waypoint the player made. That is what makes it safe to write into
 * the same sets {@link #readRealWaypoints} reads - the two directions are both opt-in button presses
 * and neither can destroy the other side's data.
 *
 * <p>The add-and-persist sequence was read from Xaero's own
 * {@code xaero.hud.minimap.waypoint.DeathpointHandler.createDeathpoint}, which is the same problem
 * (Xaero adding a real waypoint programmatically and having to make it survive a restart):
 * {@code world.getCurrentWaypointSet()} → {@code set.add(waypoint)} →
 * {@code session.getWorldManagerIO().saveWorld(world)}. That last call declares a checked
 * {@code IOException}, so it is handled here rather than at {@link XaeroBridge}'s edge, whose catch
 * only covers {@code LinkageError | RuntimeException}.
 *
 * <p>Every method returns a neutral false/null/-1 for the ordinary "not ready yet" cases (no active
 * minimap session, e.g. the main menu or between world loads) rather than throwing - only a genuine
 * API mismatch should reach {@link XaeroBridge}'s catch block and disable the bridge for the session.
 */
final class XaeroMinimapBridge {

    /**
     * The {@link MinimapWorld} node name to create when a dimension's container has no world yet -
     * the case for a dimension the player has not visited and that holds no Xaero waypoints on disk.
     * {@code "waypoints"} rather than an invented name because Xaero derives a world's file name from
     * its node ({@code MinimapWorldManagerIO.getWorldFile}) and, in the other direction, names a node
     * after the file it loaded it from ({@code loadDimensionFolder}) - so this node is exactly the
     * {@code waypoints.txt} Xaero itself would have written.
     */
    private static final String DEFAULT_WORLD_NODE = "waypoints";

    XaeroMinimapBridge() {
        // Touches nothing on construction - resolving an actual session happens lazily per call, so
        // a player with Xaero installed but not yet in a world still gets XaeroBridge.isAvailable().
    }

    /**
     * The player's own real waypoints for one dimension, read live from Xaero's actual
     * {@link WaypointSet}s. Skips {@code isTemporary()} waypoints (pings/oneoff destinations),
     * matching what Xaero's own {@code WaypointIO.saveWaypoints} excludes from the file for the same
     * reason - they are not something the player asked to keep.
     *
     * <p>Returns {@code null} - not an empty list - when there is no session to read from. The
     * distinction is load-bearing now that a sync deletes as well as adds: "Xaero has nothing here"
     * means every linked waypoint in this dimension was deleted and should be removed from easyWp
     * too, while "I could not ask Xaero" must leave everything alone. Collapsing the two would turn a
     * main-menu or between-worlds read into a mass deletion.
     */
    List<XaeroBridge.RealWaypoint> readRealWaypoints(String dimension) {
        MinimapWorldContainer container = dimensionContainer(dimension);
        if (container == null) {
            return null;
        }
        List<XaeroBridge.RealWaypoint> result = new ArrayList<>();
        for (MinimapWorld world : container.getWorlds()) {
            for (WaypointSet set : world.getIterableWaypointSets()) {
                for (xaero.common.minimap.waypoints.Waypoint wp : set.getWaypoints()) {
                    if (wp.isTemporary()) {
                        continue;
                    }
                    int y = wp.isYIncluded() ? wp.getY() : 64;
                    result.add(new XaeroBridge.RealWaypoint(wp.getName(), wp.getX(), y, wp.getZ(),
                            wp.getWaypointColor().ordinal()));
                }
            }
        }
        return result;
    }

    /**
     * Applies one dimension's worth of a sync to Xaero: removes every waypoint matching
     * {@code toRemove}, appends {@code toAdd}, and persists whatever it touched.
     *
     * <p>Takes whole batches rather than one waypoint per call on purpose: {@code saveWorld} writes
     * the dimension's file, so exporting forty waypoints one at a time would be forty disk writes.
     * Xaero's own {@code DeathpointHandler} saves per add only because it ever adds one.
     *
     * <p>Three details here are each a bug if done the obvious way:
     * <ul>
     *   <li>Removals are collected during the {@code getWaypoints()} walk and applied strictly after
     *       it exits. {@code WaypointSet.remove} mutates the very list that iteration is walking, so
     *       removing in place throws {@code ConcurrentModificationException} instead of removing.</li>
     *   <li>Removals sweep <em>every</em> {@link WaypointSet} of every {@link MinimapWorld} in the
     *       dimension, because that is where {@code readRealWaypoints} found them - a waypoint the
     *       player filed in their own set must still be removable. Additions only ever go into the
     *       current set, since that is the one a player would have put it in by hand.</li>
     *   <li>Each world that changed is saved exactly once, and removals can touch a different world
     *       from the one additions land in, so the set of touched worlds is collected rather than
     *       assumed to be a single one.</li>
     * </ul>
     *
     * <p>Appends with the single-argument {@code add}, not {@code add(wp, true)} - the two-argument
     * form prepends, which would insert easyWp's whole list above the player's own waypoints and
     * reverse their order.
     *
     * @return {@code false} if there is no session to apply this to, or if persisting failed - either
     *         way the caller falls back to writing {@code waypoints.txt} itself.
     */
    boolean applyRealWaypoints(String dimension, List<XaeroBridge.RealWaypoint> toAdd, List<XaeroBridge.RealWaypoint> toRemove) {
        MinimapWorldContainer container = dimensionContainer(dimension);
        if (container == null) {
            return false;
        }

        // Identity-based: MinimapWorld doesn't override equals, which is what we want here - two
        // distinct world nodes are two distinct files to save.
        Set<MinimapWorld> touched = new LinkedHashSet<>();

        if (!toRemove.isEmpty()) {
            for (MinimapWorld world : container.getWorlds()) {
                for (WaypointSet set : world.getIterableWaypointSets()) {
                    List<xaero.common.minimap.waypoints.Waypoint> doomed = new ArrayList<>();
                    for (xaero.common.minimap.waypoints.Waypoint candidate : set.getWaypoints()) {
                        if (matchesAny(candidate, toRemove)) {
                            doomed.add(candidate);
                        }
                    }
                    for (xaero.common.minimap.waypoints.Waypoint wp : doomed) {
                        set.remove(wp);
                    }
                    if (!doomed.isEmpty()) {
                        touched.add(world);
                    }
                }
            }
        }

        if (!toAdd.isEmpty()) {
            MinimapWorld world = container.getFirstWorld();
            if (world == null) {
                // Get-or-create: a dimension the player has never visited has no world node yet.
                world = container.addWorld(DEFAULT_WORLD_NODE);
            }
            if (world == null) {
                return false;
            }
            WaypointSet set = world.getCurrentWaypointSet();
            if (set == null) {
                return false;
            }
            for (XaeroBridge.RealWaypoint rw : toAdd) {
                set.add(toXaeroWaypoint(rw));
            }
            touched.add(world);
        }

        try {
            for (MinimapWorld world : touched) {
                container.getSession().getWorldManagerIO().saveWorld(world);
            }
        } catch (IOException e) {
            // The changes are already in Xaero's in-memory sets, so they hold for the rest of this
            // session either way; false reports that they are not on disk, so the caller can write the
            // file itself.
            com.easywp.EasyWp.LOGGER.warn("Xaero accepted the sync for {} but saving its own file failed", dimension, e);
            return false;
        }
        return true;
    }

    /** Matches by name + X + Z, the identity {@code XaeroSync} uses in both directions - see
     *  {@link #readRealWaypoints} for why Y is excluded. */
    private static boolean matchesAny(xaero.common.minimap.waypoints.Waypoint candidate, List<XaeroBridge.RealWaypoint> targets) {
        for (XaeroBridge.RealWaypoint target : targets) {
            if (candidate.getX() == target.x() && candidate.getZ() == target.z()
                    && candidate.getName().equals(target.name())) {
                return true;
            }
        }
        return false;
    }

    /** Resolves the {@link MinimapWorldContainer} for one dimension of the current world/server,
     *  creating it if Xaero hasn't seen that dimension yet this session ({@code addSubContainer} is
     *  itself get-or-create) - or {@code null} if there is no active minimap session at all. */
    private MinimapWorldContainer dimensionContainer(String dimension) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            return null;
        }
        MinimapWorldManager worldManager = session.getWorldManager();
        MinimapWorldRootContainer root = worldManager.getAutoRootContainer();
        if (root == null) {
            return null;
        }
        ResourceKey<Level> dimKey = dimensionKey(dimension);
        String dirName = session.getDimensionHelper().getDimensionDirectoryName(dimKey);
        XaeroPath dimPath = root.getPath().resolve(dirName);
        return root.addSubContainer(dimPath);
    }

    private static ResourceKey<Level> dimensionKey(String dimension) {
        Identifier id = Identifier.parse(dimension != null ? dimension : "minecraft:overworld");
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    private static xaero.common.minimap.waypoints.Waypoint toXaeroWaypoint(XaeroBridge.RealWaypoint rw) {
        WaypointColor color = WaypointColor.fromIndex(rw.colorOrdinal());
        return new xaero.common.minimap.waypoints.Waypoint(
                rw.x(), rw.y(), rw.z(),
                rw.name(), XaeroWaypointFormat.initialsFor(rw.name()),
                color, WaypointPurpose.NORMAL,
                false, // rotation: easyWp waypoints carry no facing/yaw to show on arrival
                true   // yIncluded: an easyWp waypoint always carries a real Y
        );
    }
}
