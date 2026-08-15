package unlucky.utility.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.network.syncher.SynchedEntityData;
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
import unlucky.utility.client.util.SprintProbe;
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
	 * The sprint probe's two witnesses, both off unless it is recording.
	 *
	 * <p>They exist because the flag was seen changing outside every window the first
	 * recording covered, and there are exactly two ways that can happen: someone calls
	 * the setter, or the server assigns the shared-flags byte straight into synched data
	 * — which does not go through the setter at all.
	 */
	@Inject(method = "setSprinting", at = @At("HEAD"))
	private void unlucky$sprintProbeWrite(boolean value, CallbackInfo ci) {
		if ((Object) this != Minecraft.getInstance().player) {
			return;
		}
		if (!value) {
			// AutoSprint only ever writes true, so every drop is somebody else's —
			// which is the event "Keep sprinting" decides what to do about.
			UnluckyClient.INSTANCE.modules.get(AutoSprint.class).noteSprintCleared();
		}
		if (SprintProbe.recording()) {
			SprintProbe.flagWrite(value, SprintProbe.caller());
		}
	}

	@Inject(method = "onSyncedDataUpdated(Ljava/util/List;)V", at = @At("RETURN"))
	private void unlucky$sprintProbeSync(List<SynchedEntityData.DataValue<?>> values, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (SprintProbe.recording() && self == Minecraft.getInstance().player) {
			SprintProbe.flagWrite(self.isSprinting(), "SYNC<" + SprintProbe.caller());
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
