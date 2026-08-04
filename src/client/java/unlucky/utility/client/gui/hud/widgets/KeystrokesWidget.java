package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayDeque;

import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * WASD keystroke grid with a space bar and a mouse-button row. Each key fills
 * with the accent when pressed and eases back out on release; the mouse buttons
 * show live CPS counted from a one-second sliding window of click edges.
 */
public class KeystrokesWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Keystrokes", "WASD, space and mouse key display", false));
	public final BooleanSetting bg = add(new BooleanSetting("Keys bg", "Filled key backings (off = outline only)", true));
	public final BooleanSetting mouse = add(new BooleanSetting("Mouse keys", "Show the LMB / RMB row", true));
	public final BooleanSetting spaceBar = add(new BooleanSetting("Space bar", "Show the space bar", true));
	public final BooleanSetting cps = add(new BooleanSetting("Show CPS", "Live CPS under the mouse keys", true));
	public final NumberSetting keySize = add(new NumberSetting("Key size", "Key cell size in pixels", 18, 12, 28, 1));
	public final ModeSetting keyboardLayout = add(new ModeSetting("Keyboard layout", "Labels used for the movement cluster", "WASD", "WASD", "ZQSD", "Arrows"));
	public final NumberSetting pressSpeed = add(new NumberSetting("Key animation speed", "Milliseconds for key press/release easing", 120, 40, 400, 10));
	public final BooleanSetting cpsGraph = add(new BooleanSetting("CPS graph", "Show a synchronized click-rate history graph", false));
	public final BooleanSetting scroll = add(new BooleanSetting("Mouse scroll", "Show recent wheel direction", false));

	private static final int GAP = 2;
	private static final int PRESSED_TEXT = 0xFF14141A;

	private Animation w = anim(120);
	private Animation a = anim(120);
	private Animation s = anim(120);
	private Animation d = anim(120);
	private Animation space = anim(120);
	private Animation lmb = anim(120);
	private Animation rmb = anim(120);
	private int animationMs = 120;
	private static volatile long lastScrollMs;
	private static volatile int lastScrollDirection;
	private final int[] cpsSamples = new int[24];
	private long lastCpsSample;

	// click edges within the last second, counted for CPS
	private final ArrayDeque<Long> attackClicks = new ArrayDeque<>();
	private final ArrayDeque<Long> useClicks = new ArrayDeque<>();
	private boolean attackWasDown;
	private boolean useWasDown;

	public KeystrokesWidget() {
		super("Keystrokes");
	}

	private static Animation anim(int duration) {
		return new Animation(duration, false, Easing.QUAD_OUT);
	}

	public static void recordScroll(double amount) {
		if (amount != 0.0) {
			lastScrollDirection = amount > 0 ? 1 : -1;
			lastScrollMs = System.currentTimeMillis();
		}
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.15, 0.9);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		Options options = mc().options;
		int desiredMs = pressSpeed.getInt();
		if (desiredMs != animationMs) {
			animationMs = desiredMs;
			w = anim(desiredMs); a = anim(desiredMs); s = anim(desiredMs); d = anim(desiredMs);
			space = anim(desiredMs); lmb = anim(desiredMs); rmb = anim(desiredMs);
		}

		// drive per-key animations from the live key state
		w.setDirection(options.keyUp.isDown());
		a.setDirection(options.keyLeft.isDown());
		s.setDirection(options.keyDown.isDown());
		d.setDirection(options.keyRight.isDown());
		space.setDirection(options.keyJump.isDown());
		lmb.setDirection(options.keyAttack.isDown());
		rmb.setDirection(options.keyUse.isDown());

		// count click edges into one-second sliding windows for CPS
		long now = System.currentTimeMillis();
		attackWasDown = edge(options.keyAttack.isDown(), attackWasDown, attackClicks, now);
		useWasDown = edge(options.keyUse.isDown(), useWasDown, useClicks, now);
		if (now - lastCpsSample >= 100L) {
			lastCpsSample = now;
			System.arraycopy(cpsSamples, 1, cpsSamples, 0, cpsSamples.length - 1);
			cpsSamples[cpsSamples.length - 1] = attackClicks.size() + useClicks.size();
		}

		int size = Math.max(keySize.getInt(), (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 4);
		int gridW = (horizontalLayout() ? 4 : 3) * size + (horizontalLayout() ? 3 : 2) * GAP;
		boolean showCps = cps.get();

		int x0 = getX();
		int y = getY();

		String[] labels = switch (keyboardLayout.get()) {
			case "ZQSD" -> new String[]{"Z", "Q", "S", "D"};
			case "Arrows" -> new String[]{"\u2191", "\u2190", "\u2193", "\u2192"};
			default -> new String[]{"W", "A", "S", "D"};
		};
		if (horizontalLayout()) {
			key(g, x0, y, size, size, labels[0], w.value());
			key(g, x0 + size + GAP, y, size, size, labels[1], a.value());
			key(g, x0 + 2 * (size + GAP), y, size, size, labels[2], s.value());
			key(g, x0 + 3 * (size + GAP), y, size, size, labels[3], d.value());
			y += size;
		} else {
			key(g, x0 + size + GAP, y, size, size, labels[0], w.value());
			y += size + GAP;
			// A S D
			key(g, x0, y, size, size, labels[1], a.value());
			key(g, x0 + size + GAP, y, size, size, labels[2], s.value());
			key(g, x0 + 2 * (size + GAP), y, size, size, labels[3], d.value());
			y += size;
		}

		if (spaceBar.get()) {
			y += GAP;
			int spaceH = Math.max(6, Math.round(size * 0.5f));
			key(g, x0, y, gridW, spaceH, "", space.value());
			y += spaceH;
		}

		if (mouse.get()) {
			y += GAP;
			int mouseH = showCps ? size + 4 : size;
			int cw = (gridW - GAP) / 2;
			mouseKey(g, x0, y, cw, mouseH, "LMB", lmb.value(), showCps, attackClicks.size());
			mouseKey(g, x0 + gridW - cw, y, cw, mouseH, "RMB", rmb.value(), showCps, useClicks.size());
			y += mouseH;
		}
		if (scroll.get()) {
			y += GAP;
			boolean recent = now - lastScrollMs < 240L;
			String label = lastScrollDirection >= 0 ? "MW \u2191" : "MW \u2193";
			key(g, x0, y, gridW, Math.max(10, size / 2), label, recent ? 1.0f : 0.0f);
			y += Math.max(10, size / 2);
		}
		if (cpsGraph.get()) {
			y += GAP;
			drawCpsGraph(g, x0, y, gridW, 12);
			y += 12;
		}

		setSize(gridW, y - getY());
	}

	/** Adds a rising-edge timestamp and trims the window; returns the new down state. */
	private static boolean edge(boolean down, boolean wasDown, ArrayDeque<Long> window, long now) {
		if (down && !wasDown) {
			window.addLast(now);
		}
		while (!window.isEmpty() && now - window.peekFirst() > 1000L) {
			window.removeFirst();
		}
		return down;
	}

	/** Recessed cell backing (or a thin outline when the bg toggle is off), plus the accent press fill. */
	private void drawBase(GuiGraphicsExtractor g, int x, int y, int w, int h, float t) {
		if (bg.get()) {
			Render2D.hudPanel(g, x, y, w, h, true);
		} else {
			g.outline(x, y, w, h, ColorUtil.withAlpha(Theme.textDim, 110));
		}
		if (t > 0.01f) {
			Render2D.roundedRect(g, x, y, w, h, Theme.hudPanelRadius,
					ColorUtil.multiplyAlpha(accentAt(y + h / 2, g.guiHeight()), t));
		}
	}

	private void drawCpsGraph(GuiGraphicsExtractor g, int x, int y, int width, int height) {
		Render2D.hudPanel(g, x, y, width, height, bg.get());
		int max = 1;
		for (int sample : cpsSamples) max = Math.max(max, sample);
		float step = (width - 4.0f) / (cpsSamples.length - 1);
		for (int i = 1; i < cpsSamples.length; i++) {
			float x0 = x + 2 + (i - 1) * step;
			float x1 = x + 2 + i * step;
			float y0 = y + height - 2 - cpsSamples[i - 1] / (float) max * (height - 4);
			float y1 = y + height - 2 - cpsSamples[i] / (float) max * (height - 4);
			Render2D.line(g, x0, y0, x1, y1, 1.0f, accentAt(Math.round(x1), g.guiWidth()));
		}
	}

	private void key(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, float t) {
		drawBase(g, x, y, w, h, t);
		if (!label.isEmpty()) {
			int color = ColorUtil.lerp(Theme.text, PRESSED_TEXT, t);
			Render2D.text(g, label, x + (w - Render2D.width(label)) / 2,
					y + (h - Render2D.FONT_HEIGHT) / 2 + 1, color);
		}
	}

	private void mouseKey(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, float t,
			boolean showCps, int cps) {
		drawBase(g, x, y, w, h, t);
		int color = ColorUtil.lerp(Theme.text, PRESSED_TEXT, t);
		int labelY = showCps ? y + 2 : y + (h - Render2D.FONT_HEIGHT) / 2 + 1;
		Render2D.text(g, label, x + (w - Render2D.width(label)) / 2, labelY, color);
		if (showCps) {
			String num = Integer.toString(cps);
			int cpsColor = ColorUtil.lerp(accentAt(4, 5), PRESSED_TEXT, t);
			Render2D.text(g, num, x + (w - Render2D.width(num)) / 2, y + h - Render2D.FONT_HEIGHT - 1, cpsColor);
		}
	}
}
