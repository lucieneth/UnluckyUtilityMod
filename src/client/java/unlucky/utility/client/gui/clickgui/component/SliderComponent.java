package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/** Skeet-style slider: label + value above a bordered gradient bar. */
public class SliderComponent extends GuiComponent {
	public static final int HEIGHT = 20;

	private final NumberSetting setting;
	private boolean dragging;

	public SliderComponent(NumberSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Render2D.textNoShadow(g, setting.getName(), x, y + 2, Theme.textDim);
		String value = setting.display();
		Render2D.textNoShadow(g, value, x + width - Render2D.width(value), y + 2, Theme.text);

		int barY = y + 12;
		int barHeight = 5;
		float fraction = (float) ((setting.get() - setting.getMin()) / (setting.getMax() - setting.getMin()));
		// recessed track with a 1px dark border
		Render2D.rect(g, x - 1, barY - 1, width + 2, barHeight + 2, Theme.borderDark);
		Render2D.rect(g, x, barY, width, barHeight, Theme.surface);
		int filled = (int) (width * fraction);
		if (filled > 0) {
			Render2D.horizontalGradient(g, x, barY, filled, barHeight, Theme.accent1, Theme.accent(fraction));
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && hovered(mouseX, mouseY)) {
			dragging = true;
			applyMouse(mouseX);
			return true;
		}
		return false;
	}

	@Override
	public void mouseDragged(double mouseX, double mouseY) {
		if (dragging) {
			applyMouse(mouseX);
		}
	}

	@Override
	public void mouseReleased() {
		dragging = false;
	}

	private void applyMouse(double mouseX) {
		double fraction = Math.clamp((mouseX - x) / width, 0.0, 1.0);
		setting.set(setting.getMin() + fraction * (setting.getMax() - setting.getMin()));
	}
}
