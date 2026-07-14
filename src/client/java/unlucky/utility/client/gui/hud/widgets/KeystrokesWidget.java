package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayDeque;

import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
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
	private static final int GAP = 2;
	private static final int PRESSED_TEXT = 0xFF14141A;

	private final Animation w = anim();
	private final Animation a = anim();
	private final Animation s = anim();
	private final Animation d = anim();
	private final Animation space = anim();
	private final Animation lmb = anim();
	private final Animation rmb = anim();

	// click edges within the last second, counted for CPS
	private final ArrayDeque<Long> attackClicks = new ArrayDeque<>();
	private final ArrayDeque<Long> useClicks = new ArrayDeque<>();
	private boolean attackWasDown;
	private boolean useWasDown;

	public KeystrokesWidget() {
		super("Keystrokes");
	}

	private static Animation anim() {
		return new Animation(120, false, Easing.QUAD_OUT);
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return hud().keystrokes.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.15, 0.9);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		Options options = mc().options;

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

		int size = hud.keystrokesSize.getInt();
		int gridW = 3 * size + 2 * GAP;
		boolean showCps = hud.keystrokesCps.get();

		int x0 = getX();
		int y = getY();

		// W centered on the top row
		key(g, x0 + size + GAP, y, size, size, "W", w.value());
		y += size + GAP;
		// A S D
		key(g, x0, y, size, size, "A", a.value());
		key(g, x0 + size + GAP, y, size, size, "S", s.value());
		key(g, x0 + 2 * (size + GAP), y, size, size, "D", d.value());
		y += size;

		if (hud.keystrokesSpace.get()) {
			y += GAP;
			int spaceH = Math.max(6, Math.round(size * 0.5f));
			key(g, x0, y, gridW, spaceH, "", space.value());
			y += spaceH;
		}

		if (hud.keystrokesMouse.get()) {
			y += GAP;
			int mouseH = showCps ? size + 4 : size;
			int cw = (gridW - GAP) / 2;
			mouseKey(g, x0, y, cw, mouseH, "LMB", lmb.value(), showCps, attackClicks.size());
			mouseKey(g, x0 + gridW - cw, y, cw, mouseH, "RMB", rmb.value(), showCps, useClicks.size());
			y += mouseH;
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
		if (hud().keystrokesBg.get()) {
			Render2D.roundedRect(g, x, y, w, h, 2, Theme.hudBackground);
		} else {
			g.outline(x, y, w, h, ColorUtil.withAlpha(Theme.textDim, 110));
		}
		if (t > 0.01f) {
			Render2D.roundedRect(g, x, y, w, h, 2, ColorUtil.multiplyAlpha(Theme.hudAccent(0.5f), t));
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
			int cpsColor = ColorUtil.lerp(Theme.hudAccent(0.8f), PRESSED_TEXT, t);
			Render2D.text(g, num, x + (w - Render2D.width(num)) / 2, y + h - Render2D.FONT_HEIGHT - 1, cpsColor);
		}
	}
}
