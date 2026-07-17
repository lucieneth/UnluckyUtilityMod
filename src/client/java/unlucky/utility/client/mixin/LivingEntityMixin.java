package unlucky.utility.client.mixin;

import java.util.List;

import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

	/**
	 * FakeFly stands in for the missing elytra — and <b>only</b> for that.
	 *
	 * <p>{@code canGlide()} answers two separate questions at once:
	 * <pre>if (onGround() || isPassenger() || hasEffect(LEVITATION)) return false;
	 * // ...then: is anything glideable equipped?</pre>
	 * Overriding the whole thing also overrides the first line, and that line is
	 * load-bearing: {@code updateFallFlying} clearing shared flag 7 when
	 * {@code canGlide()} goes false is the <b>only</b> place a glide ever ends. Say
	 * yes on the ground and you simply never land. So we re-check vanilla's own
	 * preconditions and only answer the equipment question.
	 */
	@Inject(method = "canGlide", at = @At("RETURN"), cancellable = true)
	private void unlucky$fakeFly(CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!cir.getReturnValueZ()
				&& !self.onGround()
				&& !self.isPassenger()
				&& !self.hasEffect(MobEffects.LEVITATION)
				&& UnluckyClient.INSTANCE.modules.get(FakeFly.class).isEnabled()
				&& unlucky$isOurGlider(self)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * The other half of FakeFly, and the half that isn't optional.
	 *
	 * <p>Once {@code canGlide()} says yes, {@code updateFallFlying} walks on to the
	 * durability tick — every 20 gliding ticks it collects the slots holding
	 * something glideable and picks one to damage:
	 * <pre>Util.getRandom(slots, random)</pre>
	 * With no elytra that list is <b>empty</b>, so vanilla asks for
	 * {@code nextInt(0)} and throws "Bound must be positive", killing the server
	 * thread. It never happens in vanilla because {@code canGlide()} can't be true
	 * with an empty list — our override is exactly what makes that state reachable.
	 *
	 * <p>Cancelling here skips the damage we were never going to be able to apply,
	 * which is also the point of the module: no elytra, no durability. The cost is
	 * the ELYTRA_GLIDE game event on those ticks, and a glide with no elytra has no
	 * business emitting it anyway. An empty list can only mean canGlide() was
	 * overridden, so this stays narrow without needing to name the module.
	 */
	@Inject(method = "updateFallFlying",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/util/Util;getRandom(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;"),
			cancellable = true)
	private void unlucky$fakeFlyNoWingsToWear(CallbackInfo ci, @Local List<EquipmentSlot> glideSlots) {
		if (glideSlots.isEmpty()) {
			ci.cancel();
		}
	}

	/**
	 * Us — either our LocalPlayer, or the integrated server's ServerPlayer standing
	 * in for us.
	 *
	 * <p>The second half is the whole reason FakeFly works at all. 26.2 gates the
	 * glide re-check in {@code updateFallFlying} behind
	 * {@code if (!level().isClientSide())}: the <b>server</b> owns gliding, and it
	 * clears shared flag 7 the moment {@code canGlide()} says no. Matching only the
	 * LocalPlayer left the integrated server unconvinced, so it corrected us one
	 * round trip after takeoff — the "few frames". Singleplayer and LAN run that
	 * server in this JVM, on this very class, so recognising its ServerPlayer is
	 * enough to make it agree. UUID-matched, so on a LAN world we only ever answer
	 * for ourselves and never hand a guest free flight.
	 *
	 * <p>On a real server there is nothing to do: it's a different machine running
	 * unmixed vanilla. See FakeFly's javadoc.
	 */
	@Unique
	private static boolean unlucky$isOurGlider(LivingEntity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player) {
			return true;
		}
		return mc.hasSingleplayerServer() && mc.player != null
				&& entity instanceof ServerPlayer server
				&& server.getUUID().equals(mc.player.getUUID());
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
