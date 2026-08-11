package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.GroupSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/** Header row for a {@link GroupSetting}: click anywhere on it to open or shut the group. */
public class GroupComponent extends GuiComponent {
	public static final int HEIGHT = 13;

	private final GroupSetting setting;
	private final Animation open;

	public GroupComponent(GroupSetting setting) {
		this.setting = setting;
		this.open = new Animation(160, setting.isExpanded(), Easing.QUAD_OUT);
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		open.setDirection(setting.isExpanded());
		boolean hover = hovered(mouseX, mouseY);
		int labelColor = setting.isExpanded()
				? Theme.text
				: (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);

		// the trailing dots are the only affordance, matching the list settings: an
		// open group already announces itself by the rows underneath it
		String dots = "...";
		int hintWidth = Render2D.width(dots) + 3;
		ScrollingText.draw(g, setting.getName(), x, y + 2, width - hintWidth, labelColor);
		Render2D.textNoShadow(g, dots, x + width - Render2D.width(dots), y + 2,
				ColorUtil.withAlpha(Theme.textDim, hover || setting.isExpanded() ? 255 : 120));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button != 0 || !hovered(mouseX, mouseY)) {
			return false;
		}
		setting.toggle();
		return true;
	}
}
