package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Gives the worn elytra cape-like sway instead of rendering rigid. Purely
 * visual: it nudges the wing rotations by the same motion-derived values the
 * cape uses ({@code capeFlap}/{@code capeLean}), so the wings flutter and lean
 * as you move. Inspired by OhHeyItsJosh's Elytra-Physics.
 */
public class ElytraPhysics extends Module {
	public final NumberSetting intensity = add(new NumberSetting("Intensity",
			"How much the elytra sways with movement", 1.0, 0.0, 3.0, 0.1));

	public ElytraPhysics() {
		super("ElytraPhysics", "Cape-like sway physics for the worn elytra", Category.RENDER);
	}
}
