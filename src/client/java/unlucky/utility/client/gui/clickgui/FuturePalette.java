package unlucky.utility.client.gui.clickgui;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.util.ColorUtil;

/**
 * The small palette used exclusively by the Future renderer.
 *
 * <p>Future has one configurable accent, but its glass must not become a
 * fully-saturated copy of that accent. These helpers keep the selected hue,
 * lower its saturation and brightness, and leave the caller in control of
 * opacity. That gives aqua a dark aqua backing, purple a dark purple backing,
 * and so on without compromising text contrast.
 */
public final class FuturePalette {
	private static final int FALLBACK_ACCENT = 0xFFE00000;

	private FuturePalette() {
	}

	/** The user-selected Future accent, or classic Future red before modules load. */
	public static int accent() {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		return theme == null ? FALLBACK_ACCENT : theme.futureColor.get();
	}

	/** Main category/expanded-row glass: dark, colored, and deliberately muted. */
	public static int panelSurface(int alpha) {
		return toned(0.23f, 0.35f, 0.15f, alpha);
	}

	/** Denser chrome for the navigation rail and small tooltips. */
	public static int chromeSurface(int alpha) {
		return toned(0.14f, 0.30f, 0.10f, alpha);
	}

	/** A subtle separator that stays visibly related to the selected hue. */
	public static int seam(int alpha) {
		return toned(0.30f, 0.38f, 0.16f, alpha);
	}

	private static int toned(float brightness, float saturationScale, float saturationFloor, int alpha) {
		float[] hsb = ColorUtil.toHsb(accent());
		// Keep neutral accents neutral instead of arbitrarily giving grey a hue.
		float saturation = hsb[1] < 0.02f ? 0.0f
				: Math.clamp(hsb[1] * saturationScale, saturationFloor, 0.50f);
		return ColorUtil.hsb(hsb[0], saturation, brightness, alpha);
	}
}
