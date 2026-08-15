package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.WeatherOverrideManager;

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
		super("NoWeather", "Clear skies, always", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		request();
	}

	@Override
	public void onTick() {
		request();
	}

	@Override
	protected void onDisable() {
		WeatherOverrideManager.release(this);
	}

	private void request() {
		WeatherOverrideManager.request(this,
				WeatherOverrideManager.State.noWeather(rain.get(), thunder.get()));
	}
}
