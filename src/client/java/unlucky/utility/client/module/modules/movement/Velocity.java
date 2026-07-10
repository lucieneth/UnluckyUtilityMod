package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Scales incoming knockback (handled by {@code ClientPacketListenerMixin}).
 * 0 = no knockback, 1 = normal.
 */
public class Velocity extends Module {
	public final NumberSetting horizontal = add(new NumberSetting("Horizontal", "Sideways knockback taken", 0.0, 0.0, 1.0, 0.01));
	public final NumberSetting vertical = add(new NumberSetting("Vertical", "Upward knockback taken", 0.0, 0.0, 1.0, 0.01));

	public Velocity() {
		super("Velocity", "Reduces knockback taken", Category.MOVEMENT);
	}
}
