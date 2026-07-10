package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Editable text field: click to focus, type to edit, Enter/Escape to finish. */
public class StringComponent extends GuiComponent {
	private static final int ROW = 13;

	private final StringSetting setting;
	private boolean focused;

	public StringComponent(StringSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return ROW;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		boolean hover = hovered(mouseX, mouseY);
		Render2D.textNoShadow(g, setting.getName(), x, y + 2,
				hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);

		int fieldX = x + width / 2;
		int fieldW = width - width / 2;
		Render2D.rect(g, fieldX, y + 1, fieldW, ROW - 2, Theme.surface);
		if (focused) {
			g.outline(fieldX, y + 1, fieldW, ROW - 2, Theme.accent1);
		}

		String text = setting.get();
		String shown = text;
		while (Render2D.width(shown) > fieldW - 6 && !shown.isEmpty()) {
			shown = shown.substring(1); // scroll to keep the caret end visible
		}
		Render2D.textNoShadow(g, shown, fieldX + 3, y + 2, text.isEmpty() ? Theme.textDim : Theme.text);
		if (focused && (System.currentTimeMillis() % 1000) < 500) {
			Render2D.rect(g, fieldX + 3 + Render2D.width(shown), y + 2, 1, 9, Theme.accent1);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (hovered(mouseX, mouseY)) {
			focused = !focused;
			return true;
		}
		focused = false; // clicking elsewhere unfocuses
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (focused && event.isAllowedChatCharacter()) {
			setting.set(setting.get() + event.codepointAsString());
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) {
			return false;
		}
		if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			String s = setting.get();
			if (!s.isEmpty()) {
				setting.set(s.substring(0, s.length() - 1));
			}
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) {
			focused = false;
			return true;
		}
		return true; // swallow other keys while focused so they don't leak (e.g. GUI hotkeys)
	}
}
