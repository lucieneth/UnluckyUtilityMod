package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.ClickGuiPalette;
import unlucky.utility.client.settings.ToggleGroupSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Checkbox row that also opens a submenu.
 *
 * <p>The row is split: the checkbox and label toggle the option, while the marker at the
 * right edge slides the submenu open. Keeping them apart matters — configuring which
 * chests to highlight should never switch storage highlighting off as a side effect.
 */
public class ToggleGroupComponent extends GuiComponent {
	public static final int HEIGHT = 13;
	/** Width of the expand hit zone at the right edge. */
	private static final int MARKER_ZONE = 12;

	private final ToggleGroupSetting setting;
	private final Animation check;

	public ToggleGroupComponent(ToggleGroupSetting setting) {
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
		boolean overMarker = hover && mouseX >= x + width - MARKER_ZONE;

		Render2D.checkbox(g, x, y + 2, 8, check.value(),
				ClickGuiPalette.accent2(), ClickGuiPalette.accent1());
		int labelColor = setting.get()
				? Theme.text
				: (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);
		ScrollingText.draw(g, setting.getName(), x + 12, y + 2, width - 12 - MARKER_ZONE, labelColor);

		// trailing dots only: the rows that appear underneath already say it is open
		String dots = "...";
		Render2D.textNoShadow(g, dots, x + width - Render2D.width(dots), y + 2,
				ColorUtil.withAlpha(Theme.textDim, overMarker || setting.isExpanded() ? 255 : 140));
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!hovered(mouseX, mouseY)) {
			return false;
		}
		if (button == 0 && mouseX >= x + width - MARKER_ZONE) {
			setting.toggleExpanded();
			return true;
		}
		if (button == 0) {
			setting.toggle();
			return true;
		}
		// right-click anywhere on the row also opens it, for anyone who does not spot the marker
		if (button == 1) {
			setting.toggleExpanded();
			return true;
		}
		return false;
	}
}
