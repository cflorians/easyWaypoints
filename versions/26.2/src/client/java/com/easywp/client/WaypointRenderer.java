package com.easywp.client;

import com.easywp.EasyWp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Handles 3D world rendering and JSON storage for waypoints.
 */
public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    /** Label backdrop, ARGB. Same value Font uses for the box in the 26.1 renderer. */
    private static final int LABEL_BACKDROP = 0x40000000;
    private static final int FULL_BRIGHT    = 0xF000F0;

    /** Submit order for the label text, so it is drawn after our backdrop quad. */
    private static final int LABEL_TEXT_ORDER = 1;

    /**
     * How far behind the glyphs the backdrop sits, in billboard local units (+Z points away from
     * the viewer). Both halves of the label now share a depth writing pipeline, so leaving them
     * coplanar makes them z-fight and the text flickers as the camera moves.
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
     * Distance the label is drawn at. Kept level with the marker on purpose.
     *
     * <p>Pulling it in to a quarter of a block did put it in front of nearby geometry, but it
     * also shrank the glyph quads by another factor of sixteen, and Iris derives the tangents
     * and mid-texture coordinates its programs light with from those quad deltas. That is the
     * most likely reason the letters came back grey. The backdrop now shares the glyphs'
     * pipeline instead, so the two halves are occluded together rather than separately.
     */
    private static final double LABEL_PROJECTION_DIST = MARKER_PROJECTION_DIST;

    private static String lastWorldId = "";
    private static final Object lock = new Object();

    /** Per-frame view bob translation, converted from camera space to world axes. */
    private static final Vector3f bobOffset = new Vector3f();

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

    public static void init() {
        if (initialized) return;
        initialized = true;
    }

    /**
     * Collects the 3D billboards for the waypoints.
     *
     * <p>Runs during the submit phase rather than drawing directly: since 26.2 the level render
     * context no longer hands out a buffer source, world geometry is queued through the submit
     * node collector and drawn later by the feature renderers.
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
        String currentDimension = client.level.dimension().identifier().toString();

        boolean hasAnyFocused = false;
        for (Waypoint wp : waypoints) {
            if (wp != null && wp.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        computeBobOffset(client, cameraState);

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

            String wpName  = wp.getName() != null ? wp.getName() : "Waypoint";
            String nameText = showDistance ? (wpName + " (" + (int) realDistance + "m)") : wpName;
            if (uppercaseLabels) {
                nameText = nameText.toUpperCase(Locale.ROOT);
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
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

            collector.submitCustomGeometry(
                    poseStack, markerType,
                    (pose, buffer) -> drawMarker(pose, buffer, r, g, b, markerAlpha, markerSize)
            );
            poseStack.popPose();

            // Label, on its own billboard much closer to the eye. Same screen result, but both
            // halves end up nearer than anything that could depth reject the text.
            float labelSize = holder.labelSize;
            float textScale = 0.035f * labelSize / 0.7f;
            float textWidth = font.width(nameText);
            float anchorY = labelSize * 1.50f;

            poseStack.pushPose();
            poseStack.translate(holder.labelX, holder.labelY, holder.labelZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(-cameraState.yRot));
            poseStack.mulPose(Axis.XP.rotationDegrees(cameraState.xRot));

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

            collector.submitCustomGeometry(
                    poseStack, backdropType,
                    (pose, buffer) -> drawBackdrop(pose, buffer, bgXMin, bgYMin, bgXMax, bgYMax, LABEL_BACKDROP)
            );

            poseStack.pushPose();
            poseStack.translate(0.0f, anchorY, 0.0f);
            poseStack.scale(-textScale, -textScale, textScale);
            float xOffset = -textWidth / 2.0f + 1.0f;

            // Text, submitted one order later. Within a single order the texts phase runs before
            // translucentCustomGeometry, so at the default order the backdrop above would be
            // painted over the letters. Orders are drained in ascending key order.
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
     */
    private static void drawBackdrop(PoseStack.Pose pose, VertexConsumer buffer, float xMin, float yMin, float xMax, float yMax, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16)  & 0xFF;
        int g = (argb >> 8)   & 0xFF;
        int b = argb          & 0xFF;
        int lightmap = 240;

        buffer.addVertex(pose, xMin, yMax, LABEL_DEPTH_GAP)
                .setColor(r, g, b, a)
                .setUv(0.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMax, yMax, LABEL_DEPTH_GAP)
                .setColor(r, g, b, a)
                .setUv(1.0f, 0.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMax, yMin, LABEL_DEPTH_GAP)
                .setColor(r, g, b, a)
                .setUv(1.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, xMin, yMin, LABEL_DEPTH_GAP)
                .setColor(r, g, b, a)
                .setUv(0.0f, 1.0f)
                .setUv2(lightmap, lightmap)
                .setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    /** Characters that are unsafe in a file name, stripped when deriving the world id. Hoisted to a
     *  constant because getWorldId() runs once per frame and String.replaceAll recompiles the
     *  pattern on every call. */
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
        if (client.getCurrentServer() != null) {
            return "mp_" + UNSAFE_FILENAME_CHARS.matcher(client.getCurrentServer().ip.replace(':', '_')).replaceAll("_");
        }
        return "mp_lan";
    }

    public static void checkAndLoadWorldWaypoints() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        String currentWorld = getWorldId();
        if (!currentWorld.equals("unknown") && !currentWorld.equals(lastWorldId)) {
            synchronized (lock) {
                lastWorldId = currentWorld;
                loadFromFile();
            }
        }
    }

    public static void saveToFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        File configFile = new File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        try {
            configFile.getParentFile().mkdirs();
            List<WaypointData> dataList = new ArrayList<>();
            for (Waypoint wp : waypoints) {
                dataList.add(new WaypointData(wp));
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(dataList, writer);
            }
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to save waypoints", e);
        }
    }

    public static void loadFromFile() {
        String worldId = getWorldId();
        if (worldId.equals("unknown")) return;

        File configFile = new File(Minecraft.getInstance().gameDirectory, "config/easywp/waypoints_" + worldId + ".json");
        waypoints.clear();
        if (!configFile.exists()) return;

        try {
            Gson gson = new Gson();
            try (FileReader reader = new FileReader(configFile)) {
                Type listType = new TypeToken<List<WaypointData>>(){}.getType();
                List<WaypointData> dataList = gson.fromJson(reader, listType);
                if (dataList != null) {
                    for (WaypointData data : dataList) {
                        waypoints.add(data.toWaypoint());
                    }
                }
            }
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to load waypoints", e);
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
        }

        public Waypoint toWaypoint() {
            return new Waypoint(
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
        }
    }
}
