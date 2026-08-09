package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.util.Render2D;

/**
 * Text renderer used by Future's narrow columns. Text that does not fit is
 * clipped to its slot and slowly ping-pongs between both ends, with a short
 * pause at each edge. Outside a Future render pass these helpers deliberately
 * preserve the normal ClickGUI's original, unclipped text placement.
 */
public final class ScrollingText {
	private static final long EDGE_PAUSE_MS = 700L;
	private static final double PIXELS_PER_SECOND = 24.0;
	private static int futureRenderDepth;

	private ScrollingText() {
	}

	public static void beginFutureRender() {
		futureRenderDepth++;
	}

	public static void endFutureRender() {
		futureRenderDepth = Math.max(0, futureRenderDepth - 1);
	}

	public static void draw(GuiGraphicsExtractor g, String text, int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		int textWidth = Render2D.width(text);
		if (futureRenderDepth == 0 || textWidth <= availableWidth) {
			Render2D.textNoShadow(g, text, x, y, color);
			return;
		}

		int offset = marqueeOffset(textWidth - availableWidth);
		g.enableScissor(x, y, x + availableWidth, y + 9);
		Render2D.textNoShadow(g, text, x - offset, y, color);
		g.disableScissor();
	}

	public static void drawRight(GuiGraphicsExtractor g, String text, int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		int textWidth = Render2D.width(text);
		if (futureRenderDepth == 0 || textWidth <= availableWidth) {
			Render2D.textNoShadow(g, text, x + availableWidth - textWidth, y, color);
			return;
		}
		draw(g, text, x, y, availableWidth, color);
	}

	public static void drawCentered(GuiGraphicsExtractor g, String text, int x, int y, int availableWidth, int color) {
		if (text == null || text.isEmpty() || availableWidth <= 0) return;
		int textWidth = Render2D.width(text);
		if (futureRenderDepth == 0 || textWidth <= availableWidth) {
			Render2D.textNoShadow(g, text, x + (availableWidth - textWidth) / 2, y, color);
			return;
		}
		draw(g, text, x, y, availableWidth, color);
	}

	private static int marqueeOffset(int overflow) {
		long travelMs = Math.max(1L, Math.round(overflow * 1000.0 / PIXELS_PER_SECOND));
		long cycleMs = 2L * (EDGE_PAUSE_MS + travelMs);
		long time = Math.floorMod(System.currentTimeMillis(), cycleMs);
		if (time < EDGE_PAUSE_MS) return 0;
		time -= EDGE_PAUSE_MS;
		if (time < travelMs) return (int) Math.round(overflow * (double) time / travelMs);
		time -= travelMs;
		if (time < EDGE_PAUSE_MS) return overflow;
		time -= EDGE_PAUSE_MS;
		return overflow - (int) Math.round(overflow * (double) time / travelMs);
	}
}
