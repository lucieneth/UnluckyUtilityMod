package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.RainbowEnchant;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/**
 * RainbowEnchant's one hook: the colour uniform the glint draw is issued with.
 *
 * <p><b>Why here and not at the quads.</b> {@code RenderPipelines.GLINT} binds
 * {@code DefaultVertexFormat.POSITION_TEX} and {@code core/glint.vsh} declares only
 * {@code Position} and {@code UV0} — the glint vertex format has no colour attribute at all, so
 * writing a colour onto the quad going into the glint buffer is dropped on the floor. The fragment
 * shader's one colour input is {@code ColorModulator}, which arrives per draw in the
 * {@code DynamicTransforms} UBO. {@link RenderType#prepare()} is what fills that UBO, and it goes
 * through the two-argument {@code writeTransform} — the overload that hardcodes white. Swapping in
 * the four-argument overload is the only place a glint colour can be injected.
 *
 * <p><b>All four glint types, on purpose.</b> Matching on the render type covers held items, the
 * hotbar, inventories, dropped stacks and item frames ({@code glint}, {@code glintTranslucent})
 * <em>and</em> entity and worn-armour glint, which the old per-quad hook could never reach.
 *
 * <p>This runs once per glint draw call, not once per quad, so the allocation below is a few
 * objects a frame rather than a few hundred.
 */
@Mixin(RenderType.class)
public class RenderTypeMixin {
	@Shadow
	@Final
	private RenderSetup state;

	@WrapOperation(method = "writeDynamicTransforms", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice unlucky$glintColor(DynamicGpuData uniforms, Matrix4f modelView,
			Matrix4f textureMatrix, Operation<GpuBufferSlice> original) {
		if (unlucky$isGlint()) {
			int tint = UnluckyClient.INSTANCE.modules.get(RainbowEnchant.class).glintColor();
			if (tint != 0) {
				// modelOffset is the zero vector the two-argument overload would have passed
				return uniforms.writeTransform(modelView, unlucky$modulator(tint), new Vector3f(),
						textureMatrix);
			}
		}
		return original.call(uniforms, modelView, textureMatrix);
	}

	/**
	 * Glint is identified by its texture transform rather than by the render type.
	 *
	 * <p>Under 26.2 there were four glint singletons to compare against. 26.3 made
	 * glint render types <b>per texture</b> — {@code itemCutoutGlint(texture)},
	 * {@code entitySolidGlint(texture)} and friends each build a fresh type per
	 * {@code Identifier} — so there is no fixed set of instances left to match. What
	 * did not change is that every one of them scrolls its glint sampler with one of
	 * three {@link TextureTransform} singletons, and nothing else in the game uses
	 * those. Testing them covers held items, the hotbar, inventories, dropped stacks,
	 * item frames, entity glint and worn-armour glint — including the "special"
	 * variants, which the old four-way identity never reached.
	 *
	 * <p>The old comment's worry — that a future render type could reuse glint
	 * texturing for something that is not a glint — is now the price of admission
	 * rather than an avoidable risk, because per-texture types leave nothing else to
	 * key on. A glint transform is still a strong signal: it exists to animate a glint.
	 */
	private boolean unlucky$isGlint() {
		TextureTransform texturing = ((RenderSetupAccessor) (Object) this.state).unlucky$textureTransform();
		return texturing == TextureTransform.GLINT_TEXTURING
				|| texturing == TextureTransform.ENTITY_GLINT_TEXTURING
				|| texturing == TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING;
	}

	private static Vector4f unlucky$modulator(int argb) {
		return new Vector4f(
				(argb >> 16 & 0xFF) / 255.0f,
				(argb >> 8 & 0xFF) / 255.0f,
				(argb & 0xFF) / 255.0f,
				(argb >>> 24) / 255.0f);
	}
}
