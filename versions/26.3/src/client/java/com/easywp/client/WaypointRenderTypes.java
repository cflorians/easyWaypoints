package com.easywp.client;

import com.easywp.EasyWp;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Render types for the waypoint markers and their labels.
 *
 * <h2>Why any of this exists</h2>
 *
 * Without a shaderpack every pass here rides a private copy of vanilla's see-through text
 * pipeline: like the vanilla pipeline it declares no depth test, so nothing is ever occluded,
 * and vanilla's shaders draw it at full brightness. That is what the {@code VANILLA_*} fields
 * do - see {@link #MARKER_PIPELINE} for why they are a private copy rather than
 * {@code RenderTypes.textSeeThrough} directly.
 *
 * <p>With a shaderpack loaded Iris replaces the core shader of vanilla's see-through text
 * pipeline with one of its own programs ({@code gbuffers_entities_translucent}), and that
 * program's lighting, fog and tone mapping drain the colour out of both the marker and the
 * label. Beacon beams are the one piece of world geometry packs keep fully emissive and (near)
 * fog free, so with shaders active both passes ride a copy of the beacon beam pipeline, handed
 * to Iris as {@code BEACON_BEAM}.
 *
 * <h2>What 26.3 changed, and what it did not</h2>
 *
 * The <em>intent</em> of every pass below is 26.2's, unchanged. Three things about how it is
 * expressed had to change, all of them mechanical except the third:
 *
 * <ul>
 * <li><b>The pipeline API moved house.</b> {@code RenderPipeline} and its supporting records
 *     ({@code DepthStencilState}, {@code CompareOp}, {@code ColorTargetState},
 *     {@code BlendFunction}) left {@code com.mojang.blaze3d.pipeline}/{@code .platform} for
 *     {@code com.mojang.renderpearl.api.pipeline} - 26.3's new render backend abstraction, which
 *     is also what brought a Vulkan backend alongside the OpenGL one. The types are the same
 *     shape; only the imports differ.</li>
 * <li><b>Custom pipelines are no longer registered, and no longer built from vanilla's
 *     snippets.</b> {@code RenderPipelines.register} and every snippet except the three
 *     {@code OIT_*} ones became private in 26.3, so 26.2's
 *     {@code RenderPipelines.register(RenderPipeline.builder(TEXT_SNIPPET)...)} has no
 *     counterpart. It also needs none: {@code PipelineCache.get} compiles a pipeline on its
 *     first cache miss, so {@code requiredPipelines()}/{@code optionalPipelines()} are a
 *     precompile list, not a registry. {@link #derive} takes the place of the snippet - it
 *     copies every property off a <em>built</em> vanilla pipeline and overrides just the two
 *     this mod cares about, which is a closer copy than a snippet ever was.</li>
 * <li><b>{@code OutputTarget} is gone, and with it the weather-target detour.</b> See
 *     {@link #MARKER_PIPELINE}.</li>
 * </ul>
 */
public final class WaypointRenderTypes {
    public static final Identifier MARKER_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/waypoint_marker.png");

    /** 1x1 opaque white, so a quad's colour comes entirely from its vertices. */
    private static final Identifier BACKDROP_TEXTURE = Identifier.fromNamespaceAndPath("easywp", "textures/label_backdrop.png");

    private static final String IRIS_API_CLASS      = "net.irisshaders.iris.api.v0.IrisApi";
    private static final String IRIS_PROGRAM_CLASS  = "net.irisshaders.iris.api.v0.IrisProgram";
    private static final String IRIS_BEACON_PROGRAM = "BEACON_BEAM";

    /** Never occluded (the test always passes), but leaves a real depth value behind. */
    private static final DepthStencilState ALWAYS_PASS_WRITE_DEPTH =
            new DepthStencilState(CompareOp.ALWAYS_PASS, true);

    /**
     * Vanilla's see-through text pipeline with depth write enabled (test still always-pass).
     * Minecraft renders clouds and translucent terrain (water) after the marker, and a pipeline
     * that never writes depth leaves nothing for them to test against, so they simply paint over
     * the marker/label regardless of how close they actually are. Writing a real depth value (at
     * the marker's own, deliberately near, projected distance) fixes that without reintroducing
     * occlusion by solid terrain, since the marker's own test still always passes. Shared by the
     * marker and the vanilla-mode backdrop below; only the bound texture differs.
     *
     * <p>Built by {@link #derive} rather than from a snippet because 26.3 made every snippet
     * private, and vanilla's own {@code TEXT_SEE_THROUGH} - confirmed from
     * {@code RenderPipelines}' bytecode - is exactly
     * {@code builder(TEXT_SNIPPET).withLocation("pipeline/text_see_through")
     * .withShaderDefine("IS_SEE_THROUGH").withColorTargetState(...)
     * .withDepthStencilState(Optional.empty())}. Copying that built pipeline and substituting the
     * depth state reproduces 26.2's private copy property for property.
     *
     * <p><b>26.3 dropped the output-target routing, and the tradeoff it was managing changed
     * shape.</b> 26.1 and 26.2 route this pass to {@code OutputTarget.WEATHER_TARGET}, because
     * back then water rendered into its own translucent target whose depth buffer started each
     * frame as a copy of the main one - so a near depth value written into main got copied there
     * too, and water lost a depth test against a "wall" a few blocks away, failing to draw
     * wherever a marker sat. 26.3 removed {@code OutputTarget} entirely: the multi-target
     * transparency compositor those targets existed for was replaced by real order-independent
     * transparency ({@code net.minecraft.client.renderer.oit}, still gated behind
     * {@code OptionsRenderState.improvedTransparency}, i.e. "Fabulous!"), where each translucent
     * render type carries an {@code OitPipelineSet} and fragments are accumulated and resolved
     * per pixel by depth instead of composited target by target. There is no weather target to
     * route to any more, so this pass writes its depth into the main buffer.
     *
     * <p>That is not a new tradeoff, it is the same one with its condition removed. 26.2's own
     * javadoc records that outside Fabulous - Fancy, vanilla's default - {@code WEATHER_TARGET}
     * silently falls back to the main target anyway, so 26.1/26.2 already write into main for
     * most players, and a hole in water immediately around a waypoint is the accepted cost. That
     * tradeoff was weighed explicitly on 2026-08-24 and decided in favour of keeping the write:
     * a label that looks submerged every time a cloud or a puddle is nearby is a constant
     * annoyance, while the hole needs a waypoint placed in or right at water to show up at all.
     * 26.3 makes the accepted case unconditional rather than introducing a new one. It has not
     * been verified in game against 26.3's OIT resolve specifically - that is the one claim here
     * read from the API surface rather than watched happening - so if a waypoint near water ever
     * looks wrong under Fabulous on 26.3, this pipeline's depth write is the first thing to try
     * turning off.
     */
    private static final RenderPipeline MARKER_PIPELINE = derive(
            RenderPipelines.TEXT_SEE_THROUGH, "pipeline/waypoint_marker", ALWAYS_PASS_WRITE_DEPTH, null);

    private static final RenderPipeline BEACON_PIPELINE = derive(
            RenderPipelines.BEACON_BEAM_TRANSLUCENT, "pipeline/waypoint_beacon", ALWAYS_PASS_WRITE_DEPTH,
            new ColorTargetState(BlendFunction.TRANSLUCENT));

    /** Vanilla beacon beam, kept as the depth-tested fallback if Iris rejects our pipeline. */
    private static final RenderType FALLBACK_MARKER = RenderTypes.beaconBeam(MARKER_TEXTURE, true);

    /** Marker pass used when no shaderpack is rendering. */
    private static final RenderType VANILLA_MARKER = MARKER_PIPELINE == null
            ? RenderTypes.textSeeThrough(MARKER_TEXTURE)
            : RenderType.create(
                    "easywp_waypoint_marker",
                    RenderSetup.builder(MARKER_PIPELINE)
                            .withTexture("Sampler0", MARKER_TEXTURE)
                            .useLightmap()
                            .createRenderSetup()
            );

    private static final RenderType BEACON_MARKER = BEACON_PIPELINE == null
            ? FALLBACK_MARKER
            : RenderType.create(
                    "easywp_waypoint_beacon",
                    RenderSetup.builder(BEACON_PIPELINE)
                            .withTexture("Sampler0", MARKER_TEXTURE)
                            .sortOnUpload()
                            .createRenderSetup()
            );

    /** Label backdrop pass used when no shaderpack is rendering. Rides {@link #MARKER_PIPELINE}
     *  too - same depth-write reasoning, just a different bound texture. */
    private static final RenderType VANILLA_BACKDROP = MARKER_PIPELINE == null
            ? RenderTypes.textSeeThrough(BACKDROP_TEXTURE)
            : RenderType.create(
                    "easywp_waypoint_label_backdrop",
                    RenderSetup.builder(MARKER_PIPELINE)
                            .withTexture("Sampler0", BACKDROP_TEXTURE)
                            .useLightmap()
                            .createRenderSetup()
            );

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

        if (BEACON_PIPELINE != null && assignProgram(BEACON_PIPELINE, IRIS_BEACON_PROGRAM)) {
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
     * Copies a built vanilla pipeline, overriding its location, its depth state and - optionally -
     * its first colour target.
     *
     * <p>This is 26.3's stand-in for 26.2's {@code RenderPipeline.builder(someVanillaSnippet)}:
     * the snippets are private now, but every property a snippet would have contributed is
     * readable off the finished pipeline, so the copy is exact by construction rather than by
     * a second transcription of vanilla's builder chain that could drift from it.
     *
     * <p>Returns {@code null} rather than throwing if 26.3 ever grows a pipeline property the
     * builder cannot take back - the callers above then fall back to the plain vanilla render
     * type, which loses the depth write but still draws the marker.
     */
    private static RenderPipeline derive(RenderPipeline base, String path,
                                         DepthStencilState depthStencilState,
                                         ColorTargetState firstColorTarget) {
        try {
            RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("easywp", path))
                    .withVertexShader(base.getShaders().get(ShaderType.VERTEX))
                    .withFragmentShader(base.getShaders().get(ShaderType.FRAGMENT))
                    .withPolygonMode(base.getPolygonMode())
                    .withCull(base.isCull())
                    .withPrimitiveTopology(base.getPrimitiveTopology())
                    .withPushConstantSize(base.pushConstantSize())
                    .withDepthStencilState(depthStencilState);

            ShaderDefines defines = base.getShaderDefines();
            for (String flag : defines.flags()) {
                builder.withShaderDefine(flag);
            }
            // The builder only takes int- and float-valued defines while ShaderDefines stores
            // them as strings, so a value that is neither cannot be copied. None of the pipelines
            // derived here has one today (vanilla's see-through text and beacon beam carry flags
            // only), so this logs rather than failing the copy - but it is the one property that
            // could silently go missing, hence the warning.
            for (Map.Entry<String, String> define : defines.values().entrySet()) {
                String value = define.getValue();
                try {
                    builder.withShaderDefine(define.getKey(), Integer.parseInt(value));
                } catch (NumberFormatException notAnInt) {
                    try {
                        builder.withShaderDefine(define.getKey(), Float.parseFloat(value));
                    } catch (NumberFormatException notAFloat) {
                        EasyWp.LOGGER.warn("Dropped the shader define {}={} while copying {}",
                                define.getKey(), value, base.getLocation());
                    }
                }
            }

            for (BindGroupLayout layout : base.getBindGroupLayouts()) {
                builder.withBindGroupLayout(layout);
            }

            List<ColorTargetState> colorTargets = base.getColorTargetStates();
            for (int i = 0; i < colorTargets.size(); i++) {
                ColorTargetState target = i == 0 && firstColorTarget != null ? firstColorTarget : colorTargets.get(i);
                if (target == null) {
                    builder.withUnusedColorTargetState(i);
                } else {
                    builder.withColorTargetState(i, target);
                }
            }

            List<VertexFormat> vertexFormats = base.getVertexFormatBindings();
            for (int i = 0; i < vertexFormats.size(); i++) {
                builder.withVertexBinding(i, vertexFormats.get(i));
            }

            return builder.build();
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to derive the {} pipeline from {}; falling back to the vanilla pass",
                    path, base.getLocation(), e);
            return null;
        }
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
