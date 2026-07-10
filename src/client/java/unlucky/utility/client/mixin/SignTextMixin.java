package unlucky.utility.client.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.AntiToS;

@Mixin(SignText.class)
public class SignTextMixin {
	@Inject(method = "getMessages", at = @At("RETURN"), cancellable = true)
	private void unlucky$censorSigns(boolean shouldFilter, CallbackInfoReturnable<Component[]> cir) {
		AntiToS antiToS = UnluckyClient.INSTANCE.modules.get(AntiToS.class);
		if (!antiToS.isEnabled()) {
			return;
		}
		Component[] original = cir.getReturnValue();
		Component[] censored = null;
		for (int i = 0; i < original.length; i++) {
			Component result = antiToS.censor(original[i]);
			if (result != original[i]) {
				if (censored == null) {
					censored = original.clone();
				}
				censored[i] = result;
			}
		}
		if (censored != null) {
			cir.setReturnValue(censored);
		}
	}
}
