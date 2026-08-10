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
 * Render types for the waypoint markers and their labels.
 *
 * <h2>Why any of this exists</h2>
 *
 * Without a shaderpack every pass here could just use vanilla's see-through render types: they
 * declare no depth state, so nothing is ever occluded, and vanilla's shaders draw them at full
 * brightness. That is exactly what the {@code VANILLA_*} fields do.
 *
 * <p>With a shaderpack loaded Iris replaces the core shader of vanilla's see-through text
 * pipeline with one of its own programs ({@code gbuffers_entities_translucent}), and that
 * program's lighting, fog and tone mapping drain the colour out of both the marker and the
 * label. Beacon beams are the one piece of world geometry packs keep fully emissive and (near)
 * fog free, so with shaders active both passes ride a copy of the beacon beam pipeline, handed
 * to Iris as {@code BEACON_BEAM}.
 */
public final class WaypointRenderTypes {
    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    /** 1x1 opaque white, so a quad's colour comes entirely from its vertices. */
    private static final Identifier BACKDROP_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/label_backdrop.png");

    private static final String IRIS_API_CLASS      = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_PROGRAM_CLASS  = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final String IRIS_BEACON_PROGRAM = "BEACON_BEAM";

    /** Marker pass used when no shaderpack is rendering. */
    private static final RenderType VANILLA_MARKER = RenderTypes.textSeeThrough(MARKER_TEXTURE);

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

    /** Label backdrop passes used when no shaderpack is rendering. */
    private static final RenderType VANILLA_BACKDROP = RenderTypes.textSeeThrough(BACKDROP_TEXTURE);

    /**
     * The label's backdrop, drawn as our own geometry instead of letting Font emit it.
     *
     * <p>26.2 rebuilt {@code TEXT_BACKGROUND_SEE_THROUGH} on {@code POSITION_COLOR}, but Iris
     * still compiles its text-background program against {@code POSITION_COLOR_LIGHTMAP}, and
     * its pipeline mixin does not widen {@code POSITION_COLOR}. The program then reads a
     * 16-byte vertex as if it were 20, and the backdrop comes out as an opaque black bar.
     *
     * <p>So the box is drawn as a plain quad on a 1x1 white texture, deliberately through the
     * *same* pipeline the glyphs use while shaders are active ({@code textPolygonOffset}), not
     * the marker's beacon pipeline. Sharing it means the box and the letters get identical
     * lighting, fog and depth behaviour, which is the only way to guarantee they never disagree
     * about being drawn.
     */
    private static final RenderType SHADER_BACKDROP = RenderTypes.textPolygonOffset(BACKDROP_TEXTURE);

    private static RenderType shaderMarker = FALLBACK_MARKER;
    private static boolean initialized = false;

    private WaypointRenderTypes() { }

    /**
     * Claims the beacon program for our marker pipeline. Must run before the first frame: Iris
     * resolves a pipeline's vertex format at assignment time, and that resolution differs once
     * the level is being rendered.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        if (assignProgram(BEACON_PIPELINE, IRIS_BEACON_PROGRAM)) {
            shaderMarker = BEACON_MARKER;
        }
    }

    /** Returns the marker pass for the current environment. */
    public static RenderType marker(boolean shaderPackActive) {
        return shaderPackActive ? shaderMarker : VANILLA_MARKER;
    }

    /**
     * Returns the label backdrop pass for the current environment. Mirrors the display mode the
     * caller picks for the glyphs, so both halves of the label ride the same pipeline.
     */
    public static RenderType labelBackdrop(boolean shaderPackActive) {
        return shaderPackActive ? SHADER_BACKDROP : VANILLA_BACKDROP;
    }

    /**
     * Hands one of our pipelines to Iris. Reflective so the mod carries no compile time
     * dependency on it; anything unexpected leaves the caller on its vanilla fallback.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean assignProgram(RenderPipeline pipeline, String program) {
        try {
            Class<?> apiClass = Class.forName(IRIS_API_CLASS);
            Class<?> programClass = Class.forName(IRIS_PROGRAM_CLASS);

            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object target = Enum.valueOf((Class<Enum>) programClass, program);
            Method assignPipeline = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
            assignPipeline.invoke(api, pipeline, target);
            return true;
        } catch (ClassNotFoundException e) {
            // Iris is not installed; the shader pass is never used in that case.
            return false;
        } catch (Exception e) {
            // Older Iris without assignPipeline, or the pipeline was already claimed.
            return false;
        }
    }
}
