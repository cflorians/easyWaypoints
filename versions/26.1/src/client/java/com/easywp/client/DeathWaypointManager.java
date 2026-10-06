package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects the player's deaths (no mixin needed) and manages the lifecycle of death waypoints:
 * created on death (evicting the oldest once {@code maxCount} is exceeded), and auto-deleted once
 * the player has stood next to one for {@code graceSeconds} straight.
 *
 * <p>Two signals, because neither covers every case on its own:
 * <ul>
 * <li><b>The alive→dead edge</b> of {@code isDeadOrDying()}, sampled each client tick. It fires
 *     the moment the death screen appears - and while it is up, so a player who disconnects from
 *     the death screen still gets the waypoint - at the dead player's own position, which does
 *     not move until the respawn packet arrives.</li>
 * <li><b>{@code Player.getLastDeathLocation()} changing.</b> The server sends the exact death
 *     position and dimension in every respawn (and login) packet - it is what the recovery
 *     compass points at. With the {@code doImmediateRespawn} gamerule the player dies and
 *     respawns inside one server tick, before the health drop is ever synced, so the client never
 *     sees it dead and the edge above never fires; this is the one signal that still arrives.</li>
 * </ul>
 * A death seen by the edge is flagged so the location change its respawn brings is not recorded a
 * second time. The first location seen after joining is only a baseline: it is a death from a
 * previous session (or one already recorded by the edge before disconnecting), never a new one.
 */
public class DeathWaypointManager {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEATH_COLOR = 0xFF000000;

    /** The player instance seen last tick. A new instance means a join, a respawn or a dimension change. */
    private static LocalPlayer lastPlayer = null;

    /** Whether the dead state the current player instance is in has already been handled. */
    private static boolean deathHandled = false;

    /** Whether the edge recorded a death whose respawn (and its new death location) has not arrived yet. */
    private static boolean edgeRecordedPendingRespawn = false;

    /** The last death location seen; {@code null} until a baseline is taken after joining. */
    private static Optional<GlobalPos> knownDeathLocation = null;

    /** Per-waypoint dwell start time, since more than one death waypoint can be in range at once when maxCount > 1. */
    private static final Map<Waypoint, Long> dwellStart = new HashMap<>();

