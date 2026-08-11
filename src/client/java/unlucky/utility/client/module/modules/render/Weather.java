package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.WeatherOverrideManager;

/** Rich client weather requester sharing the existing hooks with transitional NoWeather. */
public class Weather extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Rendered weather", "Server", "Server", "Clear", "Rain", "Thunder", "Snow"));
	public final NumberSetting rainStrength = add(new NumberSetting("Rain strength", "Rendered rain/snow strength", 1, 0, 1, 0.05));
	public final NumberSetting thunderStrength = add(new NumberSetting("Thunder strength", "Rendered thunder strength", 1, 0, 1, 0.05), () -> mode.is("Thunder"));
	public final NumberSetting precipitationOpacity = add(new NumberSetting("Precipitation opacity",
			"Opacity of rendered rain or snow", 1, 0, 1, 0.05));
	public final BooleanSetting particles = add(new BooleanSetting("Particles",
			"Allow vanilla weather particles", true));
	public final BooleanSetting ambientSound = add(new BooleanSetting("Ambient weather sound",
			"Allow rain and thunder ambience", true));
	public final BooleanSetting skyFlash = add(new BooleanSetting("Sky flash", "Allow lightning screen flashes", true));
	public final BooleanSetting snowEverywhere = add(new BooleanSetting("Snow everywhere", "Render snow precipitation in every biome", false), () -> mode.is("Snow"));

	public Weather() {
		super("Weather", "Overrides client weather through one shared owner", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override protected void onEnable() { request(); }
	@Override public void onTick() { request(); }
	@Override protected void onDisable() { WeatherOverrideManager.release(this); }

	private void request() {
		WeatherOverrideManager.Mode selected = WeatherOverrideManager.Mode.valueOf(mode.get().toUpperCase());
		WeatherOverrideManager.request(this, WeatherOverrideManager.State.forMode(selected,
				rainStrength.getFloat(), thunderStrength.getFloat(), precipitationOpacity.getFloat(),
				particles.get(), ambientSound.get(), skyFlash.get(), snowEverywhere.get()), 10);
	}
}
