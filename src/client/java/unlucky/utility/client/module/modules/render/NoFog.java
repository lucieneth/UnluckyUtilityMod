package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Clears the fog that comes from where you are — render distance and the two
 * hazy dimensions (applied in {@code FogRendererMixin}).
 *
 * <p>Fog that comes from what's <em>happening to you</em> — water, lava, powder
 * snow, blindness, darkness — lives on <b>NoRender</b> instead.
 */
public class NoFog extends Module {
	public final BooleanSetting distance = add(new BooleanSetting("Distance",
			"Far render-distance fog", true));
	public final BooleanSetting nether = add(new BooleanSetting("Nether",
			"The thick red haze of the Nether", true));
	public final BooleanSetting end = add(new BooleanSetting("End",
			"The murky haze of the End", true));

	public NoFog() {
		super("NoFog", "Removes distance and dimension fog", Category.RENDER);
	}
}
