package unlucky.utility.client.util;

public final class ColorUtil {
	private ColorUtil() {
	}

	public static int argb(int alpha, int red, int green, int blue) {
		return (alpha << 24) | (red << 16) | (green << 8) | blue;
	}

	public static int lerp(int from, int to, float t) {
		t = Math.clamp(t, 0.0f, 1.0f);
		int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return argb(a, r, g, b);
	}

	public static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | (Math.clamp(alpha, 0, 255) << 24);
	}

	/** Multiplies the existing alpha channel by {@code factor} in [0, 1]. */
	public static int multiplyAlpha(int color, float factor) {
		int alpha = (int) (((color >>> 24) & 0xFF) * Math.clamp(factor, 0.0f, 1.0f));
		return withAlpha(color, alpha);
	}

	public static int hsb(float hue, float saturation, float brightness, int alpha) {
		int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
		return withAlpha(rgb, alpha);
	}

	public static float[] toHsb(int argb) {
		return java.awt.Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, null);
	}
}
