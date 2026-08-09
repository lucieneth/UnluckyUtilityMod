package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.ClickGuiPalette;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** A one-click action button in a module's option list. */
public final class ActionComponent extends GuiComponent {
	public static final int HEIGHT = 13;

	private final ActionSetting setting;

	public ActionComponent(ActionSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		boolean hover = hovered(mouseX, mouseY);
		Render2D.rect(g, x - 1, y, width + 2, 12, Theme.borderDark);
		Render2D.rect(g, x, y + 1, width, 10, hover ? Theme.window : Theme.surface);
		String label = setting.getName();
		ScrollingText.drawCentered(g, label, x + 2, y + 2, width - 4,
				hover ? ClickGuiPalette.accent2() : Theme.textDim);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0 || !hovered(mouseX, mouseY)) {
			return false;
		}
		setting.run();
		return true;
	}
}
