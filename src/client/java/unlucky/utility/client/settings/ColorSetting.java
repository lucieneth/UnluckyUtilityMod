package unlucky.utility.client.settings;

import unlucky.utility.client.util.ColorUtil;

/** Stores an ARGB color. */
public class ColorSetting extends Setting<Integer> {
	public ColorSetting(String name, String description, int defaultArgb) {
		super(name, description, defaultArgb);
	}

	public float[] hsb() {
		return ColorUtil.toHsb(get());
	}

	public int alpha() {
		return (get() >>> 24) & 0xFF;
	}

	public void setHsb(float hue, float saturation, float brightness, int alpha) {
		set(ColorUtil.hsb(hue, saturation, brightness, alpha));
	}
}
