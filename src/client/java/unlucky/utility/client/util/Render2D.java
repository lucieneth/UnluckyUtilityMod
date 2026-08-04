package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.hud.HudWidget;
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
		HudWidget style = HudWidget.activeStyle();
		String shown = style == null ? text : style.styleText(text);
		return Math.round(font().width(shown) * (style == null ? 1.0f : style.textScale()));
	}

	public static void text(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		drawText(g, text, x, y, color, true);
	}

	public static void textNoShadow(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		drawText(g, text, x, y, color, false);
	}

	private static void drawText(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean defaultShadow) {
		HudWidget style = HudWidget.activeStyle();
		String shown = style == null ? text : style.styleText(text);
		int shownColor = styleColor(color);
		float scale = style == null ? 1.0f : style.textScale();
		String treatment = style == null ? (defaultShadow ? "Shadow" : "Plain") : style.textStyle();
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		if ("Outline".equals(treatment)) {
			int outline = ColorUtil.withAlpha(0xFF000000, Math.max(40, (shownColor >>> 24) & 0xFF));
			g.text(font(), shown, -1, 0, outline, false);
			g.text(font(), shown, 1, 0, outline, false);
			g.text(font(), shown, 0, -1, outline, false);
			g.text(font(), shown, 0, 1, outline, false);
		}
		g.text(font(), shown, 0, 0, shownColor, "Shadow".equals(treatment));
		pose.popMatrix();
	}

	private static int styleColor(int color) {
		HudWidget style = HudWidget.activeStyle();
		return style == null ? color : style.styleColor(color);
	}

	/**
	 * Text filled with a two-color gradient running along a {@code \} diagonal.
	 * Drawn as 1px horizontal strips (scissored), each colored by its screen-space
	 * diagonal coordinate, so advancing {@code phase} over time sweeps the band
	 * left to right. Pass {@code phase = 0} for a static diagonal.
	 */
	public static void diagonalGradientText(GuiGraphicsExtractor g, String text, int x, int y, float scale,
			int colorA, int colorB, float phase) {
		HudWidget style = HudWidget.activeStyle();
		if (style != null) {
			text = style.styleText(text);
			scale *= style.textScale();
			colorA = style.styleColor(colorA);
			colorB = style.styleColor(colorB);
		}
		Font font = font();
		int textW = Math.round(font.width(text) * scale);
		int textH = Math.round(FONT_HEIGHT * scale) + 1;
		float period = Math.max(textW, 16) * 1.3f; // gradient wavelength across the text
		// per-char strings/widths are identical for every strip — measure once, not
		// once per strip (this ran ~10x redundantly per frame; Phase 10 Tier 2)
		int length = text.length();
		String[] chars = new String[length];
		int[] widths = new int[length];
		for (int i = 0; i < length; i++) {
			chars[i] = String.valueOf(text.charAt(i));
			widths[i] = font.width(chars[i]);
		}
		for (int sy = 0; sy < textH; sy++) {
			int stripY = y + sy;
			g.enableScissor(x, stripY, x + textW + 2, stripY + 1);
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x, y);
			pose.scale(scale, scale);
			int localX = 0;
			for (int i = 0; i < length; i++) {
				float screenCenterX = x + (localX + widths[i] / 2f) * scale;
				// screenX - screenY = const traces a "\" line (screen y points down)
				float t = fract((screenCenterX - stripY - phase) / period);
				g.text(font, chars[i], localX, 0, ColorUtil.lerp(colorA, colorB, triangle(t)), true);
				localX += widths[i];
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
		HudWidget style = HudWidget.activeStyle();
		if (style != null) text = style.styleText(text);
		int length = text.length();
		for (int i = 0; i < length; i++) {
			String character = String.valueOf(text.charAt(i));
			int color = style == null ? Theme.accent(i, Math.max(length, 2))
					: style.accentAt(x, Math.max(g.guiWidth(), 1));
			text(g, character, x, y, color);
			x += width(character);
		}
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fill(x, y, x + w, y + h, styleColor(color));
	}

	public static void verticalGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fillGradient(x, y, x + w, y + h, styleColor(top), styleColor(bottom));
	}

	/** Horizontal gradient approximated with vertical strips. */
	public static void horizontalGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right) {
		if (w <= 0 || h <= 0) {
			return;
		}
		for (int i = 0; i < w; i++) {
			g.fill(x + i, y, x + i + 1, y + h, styleColor(ColorUtil.lerp(left, right, (float) i / w)));
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
		int styledColor = styleColor(color);
		// center band
		g.fill(x, y + r, x + w, y + h - r, styledColor);
		// top and bottom rows with circular insets
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = r - (int) Math.round(Math.sqrt((double) r * r - dy * dy));
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, styledColor);
			g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, styledColor);
		}
	}

	/**
	 * Shared HUD panel chrome. Widgets keep their own background toggle, while
	 * opacity, corner radius and the optional outline are controlled once from
	 * the HUD module rather than being copied into every widget's settings.
	 */
	public static void hudPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean background) {
		hudPanel(g, x, y, w, h, Theme.hudBg(background));
	}

	/** Variant for panels whose background is already faded by an animation. */
	public static void hudPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		HudWidget style = HudWidget.activeStyle();
		if (style != null && !style.claimSharedPanel()) return;
		boolean wrapper = style != null && style.isSharedWrapperPass();
		int radius = style == null ? Theme.hudPanelRadius : style.cornerRadius();
		// Extra padding belongs to the single complete-widget wrapper. In the
		// default Widget mode this method may be called for many internal cells;
		// expanding every one made row backgrounds overlap and compound in opacity.
		int pad = wrapper ? style.extraPadding() : 0;
		x -= pad;
		y -= pad;
		w += pad * 2;
		h += pad * 2;
		String background = style == null ? "Widget" : style.backgroundMode();
		if ("Blur".equals(background)) {
			roundedRect(g, x, y, w, h, radius, 0x660B1018);
		} else if ("Glass".equals(background)) {
			roundedRect(g, x, y, w, h, radius, 0x50141A24);
		} else if ("Gradient".equals(background)) {
			roundedGradient(g, x, y, w, h, radius, 0x7010161E, 0x70202635);
		} else if ("Flat".equals(background)) {
			roundedRect(g, x, y, w, h, radius, Theme.hudBackground);
		} else if (!"None".equals(background)) {
			roundedRect(g, x, y, w, h, radius, color);
		}
		int sourceAlpha = (color >>> 24) & 0xFF;
		String border = style == null ? "Widget" : style.borderMode();
		if (style != null && !wrapper && !"Widget".equals(border)) border = "Off";
		boolean drawBorder = style == null ? Theme.hudPanelBorder
				: "Static".equals(border) || "Animated".equals(border)
						|| ("Widget".equals(border) && Theme.hudPanelBorder);
		boolean explicitBorder = style != null && ("Static".equals(border) || "Animated".equals(border));
		if (drawBorder && (sourceAlpha > 0 || !"Widget".equals(background) || explicitBorder)
				&& Theme.hudPanelBorderOpacity > 0.0f) {
			int alpha = Math.round(sourceAlpha * Theme.hudPanelBorderOpacity);
			if (alpha <= 0) alpha = Math.round(255 * Theme.hudPanelBorderOpacity);
			if (style != null) alpha = Math.round(alpha * style.styleOpacity());
			int borderColor;
			if (style == null) {
				borderColor = ColorUtil.withAlpha(Theme.hudScreenAccentY(y + h / 2, g.guiHeight()), alpha);
			} else if ("Static".equals(border)) {
				borderColor = ColorUtil.withAlpha(style.accentAt(0, 1), alpha);
			} else {
				borderColor = ColorUtil.withAlpha(style.accentAt(y + h / 2, g.guiHeight()), alpha);
			}
			if (style != null && "Animated".equals(border)) {
				drawAnimatedWidgetBorder(g, style, x, y, w, h, alpha);
			} else {
				g.outline(x, y, w, h, borderColor);
			}
		}
	}

	private static void drawAnimatedWidgetBorder(GuiGraphicsExtractor g, HudWidget style,
			int x, int y, int w, int h, int alpha) {
		float phase = (System.currentTimeMillis() % 2200L) / 2200.0f;
		for (int px = 0; px < w; px++) {
			float wave = 0.55f + 0.45f * (float) Math.sin((px / (float) Math.max(w, 1) + phase) * Math.PI * 2.0);
			int c = ColorUtil.withAlpha(style.accentAt(x + px, g.guiWidth()), Math.round(alpha * wave));
			g.fill(x + px, y, x + px + 1, y + 1, c);
			g.fill(x + px, y + h - 1, x + px + 1, y + h, c);
		}
		for (int py = 1; py < h - 1; py++) {
			float wave = 0.55f + 0.45f * (float) Math.sin((py / (float) Math.max(h, 1) + phase) * Math.PI * 2.0);
			int c = ColorUtil.withAlpha(style.accentAt(y + py, g.guiHeight()), Math.round(alpha * wave));
			g.fill(x, y + py, x + 1, y + py + 1, c);
			g.fill(x + w - 1, y + py, x + w, y + py + 1, c);
		}
	}

	/**
	 * Draws an accent rectangle by sampling the shared full-screen sweep and
	 * clipping it to this bar. It intentionally does not interpolate from the
	 * bar's own top to bottom, so short item/status bars stay in phase with every
	 * other HUD accent on the screen.
	 */
	public static void hudAccentBar(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		hudAccentBar(g, x, y, w, h, 1.0f);
	}

	/** Shared accent bar with a multiplier for widget fade animations. */
	public static void hudAccentBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float opacity) {
		if (w <= 0 || h <= 0 || opacity <= 0.0f) {
			return;
		}
		HudWidget style = HudWidget.activeStyle();
		int alpha = Math.round(255.0f * Math.clamp(opacity, 0.0f, 1.0f));
		if (h >= w) {
			for (int py = 0; py < h; py++) {
				int color = style == null
						? ColorUtil.withAlpha(Theme.hudScreenAccentY(y + py, g.guiHeight()), alpha)
						: ColorUtil.withAlpha(style.accentAt(y + py, g.guiHeight()), Math.round(alpha * style.styleOpacity()));
				g.fill(x, y + py, x + w, y + py + 1, color);
			}
		} else {
			for (int px = 0; px < w; px++) {
				int color = style == null
						? ColorUtil.withAlpha(Theme.hudScreenAccentX(x + px, g.guiWidth()), alpha)
						: ColorUtil.withAlpha(style.accentAt(x + px, g.guiWidth()), Math.round(alpha * style.styleOpacity()));
				g.fill(x + px, y, x + px + 1, y + h, color);
			}
		}
	}

	/** Rounded rectangle with a horizontal accent gradient. */
	public static void roundedGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int left, int right) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.min(radius, Math.min(w, h) / 2);
		for (int i = 0; i < w; i++) {
			int color = styleColor(ColorUtil.lerp(left, right, (float) i / w));
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
		g.fill(0, -t / 2, Math.round(length), -t / 2 + t, styleColor(color));
		pose.popMatrix();
	}

	/**
	 * Skeet-style checkbox square: recessed when off, accent gradient when on.
	 * {@code t} is the on/off animation value in [0, 1].
	 */
	public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, float t) {
		checkbox(g, x, y, size, t, Theme.accent2, Theme.accent1);
	}

	/**
	 * Checkbox drawn with a caller-supplied accent, so a ClickGUI style that isn't
	 * Skeet's can hand in its own without this utility having to know which is on
	 * screen. The two colors are the top and bottom of the lit gradient.
	 */
	public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, float t, int top, int bottom) {
		g.fill(x, y, x + size, y + size, Theme.borderDark);
		int inner = size - 2;
		if (t < 1.0f) {
			verticalGradient(g, x + 1, y + 1, inner, inner, 0xFF3A3A3A, 0xFF2A2A2A);
		}
		if (t > 0.0f) {
			verticalGradient(g, x + 1, y + 1, inner, inner,
					ColorUtil.multiplyAlpha(top, t), ColorUtil.multiplyAlpha(bottom, t));
		}
	}
}
