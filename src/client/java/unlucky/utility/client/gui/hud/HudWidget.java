package unlucky.utility.client.gui.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A draggable HUD element. Position is stored as a fraction of the available
 * screen space, so widgets keep their place when the window is resized.
 */
public abstract class HudWidget {
	protected static final int MARGIN = 4;

	private final String name;
	private double fracX = Double.NaN;
	private double fracY = Double.NaN;
	// absolute position and size of the last rendered frame, for drawing and hit testing
	private int absX;
	private int absY;
	private int lastWidth;
	private int lastHeight;
	// which screen edge the widget hugs, so content can justify toward it
	private boolean anchorRight;
	private boolean anchorBottom;
	// potion-avoidance: a downward slide the manager asks for, eased toward each frame
	private float pushY;
	private float targetPushY;
	private long lastPushNanos;

	protected HudWidget(String name) {
		this.name = name;
	}

	protected static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public String getName() {
		return name;
	}

	public int getX() {
		return absX;
	}

	public int getY() {
		return absY;
	}

	public int getWidth() {
		return lastWidth;
	}

	public int getHeight() {
		return lastHeight;
	}

	public double getFracX() {
		return Double.isNaN(fracX) ? 0.0 : fracX;
	}

	public double getFracY() {
		return Double.isNaN(fracY) ? 0.0 : fracY;
	}

	public void setFractions(double fx, double fy) {
		this.fracX = Math.clamp(fx, 0.0, 1.0);
		this.fracY = Math.clamp(fy, 0.0, 1.0);
	}

	/** Move so the widget's top-left lands at (x, y) on a screen of the given size. */
	public void moveTo(int x, int y, int screenWidth, int screenHeight) {
		int availableX = Math.max(screenWidth - lastWidth - 2 * MARGIN, 1);
		int availableY = Math.max(screenHeight - lastHeight - 2 * MARGIN, 1);
		setFractions((x - MARGIN) / (double) availableX, (y - MARGIN) / (double) availableY);
	}

	protected void setSize(int width, int height) {
		this.lastWidth = width;
		this.lastHeight = height;
	}

	/** Natural (un-pushed) left edge for a screen of the given width. */
	public int naturalLeft(int screenWidth) {
		if (Double.isNaN(fracX)) {
			applyDefaultPosition();
		}
		return MARGIN + (int) Math.round(getFracX() * Math.max(screenWidth - lastWidth - 2 * MARGIN, 0));
	}

	/** Natural (un-pushed) top edge for a screen of the given height. */
	public int naturalTop(int screenHeight) {
		if (Double.isNaN(fracY)) {
			applyDefaultPosition();
		}
		return MARGIN + (int) Math.round(getFracY() * Math.max(screenHeight - lastHeight - 2 * MARGIN, 0));
	}

	/**
	 * Sets the desired vertical slide (avoidance). The manager resets this to 0 each
	 * frame for every widget; {@link #render} eases the actual offset toward it, so
	 * widgets glide when they need to dodge and back when they don't. Positive slides
	 * down (potion icons), negative slides up (open chat).
	 */
	public void setTargetPush(float push) {
		this.targetPushY = push;
	}

	/** Accumulates onto the desired slide, so independent avoidance passes combine. */
	public void addTargetPush(float delta) {
		this.targetPushY += delta;
	}

	/** Whether the widget draws right now (its HUD toggle is on etc.). */
	public abstract boolean isVisible();

	public final void render(GuiGraphicsExtractor g, boolean editing) {
		if (Double.isNaN(fracX)) {
			applyDefaultPosition();
		}
		absX = MARGIN + (int) Math.round(fracX * Math.max(g.guiWidth() - lastWidth - 2 * MARGIN, 0));
		absY = MARGIN + (int) Math.round(fracY * Math.max(g.guiHeight() - lastHeight - 2 * MARGIN, 0));
		absY += Math.round(easePush());
		// resolve which edge we hug from last frame's center — content justifies toward it
		anchorRight = absX + lastWidth / 2 > g.guiWidth() / 2;
		anchorBottom = absY + lastHeight / 2 > g.guiHeight() / 2;
		draw(g, editing);
	}

	/** Advances {@link #pushY} toward the requested target with frame-rate-independent easing. */
	private float easePush() {
		long now = System.nanoTime();
		float dt = lastPushNanos == 0L ? 0f : Math.min((now - lastPushNanos) / 1.0e9f, 0.1f);
		lastPushNanos = now;
		pushY += (targetPushY - pushY) * (1f - (float) Math.exp(-14f * dt));
		if (Math.abs(targetPushY - pushY) < 0.4f) {
			pushY = targetPushY;
		}
		return pushY;
	}

	/** True when the widget hugs the right half of the screen (justify content right). */
	protected boolean anchorRight() {
		return anchorRight;
	}

	/** True when the widget hugs the bottom half of the screen (stack content upward). */
	protected boolean anchorBottom() {
		return anchorBottom;
	}

	/** X for a line of the given width so it justifies toward the hugged edge. */
	protected int alignedX(int lineWidth, int pad) {
		return anchorRight ? absX + lastWidth - pad - lineWidth : absX + pad;
	}

	/** A line of HUD text with its color, for size-sorted vertical stacks. */
	public record TextLine(String text, int color) {
	}

	/**
	 * Orders stacked lines by width so the block fans out from its docked corner:
	 * the widest line sits nearest the vertical edge the widget hugs (the bottom
	 * when docked low, the top when docked high), the narrowest farthest from it.
	 */
	protected <T> void sortBySize(java.util.List<T> lines, java.util.function.ToIntFunction<T> width) {
		lines.sort(java.util.Comparator.comparingInt(width)); // narrowest first
		if (!anchorBottom) {
			java.util.Collections.reverse(lines); // docked high: widest on top
		}
	}

	protected abstract void draw(GuiGraphicsExtractor g, boolean editing);

	/** Sets the default fractional position, e.g. (0, 0) top left, (1, 0) top right. */
	protected abstract void applyDefaultPosition();

	/** True when the widget's center sits in the right half of the screen. */
	protected boolean alignsRight(int screenWidth) {
		return absX + lastWidth / 2 > screenWidth / 2;
	}
}
