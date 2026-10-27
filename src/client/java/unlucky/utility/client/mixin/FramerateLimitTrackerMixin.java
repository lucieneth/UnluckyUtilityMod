package unlucky.utility.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.FocusFps;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
	/** FocusFps: the unfocused cap, on top of whatever vanilla already decided. */
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void unlucky$focusFps(CallbackInfoReturnable<Integer> cir) {
		FocusFps focusFps = UnluckyClient.INSTANCE.modules.get(FocusFps.class);
		if (focusFps != null) {
			cir.setReturnValue(focusFps.apply(cir.getReturnValue()));
		}
	}
}
