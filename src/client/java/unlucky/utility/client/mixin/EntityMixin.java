package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.BoatFly;
import unlucky.utility.client.module.modules.movement.EntitySpeed;
import unlucky.utility.client.module.modules.movement.Velocity;

/** Vehicle movement hooks placed immediately before vanilla resolves collisions. */
@Mixin(Entity.class)
public class EntityMixin {
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 unlucky$vehicleMovement(Vec3 movement) {
		Entity self = (Entity) (Object) this;
		if (self instanceof AbstractBoat boat) {
			return UnluckyClient.INSTANCE.modules.get(BoatFly.class).movement(boat, movement);
		}
		if (self instanceof LivingEntity living) {
			return UnluckyClient.INSTANCE.modules.get(EntitySpeed.class).movement(living, movement);
		}
		return movement;
	}

	@WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
	private void unlucky$velocityEntityPush(Entity receiver, double x, double y, double z,
			Operation<Void> original) {
		Vec3 push = UnluckyClient.INSTANCE.modules.get(Velocity.class)
				.entityPush(receiver, new Vec3(x, y, z));
		if (push.lengthSqr() != 0.0) {
			original.call(receiver, push.x, push.y, push.z);
		}
	}
}
