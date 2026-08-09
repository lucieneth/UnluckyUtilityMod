package unlucky.utility.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.EntityControl;

/** EntityControl's two narrow vanilla gates: controller selection and saddle-only jumping. */
@Mixin(Mob.class)
public class MobMixin {
	@Inject(method = "getControllingPassenger", at = @At("RETURN"), cancellable = true)
	private void unlucky$entityController(CallbackInfoReturnable<LivingEntity> cir) {
		EntityControl module = UnluckyClient.INSTANCE.modules.get(EntityControl.class);
		cir.setReturnValue(module.controller((Mob) (Object) this, cir.getReturnValue()));
	}

	@Inject(method = "isSaddled", at = @At("RETURN"), cancellable = true)
	private void unlucky$entityControlSaddle(CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()
				&& UnluckyClient.INSTANCE.modules.get(EntityControl.class).controls((Mob) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
