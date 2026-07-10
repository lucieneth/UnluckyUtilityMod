package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.AntiLevitation;
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

	/**
	 * AntiLevitation. travelInAir does {@code getEffect(LEVITATION)} then null-checks
	 * it, so handing back null drops the upward pull and falls through to normal
	 * gravity. We test the holder ourselves rather than pinning an ordinal.
	 */
	@Redirect(method = "travelInAir", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;getEffect(Lnet/minecraft/core/Holder;)Lnet/minecraft/world/effect/MobEffectInstance;"))
	private MobEffectInstance unlucky$antiLevitation(LivingEntity self, Holder<MobEffect> effect) {
		if (self == Minecraft.getInstance().player && effect == MobEffects.LEVITATION) {
			AntiLevitation module = UnluckyClient.INSTANCE.modules.get(AntiLevitation.class);
			if (module.isEnabled() && module.levitation.get()) {
				return null;
			}
		}
		return self.getEffect(effect);
	}

	/** AntiLevitation's optional slow-falling half: getEffectiveGravity asks once. */
	@Redirect(method = "getEffectiveGravity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/LivingEntity;hasEffect(Lnet/minecraft/core/Holder;)Z"))
	private boolean unlucky$antiSlowFalling(LivingEntity self, Holder<MobEffect> effect) {
		if (self == Minecraft.getInstance().player && effect == MobEffects.SLOW_FALLING) {
			AntiLevitation module = UnluckyClient.INSTANCE.modules.get(AntiLevitation.class);
			if (module.isEnabled() && module.slowFalling.get()) {
				return false;
			}
		}
		return self.hasEffect(effect);
	}
}
