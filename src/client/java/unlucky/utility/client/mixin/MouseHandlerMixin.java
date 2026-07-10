package unlucky.utility.client.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Freecam;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	@Redirect(method = "turnPlayer",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void unlucky$freecamTurn(LocalPlayer player, double yRot, double xRot) {
		Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
		if (freecam.isEnabled()) {
			freecam.turn(yRot, xRot);
		} else {
			player.turn(yRot, xRot);
		}
	}
}
