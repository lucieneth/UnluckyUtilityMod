package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.AutoReconnect;

/**
 * AutoReconnect's countdown and its two buttons, on the screen you land on when a server
 * drops you.
 *
 * <p>The screen is the right place for both halves. It is where the disconnect <em>reason</em>
 * first exists in a form we can read, so the classification happens here rather than being
 * reconstructed later; and it is the only moment the player is looking at the decision, so
 * "go now" and "don't" belong here rather than in a chat message they would have to catch.
 *
 * <p>Extending {@link Screen} is what lets the mixin call the protected
 * {@code addRenderableWidget} and read {@code width}/{@code height} — the standard shape for
 * adding widgets to a vanilla screen. The buttons go at fixed coordinates under the vanilla
 * layout rather than into its {@code LinearLayout}, so a future change to that layout cannot
 * throw here.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
	private static final int BUTTON_WIDTH = 100;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;
	/** Distance from the bottom of the screen, clear of vanilla's own buttons. */
	private static final int BOTTOM_MARGIN = 30;

	/**
	 * Whether this screen has already had its disconnect classified.
	 *
	 * <p>{@code init()} runs again on every window resize, and classification is destructive —
	 * it consumes the "a module asked for this" flag. Re-running it would turn a suppressed
	 * AutoLog logout into an ordinary kick, and reconnect you into the fight you just left,
	 * because you happened to resize the window while the countdown was up.
	 */
	@Unique
	private boolean unlucky$classified;

	protected DisconnectedScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void unlucky$autoReconnect(CallbackInfo ci) {
		AutoReconnect module = UnluckyClient.INSTANCE.modules.get(AutoReconnect.class);
		if (!unlucky$classified) {
			unlucky$classified = true;
			// The reason lives in the screen's title, which is the message the server sent.
			module.onDisconnected(this.title);
		}
		if (!module.isEnabled() || !module.showCountdown.get() || !module.hasTarget()) {
			return;
		}

		int y = this.height - BOTTOM_MARGIN;
		int left = this.width / 2 - BUTTON_WIDTH - GAP / 2;
		Button reconnect = Button
				.builder(unlucky$label(module), button -> module.reconnect())
				.bounds(left, y, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(reconnect);
		module.setCountdownButton(reconnect);

		addRenderableWidget(Button
				.builder(Component.literal("Cancel"), button -> {
					module.cancel();
					reconnect.setMessage(Component.literal("Reconnect now"));
				})
				.bounds(this.width / 2 + GAP / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
				.build());
	}

	private static Component unlucky$label(AutoReconnect module) {
		return Component.literal(module.isScheduled()
				? "Reconnecting in " + module.secondsLeft() + "s"
				: "Reconnect now");
	}
}
