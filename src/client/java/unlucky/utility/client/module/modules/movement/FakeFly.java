package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;

/**
 * Glide exactly like with an elytra — without one equipped. Keep your
 * chestplate on, lose no durability. Jump mid-air to start gliding, rockets
 * work as normal. The glide-equipment check is overridden in
 * {@code LivingEntityMixin}; whether flight holds is up to the server.
 */
public class FakeFly extends Module {
	public FakeFly() {
		super("FakeFly", "Elytra flight without an elytra", Category.MOVEMENT);
	}
}
