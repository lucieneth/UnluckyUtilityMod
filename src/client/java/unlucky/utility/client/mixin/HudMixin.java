package unlucky.utility.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;

/**
 * NoRender: the carved-pumpkin overlay. In 26.2 the head-worn camera overlay is
 * data-driven — {@code Equippable.cameraOverlay()} on the head item — and drawn
 * through {@code Hud.extractTextureOverlay}, which fires <em>only</em> for that
 * (the spyglass has its own path). Cancelling it removes the pumpkin vignette,
 * and any modded head item that ships a camera overlay.
 */
@Mixin(Hud.class)
public class HudMixin {
	@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
	private void unlucky$noPumpkinOverlay(GuiGraphicsExtractor graphics, Identifier texture, float alpha, CallbackInfo ci) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (module.isEnabled() && module.pumpkinOverlay.get()) {
			ci.cancel();
		}
	}
}
