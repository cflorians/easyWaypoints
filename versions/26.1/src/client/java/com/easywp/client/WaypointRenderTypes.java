package com.easywp.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;

/**
 * Render types for the world markers, one per rendering environment.
 *
 * <p>Without a shaderpack the marker uses {@code textSeeThrough}. That pipeline declares no
 * fog uniform and no depth state at all, so the marker keeps its exact colour and is never
 * occluded by terrain.
 *
 * <p>With a shaderpack loaded Iris swaps the core shader of every vanilla pipeline for one of
 * its own programs. {@code textSeeThrough} is routed to {@code gbuffers_entities_translucent},
 * where the pack applies its lighting, fog and tone mapping - that is what drains the colour
 * out of the marker and lets the fog swallow it. Beacon beams are the one piece of world
 * geometry that packs keep fully emissive and (near) fog free, precisely so they stay readable
 * at range, so the shader pass draws the marker through a private pipeline built on the
 * vanilla beacon beam snippet and hands it to Iris as {@link #IRIS_BEACON_PROGRAM}.
 *
 * <p>The private pipeline is a copy of the vanilla beacon beam except for its depth state,
 * relaxed to "always pass, never write" so the marker keeps the see-through behaviour of the
 * vanilla pass without stamping depth that the pack's composite passes would then read.
 */
public final class WaypointRenderTypes {
    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    private static final String IRIS_API_CLASS     = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_PROGRAM_CLASS = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final String IRIS_BEACON_PROGRAM = "BEACON_BEAM";

    /** Marker pass used when no shaderpack is rendering. */
    public static final RenderType VANILLA_MARKER = RenderTypes.textSeeThrough(MARKER_TEXTURE);

    /** Vanilla beacon beam, kept as the depth-tested fallback if Iris rejects our pipeline. */
    private static final RenderType FALLBACK_MARKER = RenderTypes.beaconBeam(MARKER_TEXTURE, true);

    private static final RenderPipeline BEACON_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("easywp", "pipeline/waypoint_beacon"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build()
    );

    private static final RenderType BEACON_MARKER = RenderType.create(
            "easywp_waypoint_beacon",
            RenderSetup.builder(BEACON_PIPELINE)
                    .withTexture("Sampler0", MARKER_TEXTURE)
                    .sortOnUpload()
                    .createRenderSetup()
    );

    private static RenderType shaderMarker = FALLBACK_MARKER;
    private static boolean initialized = false;

    private WaypointRenderTypes() { }

    /**
     * Claims the beacon beam program for our pipeline. Must run before the first frame;
     * Iris resolves the pipeline's vertex format at assignment time, so this cannot happen
     * while the level is being rendered.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (assignBeaconProgram()) {
            shaderMarker = BEACON_MARKER;
        }
    }

    /**
     * Returns the marker pass for the current environment.
     */
    public static RenderType marker(boolean shaderPackActive) {
        return shaderPackActive ? shaderMarker : VANILLA_MARKER;
    }

    /**
     * Iris skips any pipeline it does not know about while a pack is loaded, so the private
     * pipeline is only usable once it has been mapped to a program. Anything unexpected here
     * leaves the vanilla beacon beam in place: depth tested, but never invisible.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean assignBeaconProgram() {
        try {
            Class<?> apiClass = Class.forName(IRIS_API_CLASS);
            Class<?> programClass = Class.forName(IRIS_PROGRAM_CLASS);

            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object beaconBeam = Enum.valueOf((Class<Enum>) programClass, IRIS_BEACON_PROGRAM);
            Method assignPipeline = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
            assignPipeline.invoke(api, BEACON_PIPELINE, beaconBeam);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
