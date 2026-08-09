package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.Velocity;

/** Cancels only the local pull applied by fishing-hook entity event 31. */
@Mixin(FishingHook.class)
public class FishingHookMixin {
	@WrapOperation(method = "handleEntityEvent", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/projectile/FishingHook;pullEntity(Lnet/minecraft/world/entity/Entity;)V"))
	private void unlucky$velocityFishingPull(FishingHook hook, Entity entity, Operation<Void> original) {
		Velocity velocity = UnluckyClient.INSTANCE.modules.get(Velocity.class);
		if (!hook.level().isClientSide() || !velocity.preventsFishingPull(entity)) {
			original.call(hook, entity);
		}
	}
}
