package unlucky.utility.client.mixin;

import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay fullbright, Indigo path. Fabric's Indigo renderer (the default block
 * renderer) uses its own {@link AoCalculator} instead of vanilla ambient
 * occlusion, so the vanilla hooks don't reach it. After it computes per-vertex
 * AO factors and light, force them flat and full-bright for listed blocks.
 */
@Mixin(value = AoCalculator.class, remap = false)
public class AoCalculatorMixin {
	private static final int FULL_BRIGHT = 0xF000F0;

	@Shadow
	public float[] ao;
	@Shadow
	public int[] light;

	@Inject(method = "compute", at = @At("TAIL"))
	private void unlucky$flatFullbright(CallbackInfo ci) {
		if (!XRay.fullbrightActive()) {
			return;
		}
		for (int i = 0; i < ao.length; i++) {
			ao[i] = 1.0f;
		}
		for (int i = 0; i < light.length; i++) {
			light[i] = FULL_BRIGHT;
		}
	}
}
