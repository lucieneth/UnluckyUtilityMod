package unlucky.utility.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.component.ScrollingText;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * The body of an expanded color row, shared by every color picker in the client
 * so they all look and behave the same: a tab strip picking the input style,
 * then either hue/saturation/value bars, a #RRGGBB field, or three 0-255
 * channel fields.
 *
 * <p>Call sites own the header row (label + swatch) and decide where the body
 * goes; they hand it {@code (x, y, width)} on every call and forward mouse and
 * key events. One instance per screen is enough — only one row can be expanded
 * at a time — but a per-row instance is fine too.
 *
 * <p>The chosen style lives in {@link ThemeModule#colorMode} so it is global and
 * persists: pick RGB once and every picker in the client is RGB.
 *
 * <p>Alpha is not editable here, matching what the bars always did — a written
 * value keeps the setting's existing alpha. An 8-digit hex code is still
 * accepted (AARRGGBB) for the odd case where you want to set it.
 */
public class ColorPicker {
	public static final String[] MODES = {"Picker", "HEX", "RGB"};
	private static final String[] CHANNELS = {"R", "G", "B"};

	private static final int TAB_ROW = 12;
	private static final int BAR = 11;
	private static final int FIELD_ROW = 14;
	private static final int LABEL_W = 26;
	/** Strips used to paint the saturation/value gradients; the hue bar uses one per pixel. */
	private static final int GRADIENT_STEPS = 32;

	private final TextBox box = new TextBox();
	/** Focused text field: -1 none, else the hex field (0) or an RGB channel (0-2). */
	private int focus = -1;
	private boolean draggingText;
	private int fieldTextX;
	/** Bar being dragged: -1 none, else 0 hue / 1 saturation / 2 value. */
	private int draggingBar = -1;
	private ColorSetting target;
	private int bodyX;
	private int bodyWidth;

	// HSB is cached rather than derived every frame: at saturation or value 0 the
	// hue is not recoverable from the ARGB, so dragging Val to the bottom and back
	// up would otherwise snap the color to red.
	private ColorSetting cachedFor;
	private int cachedArgb;
	private float[] hsb = {0.0f, 0.0f, 0.0f};

	public ColorPicker() {
		box.onChange(this::commit);
	}

	private static ThemeModule theme() {
		return UnluckyClient.INSTANCE == null ? null : UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
	}

	public static String mode() {
		ThemeModule theme = theme();
		return theme == null ? MODES[0] : theme.colorMode.get();
	}

	private static void setMode(String mode) {
		ThemeModule theme = theme();
		if (theme != null) {
			theme.colorMode.set(mode);
		}
	}

	/** Height of the expanded body for the current mode; the header row is the caller's. */
	public static int height() {
		return TAB_ROW + (MODES[0].equals(mode()) ? 3 * BAR : FIELD_ROW);
	}

	/** Drops text focus and any drag — call when the row collapses or the screen closes. */
	public void close() {
		focus = -1;
		draggingText = false;
		draggingBar = -1;
	}

	/** True while a field has keyboard focus, so the host can swallow hotkeys. */
	public boolean typing() {
		return focus >= 0;
	}

	/** True while a bar or a text selection is being dragged, so the host stays out of the way. */
	public boolean dragging() {
		return draggingBar >= 0 || draggingText;
	}

	// --- rendering ------------------------------------------------------------

	public void render(GuiGraphicsExtractor g, ColorSetting setting, int x, int y, int width,
			int mouseX, int mouseY) {
		bodyX = x;
		bodyWidth = width;
		drawTabs(g, x, y, width, mouseX, mouseY);
		int bodyY = y + TAB_ROW;
		switch (mode()) {
			case "HEX" -> drawHex(g, setting, x, bodyY, width);
			case "RGB" -> drawRgb(g, setting, x, bodyY, width);
			default -> drawBars(g, setting, x, bodyY, width);
		}
	}

	private void drawTabs(GuiGraphicsExtractor g, int x, int y, int width, int mouseX, int mouseY) {
		String current = mode();
		for (int i = 0; i < MODES.length; i++) {
			int tabX = tabX(x, width, i);
			int tabW = tabX(x, width, i + 1) - tabX - 1;
			boolean on = MODES[i].equals(current);
			boolean hover = Render2D.hovered(mouseX, mouseY, tabX, y + 1, tabW, TAB_ROW - 3);
			Render2D.rect(g, tabX, y + 1, tabW, TAB_ROW - 3,
					on ? ColorUtil.withAlpha(Theme.accent1, 55) : Theme.surface);
			if (on) {
				g.outline(tabX, y + 1, tabW, TAB_ROW - 3, Theme.accent1);
			}
			int color = on ? Theme.text
					: hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim;
			ScrollingText.drawCentered(g, MODES[i], tabX + 1, y + 2, tabW - 2, color);
		}
	}

	private void drawBars(GuiGraphicsExtractor g, ColorSetting setting, int x, int y, int width) {
		float[] current = hsbOf(setting);
		drawBar(g, x, y, width, "Hue", 0, current);
		drawBar(g, x, y + BAR, width, "Sat", 1, current);
		drawBar(g, x, y + 2 * BAR, width, "Val", 2, current);
	}

	private void drawBar(GuiGraphicsExtractor g, int x, int rowY, int width, String label, int channel,
			float[] current) {
		Render2D.textNoShadow(g, label, x + 4, rowY + 2, Theme.textDim);
		int barX = barX(x);
		int barW = barWidth(width);
		int barY = rowY + 2;
		Render2D.rect(g, barX - 1, barY - 1, barW + 2, 7, Theme.borderDark);
		switch (channel) {
			// the full wheel, one strip per pixel — six lerped sextants band visibly
			case 0 -> {
				for (int i = 0; i < barW; i++) {
					g.fill(barX + i, barY, barX + i + 1, barY + 5,
							ColorUtil.hsb((float) i / barW, 1.0f, 1.0f, 255));
				}
			}
			// saturation and value are plain two-color ramps, so a few strips do
			case 1 -> ramp(g, barX, barY, barW,
					ColorUtil.hsb(current[0], 0.0f, current[2], 255),
					ColorUtil.hsb(current[0], 1.0f, current[2], 255));
			default -> ramp(g, barX, barY, barW,
					ColorUtil.hsb(current[0], current[1], 0.0f, 255),
					ColorUtil.hsb(current[0], current[1], 1.0f, 255));
		}
		int handleX = barX + (int) (current[channel] * (barW - 1));
		Render2D.rect(g, handleX - 1, barY - 2, 3, 9, Theme.borderDark);
		Render2D.rect(g, handleX, barY - 1, 1, 7, Theme.text);
	}

	private static void ramp(GuiGraphicsExtractor g, int x, int y, int width, int left, int right) {
		int steps = Math.min(width, GRADIENT_STEPS);
		for (int i = 0; i < steps; i++) {
			int from = x + i * width / steps;
			int to = x + (i + 1) * width / steps;
			g.fill(from, y, to, y + 5, ColorUtil.lerp(left, right, (float) i / Math.max(1, steps - 1)));
		}
	}

	private void drawHex(GuiGraphicsExtractor g, ColorSetting setting, int x, int y, int width) {
		Render2D.textNoShadow(g, "Hex", x + 4, y + 4, Theme.textDim);
		int fieldX = barX(x);
		int fieldW = barWidth(width) + 1;
		Render2D.rect(g, fieldX, y + 2, fieldW, 11, Theme.surface);
		fieldTextX = fieldX + 3;
		if (focus == 0) {
			g.outline(fieldX, y + 2, fieldW, 11, Theme.accent1);
			box.render(g, fieldX + 3, y + 3, fieldW - 6, true, null);
		} else {
			TextBox.renderStatic(g, hexOf(setting), fieldX + 3, y + 3, fieldW - 6, null);
		}
	}

	private void drawRgb(GuiGraphicsExtractor g, ColorSetting setting, int x, int y, int width) {
		for (int i = 0; i < CHANNELS.length; i++) {
			int cellX = cellX(x, width, i);
			Render2D.textNoShadow(g, CHANNELS[i], cellX, y + 4, Theme.textDim);
			int fieldX = cellX + 8;
			int fieldW = Math.max(12, cellX(x, width, i + 1) - 3 - fieldX);
			Render2D.rect(g, fieldX, y + 2, fieldW, 11, Theme.surface);
			if (focus == i) {
				fieldTextX = fieldX + 3;
				g.outline(fieldX, y + 2, fieldW, 11, Theme.accent1);
				box.render(g, fieldX + 3, y + 3, fieldW - 6, true, null);
			} else {
				TextBox.renderStatic(g, String.valueOf(channelOf(setting, i)), fieldX + 3, y + 3, fieldW - 6, null);
			}
		}
	}

	// --- geometry -------------------------------------------------------------

	private static int tabX(int x, int width, int index) {
		return x + index * width / MODES.length;
	}

	private static int barX(int x) {
		return x + LABEL_W;
	}

	private static int barWidth(int width) {
		return Math.max(6, width - LABEL_W - 4);
	}

	private static int cellX(int x, int width, int index) {
		return x + 4 + index * (width - 8) / CHANNELS.length;
	}

	// --- events ---------------------------------------------------------------

	/**
	 * Handles a left click inside the body. Returns true when the click landed on
	 * the picker; callers should treat that as consumed.
	 */
	public boolean mouseClicked(double mouseX, double mouseY, ColorSetting setting, int x, int y, int width) {
		bodyX = x;
		bodyWidth = width;
		target = setting;
		if (mouseY < y + TAB_ROW) {
			for (int i = 0; i < MODES.length; i++) {
				if (mouseX >= tabX(x, width, i) && mouseX < tabX(x, width, i + 1)) {
					setMode(MODES[i]);
					close();
					break;
				}
			}
			return true;
		}
		int bodyY = y + TAB_ROW;
		switch (mode()) {
			case "HEX" -> {
				int fieldX = barX(x);
				if (mouseX >= fieldX) {
					focusField(0, hexOf(setting), mouseX);
				} else {
					close();
				}
			}
			case "RGB" -> {
				close();
				for (int i = 0; i < CHANNELS.length; i++) {
					if (mouseX >= cellX(x, width, i) && mouseX < cellX(x, width, i + 1)) {
						focusField(i, String.valueOf(channelOf(setting, i)), mouseX);
						break;
					}
				}
			}
			default -> {
				close();
				draggingBar = Math.clamp((int) ((mouseY - bodyY) / BAR), 0, 2);
				applyBar(mouseX);
			}
		}
		return true;
	}

	private void focusField(int field, String text, double mouseX) {
		boolean fresh = focus != field;
		focus = field;
		// first click selects the whole code so typing replaces it; only a click on an
		// already-focused field places the caret and starts a drag-selection
		draggingText = !fresh;
		if (fresh) {
			box.setText(text);
			box.selectAll();
		} else {
			box.click(mouseX - fieldTextX);
		}
	}

	public void mouseDragged(double mouseX) {
		if (draggingBar >= 0) {
			applyBar(mouseX);
		} else if (draggingText && focus >= 0) {
			box.drag(mouseX - fieldTextX);
		}
	}

	public void mouseReleased() {
		draggingBar = -1;
		draggingText = false;
	}

	public boolean charTyped(CharacterEvent event) {
		return focus >= 0 && box.charTyped(event);
	}

	public boolean keyPressed(KeyEvent event) {
		if (focus < 0) {
			return false;
		}
		if (box.keyPressed(event)) {
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_TAB && "RGB".equals(mode()) && target != null) {
			int next = (focus + 1) % CHANNELS.length;
			focus = next;
			box.setText(String.valueOf(channelOf(target, next)));
			box.selectAll();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return true; // swallow everything else while typing so hotkeys don't fire
	}

	// --- values ---------------------------------------------------------------

	private void applyBar(double mouseX) {
		if (target == null || draggingBar < 0) {
			return;
		}
		float[] current = hsbOf(target);
		current[draggingBar] = (float) Math.clamp((mouseX - barX(bodyX)) / barWidth(bodyWidth), 0.0, 1.0);
		writeArgb(ColorUtil.hsb(current[0], current[1], current[2], target.alpha()), false);
	}

	/** Parses whatever is in the focused field; leaves the color alone if it isn't valid yet. */
	private void commit() {
		if (target == null || focus < 0) {
			return;
		}
		String raw = box.text().trim();
		if ("HEX".equals(mode())) {
			String digits = raw.startsWith("#") ? raw.substring(1) : raw;
			if (digits.length() != 6 && digits.length() != 8) {
				return;
			}
			long value;
			try {
				value = Long.parseLong(digits, 16);
			} catch (NumberFormatException ignored) {
				return;
			}
			int alpha = digits.length() == 8 ? (int) ((value >>> 24) & 0xFF) : target.alpha();
			writeArgb(ColorUtil.withAlpha((int) (value & 0xFFFFFF), alpha), true);
			return;
		}
		if (raw.isEmpty()) {
			return;
		}
		int value;
		try {
			value = Integer.parseInt(raw);
		} catch (NumberFormatException ignored) {
			return;
		}
		int shift = 16 - focus * 8;
		writeArgb((target.get() & ~(0xFF << shift)) | (Math.clamp(value, 0, 255) << shift), true);
	}

	/**
	 * Writes the setting and keeps the HSB cache in step. {@code resync} re-derives
	 * the cache from the new color (a typed code is the source of truth); a bar
	 * drag passes false so the hue survives saturation or value hitting zero.
	 */
	private void writeArgb(int argb, boolean resync) {
		target.set(argb);
		cachedFor = target;
		cachedArgb = argb;
		if (resync) {
			hsb = ColorUtil.toHsb(argb);
		}
	}

	private float[] hsbOf(ColorSetting setting) {
		if (setting != cachedFor || (setting.get() & 0xFFFFFF) != (cachedArgb & 0xFFFFFF)) {
			cachedFor = setting;
			cachedArgb = setting.get();
			hsb = ColorUtil.toHsb(cachedArgb);
		}
		return hsb;
	}

	private static String hexOf(ColorSetting setting) {
		return String.format("#%06X", setting.get() & 0xFFFFFF);
	}

	private static int channelOf(ColorSetting setting, int index) {
		return (setting.get() >> (16 - index * 8)) & 0xFF;
	}
}
