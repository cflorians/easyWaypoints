package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Detects the player's death by watching health across client ticks (no mixin
 * needed - the death location doesn't change until the respawn packet arrives,
 * so it's still valid to sample at the tick health first reaches 0) and manages
 * the lifecycle of the single temporary death waypoint: created on death,
 * replacing any previous one, and auto-deleted once the player has stood next
 * to it for {@code graceSeconds} straight.
 */
public class DeathWaypointManager {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DEATH_COLOR = 0xFFFF5555;

    private static boolean wasAlive = true;

    /** Which death waypoint the player is currently standing next to, and since when. */
    private static Waypoint dwellWaypoint = null;
    private static long dwellStartMillis = 0L;

    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            wasAlive = true;
            dwellWaypoint = null;
            return;
        }

        // Keeps the current world's waypoint list fresh even if the renderer is in
        // DISABLED mode, since WaypointRenderer.render() skips the reload in that case.
        WaypointRenderer.checkAndLoadWorldWaypoints();

        ModConfig.DeathWaypoints cfg = ModConfig.get().deathWaypoints;
        boolean aliveNow = client.player.getHealth() > 0.0f;

        if (!cfg.enabled) {
            wasAlive = aliveNow;
            dwellWaypoint = null;
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
            dwellWaypoint = null;
        }
    }

    private static void createDeathWaypoint(Minecraft client) {
        WaypointRenderer.waypoints.removeIf(Waypoint::isDeath);
        dwellWaypoint = null;

        BlockPos pos = client.player.blockPosition();
        String dimension = client.level.dimension().identifier().toString();
        String name = I18nHelper.getComponent("death.name", LocalTime.now().format(TIME_FORMAT)).getString();

        Waypoint wp = new Waypoint(name, pos, DEATH_COLOR, dimension, false, true, false, true, System.currentTimeMillis());
        WaypointRenderer.waypoints.add(wp);
        WaypointRenderer.saveToFile();

        client.gui.setOverlayMessage(I18nHelper.getComponent("death.created"), true);
    }

    private static void checkArrival(Minecraft client, ModConfig.DeathWaypoints cfg) {
        Waypoint deathWp = null;
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp != null && wp.isDeath()) {
                deathWp = wp;
                break;
            }
        }

        if (deathWp == null) {
            dwellWaypoint = null;
            return;
        }

        String wpDim = deathWp.getDimension() != null ? deathWp.getDimension() : "minecraft:overworld";
        String currentDimension = client.level.dimension().identifier().toString();
        BlockPos playerPos = client.player.blockPosition();

        double dx = playerPos.getX() - deathWp.getPos().getX();
        double dy = playerPos.getY() - deathWp.getPos().getY();
        double dz = playerPos.getZ() - deathWp.getPos().getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        boolean inRange = wpDim.equals(currentDimension) && distance <= cfg.radius;
        long now = System.currentTimeMillis();

        if (!inRange) {
            dwellWaypoint = null;
            return;
        }

        if (dwellWaypoint != deathWp) {
            // Just entered the radius - start the dwell timer instead of deleting immediately.
            dwellWaypoint = deathWp;
            dwellStartMillis = now;
            return;
        }

        if ((now - dwellStartMillis) >= (long) (cfg.graceSeconds * 1000.0)) {
            WaypointRenderer.waypoints.remove(deathWp);
            WaypointRenderer.saveToFile();
            client.gui.setOverlayMessage(I18nHelper.getComponent("death.reached"), true);
            dwellWaypoint = null;
        }
    }
}
