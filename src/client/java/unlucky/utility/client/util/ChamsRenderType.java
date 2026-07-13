package unlucky.utility.client.util;

import java.util.Optional;
import java.util.function.Function;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Render types for Chams: tinted, full-bright passes over an entity's model,
 * mirroring {@code ENTITY_TRANSLUCENT} with different depth tests:
 * <ul>
 *   <li>through-walls: no depth test (draws everywhere)</li>
 *   <li>visible: passes where the fragment is in front of the scene (reversed-Z GEQ)</li>
 *   <li>occluded: passes where the fragment is behind the scene (LESS) — the
 *       CS:GO two-tone behind-wall pass</li>
 * </ul>
 */
public final class ChamsRenderType {
	/** Custom screen-space shader (samples the image by screen position, not model UVs). */
	private static final Identifier SCREEN_SHADER = UnluckyClientMod.id("core/chams_screen");
	/** End-portal fragment shader (vanilla layer effect in screen space; shares the screen vsh). */
	private static final Identifier PORTAL_SHADER = UnluckyClientMod.id("core/chams_portal");

	private static final RenderPipeline THROUGH_WALLS_PIPELINE =
			pipeline("pipeline/unlucky_chams", Optional.empty());
	// depth-tested passes re-render the SAME geometry as the real model, so they must
	// win the depth test against it (a positive depth bias — like vanilla's block-break
	// "crumbling" overlay) while still being occluded by nearer terrain. Without it the
	// chams z-fights and only shows at silhouette edges.
	private static final DepthStencilState ON_TOP = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0f, 10.0f);
	private static final RenderPipeline VISIBLE_PIPELINE =
			pipeline("pipeline/unlucky_chams_visible", Optional.of(ON_TOP));
	private static final RenderPipeline OCCLUDED_PIPELINE =
			pipeline("pipeline/unlucky_chams_occluded", Optional.of(new DepthStencilState(CompareOp.LESS_THAN, false)));
	// screen-space image variants: same pipeline, but our custom shader
	private static final RenderPipeline SCREEN_THROUGH_PIPELINE =
			screenPipeline("pipeline/unlucky_chams_screen", Optional.empty());
	private static final RenderPipeline SCREEN_DEPTH_PIPELINE =
			screenPipeline("pipeline/unlucky_chams_screen_depth", Optional.of(ON_TOP));
	// end-portal variants: screen vsh + the portal layer fsh (PORTAL_LAYERS mirrors vanilla)
	private static final RenderPipeline PORTAL_THROUGH_PIPELINE =
			portalPipeline("pipeline/unlucky_chams_portal", Optional.empty());
	private static final RenderPipeline PORTAL_DEPTH_PIPELINE =
			portalPipeline("pipeline/unlucky_chams_portal_depth", Optional.of(ON_TOP));

	private static final Function<Identifier, RenderType> THROUGH_WALLS = typeFor(THROUGH_WALLS_PIPELINE, "unlucky_chams");
	private static final Function<Identifier, RenderType> VISIBLE = typeFor(VISIBLE_PIPELINE, "unlucky_chams_visible");
	private static final Function<Identifier, RenderType> OCCLUDED = typeFor(OCCLUDED_PIPELINE, "unlucky_chams_occluded");
	private static final Function<Identifier, RenderType> SCREEN_THROUGH = typeFor(SCREEN_THROUGH_PIPELINE, "unlucky_chams_screen");
	private static final Function<Identifier, RenderType> SCREEN_DEPTH = typeFor(SCREEN_DEPTH_PIPELINE, "unlucky_chams_screen_depth");
	private static final Function<Identifier, RenderType> PORTAL_THROUGH = typeFor(PORTAL_THROUGH_PIPELINE, "unlucky_chams_portal");
	private static final Function<Identifier, RenderType> PORTAL_DEPTH = typeFor(PORTAL_DEPTH_PIPELINE, "unlucky_chams_portal_depth");

	private ChamsRenderType() {
	}

	private static RenderPipeline pipeline(String location, Optional<DepthStencilState> depth) {
		return RenderPipelines.register(baseBuilder(location, depth).build());
	}

	/** Like {@link #pipeline} but with our screen-space vertex/fragment shader. */
	private static RenderPipeline screenPipeline(String location, Optional<DepthStencilState> depth) {
		return RenderPipelines.register(baseBuilder(location, depth)
				.withVertexShader(SCREEN_SHADER)
				.withFragmentShader(SCREEN_SHADER)
				.build());
	}

	/**
	 * Screen vsh + the end-portal layer fsh. PORTAL_LAYERS mirrors vanilla's
	 * END_PORTAL pipeline (15; the gateway uses 16). GameTime is available because
	 * ENTITY_SNIPPET already chains the GLOBALS bind group (via MATRICES_FOG_SNIPPET).
	 */
	private static RenderPipeline portalPipeline(String location, Optional<DepthStencilState> depth) {
		return RenderPipelines.register(baseBuilder(location, depth)
				.withVertexShader(SCREEN_SHADER)
				.withFragmentShader(PORTAL_SHADER)
				.withShaderDefine("PORTAL_LAYERS", 15)
				.build());
	}

	private static RenderPipeline.Builder baseBuilder(String location, Optional<DepthStencilState> depth) {
		return RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
				.withLocation(location)
				.withShaderDefine("ALPHA_CUTOUT", 0.1F)
				.withShaderDefine("PER_FACE_LIGHTING")
				.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.withDepthStencilState(depth);
	}

	private static Function<Identifier, RenderType> typeFor(RenderPipeline pipeline, String name) {
		return Util.memoize(texture -> {
			RenderSetup setup = RenderSetup.builder(pipeline)
					.withTexture("Sampler0", texture)
					.useLightmap()
					.useOverlay()
					.sortOnUpload()
					.setOutline(RenderSetup.OutlineProperty.NONE)
					.createRenderSetup();
			return RenderType.create(name, setup);
		});
	}

	/** Forces the pipelines to register during client init (before first use). */
	public static void init() {
	}

	public static RenderType get(Identifier texture, boolean throughWalls) {
		return throughWalls ? THROUGH_WALLS.apply(texture) : RenderTypes.entityTranslucent(texture, false);
	}

	public static RenderType visible(Identifier texture) {
		return VISIBLE.apply(texture);
	}

	public static RenderType occluded(Identifier texture) {
		return OCCLUDED.apply(texture);
	}

	/** The custom "galaxy" chams image, sampled in screen space (CS:GO galaxy chams). */
	public static final Identifier IMAGE = UnluckyClientMod.id("textures/gui/chams.png");

	/** Blank white texture so a tint renders as a solid flat colour (CS:GO — no skin showing). */
	public static final Identifier WHITE = UnluckyClientMod.id("textures/white.png");

	/**
	 * Chams pass that paints the {@link #IMAGE} over the model in <em>screen space</em>
	 * — the image stays fixed while the model moves through it. Through-walls drops the
	 * depth test; otherwise terrain occludes it normally.
	 */
	public static RenderType image(boolean throughWalls) {
		return (throughWalls ? SCREEN_THROUGH : SCREEN_DEPTH).apply(IMAGE);
	}

	/** The vanilla end-portal starfield texture; every portal layer samples it.
	 *  26.2 moved it into an end_portal/ subfolder — the old flat path is gone. */
	private static final Identifier END_PORTAL_TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");

	/**
	 * Chams pass that paints the animated end-portal starfield over the model in
	 * screen space — like looking into an end portal shaped like a player.
	 */
	public static RenderType portal(boolean throughWalls) {
		return (throughWalls ? PORTAL_THROUGH : PORTAL_DEPTH).apply(END_PORTAL_TEXTURE);
	}
}
