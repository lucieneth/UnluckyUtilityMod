package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * No fall damage. The server derives fall damage from the {@code onGround} flag
 * on our movement packets — it resets its own fall distance whenever we claim to
 * be grounded — so {@code LocalPlayerMixin} lies about that flag while we fall.
 * Nothing is spoofed client-side, so the world still looks and feels normal.
 */
public class NoFall extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Packet: only lie while actually falling. Constant: always claim to be grounded",
			"Packet", "Packet", "Constant"));
	public final NumberSetting minFall = add(new NumberSetting("Min fall distance",
			"Packet mode: start lying once you've fallen this far (blocks)", 3.0, 0.0, 10.0, 0.5));
	public final BooleanSetting elytra = add(new BooleanSetting("Disable during elytra",
			"Leave elytra flight alone — claiming to be grounded mid-glide can cancel it", true));

	public NoFall() {
		super("NoFall", "Removes fall damage", Category.MOVEMENT);
	}
}
