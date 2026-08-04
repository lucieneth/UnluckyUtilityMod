package unlucky.utility.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.gui.FrameBlur;

/**
 * Reopens the frame's blur claim, once, at the top of GUI extraction.
 *
 * <p>This is the only point that runs every frame regardless of what is on screen.
 * The obvious alternative — clearing the claim on the way out, in {@code GuiRendererMixin}
 * — silently does not: its injections sit around {@code processBlurEffect}, which vanilla
 * skips entirely on frames where nothing blurred, so the claim would stick and no menu
 * would ever blur again. Neither will the HUD element do: F1 skips it.
 */
@Mixin(Gui.class)
public class GuiBlurMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void unlucky$openFrameBlur(DeltaTracker delta, boolean bl, boolean bl2, CallbackInfo ci) {
		FrameBlur.beginFrame();
	}
}