    /** Reused scratch space for {@link #checkArrival}, which runs every client tick - avoids
     *  allocating a fresh list 20 times a second in the common case of zero death waypoints. */
    private static final List<Waypoint> deathsScratch = new ArrayList<>();

    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            lastPlayer = null;
            knownDeathLocation = null;
            edgeRecordedPendingRespawn = false;
            dwellStart.clear();
            return;
        }

        // Keeps the current world's waypoint list fresh even if the renderer is in
        // DISABLED mode, since the HUD renderer (which also reloads) draws nothing in that mode.
        WaypointStore.checkAndLoadWorldWaypoints();

        ModConfig.DeathWaypoints cfg = ModConfig.get().deathWaypoints;
        LocalPlayer player = client.player;
        boolean aliveNow = !player.isDeadOrDying();

        // Death location first: on a normal respawn the new player instance and the new location
        // arrive in the same tick, and the edge flag must still be set when the location is judged.
        Optional<GlobalPos> deathLocation = player.getLastDeathLocation();
        if (knownDeathLocation == null) {
            knownDeathLocation = deathLocation;
        } else if (!deathLocation.equals(knownDeathLocation)) {
            knownDeathLocation = deathLocation;
            if (deathLocation.isPresent() && !edgeRecordedPendingRespawn && cfg.enabled) {
                GlobalPos at = deathLocation.get();
                createDeathWaypoint(client, at.pos(), at.dimension().identifier().toString());
            }
            edgeRecordedPendingRespawn = false;
        }

        if (player != lastPlayer) {
            // A player already dead the moment it appears (joining a world on the death screen)
            // died before this session was watching, so the edge must not record it.
            deathHandled = !aliveNow;
            // A new instance with an unchanged location (dying twice on the same block) still
            // ends the pending respawn the edge was waiting for.
            if (lastPlayer != null) {
                edgeRecordedPendingRespawn = false;
            }
            lastPlayer = player;
        }

        if (!cfg.enabled) {
            deathHandled = !aliveNow;
            dwellStart.clear();
            return;
        }

        if (!aliveNow && !deathHandled) {
            createDeathWaypoint(client, player.blockPosition(), player.level().dimension().identifier().toString());
            edgeRecordedPendingRespawn = true;
        }
        deathHandled = !aliveNow;

        // Skipped while dead: the player doesn't control movement on the death screen, so
        // sitting at the death spot before respawning must never count as "arriving".
        if (aliveNow) {
            checkArrival(client, cfg);
        } else {
            dwellStart.clear();
        }
    }

    private static void createDeathWaypoint(Minecraft client, BlockPos pos, String dimension) {
        dwellStart.clear();

        // Keep at most (maxCount - 1) existing deaths so the new one fits within the configured cap;
        // the oldest are evicted first. maxCount defaults to 1, reproducing the old "only the latest
        // death survives" behavior.
        int maxCount = Math.max(1, ModConfig.get().deathWaypoints.maxCount);
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            // One deleted from the list and waiting out its Undo is already gone: it neither takes
            // a slot nor gets evicted from under that Undo.
            if (wp != null && wp.isDeath() && !wp.isPendingRemoval()) {
                deaths.add(wp);
            }
        }
        deaths.sort(Comparator.comparingLong(Waypoint::getCreatedAtMillis));
        while (deaths.size() >= maxCount) {
            WaypointStore.remove(deaths.remove(0));
        }

        String name = I18nHelper.getComponent("death.name", LocalTime.now().format(TIME_FORMAT)).getString();

        Waypoint wp = new Waypoint(name, pos, DEATH_COLOR, dimension, true, false, true, System.currentTimeMillis());
        WaypointStore.waypoints.add(wp);
        WaypointStore.saveToFile();

        ClientCompat.setOverlayMessage(client, I18nHelper.getComponent("death.created"), true);
    }

    private static void checkArrival(Minecraft client, ModConfig.DeathWaypoints cfg) {
        deathsScratch.clear();
        for (Waypoint wp : WaypointStore.waypoints) {
            // Not one pending deletion: it is already gone from the world, nothing to arrive at.
            if (wp != null && wp.isDeath() && !wp.isPendingRemoval()) {
                deathsScratch.add(wp);
            }
        }

        // Drop dwell timers for deaths that no longer exist (deleted manually, or evicted by a new death).
        dwellStart.keySet().retainAll(deathsScratch);

        if (deathsScratch.isEmpty()) {
            return;
        }

        String currentDimension = WaypointStore.getCurrentDimension();
        BlockPos playerPos = client.player.blockPosition();
        long now = System.currentTimeMillis();

        // Each death waypoint dwells independently, so with maxCount > 1 the player can walk
        // through several at once and each is deleted on its own schedule.
        for (Waypoint deathWp : deathsScratch) {
            String wpDim = deathWp.dimensionOrOverworld();

            double dx = playerPos.getX() - deathWp.getPos().getX();
            double dy = playerPos.getY() - deathWp.getPos().getY();
            double dz = playerPos.getZ() - deathWp.getPos().getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            boolean inRange = wpDim.equals(currentDimension) && distance <= cfg.radius;

            if (!inRange) {
                dwellStart.remove(deathWp);
                continue;
            }

            Long start = dwellStart.get(deathWp);
            if (start == null) {
                // Just entered the radius - start the dwell timer instead of deleting immediately.
                dwellStart.put(deathWp, now);
                continue;
            }

            if ((now - start) >= (long) (cfg.graceSeconds * 1000.0)) {
                WaypointStore.remove(deathWp);
                WaypointStore.saveToFile();
                ClientCompat.setOverlayMessage(client, I18nHelper.getComponent("death.reached"), true);
                dwellStart.remove(deathWp);
            }
        }
    }
}
