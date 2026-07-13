package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.util.FriendManager;

/**
 * Friends: blue {@code •} before friend names in the tablist.
 * {@code getNameForDisplay} is the single source for the shown name — the
 * overlay calls it both when measuring column widths and when drawing, so
 * wrapping the return value here keeps layout and render consistent.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void unlucky$friendDot(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		Friends friends = UnluckyClient.INSTANCE.modules.get(Friends.class);
		if (!friends.marksTablist(info.getProfile().id())) {
			return;
		}
		cir.setReturnValue(Component.empty()
				.append(Component.literal(FriendManager.DOT + " ").withColor(FriendManager.TEXT_COLOR))
				.append(cir.getReturnValue()));
	}
}
