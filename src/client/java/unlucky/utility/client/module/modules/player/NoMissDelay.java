package unlucky.utility.client.module.modules.player;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;

/**
 * Drops the half-second lockout vanilla puts on the attack key after a swing at nothing.
 *
 * <p>A miss sets {@code Minecraft.missTime} to 10, and {@code startAttack} refuses every click
 * until it counts down. We clear it at the end of each tick, after {@code handleKeybinds}, so
 * the next tick's click goes through. The 10000 vanilla writes while a screen is open is left
 * alone: that one exists so the click that closes a menu does not also swing.
 */
public class NoMissDelay extends Module {
	private static final int SCREEN_LOCK = 10000;

	public NoMissDelay() {
		super("NoMissDelay", "Removes the attack cooldown after missing", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().missTime > 0 && mc().missTime < SCREEN_LOCK) {
			mc().missTime = 0;
		}
	}
}
