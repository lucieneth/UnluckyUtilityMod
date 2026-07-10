package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay fullbright: force full light coords while meshing an in-range section.
 * Only the listed blocks are tesselated during xray, so every light lookup
 * here belongs to a shown block — returning full-bright lights the ores at
 * light 16 no matter how deep underground they are.
 */
@Mixin(BlockModelLighter.Cache.class)
public class BlockModelLighterCacheMixin {
	private static final int FULL_BRIGHT = 0xF000F0;

	@Inject(method = "getLightCoords", at = @At("HEAD"), cancellable = true)
	private void unlucky$xrayFullbright(BlockState state, BlockAndTintGetter level, BlockPos pos,
			CallbackInfoReturnable<Integer> cir) {
		if (XRay.fullbrightActive()) {
			cir.setReturnValue(FULL_BRIGHT);
		}
	}
}
