package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.ui.Theme;

/**
 * Convenience drawing helpers on top of {@link GuiGraphicsExtractor}.
 * All coordinates are in GUI-scaled pixels.
 */
public final class Render2D {
	public static final int FONT_HEIGHT = 9;

	private Render2D() {
	}

	public static Font font() {
		return Minecraft.getInstance().font;
	}

	public static int width(String text) {
		return font().width(text);
	}

	public static void text(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.text(font(), text, x, y, color, true);
	}

	public static void textNoShadow(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		g.text(font(), text, x, y, color, false);
	}

	/**
	 * Text filled with a two-color gradient running along a {@code \} diagonal.
	 * Drawn as 1px horizontal strips (scissored), each colored by its screen-space
	 * diagonal coordinate, so advancing {@code phase} over time sweeps the band
	 * left to right. Pass {@code phase = 0} for a static diagonal.
	 */
	public static void diagonalGradientText(GuiGraphicsExtractor g, String text, int x, int y, float scale,
			int colorA, int colorB, float phase) {
		Font font = font();
		int textW = Math.round(font.width(text) * scale);
		int textH = Math.round(FONT_HEIGHT * scale) + 1;
		float period = Math.max(textW, 16) * 1.3f; // gradient wavelength across the text
		for (int sy = 0; sy < textH; sy++) {
			int stripY = y + sy;
			g.enableScissor(x, stripY, x + textW + 2, stripY + 1);
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			pose.scale(scale, scale);
			int localX = 0;
			for (int i = 0; i < text.length(); i++) {
				String ch = String.valueOf(text.charAt(i));
				int cw = font.width(ch);
				float screenCenterX = x + (localX + cw / 2f) * scale;
				// screenX - screenY = const traces a "\" line (screen y points down)
				float t = fract((screenCenterX - stripY - phase) / period);
				g.text(font, ch, localX, 0, ColorUtil.lerp(colorA, colorB, triangle(t)), true);
				localX += cw;
			}
			pose.popMatrix();
			g.disableScissor();
		}
	}

	private static float fract(float v) {
		return v - (float) Math.floor(v);
	}

	/** Ping-pong 0->1->0 so a repeating gradient has no hard seam. */
	private static float triangle(float t) {
		return t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f;
	}

	/** Draws text with a per-character sweep across the accent gradient. */
	public static void gradientText(GuiGraphicsExtractor g, String text, int x, int y) {
		int length = text.length();
		for (int i = 0; i < length; i++) {
			String character = String.valueOf(text.charAt(i));
			g.text(font(), character, x, y, Theme.accent(i, Math.max(length, 2)), true);
			x += width(character);
		}
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fill(x, y, x + w, y + h, color);
	}

	public static void verticalGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fillGradient(x, y, x + w, y + h, top, bottom);
	}

	/** Horizontal gradient approximated with vertical strips. */
	public static void horizontalGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right) {
		if (w <= 0 || h <= 0) {
			return;
		}
		for (int i = 0; i < w; i++) {
			g.fill(x + i, y, x + i + 1, y + h, ColorUtil.lerp(left, right, (float) i / w));
		}
	}

	/** Rounded rectangle built from fills; radius is clamped to fit. */
	public static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w, h) / 2);
		if (r <= 0) {
			rect(g, x, y, w, h, color);
			return;
		}
		// center band
		g.fill(x, y + r, x + w, y + h - r, color);
		// top and bottom rows with circular insets
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt((double) r * r - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** Rounded rectangle with a horizontal accent gradient. */
	public static void roundedGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int left, int right) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w, h) / 2);
		for (int i = 0; i < w; i++) {
			int color = ColorUtil.lerp(left, right, (float) i / w);
			int inset = 0;
			if (i < r) {
				double dx = r - i - 0.5;
				inset = r - (int) Math.round(Math.sqrt((double) r * r - dx * dx));
			} else if (i >= w - r) {
				double dx = i - (w - r) + 0.5;
				inset = r - (int) Math.round(Math.sqrt((double) r * r - dx * dx));
			}
			g.fill(x + i, y + inset, x + i + 1, y + h - inset, color);
		}
	}

	public static boolean hovered(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	/** Arbitrary-angle 2D line via a rotated fill. */
	public static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float thickness, int color) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 0.01f) {
			return;
		}
		int t = Math.max(1, Math.round(thickness));
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x1, y1);
		pose.rotate((float) Math.atan2(dy, dx));
		g.fill(0, -t / 2, Math.round(length), -t / 2 + t, color);
		pose.popMatrix();
	}

	/**
	 * Skeet-style checkbox square: recessed when off, accent gradient when on.
	 * {@code t} is the on/off animation value in [0, 1].
	 */
	public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, float t) {
		g.fill(x, y, x + size, y + size, Theme.borderDark);
		int inner = size - 2;
		if (t < 1.0f) {
			verticalGradient(g, x + 1, y + 1, inner, inner, 0xFF3A3A3A, 0xFF2A2A2A);
		}
		if (t > 0.0f) {
			verticalGradient(g, x + 1, y + 1, inner, inner,
					ColorUtil.multiplyAlpha(Theme.accent2, t), ColorUtil.multiplyAlpha(Theme.accent1, t));
		}
	}
}
