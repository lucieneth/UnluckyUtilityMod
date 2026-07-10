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
	private static final RenderPipeline THROUGH_WALLS_PIPELINE =
			pipeline("pipeline/unlucky_chams", Optional.empty());
	private static final RenderPipeline VISIBLE_PIPELINE =
			pipeline("pipeline/unlucky_chams_visible", Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)));
	private static final RenderPipeline OCCLUDED_PIPELINE =
			pipeline("pipeline/unlucky_chams_occluded", Optional.of(new DepthStencilState(CompareOp.LESS_THAN, false)));

	private static final Function<Identifier, RenderType> THROUGH_WALLS = typeFor(THROUGH_WALLS_PIPELINE, "unlucky_chams");
	private static final Function<Identifier, RenderType> VISIBLE = typeFor(VISIBLE_PIPELINE, "unlucky_chams_visible");
	private static final Function<Identifier, RenderType> OCCLUDED = typeFor(OCCLUDED_PIPELINE, "unlucky_chams_occluded");

	private ChamsRenderType() {
	}

	private static RenderPipeline pipeline(String location, Optional<DepthStencilState> depth) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
				.withLocation(location)
				.withShaderDefine("ALPHA_CUTOUT", 0.1F)
				.withShaderDefine("PER_FACE_LIGHTING")
				.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withCull(false)
				.withDepthStencilState(depth)
				.build());
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
}
