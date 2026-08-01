package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class WaypointHudRenderer implements HudElement {

    // ── Sprites de la barra localizadora de Minecraft (modo LOCATOR_BAR) ──────
    private static final Identifier LOCATOR_BAR_BACKGROUND   = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_background");
    private static final Identifier LOCATOR_BAR_DOT_0        = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_dot/default_0");
    private static final Identifier LOCATOR_BAR_DOT_1        = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_dot/default_1");
    private static final Identifier LOCATOR_BAR_DOT_2        = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_dot/default_2");
    private static final Identifier LOCATOR_BAR_DOT_3        = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_dot/default_3");

    /** Semiángulo máximo del FOV de la barra localizadora (para el modo LOCATOR_BAR). */
    private static final double MAX_FOV = 60.0;

    // ─────────────────────────────────────────────────────────────────────────

    public static void register() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("easywp", "locator_bar_waypoints"),
            new WaypointHudRenderer()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui) return;

        WaypointRenderer.checkAndLoadWorldWaypoints();
        List<Waypoint> waypoints = WaypointRenderer.waypoints;
        if (waypoints.isEmpty()) return;

        // ── Modo LOCATOR_BAR ─────────────────────────────────────────────────
        if (ModKeyBindings.displayMode == WaypointDisplayMode.LOCATOR_BAR) {
            renderLocatorBar(graphics, client, waypoints);
        }
    }

    /**
     * Resuelve la posición del waypoint en la dimensión actual,
     * aplicando la conversión Overworld⟷Nether cuando el waypoint es "shared".
     *
     * @return BlockPos en la dimensión actual, o {@code null} si no corresponde.
     */
    private static BlockPos resolveWaypointPos(Waypoint wp, String wpDim, String currentDim) {
        if (wpDim.equals(currentDim)) {
            return wp.getPos();
        }
        if (!wp.isShared()) return null;

        if (wpDim.equals("minecraft:overworld") && currentDim.equals("minecraft:the_nether")) {
            return new BlockPos(
                (int) Math.round(wp.getPos().getX() / 8.0),
                wp.getPos().getY(),
                (int) Math.round(wp.getPos().getZ() / 8.0)
            );
        }
        if (wpDim.equals("minecraft:the_nether") && currentDim.equals("minecraft:overworld")) {
            return new BlockPos(
                (int) Math.round(wp.getPos().getX() * 8.0),
                wp.getPos().getY(),
                (int) Math.round(wp.getPos().getZ() * 8.0)
            );
        }
        return null;
    }

    // =========================================================================
    //  MODO LOCATOR_BAR (sin cambios respecto al original)
    // =========================================================================

    private void renderLocatorBar(GuiGraphicsExtractor graphics, Minecraft client,
                                  List<Waypoint> waypoints) {
        String currentDimension = client.level.dimension().identifier().toString();

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) { hasAnyFocused = true; break; }
        }

        int screenWidth  = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();
        if (screenWidth <= 0 || screenHeight <= 0) {
            screenWidth  = graphics.guiWidth();
            screenHeight = graphics.guiHeight();
        }

        int centerX = screenWidth / 2;
        int barLeft = centerX - 91;
        int barTop  = screenHeight - 29;

        Vec3 playerPos = client.player.position();
        double playerX = playerPos.x;
        double playerY = playerPos.y;
        double playerZ = playerPos.z;
        float playerYaw = client.player.getYRot();

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        boolean drawnBackground = false;

        for (Waypoint wp : waypoints) {
            if (wp == null || !wp.isVisible()) continue;
            if (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible()) continue;

            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            BlockPos wpPos = resolveWaypointPos(wp, wpDim, currentDimension);
            if (wpPos == null) continue;

            if (!drawnBackground) {
                graphics.blitSprite(pipeline, LOCATOR_BAR_BACKGROUND, barLeft, barTop, 182, 5);
                drawnBackground = true;
            }

            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY() + 0.5;
            double targetZ = wpPos.getZ() + 0.5;

            double dx = targetX - playerX;
            double dy = targetY - playerY;
            double dz = targetZ - playerZ;

            double distanceSq = dx * dx + dy * dy + dz * dz;
            double targetYaw  = Math.toDegrees(Math.atan2(-dx, dz));
            float diff        = Mth.wrapDegrees((float) (targetYaw - playerYaw));

            if (Math.abs(diff) > MAX_FOV) continue;

            int offset = (int) Math.floor((diff * 173.0) / 120.0);
            int dotX   = centerX + offset - 4;

            int color = 0xFF000000 | wp.getColor();

            Identifier dotSprite;
            if (distanceSq < 16384.0) {
                dotSprite = LOCATOR_BAR_DOT_0;
            } else if (distanceSq < 65536.0) {
                dotSprite = LOCATOR_BAR_DOT_1;
            } else if (distanceSq < 1048576.0) {
                dotSprite = LOCATOR_BAR_DOT_2;
            } else {
                dotSprite = LOCATOR_BAR_DOT_3;
            }

            graphics.blitSprite(pipeline, dotSprite, dotX, barTop - 2, 9, 9, color);
        }
    }
}
