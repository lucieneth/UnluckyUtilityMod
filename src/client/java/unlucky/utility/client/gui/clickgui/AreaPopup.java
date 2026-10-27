package unlucky.utility.client.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.scene.AreaScene;
import unlucky.utility.client.gui.scene.AreaSource;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/**
 * Popup around an {@link AreaScene}: the module's work area drawn as blocks, with how much of
 * it is finished along the top.
 */
public final class AreaPopup {
	private static final int WIDTH = 360;
	private static final int HEIGHT = 270;
	private static final int HEADER = 30;

	private static final AreaScene SCENE = new AreaScene();
	private static AreaSource source;
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static int dragOffX;
	private static int dragOffY;

	private AreaPopup() {
	}

	public static boolean isOpen() {
		return source != null;
	}

	public static void open(AreaSource area) {
		source = area;
		SCENE.open(area);
	}

	public static void close() {
		source = null;
	}

	public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (source == null) {
			return;
		}
		if (popX == Integer.MIN_VALUE) {
			popX = (g.guiWidth() - WIDTH) / 2;
			popY = (g.guiHeight() - HEIGHT) / 2;
		}
		int x = popX;
		int y = popY;

		Render2D.rect(g, x - 1, y - 1, WIDTH + 2, HEIGHT + 2, Theme.borderDark);
		Render2D.rect(g, x, y, WIDTH, HEIGHT, Theme.window);
		g.outline(x, y, WIDTH, HEIGHT, Theme.border);
		Render2D.textNoShadow(g, source.title(), x + 6, y + 5, Theme.text);

		int total = SCENE.done() + SCENE.left();
		String progress = total == 0 ? "" : SCENE.done() + " / " + total + " done";
		Render2D.textNoShadow(g, progress, x + WIDTH - 6 - Render2D.width(progress), y + 5, Theme.accent1);
		String hint = source.corners() != null ? "Drag 1 / 2 to move the corners" : "Left drag orbit, right drag pan";
		if (SCENE.clipped()) {
			hint += "  (showing the 48-block window around you)";
		}
		Render2D.textNoShadow(g, hint, x + 6, y + 17, Theme.textDim);

		SCENE.render(g, x + 5, y + HEADER, WIDTH - 10, HEIGHT - HEADER - 5, mouseX, mouseY);
	}

	/** Consumes every click while open; clicking outside the panel closes it. */
	public static boolean mouseClicked(double mouseX, double mouseY, int button, int guiWidth, int guiHeight) {
		if (source == null) {
			return false;
		}
		if (!Render2D.hovered(mouseX, mouseY, popX, popY, WIDTH, HEIGHT)) {
			close();
			return true;
		}
		if (SCENE.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseY < popY + HEADER) {
			draggingPopup = true;
			dragOffX = (int) mouseX - popX;
			dragOffY = (int) mouseY - popY;
		}
		return true;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int guiWidth, int guiHeight) {
		if (source != null && SCENE.dragging()) {
			SCENE.mouseDragged(mouseX, mouseY);
			return true;
		}
		if (!draggingPopup) {
			return false;
		}
		popX = Math.clamp((int) mouseX - dragOffX, 0, Math.max(guiWidth - WIDTH, 0));
		popY = Math.clamp((int) mouseY - dragOffY, 0, Math.max(guiHeight - HEIGHT, 0));
		return true;
	}

	public static void mouseReleased() {
		draggingPopup = false;
		SCENE.mouseReleased();
	}

	public static boolean mouseScrolled(double scrollY) {
		if (source == null) {
			return false;
		}
		SCENE.mouseScrolled(scrollY);
		return true;
	}
}
