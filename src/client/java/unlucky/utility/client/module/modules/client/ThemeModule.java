package unlucky.utility.client.module.modules.client;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.ui.Theme;

/** Live restyling of the client's accent gradient. */
public class ThemeModule extends Module {
	public final ColorSetting accent1 = add(new ColorSetting("Accent 1", "Gradient start color", Theme.accent1));
	public final ColorSetting accent2 = add(new ColorSetting("Accent 2", "Gradient end color", Theme.accent2));
	public final BooleanSetting blur = add(new BooleanSetting("Blur", "Blur behind client menus (costs FPS)", true));

	public ThemeModule() {
		super("Theme", "Colors of the client", Category.MISC);
		setEnabledSilently(true);
	}

	@Override
	public void onTick() {
		apply();
	}

	public void apply() {
		Theme.accent1 = accent1.get();
		Theme.accent2 = accent2.get();
	}
}

