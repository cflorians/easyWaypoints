package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the player's death by watching health across client ticks (no mixin
 * needed - the death location doesn't change until the respawn packet arrives,
 * so it's still valid to sample at the tick health first reaches 0) and manages
 * the lifecycle of death waypoints: created on death (evicting the oldest once
 * {@code maxCount} is exceeded), and auto-deleted once the player has stood next
 * to one for {@code graceSeconds} straight.
 */
public class DeathWaypointManager {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEATH_COLOR = 0xFF000000;

    private static boolean wasAlive = true;

    /** Per-waypoint dwell start time, since more than one death waypoint can be in range at once when maxCount > 1. */
    private static final Map<Waypoint, Long> dwellStart = new HashMap<>();

    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            wasAlive = true;
            dwellStart.clear();
            return;
        }

        // Keeps the current world's waypoint list fresh even if the renderer is in
        // DISABLED mode, since WaypointRenderer.render() skips the reload in that case.
        WaypointRenderer.checkAndLoadWorldWaypoints();

        ModConfig.DeathWaypoints cfg = ModConfig.get().deathWaypoints;
        boolean aliveNow = client.player.getHealth() > 0.0f;

        if (!cfg.enabled) {
            wasAlive = aliveNow;
            dwellStart.clear();
            return;
        }

        if (!aliveNow && wasAlive) {
            createDeathWaypoint(client);
        }
        wasAlive = aliveNow;

        // Skipped while dead: the player doesn't control movement on the death screen, so
        // sitting at the death spot before respawning must never count as "arriving".
        if (aliveNow) {
            checkArrival(client, cfg);
        } else {
            dwellStart.clear();
        }
    }

    private static void createDeathWaypoint(Minecraft client) {
        dwellStart.clear();

        // Keep at most (maxCount - 1) existing deaths so the new one fits within the configured cap;
        // the oldest are evicted first. maxCount defaults to 1, reproducing the old "only the latest
        // death survives" behavior.
        int maxCount = Math.max(1, ModConfig.get().deathWaypoints.maxCount);
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp != null && wp.isDeath()) {
                deaths.add(wp);
            }
        }
        deaths.sort(Comparator.comparingLong(Waypoint::getCreatedAtMillis));
        while (deaths.size() >= maxCount) {
            WaypointRenderer.waypoints.remove(deaths.remove(0));
        }

        BlockPos pos = client.player.blockPosition();
        String dimension = client.level.dimension().identifier().toString();
        String name = I18nHelper.getComponent("death.name", LocalTime.now().format(TIME_FORMAT)).getString();

        Waypoint wp = new Waypoint(name, pos, DEATH_COLOR, dimension, false, true, false, true, System.currentTimeMillis());
        WaypointRenderer.waypoints.add(wp);
        WaypointRenderer.saveToFile();

        client.gui.setOverlayMessage(I18nHelper.getComponent("death.created"), true);
    }

    private static void checkArrival(Minecraft client, ModConfig.DeathWaypoints cfg) {
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp != null && wp.isDeath()) {
                deaths.add(wp);
            }
        }

        // Drop dwell timers for deaths that no longer exist (deleted manually, or evicted by a new death).
        dwellStart.keySet().retainAll(deaths);

        if (deaths.isEmpty()) {
            return;
        }

        String currentDimension = client.level.dimension().identifier().toString();
        BlockPos playerPos = client.player.blockPosition();
        long now = System.currentTimeMillis();

        // Each death waypoint dwells independently, so with maxCount > 1 the player can walk
        // through several at once and each is deleted on its own schedule.
        for (Waypoint deathWp : deaths) {
            String wpDim = deathWp.getDimension() != null ? deathWp.getDimension() : "minecraft:overworld";

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
                WaypointRenderer.waypoints.remove(deathWp);
                WaypointRenderer.saveToFile();
                client.gui.setOverlayMessage(I18nHelper.getComponent("death.reached"), true);
                dwellStart.remove(deathWp);
            }
        }
    }
}
