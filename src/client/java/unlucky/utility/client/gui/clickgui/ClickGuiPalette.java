package unlucky.utility.client.gui.clickgui;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.Theme;

/**
 * The accent the ClickGUI setting rows draw with, following whichever style is on screen.
 *
 * <p>Both styles deliberately share one set of component classes so their behaviour cannot
 * drift apart — which means the components must not reach for {@link Theme#accent1} by
 * hand. Doing so left Future's aqua glass full of Skeet-green checkboxes, sliders and
 * dropdown marks: every control the user actually touches was themed by the wrong client.
 *
 * <p>Future is defined as a single configurable accent rather than a gradient, so both
 * ends of a gradient collapse onto {@link FuturePalette#accent()} there and the ramp
 * flattens to that one color. Only these accents move; the recessed greys and border
 * blacks around them are neutral enough for both looks and stay put.
 */
public final class ClickGuiPalette {
	private ClickGuiPalette() {
	}

	/** True while the Future renderer is the selected style, and so the one drawing. */
	public static boolean future() {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		return theme != null && theme.clickGuiStyle.is("Future");
	}

	/** Gradient start, or Future's single accent. */
	public static int accent1() {
		return future() ? FuturePalette.accent() : Theme.accent1;
	}

	/** Gradient end, or Future's single accent. */
	public static int accent2() {
		return future() ? FuturePalette.accent() : Theme.accent2;
	}

	/** Point on the accent ramp, t in [0, 1] — flat under Future. */
	public static int accent(float t) {
		return future() ? FuturePalette.accent() : Theme.accent(t);
	}
}
