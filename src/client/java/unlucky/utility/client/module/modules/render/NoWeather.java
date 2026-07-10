package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Permanent clear skies, client-side only. Zeroing the rain/thunder levels
 * ({@code LevelMixin}) removes the falling rain, the sky darkening and the fog
 * tint in one go; {@code ClientLevelMixin} additionally skips the weather tick,
 * which is what spawns rain particles and the ambient downpour sound.
 * The server still thinks it's raining — crops grow, mobs spawn as normal.
 */
public class NoWeather extends Module {
	public final BooleanSetting rain = add(new BooleanSetting("Rain",
			"Hide rain and snow, and mute the ambient rain sound", true));
	public final BooleanSetting thunder = add(new BooleanSetting("Thunder",
			"Stop the sky darkening and the lightning screen flash", true));

	public NoWeather() {
		super("NoWeather", "Clear skies, always", Category.RENDER);
	}
}
