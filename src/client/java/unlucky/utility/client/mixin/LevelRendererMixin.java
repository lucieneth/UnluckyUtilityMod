package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
}
