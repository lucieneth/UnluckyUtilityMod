package unlucky.utility.client.ui;

import unlucky.utility.client.util.ColorUtil;

/**
 * Global color palette, skeet-inspired: near-black surfaces, 1px borders and a
 * green accent gradient by default. The two accent colors flow through the
 * whole client and can be restyled live from the Theme module.
 */
public final class Theme {
	/** Main window / content background. */
	public static int window = 0xFF111111;
	/** Sidebar background, a step darker. */
	public static int sidebar = 0xFF0B0B0B;
	/** Group box background (same as window, boxes are defined by their border). */
	public static int group = 0xFF111111;
	/** 1px borders around boxes and controls. */
	public static int border = 0xFF232323;
	/** Outermost window border. */
	public static int borderDark = 0xFF000000;
	/** Chunky window frame (dark navy) — gives the window depth against the world. */
	public static int frame = 0xFF06070C;
	/** Subtle bevel highlight just inside the frame. */
	public static int frameBevel = 0xFF2B2B36;
	/** Recessed control backgrounds (slider tracks, dropdowns, off-checkboxes). */
	public static int surface = 0xFF1B1B1B;
	/** Legacy panel color, used by notifications and the HUD editor chrome. */
	public static int panel = 0xF2101014;
	/** Accent gradient start (skeet green). */
	public static int accent1 = 0xFF87B93D;
	/** Accent gradient end (lighter green). */
	public static int accent2 = 0xFFB9E35C;
	/** Primary text. */
	public static int text = 0xFFE8E8E8;
	/** Muted text for inactive labels. */
	public static int textDim = 0xFF9A9AA0;
	/** Color drawn behind HUD text blocks. */
	public static int hudBackground = 0x58000000;

	/** HUD gradient, kept separate from the ClickGUI accent so both restyle independently. */
	public static int hudAccent1 = 0xFF87B93D;
	public static int hudAccent2 = 0xFFB9E35C;
	private Theme() {
	}

	/** Point on the accent gradient, t in [0, 1]. */
	public static int accent(float t) {
		return ColorUtil.lerp(accent1, accent2, t);
	}

	/** HUD backing color, or transparent when this widget's background toggle is off. */
	public static int hudBg(boolean enabled) {
		return enabled ? hudBackground : 0;
	}

	/** HUD accent gradient point, t in [0, 1]. */
	public static int hudAccent(float t) {
		return ColorUtil.lerp(hudAccent1, hudAccent2, t);
	}

	public static int hudAccent(int index, int total) {
		return total <= 1 ? hudAccent1 : hudAccent((float) index / (total - 1));
	}

	// ArrayList gradient animation, driven by HudModule settings
	public static boolean hudArrayAnimate = true;
	public static float hudArraySpeed = 1.0f;
	public static boolean hudArrayDown = true;

	/**
	 * Gradient point for element {@code index} of {@code total}. When animation
	 * is on the colors scroll over time (direction and speed configurable) and
	 * wrap seamlessly via a triangle wave; otherwise it's a static gradient.
	 */
	public static int hudScrollingAccent(int index, int total) {
		if (!hudArrayAnimate) {
			return hudAccent(index, total);
		}
		float base = total <= 1 ? 0.0f : (float) index / total;
		float period = Math.max(200.0f, 3000.0f / Math.max(0.05f, hudArraySpeed));
		float phase = (System.currentTimeMillis() % (long) period) / period;
		if (!hudArrayDown) {
			phase = -phase;
		}
		float t = ((base + phase) % 1.0f + 1.0f) % 1.0f;
		float triangle = t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f;
		return hudAccent(triangle);
	}

	/** HUD accent flowing over time, offset staggers elements. */
	public static int hudFlowingAccent(float offset) {
		float t = (System.currentTimeMillis() % 4000L) / 4000.0f + offset;
		t = t % 1.0f;
		float pingPong = t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f;
		return hudAccent(pingPong);
	}

	/** Accent gradient spread over a list: element {@code index} of {@code total}. */
	public static int accent(int index, int total) {
		if (total <= 1) {
			return accent1;
		}
		return accent((float) index / (total - 1));
	}

	/** Accent color slowly flowing over time, offset lets elements shimmer in sequence. */
	public static int flowingAccent(float offset) {
		float t = (System.currentTimeMillis() % 4000L) / 4000.0f + offset;
		t = t % 1.0f;
		// ping-pong so the gradient loops without a hard seam
		float pingPong = t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f;
		return accent(pingPong);
	}
}
