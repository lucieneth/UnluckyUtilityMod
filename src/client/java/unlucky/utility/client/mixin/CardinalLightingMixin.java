package unlucky.utility.client.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay fullbright: remove the directional face shading (top brighter, sides and
 * bottom darker) so listed blocks render perfectly flat and evenly lit.
 * Pairs with the full light coords from {@link BlockModelLighterCacheMixin}.
 */
@Mixin(CardinalLighting.class)
public class CardinalLightingMixin {
	@Inject(method = "byFace", at = @At("HEAD"), cancellable = true)
	private void unlucky$xrayFlatShade(Direction direction, CallbackInfoReturnable<Float> cir) {
		if (XRay.fullbrightActive()) {
			cir.setReturnValue(1.0f);
		}
	}

	// fluids read the directional accessors directly instead of byFace, so flatten
	// those too (up * north/west shading) for evenly-lit water and lava
	@Inject(method = {"up", "down", "north", "south", "east", "west"}, at = @At("HEAD"), cancellable = true)
	private void unlucky$xrayFlatFaces(CallbackInfoReturnable<Float> cir) {
		if (XRay.fullbrightActive()) {
			cir.setReturnValue(1.0f);
		}
	}
}
