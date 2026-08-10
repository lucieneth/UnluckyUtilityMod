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
import unlucky.utility.client.module.modules.movement.ClickTP;
import unlucky.utility.client.module.modules.movement.TridentFly;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.player.FastUse;
import unlucky.utility.client.util.EspGlow;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Shadow
	private int rightClickDelay;

	/**
	 * Marks a disconnect as coming from our side rather than the server's.
	 *
	 * <p>AutoReconnect has to tell "you clicked Disconnect" from "the server dropped you", and
	 * the protocol offers nothing to distinguish them — both end at the same screen with a
	 * message. But every local departure passes through this method and no remote one does,
	 * so the fact is recorded here rather than guessed from the wording afterwards.
	 */
	@Inject(method = "disconnectFromWorld", at = @At("HEAD"))
	private void unlucky$localDisconnect(net.minecraft.network.chat.Component reason, CallbackInfo ci) {
		unlucky.utility.client.module.modules.misc.AutoReconnect.onLocalDisconnect();
	}

	@Inject(method = "shouldEntityAppearGlowing", at = @At("RETURN"), cancellable = true)
	private void unlucky$espGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && EspGlow.colorFor(entity) != 0) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * Right-click actions that replace the vanilla use. ClickTP and TridentFly
	 * both want this click, so they share one handler with an explicit order —
	 * two cancellable injects at the same point would fire in undefined order,
	 * and a cancel by one wouldn't stop the other from running.
	 *
	 * <p>Cancelling matters for TridentFly: vanilla would otherwise charge, and
	 * on release <em>throw</em>, an unenchanted trident.
	 */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void unlucky$rightClickActions(CallbackInfo ci) {
		// AutoEat eats by holding the use key, which drives this every tick —
		// don't let an interact module hijack the meal.
		if (AutoEat.busy()) {
			return;
		}
		ClickTP clickTp = UnluckyClient.INSTANCE.modules.get(ClickTP.class);
		if (clickTp.isEnabled() && clickTp.button.is("Right") && clickTp.tryTeleport()) {
			ci.cancel();
			return;
		}
		TridentFly tridentFly = UnluckyClient.INSTANCE.modules.get(TridentFly.class);
		if (tridentFly.isEnabled() && tridentFly.tryBoost()) {
			ci.cancel();
		}
	}

	/** ClickTP on middle-click, which vanilla spends on pick-block. */
	/**
	 * Keeps vanilla off a destroy the Printer is driving.
	 *
	 * <p>{@code continueAttack} calls {@code stopDestroyBlock()} on every tick the attack
	 * key is not held, and our module tick runs after it — so a module that mines by
	 * calling start/continue itself had its progress reset to zero every single tick and
	 * never broke anything, while every call it made returned success. Dropping the
	 * vanilla pass for those ticks is the whole fix; the player is not left-clicking
	 * anyway, so nothing else is lost.
	 */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void unlucky$keepMining(boolean leftDown, CallbackInfo ci) {
		if (leftDown) {
			return;
		}
		if (UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.world.Printer.class).isMining()
				|| UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.world.VeinMiner.class).isMining()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void unlucky$middleClickTeleport(CallbackInfo ci) {
		ClickTP clickTp = UnluckyClient.INSTANCE.modules.get(ClickTP.class);
		if (clickTp.isEnabled() && clickTp.button.is("Middle") && clickTp.tryTeleport()) {
			ci.cancel();
		}
	}

	// startUseItem sets rightClickDelay to 4; RETURN (not TAIL) so early exits
	// are covered too. Shortening a delay that was never set is harmless.
	// (A HEAD cancel above returns before this ever runs.)
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
