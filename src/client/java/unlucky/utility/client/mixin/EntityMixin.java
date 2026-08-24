package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.AutoSprint;
import unlucky.utility.client.module.modules.movement.BoatFly;
import unlucky.utility.client.module.modules.movement.EntitySpeed;
import unlucky.utility.client.module.modules.movement.NoPush;
import unlucky.utility.client.module.modules.movement.Step;
import unlucky.utility.client.module.modules.movement.Velocity;

/** Vehicle movement hooks placed immediately before vanilla resolves collisions. */
@Mixin(Entity.class)
public class EntityMixin {
	/**
	 * Every drop of the local player's sprint flag, reported to AutoSprint.
	 *
	 * <p>AutoSprint only ever writes {@code true}, so a {@code false} here is always
	 * somebody else's — vanilla cancelling at a wall, or an attack's sprint reset — and
	 * that is exactly the event its "Keep sprinting" setting is a preference about.
	 */
	@Inject(method = "setSprinting", at = @At("HEAD"))
	private void unlucky$sprintCleared(boolean value, CallbackInfo ci) {
		if ((Object) this != Minecraft.getInstance().player) {
			return;
		}
		if (!value) {
			UnluckyClient.INSTANCE.modules.get(AutoSprint.class).noteSprintCleared();
		}
	}

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

	/**
	 * The one place a collision becomes velocity, and both modules that care about it live here.
	 *
	 * <p>Order matters and is not arbitrary: NoPush decides whether the push happens at all — it
	 * is the only one of the two that knows <em>who</em> is pushing, since {@code this} is the
	 * source and the wrapped argument is the receiver — and Velocity then scales whatever survived.
	 * Two separate wraps would each see a different vector depending on load order.
	 */
	@WrapOperation(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
	private void unlucky$velocityEntityPush(Entity receiver, double x, double y, double z,
			Operation<Void> original) {
		Vec3 push = UnluckyClient.INSTANCE.modules.get(NoPush.class)
				.entityPush((Entity) (Object) this, receiver, new Vec3(x, y, z));
		push = UnluckyClient.INSTANCE.modules.get(Velocity.class).entityPush(receiver, push);
		if (push.lengthSqr() != 0.0) {
			original.call(receiver, push.x, push.y, push.z);
		}
	}

	/**
	 * Step, for the vehicles {@code LivingEntity} does not cover: boats, minecarts, anything else
	 * that never overrides {@code maxUpStep()}. A living vehicle is handled by the override in
	 * {@code LivingEntityMixin}, so exactly one of the two hooks answers for any given entity.
	 */
	@Inject(method = "maxUpStep", at = @At("RETURN"), cancellable = true)
	private void unlucky$stepHeight(CallbackInfoReturnable<Float> cir) {
		float raised = UnluckyClient.INSTANCE.modules.get(Step.class)
				.stepHeight((Entity) (Object) this, cir.getReturnValueF());
		if (raised != cir.getReturnValueF()) {
			cir.setReturnValue(raised);
		}
	}
}
