package com.easywp.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles 3D world rendering and JSON storage for waypoints in 1.21.x (Yarn mappings).
 */
public class WaypointRenderer {
    public static final List<Waypoint> waypoints = new ArrayList<>();
    private static boolean initialized = false;

    public static final Identifier MARKER_TEXTURE = Identifier.of("easywp", "textures/waypoint_marker.png");

    public static final RenderLayer WAYPOINT_SEE_THROUGH = RenderLayer.getTextSeeThrough(MARKER_TEXTURE);
    public static final RenderLayer WAYPOINT_VISIBLE     = RenderLayer.getText(MARKER_TEXTURE);
    public static final RenderLayer WAYPOINT_SHADER_COMPAT = RenderLayer.getBeaconBeam(MARKER_TEXTURE, true);

    private static final float WAYPOINT_VISUAL_ANGLE        = 0.055f;
    private static final float WAYPOINT_MIN_SIZE            = 0.25f;
    private static final float WAYPOINT_MAX_SIZE            = 500.0f;
    private static final float WAYPOINT_GROWTH_START_DIST   = 2.0f;
    private static final float MARKER_ASPECT_FACTOR         = 5.0f / 14.0f;

    private static String lastWorldId = "";
    private static final Object lock = new Object();

    private static class WaypointHolder {
        Waypoint waypoint;
        double realDistance;
        double renderX;
        double renderY;
        double renderZ;
        float markerSize;

        WaypointHolder(Waypoint waypoint, double realDistance, double renderX, double renderY, double renderZ, float markerSize) {
            this.waypoint = waypoint;
            this.realDistance = realDistance;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.markerSize = markerSize;
        }
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        WorldRenderEvents.LAST.register(WaypointRenderer::render);
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null || client.player == null) return;
        if (ModKeyBindings.displayMode == WaypointDisplayMode.DISABLED) return;

        checkAndReloadWorldData();

        if (waypoints.isEmpty()) return;

        String currentDim = client.world.getRegistryKey().getValue().toString();
        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        double camX = cameraPos.x;
        double camY = cameraPos.y;
        double camZ = cameraPos.z;

        MatrixStack matrixStack = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();

        if (matrixStack == null || consumers == null) return;

        List<WaypointHolder> visibleWaypoints = new ArrayList<>();

        for (Waypoint wp : waypoints) {
            if (!wp.isVisible()) continue;

            BlockPos renderPos = wp.getPos();
            boolean isSameDim = currentDim.equals(wp.getDimension());

            if (!isSameDim) {
                if (!wp.isShared()) continue;
                BlockPos converted = Waypoint.getConvertedPos(wp.getPos(), wp.getDimension(), currentDim);
                if (converted == null) continue;
                renderPos = converted;
            }

            double targetX = renderPos.getX() + 0.5;
            double targetY = renderPos.getY() + 1.5;
            double targetZ = renderPos.getZ() + 0.5;

            double dx = targetX - camX;
            double dy = targetY - camY;
            double dz = targetZ - camZ;
            double realDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double renderX = targetX;
            double renderY = targetY;
            double renderZ = targetZ;

            if (realDistance > WAYPOINT_MAX_SIZE) {
                double factor = WAYPOINT_MAX_SIZE / realDistance;
                renderX = camX + dx * factor;
                renderY = camY + dy * factor;
                renderZ = camZ + dz * factor;
            }

            float markerSize;
            if (realDistance <= WAYPOINT_GROWTH_START_DIST) {
                markerSize = WAYPOINT_MIN_SIZE;
            } else {
                markerSize = (float) (realDistance * WAYPOINT_VISUAL_ANGLE);
                if (markerSize < WAYPOINT_MIN_SIZE) markerSize = WAYPOINT_MIN_SIZE;
            }

            visibleWaypoints.add(new WaypointHolder(wp, realDistance, renderX, renderY, renderZ, markerSize));
        }

        if (visibleWaypoints.isEmpty()) return;

        visibleWaypoints.sort((a, b) -> Double.compare(b.realDistance, a.realDistance));

