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
	public final unlucky.utility.client.settings.ModeSetting colorMode =
			add(new unlucky.utility.client.settings.ModeSetting("Color input",
					"How every color picker in the client takes input. Picker is the hue/saturation/"
							+ "value bars; HEX takes a #RRGGBB code; RGB takes three 0-255 channels. "
							+ "Same as clicking the tabs inside any picker.",
					"Picker", "Picker", "HEX", "RGB"));
	public final unlucky.utility.client.settings.ModeSetting barStyle =
			add(new unlucky.utility.client.settings.ModeSetting("Top bar",
					"The strip along the top of the ClickGUI. Rainbow runs the whole wheel; "
							+ "Accent flows between your two accent colors; Static holds one color.",
					"Rainbow", "Rainbow", "Accent", "Static"));
	public final ColorSetting barColor = add(new ColorSetting("Top bar color",
			"The color the strip holds", Theme.accent1), () -> barStyle.is("Static"));
	public final unlucky.utility.client.settings.NumberSetting barSpeed =
			add(new unlucky.utility.client.settings.NumberSetting("Top bar speed",
					"How fast the strip flows", 1.0, 0.1, 4.0, 0.1), () -> !barStyle.is("Static"));
	public final unlucky.utility.client.settings.ModeSetting guiScaling =
			add(new unlucky.utility.client.settings.ModeSetting("GUI scaling",
					"What dragging the ClickGUI's corner does. Reflow keeps the module boxes one "
							+ "size and fits more or fewer columns as the window changes shape; "
							+ "Zoom keeps the window exactly as it looks now and makes the whole "
							+ "thing bigger or smaller.",
					"Reflow", "Reflow", "Zoom"));
	public final unlucky.utility.client.settings.ModeSetting guiOpensOn = add(new unlucky.utility.client.settings.ModeSetting(
			"GUI opens on", "The page the ClickGUI shows on its first open after launch",
			"Search", "Search", "Combat", "Player", "Movement", "Render", "World", "Misc"));
	public final unlucky.utility.client.settings.NumberSetting moduleLines =
			add(new unlucky.utility.client.settings.NumberSetting("Module lines",
					"Rows a module box shows before the rest folds away behind the ... in its "
							+ "bottom-right corner. Boxes shorter than this never get one.",
					12, 4, 40, 1));

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

