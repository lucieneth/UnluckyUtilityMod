package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.RainbowEnchant;

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
	@WrapOperation(method = "writeDynamicTransforms", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
	private GpuBufferSlice unlucky$glintColor(DynamicUniforms uniforms, Matrix4f modelView,
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
	 * Identity against the four singletons, rather than reading the render setup's texture
	 * transform: it needs no accessor, and it cannot quietly start matching a future render type
	 * that happens to reuse glint texturing for something that is not a glint.
	 */
	private boolean unlucky$isGlint() {
		Object self = this;
		return self == RenderTypes.glint()
				|| self == RenderTypes.glintTranslucent()
				|| self == RenderTypes.entityGlint()
				|| self == RenderTypes.armorEntityGlint();
	}

	private static Vector4f unlucky$modulator(int argb) {
		return new Vector4f(
				(argb >> 16 & 0xFF) / 255.0f,
				(argb >> 8 & 0xFF) / 255.0f,
				(argb & 0xFF) / 255.0f,
				(argb >>> 24) / 255.0f);
	}
}
