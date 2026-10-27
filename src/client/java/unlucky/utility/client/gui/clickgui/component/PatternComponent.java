package unlucky.utility.client.gui.clickgui.component;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.PatternPopup;
import unlucky.utility.client.settings.PatternSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Button row that opens the 3D pattern editor, naming the preset or the block count on its face. */
public class PatternComponent extends GuiComponent {
	private final PatternSetting setting;

	public PatternComponent(PatternSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return 26;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		ScrollingText.draw(g, setting.getName(), x, y + 2, width, Theme.textDim);
		int boxY = y + 12;
		boolean hover = hovered(mouseX, mouseY);
		Render2D.rect(g, x - 1, boxY - 1, width + 2, 14, Theme.borderDark);
		Render2D.rect(g, x, boxY, width, 12, hover ? Theme.panel : Theme.surface);
		int blocks = setting.get().size();
		String preset = setting.matchingPreset();
		String label = blocks == 0
				? "Empty — click to edit..."
				: (preset != null ? preset + ", " : "Custom, ") + blocks + (blocks == 1 ? " block" : " blocks");
		ScrollingText.draw(g, label, x + 4, boxY + 2, width - 8, hover ? Theme.text : Theme.textDim);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == InputConstants.MOUSE_BUTTON_LEFT && hovered(mouseX, mouseY)) {
			PatternPopup.open(setting);
			return true;
		}
		return false;
	}
}
