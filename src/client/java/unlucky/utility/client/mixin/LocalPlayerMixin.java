package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import net.minecraft.client.gui.screens.Screen;
import unlucky.utility.client.module.modules.movement.InventoryMove;
import unlucky.utility.client.module.modules.movement.NoFall;
import unlucky.utility.client.module.modules.movement.NoSlow;
import unlucky.utility.client.module.modules.player.AntiHunger;

/**
 * Shapes the outgoing movement packets for NoFall and AntiHunger — both lie
 * about the same {@code onGround} flag, so they share one hook.
 *
 * <p>{@code sendPosition} passes {@code this.onGround()} into every packet
 * variant (PosRot / Pos / Rot / StatusOnly) and into its own {@code lastOnGround}
 * bookkeeping. Redirecting the call covers all of them at once, which keeps the
 * spoof self-consistent — a partial lie would make the client send spurious
 * status packets.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
	@Shadow
	private float itemUseSpeedMultiplier() {
		throw new AssertionError();
	}

	/**
	 * NoSlow. modifyInput scales the move vector by itemUseSpeedMultiplier() while
	 * an item is in use — hand back 1 instead and the slowdown never happens.
	 */
	@Redirect(method = "modifyInput",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F"))
	private float unlucky$noSlowItemUse(LocalPlayer self) {
		NoSlow noSlow = UnluckyClient.INSTANCE.modules.get(NoSlow.class);
		if (noSlow.isEnabled() && noSlow.items.get() && self == Minecraft.getInstance().player) {
			return 1.0f;
		}
		return this.itemUseSpeedMultiplier(); // redirect's receiver is always this
	}

	/**
	 * InventoryMove's portal option. Inside a portal, handlePortalTransitionEffect
	 * closes any screen that isn't the pause menu — that check is this call. Say yes
	 * and the inventory / ClickGUI survive while the portal charges.
	 */
	@Redirect(method = "handlePortalTransitionEffect",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z"))
	private boolean unlucky$screensInPortal(Screen screen) {
		if (UnluckyClient.INSTANCE.modules.get(InventoryMove.class).allowInPortal()) {
			return true;
		}
		return screen.isAllowedInPortal();
	}

	@Redirect(method = "sendPosition",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
	private boolean unlucky$spoofOnGround(LocalPlayer self) {
		boolean real = self.onGround();
		if (real || self != Minecraft.getInstance().player) {
			return real; // standing on ground: nothing worth lying about
		}
		// The server runs updateFallFlying on the state we report, so claiming to be
		// grounded mid-glide makes it stop the elytra for us.
		boolean gliding = self.isFallFlying();

		AntiHunger antiHunger = UnluckyClient.INSTANCE.modules.get(AntiHunger.class);
		if (antiHunger.isEnabled() && antiHunger.spoofGround.get() && !gliding) {
			return true; // server never sees the jump, so it charges no jump exhaustion
		}

		NoFall noFall = UnluckyClient.INSTANCE.modules.get(NoFall.class);
		if (!noFall.isEnabled() || (gliding && noFall.elytra.get())) {
			return real;
		}
		if (noFall.mode.is("Constant")) {
			return true;
		}
		// Packet mode: only lie once the drop is far enough to hurt, so ordinary
		// jumping still looks honest to the server
		return self.fallDistance > noFall.minFall.get();
	}

	@Inject(method = "sendIsSprintingIfNeeded", at = @At("HEAD"), cancellable = true)
	private void unlucky$spoofSprint(CallbackInfo ci) {
		AntiHunger antiHunger = UnluckyClient.INSTANCE.modules.get(AntiHunger.class);
		if (antiHunger.isEnabled() && antiHunger.spoofSprint.get()) {
			ci.cancel(); // server keeps thinking we walk; sprint costs nothing
		}
	}
}
