package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.BrewQueuePopup;
import unlucky.utility.client.settings.BrewQueueSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Button row that opens the brew queue popup, summarising the queue on its face. */
public class BrewQueueComponent extends GuiComponent {
	private final BrewQueueSetting setting;

	public BrewQueueComponent(BrewQueueSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return 26;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Render2D.textNoShadow(g, setting.getName(), x, y + 2, Theme.textDim);
		int boxY = y + 12;
		boolean hover = hovered(mouseX, mouseY);
		Render2D.rect(g, x - 1, boxY - 1, width + 2, 14, Theme.borderDark);
		Render2D.rect(g, x, boxY, width, 12, hover ? Theme.panel : Theme.surface);
		int orders = setting.get().size();
		String label = orders == 0
				? "Empty — click to add..."
				: orders + (orders == 1 ? " order, " : " orders, ") + setting.total() + " bottles";
		Render2D.textNoShadow(g, label, x + 4, boxY + 2, hover ? Theme.text : Theme.textDim);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && hovered(mouseX, mouseY)) {
			BrewQueuePopup.open(setting);
			return true;
		}
		return false;
	}
}
