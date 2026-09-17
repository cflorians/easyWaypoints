package com.easywp.client;

import com.easywp.EasyWp;
import com.easywp.JsonStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Handles 3D world rendering and JSON storage for waypoints.
 */
public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();

    /** Reused across loads and saves - Gson instances are safe to share and hold no per-call state. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Label backdrop, ARGB. Same value Font uses for the box in the 26.1 renderer. */
    private static final int LABEL_BACKDROP = 0x40000000;

    private static final int FULL_BRIGHT    = 0xF000F0;

    /** Submit order for the label text, so it is drawn after our backdrop quad. */
    private static final int LABEL_TEXT_ORDER = 1;

    /**
     * How far behind the glyphs the backdrop sits, in billboard local units (+Z points away from
     * the viewer), used only while a shaderpack is active. Under a shaderpack both halves of the
     * label ride a real depth-testing pipeline ({@code textPolygonOffset}, see
     * {@link WaypointRenderTypes#labelBackdrop}), so leaving them exactly coplanar makes them
     * z-fight and the text flickers as the camera moves.
     *
     * <p>Applying this gap in vanilla mode too was tried and reverted: {@link WaypointRenderTypes}'s
     * vanilla-mode pipeline tests depth as {@code ALWAYS_PASS} for both the backdrop and (via
     * vanilla's own see-through text) the glyphs, so neither ever depth-tests against the other -
     * z-fighting needs both sides of a pair to actually compare depth, not just one of them to
     * write it, and the backdrop writing a value nothing compares against has no flicker to cause.
     * The gap has no z-fighting to prevent in vanilla mode, so it only costs accuracy there. The
     * label's own billboard sits only
     * {@link #LABEL_PROJECTION_DIST} (0.25 blocks) from the eye, and that distance is itself a
     * camera-space depth of {@code LABEL_PROJECTION_DIST * cos(θ)} where θ is the angle between the
     * view direction and the ray to the waypoint - shrinking toward zero as a waypoint drifts from
     * the crosshair toward the edge of the screen. A depth gap that is a fixed absolute value
     * becomes a growing fraction of that shrinking depth, and since the backdrop and the glyphs
     * then divide by different depths in the perspective projection, the two drift apart on screen
     * - the backdrop pulls toward the screen centre relative to the text, worse the further off
     * centre the waypoint is. Gating the gap on shader-mode removes the drift for every player who
     * has no shaderpack loaded, which is the common case, without touching the shader-mode pipeline
     * this constant exists for.
     */
    private static final float LABEL_DEPTH_GAP = 0.005f;

    // Scaling constants for angular distance sizing
    private static final float WAYPOINT_VISUAL_ANGLE        = 0.055f;
    private static final float WAYPOINT_MIN_SIZE            = 0.25f;
    private static final float WAYPOINT_MAX_SIZE            = 500.0f;
    private static final float WAYPOINT_GROWTH_START_DIST   = 2.0f;
    private static final float MARKER_ASPECT_FACTOR         = 5.0f / 14.0f; // Pre-calculated (5/7) / 2

    // Distance the billboard is actually drawn at, short enough to stay out of any fog ramp
    private static final double MARKER_PROJECTION_DIST = 4.0;

    /**
     * Half the player's collision box width (the box is 0.6 blocks wide), so the closest a solid
     * block face can ever get to the eye - standing flush against a wall - is this far away.
     */
    private static final double PLAYER_WALL_CLEARANCE = 0.3;

    /**
     * Distance the label is drawn at, closer than {@link #PLAYER_WALL_CLEARANCE} on purpose: under
     * a shaderpack {@code POLYGON_OFFSET} depth-tests like terrain (see {@code labelMode} below),
     * and a billboard projected nearer than any block the player's own hitbox can physically touch
     * can never lose that test - the label stops disappearing at any distance, not just past some
     * chosen threshold.
     *
     * <p>The cost is the same one this value has always carried: pulling the billboard in shrinks
     * the glyph quads by the same factor perspective divides back out on screen, and Iris derives
     * the tangents and mid-texture coordinates its programs light with from those quad deltas. A
     * quarter of a block (4.0, a 16x pull-in) was tried once and the letters came back grey; this
     * is a smaller pull-in than that, but the margin is not large - watch for the same symptom.
     */
    private static final double LABEL_PROJECTION_DIST = PLAYER_WALL_CLEARANCE - 0.05;

    // Behind-the-camera cull: skips a waypoint's marker-size math and draw calls when it is far
    // enough out of view that it cannot be on screen, without measuring its actual projected
    // position. Distance-gated so a marker the player is standing right next to - where the
    // direction to it can point anywhere - is never culled by the angle check alone. The dot
    // threshold is deliberately generous (root: cos(120deg)) to clear even the widest vanilla FOV
    // plus dynamic-FOV effects with margin, since a false cull is a visible bug and a missed cull
    // just costs the perf win.
    private static final double CULL_MIN_DISTANCE = 8.0;
    private static final float CULL_DOT_THRESHOLD = -0.5f;

    private static String lastWorldId = "";
    private static Object lastCheckedLevel = null;

    /** Refreshed only alongside {@link #lastCheckedLevel} - see {@link #checkAndLoadWorldWaypoints()}. */
    private static String cachedDimension = "minecraft:overworld";

    /** Per-frame view bob translation, converted from camera space to world axes. */
    private static final Vector3f bobOffset = new Vector3f();

    /** Per-frame camera forward direction in world space, used only for the behind-camera cull. */
    private static final Vector3f cameraForward = new Vector3f();

    /**
     * Mirrors the translation half of {@code GameRenderer.bobView}.
     *
     * <p>Vanilla folds the bob into the projection matrix and Iris moves it to the model view,
     * but either way it ends up as {@code position = bobTranslation + bobRotation * cameraPos},
     * so the translation is added once, in camera space, regardless of how far the geometry is.
     * Only the translation matters here: the two rotations the bob also applies pivot around the
     * eye and therefore move near and far geometry by the same angle.
     */
    private static void computeBobOffset(Minecraft client, CameraRenderState cameraState) {
        CameraEntityRenderState eye = cameraState.entityRenderState;
        if (!eye.isPlayer || !client.options.bobView().get()) {
            bobOffset.set(0.0f, 0.0f, 0.0f);
            return;
        }

        float walkDistance = eye.backwardsInterpolatedWalkDistance;
        float bob = eye.bob;
        bobOffset.set(
                Mth.sin(walkDistance * (float) Math.PI) * bob * 0.5f,
                -Math.abs(Mth.cos(walkDistance * (float) Math.PI) * bob),
                0.0f
        );
        // Camera orientation maps camera local axes (X right, Y up, Z backwards) onto world axes,
        // which is the space the render offsets below are expressed in.
        bobOffset.rotate(cameraState.orientation);
    }

    private static class WaypointHolder {
        Waypoint waypoint;
        double realDistance;
        double markerX;
        double markerY;
        double markerZ;
        float markerSize;
        double labelX;
        double labelY;
        double labelZ;
        float labelSize;

        public void setMarker(double x, double y, double z, float size) {
            this.markerX = x;
            this.markerY = y;
            this.markerZ = z;
            this.markerSize = size;
        }

        public void setLabel(double x, double y, double z, float size) {
            this.labelX = x;
            this.labelY = y;
            this.labelZ = z;
            this.labelSize = size;
        }
    }

    private static final List<WaypointHolder> activeWaypoints = new ArrayList<>();
    private static final List<WaypointHolder> holderPool = new ArrayList<>();

    /**
     * Slides a billboard down its own view ray to {@code targetDist} and shrinks it by the same
     * factor. A perspective projection divides by z, so scaling every camera space coordinate by
     * one factor leaves the on-screen result untouched - but the geometry now sits close to the
     * eye instead of at the fog wall, and per-vertex fog (vanilla's, and the one a shaderpack
     * applies inside its gbuffers program) is a function of that distance. Depth follows too,
     * which is what keeps the depth tested half of the label out of nearby geometry. Small
     * coordinates also help float precision.
     *
     * <p>One caveat: the view bob adds a fixed camera space offset to the whole scene, and that
     * offset is NOT scaled by the shrink, so it survives as parallax - harmless at 144 blocks, a
     * visible sway up close. Feeding back {@code (shrink - 1)} times the bob puts the billboard
     * exactly where the unshrunk one would have landed.
     */
    private static void project(WaypointHolder holder, boolean isMarker,
                                double dirX, double dirY, double dirZ,
                                double clampDist, float baseSize, double targetDist) {
        double dist = Math.min(clampDist, targetDist);
        double shrink = dist / clampDist;
        double bobFix = shrink - 1.0;

        double x = dirX * dist + bobOffset.x * bobFix;
        double y = dirY * dist + bobOffset.y * bobFix;
        double z = dirZ * dist + bobOffset.z * bobFix;
        float size = baseSize * (float) shrink;

        if (isMarker) {
            holder.setMarker(x, y, z, size);
        } else {
            holder.setLabel(x, y, z, size);
        }
    }

    /**
     * Collects the 3D billboards for the waypoints.
     *
     * <p>Runs during the submit phase rather than drawing directly: since 26.2 the level render
     * context no longer hands out a buffer source, world geometry is queued through the submit
     * node collector and drawn later by the feature renderers. 26.3 keeps that path unchanged -
     * {@code LevelRenderContext}, {@code SubmitNodeCollector.submitCustomGeometry}/{@code submitText}
     * and the order keys all kept their 26.2 signatures - so this method is 26.2's, with one
     * rename: {@code PoseStack.mulPose(Quaternionf)} became {@code rotateDegrees(Axis, float)}.
     */
    public static void render(LevelRenderContext context) {
        if (ModKeyBindings.displayMode != WaypointDisplayMode.WORLD_MARKERS) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        checkAndLoadWorldWaypoints();

        Font font = client.font;
        CameraRenderState cameraState = context.levelState().cameraRenderState;
        if (cameraState == null || !cameraState.initialized) return;

        Vec3 cameraPos = cameraState.pos;
        PoseStack poseStack = context.poseStack();
        SubmitNodeCollector collector = context.submitNodeCollector();
        String currentDimension = cachedDimension;

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        computeBobOffset(client, cameraState);
        cameraForward.set(0.0f, 0.0f, -1.0f).rotate(cameraState.orientation);

        float sizeScale = (float) (ModConfig.get().waypointSize.sizePercent / 100.0);
        boolean uppercaseLabels = ModConfig.get().labelDisplay.uppercase;
        boolean showDistance = ModConfig.get().labelDisplay.showDistance;
        int markerAlpha = (int) Math.round(255 * Mth.clamp(ModConfig.get().waypointSize.opacityPercent / 100.0, 0.0, 1.0));
        double maxRenderDist = Math.max(32.0, client.options.getEffectiveRenderDistance() * 16.0 - 16.0);

        activeWaypoints.clear();
        int poolIndex = 0;

        for (Waypoint wp : waypoints) {
            if (wp == null || !wp.isVisible()) continue;
            if (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible()) continue;

            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            BlockPos wpPos = null;

            if (wpDim.equals(currentDimension)) {
                wpPos = wp.getPos();
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && currentDimension.equals("minecraft:the_nether")) {
                    wpPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                } else if (wpDim.equals("minecraft:the_nether") && currentDimension.equals("minecraft:overworld")) {
                    wpPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                }
            }

            if (wpPos == null) continue;

            double targetX = wpPos.getX() + 0.5;
            double targetY = wpPos.getY();
            double targetZ = wpPos.getZ() + 0.5;

            double dx = targetX - cameraPos.x;
            double dy = targetY - cameraPos.y;
            double dz = targetZ - cameraPos.z;
            double realDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (realDistance < 0.001) continue;

            double clampDist = Math.min(realDistance, maxRenderDist);

            double dirX = dx / realDistance;
            double dirY = dy / realDistance;
            double dirZ = dz / realDistance;

            if (realDistance > CULL_MIN_DISTANCE) {
                float viewDot = (float) (dirX * cameraForward.x + dirY * cameraForward.y + dirZ * cameraForward.z);
                if (viewDot < CULL_DOT_THRESHOLD) continue;
            }

            double growthDist = Math.max(0.0, clampDist - WAYPOINT_GROWTH_START_DIST);
            float baseSize = (float) Mth.clamp(
                    WAYPOINT_MIN_SIZE + WAYPOINT_VISUAL_ANGLE * (float) growthDist,
                    WAYPOINT_MIN_SIZE,
                    WAYPOINT_MAX_SIZE
            ) * sizeScale;

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
            project(holder, true, dirX, dirY, dirZ, clampDist, baseSize, MARKER_PROJECTION_DIST);
            project(holder, false, dirX, dirY, dirZ, clampDist, baseSize, LABEL_PROJECTION_DIST);
            activeWaypoints.add(holder);
        }

        // Sort farther waypoints first for correct depth ordering
        activeWaypoints.sort((a, b) -> Double.compare(b.realDistance, a.realDistance));

        boolean isShaderActive = ShaderDetector.isShaderPackActive();
        RenderType markerType = WaypointRenderTypes.marker(isShaderActive);
        RenderType backdropType = WaypointRenderTypes.labelBackdrop(isShaderActive);

        // 26.2 dropped the lightmap from the see-through text pipeline: TEXT_SNIPPET binds
        // neither BindGroupLayouts.SAMPLER2 nor a lightmap vertex element, because vanilla's
        // see-through shader just draws at full brightness. Iris still routes that pipeline to
        // ShaderKey.TEXT, which is LightingModel.LIGHTMAP and samples the lightmap that is not
        // bound - hence dark letters. The polygon offset mode is built on WORLD_TEXT_SNIPPET,
        // which does bind it, at the cost of depth testing the label like terrain. Vanilla
        // rendering keeps the see-through mode it already draws right.
        Font.DisplayMode labelMode = isShaderActive ? Font.DisplayMode.POLYGON_OFFSET : Font.DisplayMode.SEE_THROUGH;

        for (WaypointHolder holder : activeWaypoints) {
            Waypoint wp = holder.waypoint;
            double realDistance = holder.realDistance;
            float markerSize = holder.markerSize;

            String wpName = wp.getName() != null ? wp.getName() : "Waypoint";
            int distanceMeters = showDistance ? (int) realDistance : 0;
            String nameText;
            float textWidth;
            if (wp.labelCacheMatches(wpName, uppercaseLabels, showDistance, distanceMeters)) {
                nameText = wp.getCachedLabelText();
                textWidth = wp.getCachedLabelWidth();
            } else {
                // Uppercased before the suffix is appended, not after: the trailing "m" is a unit
                // symbol and stays lowercase whatever the label styling says.
                nameText = uppercaseLabels ? wpName.toUpperCase(Locale.ROOT) : wpName;
                if (showDistance) {
                    nameText = nameText + " (" + distanceMeters + "m)";
                }
                textWidth = font.width(nameText);
                wp.setCachedLabel(wpName, uppercaseLabels, showDistance, distanceMeters, nameText, textWidth);
            }

            int wpColor = wp.getColor();
            int r = (wpColor >> 16) & 0xFF;
            int g = (wpColor >> 8)  & 0xFF;
            int b = wpColor         & 0xFF;

            // Marker. Single pass: overlapping passes blend into each other and wash the colour
            // out. Submits sharing a render type are batched in submission order, so the
            // far-to-near sort above still decides which billboard ends up on top.
            poseStack.pushPose();
            poseStack.translate(holder.markerX, holder.markerY, holder.markerZ);
            poseStack.rotateDegrees(Axis.YP, -cameraState.yRot);
            poseStack.rotateDegrees(Axis.XP, cameraState.xRot);

            collector.submitCustomGeometry(
                    poseStack, markerType,
                    (pose, buffer) -> drawMarker(pose, buffer, r, g, b, markerAlpha, markerSize)
            );
            poseStack.popPose();

            // Label, on its own billboard much closer to the eye. Same screen result, but both
            // halves end up nearer than anything that could depth reject the text.
            float labelSize = holder.labelSize;
            float textScale = 0.035f * labelSize / 0.7f;
            float anchorY = labelSize * 1.50f;

            poseStack.pushPose();
            poseStack.translate(holder.labelX, holder.labelY, holder.labelZ);
            poseStack.rotateDegrees(Axis.YP, -cameraState.yRot);
            poseStack.rotateDegrees(Axis.XP, cameraState.xRot);

            // Backdrop, drawn as our own quad rather than by Font, because 26.2's text background
            // pipeline moved to POSITION_COLOR while Iris still compiles its program against
            // POSITION_COLOR_LIGHTMAP - a 16 byte vertex read as 20, which comes out as an opaque
            // black bar. Emitted in the billboard frame so it inherits the marker's winding, and
            // sized to the box Font would have produced: x-1 to x+width, y-1 to y+9, mapped
            // through the label's flipped, scaled pose.
            float bgXMin = -(textWidth * 0.5f + 1.0f) * textScale;
            float bgXMax = textWidth * 0.5f * textScale;
            float bgYMin = anchorY - 9.0f * textScale;
            float bgYMax = anchorY + textScale;
            float backdropDepthZ = isShaderActive ? LABEL_DEPTH_GAP : 0.0f;

            collector.submitCustomGeometry(
                    poseStack, backdropType,
                    (pose, buffer) -> drawBackdrop(pose, buffer, bgXMin, bgYMin, bgXMax, bgYMax, LABEL_BACKDROP, backdropDepthZ)
            );

            poseStack.pushPose();
            poseStack.translate(0.0f, anchorY, 0.0f);
            poseStack.scale(-textScale, -textScale, textScale);

            // Text, submitted one order later. Within a single order the texts phase runs before
            // translucentCustomGeometry, so at the default order the backdrop above would be
            // painted over the letters. Orders are drained in ascending key order.
            float xOffset = -textWidth / 2.0f + 1.0f;
            collector.order(LABEL_TEXT_ORDER).submitText(
                    poseStack, xOffset, 0.0f,
                    FormattedCharSequence.forward(nameText, Style.EMPTY),
                    false, labelMode,
                    FULL_BRIGHT, 0xFFFFFFFF, 0, 0
            );
            poseStack.popPose();

            poseStack.popPose();
        }
    }

    /**
     * Emits the billboard quad.
     *
     * <p>The normal is written even though neither vanilla pipeline declares one: while a
     * shaderpack is loaded Iris widens the beacon beam's BLOCK format to its own TERRAIN
     * format, which does carry a normal, and the buffer refuses to close a vertex that
     * leaves it unset. On the vanilla pass the extra element is simply dropped.
     */
    private static void drawMarker(PoseStack.Pose pose, VertexConsumer buffer, int r, int g, int b, int a, float size) {
        float halfWidth = size * MARKER_ASPECT_FACTOR;
        int lightmap = 240;

        buffer.addVertex(pose, -halfWidth, size, 0.0f)
                .setColor(r, g, b, a)
                .setUv(0.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, halfWidth, size, 0.0f)
                .setColor(r, g, b, a)
                .setUv(1.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, halfWidth, 0.0f, 0.0f)
                .setColor(r, g, b, a)
                .setUv(1.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, -halfWidth, 0.0f, 0.0f)
                .setColor(r, g, b, a)
                .setUv(0.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    /**
     * Emits the label's backing box. Same vertex layout and winding as the marker quad.
     *
     * @param depthZ how far behind the glyphs (+Z) to push the quad - {@link #LABEL_DEPTH_GAP}
     *               under a shaderpack, {@code 0} otherwise; see that constant's javadoc.
     */
    private static void drawBackdrop(PoseStack.Pose pose, VertexConsumer buffer, float xMin, float yMin, float xMax, float yMax, int argb, float depthZ) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16)  & 0xFF;
        int g = (argb >> 8)   & 0xFF;
        int b = argb          & 0xFF;
        int lightmap = 240;

        buffer.addVertex(pose, xMin, yMax, depthZ)
                .setColor(r, g, b, a)
                .setUv(0.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMax, yMax, depthZ)
                .setColor(r, g, b, a)
                .setUv(1.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMax, yMin, depthZ)
                .setColor(r, g, b, a)
                .setUv(1.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMin, yMin, depthZ)
                .setColor(r, g, b, a)
                .setUv(0.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

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

        // Not synchronized: every caller of this - render(), DeathWaypointManager.tick() and the
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

    /** The current dimension id, cached alongside {@link #checkAndLoadWorldWaypoints()}. Callers
     *  that already trigger that check first (render(), DeathWaypointManager.tick()) always see a
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
            List<WaypointData> dataList = new ArrayList<>();
            for (Waypoint wp : waypoints) {
                dataList.add(new WaypointData(wp));
            }

            // Serialised in full before anything touches the disk: JsonStore needs the whole
            // document up front to write it in one atomic replacement, and this file is the only
            // copy the waypoints exist in, so a half-written one would lose them for good.
            JsonStore.write(waypointsFile(worldId), GSON.toJson(dataList));
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
     * session address changed (see this class's own javadoc) or any other world's waypoints stay
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
                    candidate.isFocused(),
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

        public WaypointData(Waypoint wp) {
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
            return wp;
        }
    }
}
