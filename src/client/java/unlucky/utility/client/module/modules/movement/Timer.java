package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Runs the client's game loop faster or slower than 20 ticks a second.
 *
 * <p>Vanilla turns real time into ticks by dividing by {@code getTickTargetMillis} (50 ms, or
 * the server's slower rate under {@code /tick}); {@code MinecraftMixin} divides that by the
 * speed here. Everything that ticks on the client speeds up with it — movement, item use,
 * module ticks — and so does the rate of movement packets, which is what an anticheat sees.
 */
public class Timer extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed",
			"Game speed multiplier (1 = vanilla)", 1.5, 0.1, 5.0, 0.05));

	public Timer() {
		super("Timer", "Speeds up or slows down the game tick rate", Category.MOVEMENT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	/** The divisor for vanilla's tick length: 1 while off. */
	public float multiplier() {
		return isEnabled() ? Math.max(0.1f, speed.getFloat()) : 1.0f;
	}
}
