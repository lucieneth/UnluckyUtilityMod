package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.gui.clickgui.ClickGuiPalette;
import unlucky.utility.client.settings.StringListSetting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Compact semicolon-delimited editor for an ordered {@link StringListSetting}. */
public class StringListComponent extends GuiComponent {
	private static final int ROW = 13;
	private final StringListSetting setting;
	private final TextBox box = new TextBox();
	private boolean focused;
	private boolean draggingText;

	public StringListComponent(StringListSetting setting) {
		this.setting = setting;
		box.onChange(() -> setting.setEditorText(box.text()));
	}

	@Override public int getHeight() { return ROW; }

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (!focused) box.setText(setting.editorText());
		boolean hover = hovered(mouseX, mouseY);
		ScrollingText.draw(g, setting.getName(), x, y + 2, width / 2 - 3,
				hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);
		int fieldX = x + width / 2;
		int fieldW = width - width / 2;
		Render2D.rect(g, fieldX, y + 1, fieldW, ROW - 2, Theme.surface);
		if (focused) g.outline(fieldX, y + 1, fieldW, ROW - 2, ClickGuiPalette.accent1());
		box.render(g, fieldX + 3, y + 2, fieldW - 6, focused, null);
	}

	private int textX() { return x + width / 2 + 3; }

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!hovered(mouseX, mouseY)) { focused = false; return false; }
		boolean inField = mouseX >= x + width / 2;
		if (!focused) {
			focused = true;
			box.setText(setting.editorText());
			if (inField) box.click(mouseX - textX()); else box.moveCaretToEnd();
		} else if (inField) {
			box.click(mouseX - textX());
		} else {
			focused = false;
		}
		draggingText = focused && inField;
		return true;
	}

	@Override public void mouseDragged(double mouseX, double mouseY) { if (draggingText) box.drag(mouseX - textX()); }
	@Override public void mouseReleased() { draggingText = false; }
	@Override public boolean charTyped(CharacterEvent event) { return focused && box.charTyped(event); }
	@Override public boolean typing() { return focused; }

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!focused) return false;
		if (box.keyPressed(event)) return true;
		if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) focused = false;
		return true;
	}
}
