package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.FastUse;
import unlucky.utility.client.util.EspGlow;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
	private void unlucky$espGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && EspGlow.colorFor(entity) != 0) {
			cir.setReturnValue(true);
		}
	}

	// startUseItem sets rightClickDelay to 4; RETURN (not TAIL) so early exits
	// are covered too. Shortening a delay that was never set is harmless.
	@Inject(method = "startUseItem", at = @At("RETURN"))
	private void unlucky$fastUse(CallbackInfo ci) {
		FastUse fastUse = UnluckyClient.INSTANCE.modules.get(FastUse.class);
		Minecraft mc = Minecraft.getInstance();
		if (!fastUse.isEnabled() || mc.player == null) {
			return;
		}
		if (fastUse.appliesTo(mc.player.getMainHandItem(), mc.player.getOffhandItem())) {
			this.rightClickDelay = Math.min(this.rightClickDelay, fastUse.delay.getInt());
		}
	}
}
