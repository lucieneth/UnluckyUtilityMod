package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
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
import unlucky.utility.client.module.modules.movement.Jesus;
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
			LivingEntity entity = (LivingEntity) (Object) this;
			UnluckyClient.INSTANCE.session.onTotemPop(entity);
			UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.render.PopChams.class).onPop(entity);
		}
	}

	/**
	 * Jesus (Solid mode). Vanilla reaches this from {@code LiquidBlock.getCollisionShape}
	 * through the collision context, so answering "yes" hands the fluid a real
	 * collision box — the strider's own mechanic. Vanilla pairs it with an
	 * {@code isAbove} check, so being submerged still lets you swim.
	 */
	@Inject(method = "canStandOnFluid", at = @At("RETURN"), cancellable = true)
	private void unlucky$jesus(FluidState fluid, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() || (Object) this != Minecraft.getInstance().player) {
			return;
		}
		Jesus jesus = UnluckyClient.INSTANCE.modules.get(Jesus.class);
		// Saying "yes" here disables swim physics entirely (shouldTravelInFluid),
		// so a missing isEnabled() check leaves you unable to swim with the module
		// off — exactly the bug shipped on 2026-07-10.
		if (jesus.isEnabled() && jesus.standsOn(fluid)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * Jesus, half two. {@code LiquidBlock.getCollisionShape} collides the fluid
	 * against <em>this</em> shape, and {@code LivingEntity}'s default is
	 * {@code Shapes.empty()} — so {@code canStandOnFluid} alone gives you nothing
	 * to stand on. The strider overrides this with a half-height column; we hand
	 * back a box up to the rendered water surface.
	 */
	@Inject(method = "getLiquidCollisionShape", at = @At("RETURN"), cancellable = true)
	private void unlucky$jesusShape(CallbackInfoReturnable<VoxelShape> cir) {
		if ((Object) this == Minecraft.getInstance().player
				&& UnluckyClient.INSTANCE.modules.get(Jesus.class).solid()) {
			cir.setReturnValue(Jesus.SURFACE_SHAPE);
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
