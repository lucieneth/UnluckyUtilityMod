package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.BlockOutline;
import unlucky.utility.client.util.StorageEspRenderer;

/**
 * Storage ESP rides the block-entity submission pass: it is the point in the
 * frame where a {@link SubmitNodeCollector} and a camera-relative pose stack are
 * both in hand, which is all the outline mask needs.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Inject(method = "submitBlockEntities", at = @At("TAIL"))
	private void unlucky$submitStorageEsp(PoseStack poseStack, LevelRenderState renderState,
			SubmitNodeCollector collector, CallbackInfo ci) {
		StorageEspRenderer.submit(poseStack, collector);
	}

	/**
	 * The selected-block outline, and the only place it is decided.
	 *
	 * <p>Wrapping the submission rather than cancelling {@code submitBlockOutline} outright is
	 * what lets BlockOutline restyle the outline without taking over drawing it: shape, colour and
	 * width are all arguments here, so the ordinary case stays vanilla's own per-frame submission
	 * with different numbers in it. Only the cases vanilla cannot express — a fill, an outline
	 * through terrain, a face marker — answer {@code draw = false} and draw themselves elsewhere.
	 *
	 * <p>The replacement state is built with the seven-argument constructor so the three debug
	 * shapes survive: the four-argument one passes null for them, and the block-collision debug
	 * renderer reads all three.
	 */
	@WrapOperation(method = "submitBlockOutline", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V"))
	private void unlucky$blockOutline(LevelRenderer self, PoseStack pose, SubmitNodeCollector collector,
			RenderType renderType, BlockOutlineRenderState state, int color, float width,
			boolean translucent, Operation<Void> original) {
		BlockOutline module = UnluckyClient.INSTANCE.modules.get(BlockOutline.class);
		BlockOutline.Decision decision = module.decide(state.pos(), state.shape(), color, width);
		if (!decision.draw()) {
			return;
		}
		BlockOutlineRenderState submitted = decision.shape() == state.shape() ? state
				: new BlockOutlineRenderState(state.pos(), state.isTranslucent(), state.highContrast(),
						decision.shape(), state.collisionShape(), state.occlusionShape(),
						state.interactionShape());
		original.call(self, pose, collector, renderType, submitted, decision.color(),
				decision.width(), translucent);
	}
}
