package com.easywp.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;

/**
 * Render types for the world markers, one per rendering environment.
 *
 * <p>Without a shaderpack the marker rides a private copy of vanilla's {@code textSeeThrough}
 * shaders. Like the vanilla pipeline it declares no depth test - never occluded by terrain -
 * but unlike it, it writes depth (see below).
 *
 * <p>With a shaderpack loaded Iris swaps the core shader of every vanilla pipeline for one of
 * its own programs. {@code textSeeThrough} is routed to {@code gbuffers_entities_translucent},
 * where the pack applies its lighting, fog and tone mapping - that is what drains the colour
 * out of the marker and lets the fog swallow it. Beacon beams are the one piece of world
 * geometry that packs keep fully emissive and (near) fog free, precisely so they stay readable
 * at range, so the shader pass draws the marker through a private pipeline built on the
 * vanilla beacon beam snippet and hands it to Iris as {@link #IRIS_BEACON_PROGRAM}.
 *
 * <p>Both private pipelines below write depth despite testing it as "always pass, so it never
 * loses to real terrain". Minecraft renders clouds and translucent terrain (water) into their
 * own render targets - separate from the one this marker draws into - and composites them back
 * over the main target afterward using depth. A pipeline that never writes depth leaves nothing
 * there for that composite to test against, so clouds and water painted later in the frame
 * simply draw over the marker regardless of how close it actually is. Writing a real depth
 * value (at the marker's own, deliberately near, projected distance) fixes that without
 * reintroducing occlusion by solid terrain, since the marker's own test still always passes.
 */
public final class WaypointRenderTypes {
    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    private static final String IRIS_API_CLASS     = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_PROGRAM_CLASS = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final String IRIS_BEACON_PROGRAM = "BEACON_BEAM";

    /** Same shaders as vanilla's {@code textSeeThrough}; only the depth state differs. */
    private static final RenderPipeline MARKER_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("easywp", "pipeline/waypoint_marker"))
                    .withVertexShader("core/rendertype_text_see_through")
                    .withFragmentShader("core/rendertype_text_see_through")
                    .withSampler("Sampler0")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .build()
    );

    /** Marker pass used when no shaderpack is rendering. */
    public static final RenderType VANILLA_MARKER = RenderType.create(
            "easywp_waypoint_marker",
            RenderSetup.builder(MARKER_PIPELINE).withTexture("Sampler0", MARKER_TEXTURE).useLightmap().createRenderSetup()
    );

    /** Vanilla beacon beam, kept as the depth-tested fallback if Iris rejects our pipeline. */
    private static final RenderType FALLBACK_MARKER = RenderTypes.beaconBeam(MARKER_TEXTURE, true);

    private static final RenderPipeline BEACON_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("easywp", "pipeline/waypoint_beacon"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .build()
    );

    private static final RenderType BEACON_MARKER = RenderType.create(
            "easywp_waypoint_beacon",
            RenderSetup.builder(BEACON_PIPELINE)
                    .withTexture("Sampler0", MARKER_TEXTURE)
                    .sortOnUpload()
                    .createRenderSetup()
    );

    /**
     * Same shaders as vanilla's {@code textBackgroundSeeThrough} (colour only, no texture),
     * depth write enabled for the same reason as {@link #MARKER_PIPELINE}. Only used when no
     * shaderpack is rendering - see {@link #SHADER_BACKDROP} for why it cannot be used while one is.
     */
    private static final RenderPipeline BACKDROP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TEXT_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("easywp", "pipeline/waypoint_label_backdrop"))
                    .withVertexShader("core/rendertype_text_background_see_through")
                    .withFragmentShader("core/rendertype_text_background_see_through")
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .build()
    );

    /** Label backdrop pass without a shaderpack, drawn as our own quad instead of through Font's
     *  built-in background box so it can carry the depth write described above. */
    public static final RenderType LABEL_BACKDROP = RenderType.create(
            "easywp_waypoint_label_backdrop",
            RenderSetup.builder(BACKDROP_PIPELINE).useLightmap().sortOnUpload().createRenderSetup()
    );

    /**
     * Label backdrop while a shaderpack is rendering.
     *
     * <p>{@link #LABEL_BACKDROP} is simply invisible under Iris. A pack swaps the core shader of
     * every pipeline it recognises and skips the ones it does not, and unlike {@link
     * #BEACON_PIPELINE} the private backdrop pipeline is never handed to it - so the quad is
     * dropped, the glyphs keep drawing on their own, and the label turns unreadable the moment
     * anything pale (fog, sky, snow) ends up behind it.
     *
     * <p>The fix is to ride a pipeline Iris does recognise: vanilla's own
     * {@code textBackgroundSeeThrough}, the same family the glyphs already draw through in
     * {@code SEE_THROUGH} mode. Sharing that family keeps both halves of the label equally
     * un-occluded, so they can never disagree about being drawn. What it gives up is the depth
     * write: with shaders on, clouds and water can paint over the backdrop again, exactly as
     * they did before {@link #LABEL_BACKDROP}'s depth write was added. That is a far smaller
     * problem than a label nobody can read, and it only applies while a pack is loaded - the
     * vanilla path above keeps its depth write.
     */
    private static final RenderType SHADER_BACKDROP = RenderTypes.textBackgroundSeeThrough();

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
     * Returns the label backdrop pass for the current environment. Mirrors what the glyphs ride,
     * so both halves of the label are always drawn or skipped together.
     */
    public static RenderType labelBackdrop(boolean shaderPackActive) {
        return shaderPackActive ? SHADER_BACKDROP : LABEL_BACKDROP;
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
