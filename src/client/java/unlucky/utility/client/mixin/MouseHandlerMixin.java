package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.module.modules.render.Freecam;
import unlucky.utility.client.module.modules.render.Freelook;
import unlucky.utility.client.module.modules.visuals.Zoom;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	/**
	 * Friends: middle-click a player under the crosshair to add/remove them.
	 * Runs in-game only (no screen, no overlay); vanilla's own middle-click
	 * (pick block) still proceeds — on a player it does nothing in survival.
	 */
	@Inject(method = "onButton", at = @At("HEAD"))
	private void unlucky$friendToggle(long handle, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (action != 1 || buttonInfo.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE
				|| handle != mc.getWindow().handle()
				|| mc.gui.screen() != null || mc.gui.overlay() != null || mc.player == null) {
			return;
		}
		if (mc.crosshairPickEntity instanceof AbstractClientPlayer player && player != mc.player) {
			UnluckyClient.INSTANCE.modules.get(Friends.class).onMiddleClick(player);
		}
	}

	@Redirect(method = "turnPlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void unlucky$freecamTurn(LocalPlayer player, double yRot, double xRot) {
		Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
		Freelook freelook = UnluckyClient.INSTANCE.modules.get(Freelook.class);
		if (freecam.isEnabled()) {
			freecam.turn(yRot, xRot);
		} else if (freelook.isActive()) {
			// the body stays put — the deltas steer the camera instead
			freelook.turn(yRot, xRot);
		} else {
			player.turn(yRot, xRot);
		}
	}

	/**
	 * Zoom's mouse-wheel step. While the zoom key is held the wheel changes the
	 * zoom factor instead of the hotbar slot, so the scroll is swallowed.
	 */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void unlucky$zoomScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (handle != mc.getWindow().handle() || mc.gui.screen() != null || mc.player == null) {
			return;
		}
		if (UnluckyClient.INSTANCE.modules.get(Zoom.class).onScroll(yOffset)) {
			ci.cancel();
		}
	}
}
