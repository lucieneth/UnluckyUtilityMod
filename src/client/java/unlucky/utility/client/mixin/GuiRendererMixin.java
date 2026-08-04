package unlucky.utility.client.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.gui.clickgui.FuturePanelBlur;

/** Clips the stock menu-blur result to the Future ClickGUI's registered panels. */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
	private static final String PROCESS_BLUR = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V";

	@Inject(method = "draw", at = @At(value = "INVOKE", target = PROCESS_BLUR, shift = At.Shift.BEFORE))
	private void unlucky$saveSharpFutureBackdrop(CallbackInfo ci) {
		FuturePanelBlur.beforeVanillaBlur();
	}

	@Inject(method = "draw", at = @At(value = "INVOKE", target = PROCESS_BLUR, shift = At.Shift.AFTER))
	private void unlucky$clipFutureBackdropBlur(CallbackInfo ci) {
		FuturePanelBlur.afterVanillaBlur();
	}
}
