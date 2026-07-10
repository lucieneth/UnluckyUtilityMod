package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay: hidden blocks don't occlude fluid faces, so water renders as a full
 * volume (surface + faces against the now-invisible ground) instead of a
 * floating one-block-thick sheet. Fullbright also lights fluids to light 16.
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Inject(method = "isFaceOccludedByNeighbor", at = @At("HEAD"), cancellable = true)
	private static void unlucky$xrayFluidFaces(Direction direction, float height, BlockState neighborState,
			CallbackInfoReturnable<Boolean> cir) {
		if (XRay.active() && XRay.hides(neighborState)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
	private void unlucky$xrayFluidLight(BlockAndTintGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (XRay.fullbrightActive()) {
			cir.setReturnValue(0xF000F0);
		}
	}
}
