package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Crosshair targeting for the ping keybind.
 *
 * <p>Runs exactly once per key press, never per frame. Vanilla already performs a strictly more
 * expensive raycast - the same block clip plus a full entity sweep - twice per frame and tick,
 * forever, just to decide which block the crosshair highlights. One longer, block-only ray on
 * demand does not register.
 *
 * <p>{@code BlockGetter.traverseBlocks} underneath is an Amanatides and Woo voxel DDA: it visits
 * only the blocks the ray actually pierces, so cost grows linearly with range rather than with
 * the enclosed volume, and it reuses a single MutableBlockPos for the whole walk.
 *
 * <p>Rays leaving the loaded area are safe: a client level reports void air for unloaded chunks
 * and never loads or generates terrain, so a range set past the render distance simply misses.
 * That's also why {@link ModConfig.Ping#followRenderDistance} is a sound option: pinned to the
 * client's own view distance, the ray can never overshoot into guaranteed-empty space.
 */
public final class WaypointPing {

    private WaypointPing() { }

    /**
     * The block the player is looking at, or - when the ray reaches its end without hitting
     * anything - the block at the far end of the ray, so callers always get a usable position.
     */
    public static BlockPos target(Minecraft client) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.level == null) {
            // Callers already guard on both; this only keeps the method total.
            return BlockPos.ZERO;
        }

        ModConfig.Ping cfg = ModConfig.get().ping;
        double maxDistance = cfg.followRenderDistance
                ? client.options.getEffectiveRenderDistance() * 16.0
                : cfg.maxDistance;

        Vec3 eye = camera.getEyePosition(1.0f);
        Vec3 end = eye.add(camera.getViewVector(1.0f).scale(maxDistance));

        BlockHitResult hit = client.level.clip(new ClipContext(
                eye,
                end,
                // VISUAL tests getVisualShape, so the ray ignores foliage, torches and glass -
                // the clutter a player does not mean to point at. OUTLINE (what vanilla's own
                // crosshair uses) would stop dead on a flower a hundred blocks out.
                ClipContext.Block.VISUAL,
                cfg.hitFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE,
                camera
        ));

        // A miss still carries a position: BlockGetter.clip falls back to
        // BlockHitResult.miss(end, ..., BlockPos.containing(end)), which is already the
        // "mark the end of the ray" behaviour we want, so there is no miss branch here.
        return hit.getBlockPos();
    }
}
