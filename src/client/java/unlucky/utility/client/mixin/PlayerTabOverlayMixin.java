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
	/** The "this player runs Unlucky" mark, in the color that player chose. */
	private static final String MARKER = "✦";

	@Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
	private void unlucky$friendDot(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
		java.util.UUID uuid = info.getProfile().id();
		Component name = cir.getReturnValue();

		int marker = UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.misc.UnluckyUsers.class).markerFor(uuid);
		int friendColor = UnluckyClient.INSTANCE.modules.get(Friends.class).tablistDotColor(uuid);
		if (marker == 0 && friendColor == 0) {
			return;
		}

		// dot • name ✦ — the friend dot leads, the Unlucky star trails
		Component decorated = name;
		if (friendColor != 0) {
			decorated = Component.empty()
					.append(Component.literal(FriendManager.DOT + " ").withColor(friendColor & 0xFFFFFF))
					.append(decorated);
		}
		if (marker != 0) {
			decorated = Component.empty()
					.append(decorated)
					.append(Component.literal(" " + MARKER).withColor(marker & 0xFFFFFF));
		}
		cir.setReturnValue(decorated);
	}
}
