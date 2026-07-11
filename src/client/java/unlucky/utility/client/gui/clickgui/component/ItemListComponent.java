package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.ItemPickerPopup;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Button row that opens the item picker popup. */
public class ItemListComponent extends GuiComponent {
	private final ItemListSetting setting;

	public ItemListComponent(ItemListSetting setting) {
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
		String label = setting.get().size() + " selected...";
		Render2D.textNoShadow(g, label, x + 4, boxY + 2, hover ? Theme.text : Theme.textDim);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && hovered(mouseX, mouseY)) {
			ItemPickerPopup.open(setting);
			return true;
		}
		return false;
	}
}
