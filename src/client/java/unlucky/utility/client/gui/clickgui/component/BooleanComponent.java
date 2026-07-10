package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/** Skeet-style checkbox row. */
public class BooleanComponent extends GuiComponent {
	public static final int HEIGHT = 13;

	private final BooleanSetting setting;
	private final Animation check;

	public BooleanComponent(BooleanSetting setting) {
		this.setting = setting;
		this.check = new Animation(160, setting.get(), Easing.QUAD_OUT);
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		check.setDirection(setting.get());
		boolean hover = hovered(mouseX, mouseY);
		Render2D.checkbox(g, x, y + 2, 8, check.value());
		int labelColor = setting.get() ? Theme.text : (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);
		Render2D.textNoShadow(g, setting.getName(), x + 12, y + 2, labelColor);
		if (setting.getMobList() != null) {
			// hint that a right-click submenu exists
			String dots = "...";
			Render2D.textNoShadow(g, dots, x + width - Render2D.width(dots), y + 2,
					ColorUtil.withAlpha(Theme.textDim, hover ? 255 : 120));
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!hovered(mouseX, mouseY)) {
			return false;
		}
		if (button == 0) {
			setting.toggle();
			return true;
		}
		if (button == 1 && setting.getMobList() != null) {
			unlucky.utility.client.gui.clickgui.MobPickerPopup.open(setting.getMobList(), setting.isMobListHostile());
			return true;
		}
		return false;
	}
}
