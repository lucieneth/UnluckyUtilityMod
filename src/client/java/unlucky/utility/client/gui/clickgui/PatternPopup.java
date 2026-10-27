package unlucky.utility.client.gui.clickgui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import unlucky.utility.client.settings.PatternSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/**
 * Popup for a {@link PatternSetting}: the preset buttons along the top, the layers down the left,
 * and the {@link PatternScene} with the pattern standing around a player in the rest.
 *
 * <p>One layer is edited at a time, since a click on a 3D grid needs to know which height it means;
 * the arrow keys or the layer buttons move between them, and each button says how many blocks its
 * layer holds so the shape reads at a glance.
 */
public final class PatternPopup {
	private static final int WIDTH = 330;
	private static final int HEIGHT = 250;
	private static final int HEADER = 34;
	private static final int LAYERS_W = 54;
	private static final int LAYER_H = 18;
	private static final String[] LAYER_NAMES = {"Below", "Feet", "Head", "Top", "Above"};

	private static final PatternScene SCENE = new PatternScene();
	private static PatternSetting target;
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static int dragOffX;
	private static int dragOffY;

	private PatternPopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(PatternSetting setting) {
		target = setting;
		SCENE.reset();
	}

	public static void close() {
		target = null;
	}

	public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (target == null) {
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
		int count = target.get().size();
		String title = target.getName() + "  (" + count + (count == 1 ? " block)" : " blocks)");
		Render2D.textNoShadow(g, title, x + 6, y + 5, Theme.text);

		String matching = target.matchingPreset();
		int buttonX = x + 5;
		for (String preset : PatternSetting.PRESETS.keySet()) {
			int w = Render2D.width(preset) + 10;
			boolean hover = Render2D.hovered(mouseX, mouseY, buttonX, y + 16, w, 13);
			boolean active = preset.equals(matching);
			Render2D.rect(g, buttonX - 1, y + 15, w + 2, 15, active ? Theme.accent1 : Theme.borderDark);
			Render2D.rect(g, buttonX, y + 16, w, 13, hover ? Theme.panel : Theme.surface);
			Render2D.textNoShadow(g, preset, buttonX + 5, y + 19, active || hover ? Theme.text : Theme.textDim);
			buttonX += w + 4;
		}

		for (int layer = PatternSetting.MAX_Y; layer >= PatternSetting.MIN_Y; layer--) {
			int rowY = layerY(y, layer);
			boolean hover = Render2D.hovered(mouseX, mouseY, x + 5, rowY, LAYERS_W, LAYER_H - 2);
			boolean active = layer == SCENE.layer();
			Render2D.rect(g, x + 4, rowY - 1, LAYERS_W + 2, LAYER_H, active ? Theme.accent1 : Theme.borderDark);
			Render2D.rect(g, x + 5, rowY, LAYERS_W, LAYER_H - 2, hover ? Theme.panel : Theme.surface);
			Render2D.textNoShadow(g, LAYER_NAMES[layer - PatternSetting.MIN_Y], x + 9, rowY + 4,
					active || hover ? Theme.text : Theme.textDim);
			int blocks = blocksOn(layer);
			if (blocks > 0) {
				String amount = String.valueOf(blocks);
				Render2D.textNoShadow(g, amount, x + 5 + LAYERS_W - 4 - Render2D.width(amount), rowY + 4, Theme.accent1);
			}
		}
		Render2D.textNoShadow(g, "↑↓ layer", x + 9, layerY(y, PatternSetting.MIN_Y) + LAYER_H + 4, Theme.textDim);

		SCENE.render(g, sceneX(x), y + HEADER, WIDTH - LAYERS_W - 14, HEIGHT - HEADER - 5, mouseX, mouseY, target);
	}

	private static int layerY(int y, int layer) {
		return y + HEADER + (PatternSetting.MAX_Y - layer) * LAYER_H;
	}

	private static int sceneX(int x) {
		return x + LAYERS_W + 9;
	}

	private static int blocksOn(int layer) {
		int blocks = 0;
		for (int[] cell : target.cells()) {
			if (cell[1] == layer) {
				blocks++;
			}
		}
		return blocks;
	}

	/** Consumes every click while open; clicking outside the panel closes it. */
	public static boolean mouseClicked(double mouseX, double mouseY, int button, int guiWidth, int guiHeight) {
		if (target == null) {
			return false;
		}
		int x = popX;
		int y = popY;
		if (!Render2D.hovered(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
			close();
			return true;
		}
		if (SCENE.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		if (mouseY < y + 15) { // drag by the title strip
			draggingPopup = true;
			dragOffX = (int) mouseX - x;
			dragOffY = (int) mouseY - y;
			return true;
		}
		int buttonX = x + 5;
		for (String preset : PatternSetting.PRESETS.keySet()) {
			int w = Render2D.width(preset) + 10;
			if (Render2D.hovered(mouseX, mouseY, buttonX, y + 16, w, 13)) {
				target.preset(preset);
				return true;
			}
			buttonX += w + 4;
		}
		for (int layer = PatternSetting.MIN_Y; layer <= PatternSetting.MAX_Y; layer++) {
			if (Render2D.hovered(mouseX, mouseY, x + 5, layerY(y, layer), LAYERS_W, LAYER_H - 2)) {
				SCENE.layer(layer);
				return true;
			}
		}
		return true;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int guiWidth, int guiHeight) {
		if (SCENE.dragging()) {
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
		if (target != null && SCENE.dragging()) {
			SCENE.mouseReleased(target);
		}
	}

	public static boolean mouseScrolled(double scrollY) {
		if (target == null) {
			return false;
		}
		SCENE.mouseScrolled(scrollY);
		return true;
	}

	/** Up and down step through the layers; anything else is the host's (Escape closes). */
	public static boolean keyPressed(KeyEvent event) {
		if (target == null) {
			return false;
		}
		if (event.key() == InputConstants.KEY_UP) {
			SCENE.layer(SCENE.layer() + 1);
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			SCENE.layer(SCENE.layer() - 1);
			return true;
		}
		return false;
	}
}
