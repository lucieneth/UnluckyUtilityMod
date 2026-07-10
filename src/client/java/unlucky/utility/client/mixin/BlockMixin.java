package unlucky.utility.client.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay: faces against hidden blocks aren't actually covered — render them.
 * Hooked at the static root so both the vanilla and the fabric-renderer
 * pipelines are covered.
 */
@Mixin(Block.class)
public class BlockMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private static void unlucky$xrayFaces(BlockState state, BlockState neighborState, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		if (XRay.active() && !XRay.hides(state) && XRay.hides(neighborState)) {
			cir.setReturnValue(true);
		}
	}
}
