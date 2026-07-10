package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.FakeFly;
import unlucky.utility.client.module.modules.movement.NoJumpDelay;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow
	private int noJumpDelay;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void unlucky$noJumpDelay(CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player
				&& UnluckyClient.INSTANCE.modules.get(NoJumpDelay.class).isEnabled()) {
			this.noJumpDelay = 0;
		}
	}

	@Inject(method = "canGlide", at = @At("RETURN"), cancellable = true)
	private void unlucky$fakeFly(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()
				&& (Object) this == Minecraft.getInstance().player
				&& UnluckyClient.INSTANCE.modules.get(FakeFly.class).isEnabled()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void unlucky$totemPop(byte id, CallbackInfo ci) {
		if (id == 35) { // totem of undying pop
			UnluckyClient.INSTANCE.session.onTotemPop((LivingEntity) (Object) this);
		}
	}
}
