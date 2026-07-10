package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.visuals.Fullbright;

@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void unlucky$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
		Fullbright fullbright = UnluckyClient.INSTANCE.modules.get(Fullbright.class);
		if (fullbright.isEnabled()) {
			// borrow the night vision path of the lightmap for a clean fullbright
			renderState.nightVisionEffectIntensity = Math.max(renderState.nightVisionEffectIntensity, fullbright.intensity.getFloat());
		}
		// XRay fullbright is handled at mesh time (BlockModelLighterCacheMixin),
		// forcing true light-16 coords on the ores rather than curving the lightmap
	}
}
