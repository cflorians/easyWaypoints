package com.easywp.client;

import com.easywp.EasyWp;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the waypoints. Their storage lives in {@link WaypointStore}.
 *
 * <h2>Why the waypoints are drawn on the HUD, not in the world</h2>
 *
 * Every waypoint - marker icon, label backdrop and label text - is projected from its world
 * position to screen coordinates and drawn in 2D during the HUD pass, the same way Xaero's
 * Minimap draws its in-world waypoints. Each of the three earlier in-world designs failed on
 * something this one cannot fail on, by construction:
 * <ul>
 * <li><b>Translucent geometry.</b> A label drawn in the world either writes depth - and then
 *     water, clouds, ice and glass drawn later fail their depth test behind it and simply vanish,
 *     leaving a hole that shows the terrain behind them - or it does not, and those same
 *     translucents paint over it. The HUD is composited after the whole world, so there is no
 *     depth buffer shared with the world at all and neither can happen.</li>
 * <li><b>Shaderpacks.</b> Anything drawn during the world pass is handed to Iris, which swaps in
 *     its own gbuffers programs: lighting, fog and tone mapping turned white text grey. The HUD is
 *     drawn after Iris's final pass, so it needs no Iris detection or special-casing whatsoever.</li>
 * <li><b>Alignment.</b> Backdrop and text are now one pose, one transform: the box is sized from
 *     the same glyph scale the text is drawn with, so the two cannot drift apart anywhere on the
 *     screen (the old shader-mode depth gap between them did exactly that toward the edges).</li>
 * </ul>
 * The trade-off is that the waypoint is now always visible, including through terrain - which is
 * what the see-through in-world version was doing anyway.
 *
 * <h2>Where the projection comes from</h2>
 *
 * {@code GameRenderer.extract} fills the frame's {@link CameraRenderState} - interpolated
 * position, rotation and the FOV-bearing projection matrix, dynamic FOV (sprint, spyglass, zoom
 * mods) included - <em>before</em> it extracts the GUI, so the HUD pass reads exactly the camera
 * the world is about to be drawn with this frame: no one-frame lag, no jitter while moving. What
 * the camera state does not carry is the view bob and hurt tilt: {@code renderLevel} multiplies
 * them into a local copy of the projection. {@link #applyBob} reproduces that multiplication by
 * calling the very same two vanilla methods, so the clip transform here is vanilla's own
 * {@code projection * bobHurt * bobView * viewRotation}. The nausea/portal screen warp is not
 * reproduced - it is a full-screen distortion, not a camera move.
 */
public class WaypointRenderer {
    private static final int LABEL_TEXT_COLOR = 0xFFFFFFFF;

    // Marker sizing in world units at the waypoint's (clamped) distance: a fixed minimum up close,
    // growing linearly with distance past WAYPOINT_GROWTH_START_DIST, which keeps a far waypoint at
    // a constant apparent (angular) size of WAYPOINT_VISUAL_ANGLE radians.
    private static final float WAYPOINT_VISUAL_ANGLE        = 0.055f;
    private static final float WAYPOINT_MIN_SIZE            = 0.25f;
    private static final float WAYPOINT_MAX_SIZE            = 500.0f;
    private static final float WAYPOINT_GROWTH_START_DIST   = 2.0f;

    /** waypoint_marker.png is 5x7 texels; the marker is drawn at that aspect. */
    private static final float MARKER_TEX_W = 5.0f;
    private static final float MARKER_TEX_H = 7.0f;

    /**
     * One label font pixel, as a fraction of the marker height. The label is 9 font pixels tall,
     * so the text is 0.45x the marker's height - the ratio the in-world renderer always drew, and
     * the one {@code WaypointCreateScreen}'s preview reproduces.
     */
    private static final float LABEL_GLYPH_PER_MARKER = 0.05f;

    /** Gap between the marker's top and the backdrop's bottom, as a fraction of the marker height. */
    private static final float LABEL_GAP_PER_MARKER = 0.05f;

    /*
     * "Looking at it": a waypoint the player is not aiming at is drawn at
     * ModConfig.lookFade.unlookedOpacityPercent (marker, backdrop and text alike), so a screen full
     * of waypoints does not compete with the one being aimed at. Aiming is judged against the
     * waypoint's drawn rectangle (marker plus label), expanded by a margin: full opacity while the
     * crosshair is inside it, then a smooth fade over the band around it. Measuring against the
     * rectangle rather than the waypoint's point means a near waypoint whose icon fills half the
     * screen counts as looked at anywhere on it, and a far one still has a comfortable target.
     *
     * The margins are fractions of the screen height, narrower horizontally than vertically: the
     * label is already wide, and a wide margin on top of it made a waypoint "looked at" from well
     * off to its side. ModConfig.lookFade.lookAreaSize (1-7, default 3) scales both.
     */
    private static final float LOOK_MARGIN_X = 0.015f;
    private static final float LOOK_MARGIN_Y = 0.04f;
    /** Width of the fade band past the margins, same unit, so sweeping the view across a waypoint
     *  eases it in and out instead of popping. */
    private static final float LOOK_FADE_X = 0.03f;
    private static final float LOOK_FADE_Y = 0.06f;
    /** Margin multiplier for each lookAreaSize setting, 1 to 7; setting 2 is the base margins above (the default, 3, is 1.5x). */
    private static final float[] LOOK_AREA_SCALE = {0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f};

    /** Clip-space w below which a point is treated as on or behind the camera plane. */
    private static final float MIN_CLIP_W = 1.0e-4f;

    private static final Matrix4f clipMatrix = new Matrix4f();
    private static final Vector4f clipPos = new Vector4f();
    private static final PoseStack bobStack = new PoseStack();

    // Method handles rather than Method.invoke: called every frame, and invoke boxed its arguments
    // into a fresh array and went through the reflective access check on each call.
    private static MethodHandle bobHurt;
    private static MethodHandle bobView;
    private static boolean bobLookupDone;

    private static class WaypointHolder {
        Waypoint waypoint;
        double realDistance;
        float screenX;
        float screenY;
        float markerHeight;
    }

    private static final List<WaypointHolder> activeWaypoints = new ArrayList<>();
    private static final List<WaypointHolder> holderPool = new ArrayList<>();

    /**
     * HUD entry point while the HUD is showing. Registered before vanilla's first HUD element, so
     * the waypoints sit under the vignette, spyglass scope, crosshair, hotbar and chat - drawn as
     * if they were part of the world, the way the in-world renderer layered them.
     */
    public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!ClientCompat.isHudHidden(Minecraft.getInstance())) {
            render(graphics);
        }
    }

    /**
     * HUD entry point while the HUD is hidden (F1). Every vanilla HUD element Fabric can attach to
     * is skipped when hidden except the sleep overlay, so this one rides that - which, with
     * nothing else of the HUD drawn before it, is still the bottom of the stack. Keeps the
     * waypoints visible under F1, as the in-world renderer always was.
     */
    public static void renderHudWhileHidden(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (ClientCompat.isHudHidden(Minecraft.getInstance())) {
            render(graphics);
        }
    }

    private static void render(GuiGraphicsExtractor graphics) {
        if (ModKeyBindings.displayMode != WaypointDisplayMode.WORLD_MARKERS) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        WaypointStore.checkAndLoadWorldWaypoints();

        CameraRenderState cameraState = ClientCompat.cameraRenderState(client);
        if (cameraState == null || !cameraState.initialized
                || cameraState.projectionMatrix == null || cameraState.viewRotationMatrix == null) return;

        Vec3 cameraPos = cameraState.pos;
        String currentDimension = WaypointStore.getCurrentDimension();

        // Clip transform for this frame: vanilla's projection * bob * view rotation. World
        // positions are fed in relative to the camera, as LevelRenderer itself does.
        bobStack.setIdentity();
        applyBob(client.gameRenderer, cameraState, client.options.bobView().get());
        clipMatrix.set(cameraState.projectionMatrix)
                .mul(bobStack.last().pose())
                .mul(cameraState.viewRotationMatrix);

        int guiWidth = graphics.guiWidth();
        int guiHeight = graphics.guiHeight();
        // Screen height of one world unit one block in front of the camera, in GUI units. Divided
        // by a point's depth (clip w) this is the perspective scale at that point.
        float unitHeight = cameraState.projectionMatrix.m11() * guiHeight * 0.5f;

        boolean hasAnyFocused = WaypointStore.hasAnyFocused();

        float sizeScale = (float) (ModConfig.get().waypointSize.sizePercent / 100.0);
        double maxRenderDist = Math.max(32.0, client.options.getEffectiveRenderDistance() * 16.0 - 16.0);

        activeWaypoints.clear();
        int poolIndex = 0;

        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp == null || !wp.isShown(hasAnyFocused)) continue;

            BlockPos wpPos = wp.positionIn(currentDimension);
            if (wpPos == null) continue;

            double dx = wpPos.getX() + 0.5 - cameraPos.x;
            double dy = wpPos.getY() - cameraPos.y;
            double dz = wpPos.getZ() + 0.5 - cameraPos.z;
            double realDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (realDistance < 0.001) continue;

            clipPos.set((float) dx, (float) dy, (float) dz, 1.0f).mul(clipMatrix);
            float w = clipPos.w;
            if (w <= MIN_CLIP_W) continue; // behind the camera

            float screenX = (clipPos.x / w * 0.5f + 0.5f) * guiWidth;
            float screenY = (0.5f - clipPos.y / w * 0.5f) * guiHeight;

            // Beyond the render distance the marker stops shrinking: it is sized as if it stood at
            // clampDist along the same ray, which is what the in-world renderer's distance clamp did.
            double clampDist = Math.min(realDistance, maxRenderDist);
            float clampDepth = (float) (w * clampDist / realDistance);
            double growthDist = Math.max(0.0, clampDist - WAYPOINT_GROWTH_START_DIST);
            float baseSize = (float) Mth.clamp(
                    WAYPOINT_MIN_SIZE + WAYPOINT_VISUAL_ANGLE * growthDist,
                    WAYPOINT_MIN_SIZE,
                    WAYPOINT_MAX_SIZE
            ) * sizeScale;
            float markerHeight = baseSize * unitHeight / clampDepth;

            // Whole waypoint off screen: marker below/at the anchor, label a little over 1.5x the
            // marker height above it. Generous on purpose - a false skip is a visible bug.
            float reach = markerHeight * 2.0f + 64.0f;
            if (screenX < -reach || screenX > guiWidth + reach
                    || screenY < -reach || screenY > guiHeight + reach) continue;

            WaypointHolder holder;
            if (poolIndex < holderPool.size()) {
                holder = holderPool.get(poolIndex);
            } else {
                holder = new WaypointHolder();
                holderPool.add(holder);
            }
            poolIndex++;

            holder.waypoint = wp;
            holder.realDistance = realDistance;
            holder.screenX = screenX;
            holder.screenY = screenY;
            holder.markerHeight = markerHeight;
            activeWaypoints.add(holder);
        }

        if (activeWaypoints.isEmpty()) return;

        // Farther waypoints first, so nearer ones are drawn over them.
        activeWaypoints.sort((a, b) -> Double.compare(b.realDistance, a.realDistance));

        Font font = client.font;
        boolean uppercaseLabels = ModConfig.get().labelDisplay.uppercase;
        boolean showDistance = ModConfig.get().labelDisplay.showDistance;
        int markerAlpha = (int) Math.round(255 * Mth.clamp(ModConfig.get().waypointSize.opacityPercent / 100.0, 0.0, 1.0));
        ModConfig.LookFade lookCfg = ModConfig.get().lookFade;
        float unlookedOpacity = (float) Mth.clamp(lookCfg.unlookedOpacityPercent / 100.0, 0.0, 1.0);
        float lookAreaScale = LOOK_AREA_SCALE[Mth.clamp(lookCfg.lookAreaSize, 1, LOOK_AREA_SCALE.length) - 1];

        for (WaypointHolder holder : activeWaypoints) {
            Waypoint wp = holder.waypoint;
            String nameText = labelText(font, wp, uppercaseLabels, showDistance, holder.realDistance);
            int textWidth = (int) wp.getCachedLabelWidth();

            LabelLayout label = labelLayout(holder, textWidth);
            // A focused (highlighted) waypoint is the one the player asked to follow, so it stays
            // fully opaque wherever it is on screen, for as long as it stays focused.
            float opacity = wp.isFocused()
                    ? 1.0f
                    : lookOpacity(holder, label, textWidth, guiWidth, guiHeight, unlookedOpacity, lookAreaScale);

            drawMarker(graphics, holder, (Math.round(markerAlpha * opacity) << 24) | (wp.getColor() & 0xFFFFFF));
            drawLabel(graphics, font, label, nameText, textWidth, opacity);

            // Within one stratum the GUI draws every text after every quad, so without this a
            // farther waypoint's text would land on top of a nearer waypoint's marker and
            // backdrop. It also keeps the vanilla HUD drawn after this (vignette, scope,
            // crosshair...) above the last label.
            graphics.nextStratum();
        }
    }

    /**
     * The label string, rebuilt and re-measured only when one of its inputs changed - the name,
     * the uppercase setting, the distance toggle or the displayed whole-metre distance.
     */
    private static String labelText(Font font, Waypoint wp, boolean uppercase, boolean showDistance, double realDistance) {
        String wpName = wp.getName() != null ? wp.getName() : "Waypoint";
        int distanceMeters = showDistance ? (int) realDistance : 0;
        if (wp.labelCacheMatches(wpName, uppercase, showDistance, distanceMeters)) {
            return wp.getCachedLabelText();
        }
        // Uppercased before the suffix is appended, not after: the trailing "m" is a unit symbol
        // and stays lowercase whatever the label styling says.
        String nameText = uppercase ? wpName.toUpperCase(Locale.ROOT) : wpName;
        if (showDistance) {
            nameText = nameText + " (" + distanceMeters + "m)";
        }
        wp.setCachedLabel(wpName, uppercase, showDistance, distanceMeters, nameText, font.width(nameText));
        return nameText;
    }

    /** The marker icon, bottom-centred on the waypoint's projected position. */
    private static void drawMarker(GuiGraphicsExtractor graphics, WaypointHolder holder, int argb) {
        float scale = holder.markerHeight / MARKER_TEX_H;
        float width = MARKER_TEX_W * scale;

        graphics.pose().pushMatrix();
        graphics.pose().translate(holder.screenX - width * 0.5f, holder.screenY - holder.markerHeight);
        graphics.pose().scale(scale, scale);
        ClientCompat.blitTinted(graphics, Icons.WAYPOINT_MARKER, 0, 0, (int) MARKER_TEX_W, (int) MARKER_TEX_H, argb);
        graphics.pose().popMatrix();
    }

    /**
     * The label: backdrop and text drawn under one pose, so they share every transform and cannot
     * come apart. The backdrop is Font's own background box in glyph space (x-1..x+width,
     * y-1..y+9), centred over the marker.
     *
     * <p>The glyph scale is a continuous fraction of the marker height, and the origin is not
     * snapped. Rounding the scale to whole physical pixels per font pixel (and the origin to the
     * pixel grid) was tried in 1.3.4 for crisper glyphs and reverted: the label then grew in
     * visible steps, lost its fixed ratio to the marker, and moved in 1px jumps while the marker
     * moved smoothly.
     */
    private static void drawLabel(GuiGraphicsExtractor graphics, Font font, LabelLayout label,
                                  String text, int textWidth, float opacity) {
        int backdrop = scaleAlpha(UiPalette.LABEL_BACKDROP, opacity);
        int textColor = scaleAlpha(LABEL_TEXT_COLOR, opacity);

        graphics.pose().pushMatrix();
        graphics.pose().translate(label.textX, label.textY);
        graphics.pose().scale(label.glyph, label.glyph);
        graphics.fill(-1, -1, textWidth, 9, backdrop);
        graphics.text(font, text, 0, 0, textColor, false);
        graphics.pose().popMatrix();
    }

    /** Where the label goes: its glyph scale and the top-left of its text, in GUI units. */
    private record LabelLayout(float glyph, float textX, float textY) { }

    private static LabelLayout labelLayout(WaypointHolder holder, int textWidth) {
        float glyph = LABEL_GLYPH_PER_MARKER * holder.markerHeight;
        float backdropBottom = holder.screenY - holder.markerHeight * (1.0f + LABEL_GAP_PER_MARKER);
        return new LabelLayout(glyph,
                holder.screenX - (textWidth - 1) * glyph * 0.5f,
                backdropBottom - 9.0f * glyph);
    }

    /**
     * 1 while the crosshair (the screen centre) is on or near the waypoint's drawn area - marker
     * and label backdrop together - easing down to {@code unlookedOpacity} as it moves away. See
     * the LOOK_* constants.
     */
    private static float lookOpacity(WaypointHolder holder, LabelLayout label, int textWidth,
                                     int guiWidth, int guiHeight, float unlookedOpacity, float areaScale) {
        float markerHalfWidth = holder.markerHeight * MARKER_TEX_W / MARKER_TEX_H * 0.5f;
        float left = Math.min(holder.screenX - markerHalfWidth, label.textX - label.glyph);
        float right = Math.max(holder.screenX + markerHalfWidth, label.textX + textWidth * label.glyph);
        float top = label.textY - label.glyph;
        float bottom = holder.screenY;

        float centreX = guiWidth * 0.5f;
        float centreY = guiHeight * 0.5f;
        // How far the crosshair is outside the rectangle on each axis, then outside the margin, in
        // fade widths - so the fade band is an ellipse around the margin box, narrower sideways.
        float dx = Math.max(0.0f, Math.max(left - centreX, centreX - right)) / guiHeight;
        float dy = Math.max(0.0f, Math.max(top - centreY, centreY - bottom)) / guiHeight;
        float fx = Math.max(0.0f, dx - LOOK_MARGIN_X * areaScale) / LOOK_FADE_X;
        float fy = Math.max(0.0f, dy - LOOK_MARGIN_Y * areaScale) / LOOK_FADE_Y;

        float t = Mth.clamp((float) Math.sqrt(fx * fx + fy * fy), 0.0f, 1.0f);
        t = t * t * (3.0f - 2.0f * t); // smoothstep: no visible kink where the fade starts or ends
        return 1.0f - t * (1.0f - unlookedOpacity);
    }

    /** {@code argb} with its alpha multiplied by {@code factor}. */
    private static int scaleAlpha(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * factor);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /**
     * Applies vanilla's hurt tilt and (when enabled) view bob to {@link #bobStack} by calling
     * {@code GameRenderer.bobHurt}/{@code bobView} themselves - the two private methods
     * {@code renderLevel} folds into the projection. Both are pure functions of the frame's camera
     * render state, which is already extracted by the time the HUD is, so calling them here yields
     * exactly the matrix the world is drawn with. Reflective because they are private; if the
     * lookup ever fails the waypoints are simply projected without bob (a few pixels of sway while
     * walking), logged once, never a crash.
     *
     * <p>Iris moves the bob from the projection to the model-view while a shaderpack is active,
     * which produces the same image, so this needs no shader special case.
     */
    private static void applyBob(GameRenderer gameRenderer, CameraRenderState cameraState, boolean bobViewEnabled) {
        if (!bobLookupDone) {
            bobLookupDone = true;
            try {
                bobHurt = privateHandle("bobHurt");
                bobView = privateHandle("bobView");
            } catch (ReflectiveOperationException | RuntimeException e) {
                bobHurt = null;
                bobView = null;
                EasyWp.LOGGER.warn("Could not find GameRenderer's view bob; waypoints are projected without it", e);
            }
        }
        if (bobHurt == null || cameraState.entityRenderState == null) return;

        try {
            // invokeExact: the static argument types here are exactly the handles' own
            // (GameRenderer, CameraRenderState, PoseStack)void, so nothing is boxed or adapted.
            bobHurt.invokeExact(gameRenderer, cameraState, bobStack);
            if (bobViewEnabled) {
                bobView.invokeExact(gameRenderer, cameraState, bobStack);
            }
        } catch (Throwable e) {
            // Throwable, as Method.invoke effectively caught before: it wrapped anything the target
            // threw, Errors included, in an InvocationTargetException.
            bobHurt = null;
            bobView = null;
            bobStack.setIdentity();
            EasyWp.LOGGER.warn("GameRenderer's view bob failed; waypoints are projected without it from now on", e);
        }
    }

    /** A handle on one of GameRenderer's private {@code (CameraRenderState, PoseStack)} bob
     *  methods. Looked up through reflection and {@code setAccessible}, exactly as before, and only
     *  then turned into a handle - {@code unreflect} honours the accessibility override, so this
     *  works wherever the reflective lookup did. */
    private static MethodHandle privateHandle(String name) throws ReflectiveOperationException {
        Method method = GameRenderer.class.getDeclaredMethod(name, CameraRenderState.class, PoseStack.class);
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method);
    }
}
