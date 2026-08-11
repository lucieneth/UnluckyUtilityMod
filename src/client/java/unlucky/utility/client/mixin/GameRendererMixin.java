package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.visuals.NoHurtCam;
import unlucky.utility.client.module.modules.render.NoRender;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void unlucky$noHurtCam(CallbackInfo ci) {
		if (UnluckyClient.INSTANCE.modules.get(NoHurtCam.class).isEnabled()) {
			ci.cancel();
		}
	}

	/**
	 * In 26.2 portal and nausea are combined into the projection strength before
	 * vanilla applies its spin/zoom matrix. Zeroing that one visual-only value
	 * keeps portal travel and the status effect fully intact.
	 */
	@Redirect(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Ljava/lang/Math;max(FF)F"))
	private float unlucky$noPortalNausea(float portalStrength, float nauseaStrength) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		return module.isEnabled() && module.portalNauseaEffect.get()
				? 0.0f : Math.max(portalStrength, nauseaStrength);
	}
}
