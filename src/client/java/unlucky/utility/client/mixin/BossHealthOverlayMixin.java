package unlucky.utility.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.NoRender;

/** NoRender: skips extracting the boss bars, so nothing reaches the GUI render state. */
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void unlucky$noBossBars(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		NoRender module = UnluckyClient.INSTANCE.modules.get(NoRender.class);
		if (module.isEnabled() && module.bossBars.get()) {
			ci.cancel();
		}
	}
}
