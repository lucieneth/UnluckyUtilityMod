package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Editable text field: click to focus (in the field: places the caret; on the
 * label: toggles), full TextBox editing — selection, clipboard, word jumps —
 * Enter/Escape to finish.
 */
public class StringComponent extends GuiComponent {
	private static final int ROW = 13;

	private final StringSetting setting;
	private final TextBox box = new TextBox();
	private boolean focused;
	private boolean draggingText;

	public StringComponent(StringSetting setting) {
		this.setting = setting;
		box.onChange(() -> setting.set(box.text()));
	}

	@Override
	public int getHeight() {
		return ROW;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (!focused) {
			box.setText(setting.get()); // stay synced with external changes while idle
		}
		boolean hover = hovered(mouseX, mouseY);
		Render2D.textNoShadow(g, setting.getName(), x, y + 2,
				hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);

		int fieldX = x + width / 2;
		int fieldW = width - width / 2;
		Render2D.rect(g, fieldX, y + 1, fieldW, ROW - 2, Theme.surface);
		if (focused) {
			g.outline(fieldX, y + 1, fieldW, ROW - 2, Theme.accent1);
		}
		box.render(g, fieldX + 3, y + 2, fieldW - 6, focused, null);
	}

	private int textX() {
		return x + width / 2 + 3;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (hovered(mouseX, mouseY)) {
			boolean inField = mouseX >= x + width / 2;
			if (!focused) {
				focused = true;
				box.setText(setting.get());
				if (inField) {
					box.click(mouseX - textX());
				} else {
					box.moveCaretToEnd();
				}
			} else if (inField) {
				box.click(mouseX - textX());
			} else {
				focused = false; // clicking the label side finishes editing
			}
			draggingText = focused && inField;
			return true;
		}
		focused = false; // clicking elsewhere unfocuses
		return false;
	}

	@Override
	public void mouseDragged(double mouseX, double mouseY) {
		if (draggingText) {
			box.drag(mouseX - textX());
		}
	}

	@Override
	public void mouseReleased() {
		draggingText = false;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return focused && box.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		if (box.keyPressed(event)) {
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) {
			focused = false;
			return true;
		}
		return true; // swallow other keys while focused so they don't leak (e.g. GUI hotkeys)
	}
}
