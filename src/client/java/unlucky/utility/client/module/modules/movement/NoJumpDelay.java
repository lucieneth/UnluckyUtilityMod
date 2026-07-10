package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;

/**
 * Removes the 10-tick cooldown between jumps (handled by
 * {@code LivingEntityMixin}). Lets you jump every tick you touch the ground.
 */
public class NoJumpDelay extends Module {
	public NoJumpDelay() {
		super("NoJumpDelay", "Removes the delay between jumps", Category.MOVEMENT);
	}
}