        boolean hasAnyFocused = false;
        for (WaypointHolder h : visibleWaypoints) {
            if (h.waypoint.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        for (WaypointHolder holder : visibleWaypoints) {
            Waypoint wp = holder.waypoint;

            boolean seeThrough;
            if (hasAnyFocused) {
                seeThrough = wp.isFocused();
            } else {
                seeThrough = (ModKeyBindings.displayMode == WaypointDisplayMode.WORLD_MARKERS);
            }

            renderWaypoint3D(client, matrixStack, consumers, camera, holder.renderX, holder.renderY, holder.renderZ, holder.markerSize, wp, holder.realDistance, seeThrough);
        }
    }

    private static void renderWaypoint3D(MinecraftClient client, MatrixStack matrixStack, VertexConsumerProvider consumers, Camera camera,
                                         double renderX, double renderY, double renderZ, float markerSize,
                                         Waypoint wp, double distance, boolean seeThrough) {
        Vec3d cameraPos = camera.getPos();

        matrixStack.push();
        matrixStack.translate(renderX - cameraPos.x, renderY - cameraPos.y, renderZ - cameraPos.z);

        matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
        matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));

        float width = markerSize;
        float height = markerSize;

        matrixStack.scale(-width, -height, width);

        Matrix4f pose = matrixStack.peek().getPositionMatrix();
        TextRenderer textRenderer = client.textRenderer;

        boolean isShaderActive = ShaderDetector.isShaderPackActive();

        int argb = wp.getColor();
        int colorR = (argb >> 16) & 0xFF;
        int colorG = (argb >> 8) & 0xFF;
        int colorB = argb & 0xFF;

        int alpha = isShaderActive ? 240 : 255;

        RenderLayer markerRenderLayer;
        if (isShaderActive) {
            markerRenderLayer = WAYPOINT_SHADER_COMPAT;
        } else if (seeThrough) {
            markerRenderLayer = WAYPOINT_SEE_THROUGH;
        } else {
            markerRenderLayer = WAYPOINT_VISIBLE;
        }

        VertexConsumer vertexConsumer = consumers.getBuffer(markerRenderLayer);

        float markerW = MARKER_ASPECT_FACTOR;
        float markerH = 0.5f;

        vertexConsumer.vertex(pose, -markerW, markerH, 0.0f).color(colorR, colorG, colorB, alpha).texture(0.0f, 1.0f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1);
        vertexConsumer.vertex(pose, markerW, markerH, 0.0f).color(colorR, colorG, colorB, alpha).texture(1.0f, 1.0f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1);
        vertexConsumer.vertex(pose, markerW, -markerH, 0.0f).color(colorR, colorG, colorB, alpha).texture(1.0f, 0.0f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1);
        vertexConsumer.vertex(pose, -markerW, -markerH, 0.0f).color(colorR, colorG, colorB, alpha).texture(0.0f, 0.0f).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(0, 0, 1);

        String distStr = (int) Math.round(distance) + "m";
        String label = wp.getName() + " (" + distStr + ")";

        float fontScale = 0.022f;
        matrixStack.scale(fontScale, fontScale, fontScale);
        Matrix4f textPose = matrixStack.peek().getPositionMatrix();

        float halfWidth = textRenderer.getWidth(label) / 2.0f;
        int textY = -40;

        int textColor = 0xFFFFFFFF;
        int bgColor = 0x60000000;

        TextRenderer.TextLayerType layerType = seeThrough ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL;
        textRenderer.draw(label, -halfWidth, textY, textColor, false, textPose, consumers, layerType, bgColor, 0xF000F0);

        matrixStack.pop();
    }

    private static String getWorldId() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return "unknown";

        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            String folderName = client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).getParent().getFileName().toString();
            return "sp_" + folderName;
        }

        if (client.getCurrentServerEntry() != null) {
            return "mp_" + client.getCurrentServerEntry().address.replace(":", "_").replace("/", "_");
        }

        return "world_default";
    }

    public static void checkAndReloadWorldData() {
        synchronized (lock) {
            String currentWorldId = getWorldId();
            if (!currentWorldId.equals(lastWorldId)) {
                lastWorldId = currentWorldId;
                waypoints.clear();
                loadFromFile();
            }
        }
    }

    public static File getSaveFile() {
        MinecraftClient client = MinecraftClient.getInstance();
        File configDir = new File(client.runDirectory, "config/easywp");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        String worldId = getWorldId();
        return new File(configDir, "waypoints_" + worldId + ".json");
    }

    public static void saveToFile() {
        synchronized (lock) {
            try {
                File file = getSaveFile();
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                try (FileWriter writer = new FileWriter(file)) {
                    gson.toJson(waypoints, writer);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void loadFromFile() {
        synchronized (lock) {
            try {
                File file = getSaveFile();
                if (file.exists()) {
                    Gson gson = new Gson();
                    try (FileReader reader = new FileReader(file)) {
                        Type type = new TypeToken<ArrayList<Waypoint>>() {}.getType();
                        List<Waypoint> loaded = gson.fromJson(reader, type);
                        if (loaded != null) {
                            waypoints.clear();
                            waypoints.addAll(loaded);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
