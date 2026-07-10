package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Color row with a bordered swatch on the right; clicking expands
 * hue / saturation / brightness bars.
 */
public class ColorComponent extends GuiComponent {
	private static final int ROW = 13;
	private static final int SLIDER_ROWS = 3;

	private final ColorSetting setting;
	private final Animation expand = new Animation(180, false, Easing.CUBIC_OUT);
	private boolean open;
	private int draggingRow = -1;

	private float hue;
	private float saturation;
	private float brightness;

	public ColorComponent(ColorSetting setting) {
		this.setting = setting;
		float[] hsb = setting.hsb();
		this.hue = hsb[0];
		this.saturation = hsb[1];
		this.brightness = hsb[2];
	}

	@Override
	public int getHeight() {
		return ROW + (int) (expand.value() * SLIDER_ROWS * ROW);
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		expand.setDirection(open);

		boolean hover = Render2D.hovered(mouseX, mouseY, x, y, width, ROW);
		Render2D.textNoShadow(g, setting.getName(), x, y + 2,
				hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);
		int swatchX = x + width - 13;
		Render2D.rect(g, swatchX - 1, y + 1, 13, 9, Theme.borderDark);
		Render2D.rect(g, swatchX, y + 2, 11, 7, setting.get() | 0xFF000000);

		int extra = getHeight() - ROW;
		if (extra > 0) {
			g.enableScissor(x, y + ROW, x + width, y + ROW + extra);
			renderBar(g, 0, "Hue", hue, true);
			renderBar(g, 1, "Sat", saturation, false);
			renderBar(g, 2, "Val", brightness, false);
			g.disableScissor();
		}
	}

	private void renderBar(GuiGraphicsExtractor g, int row, String label, float value, boolean rainbow) {
		int rowY = y + ROW + row * ROW;
		Render2D.textNoShadow(g, label, x + 4, rowY + 2, Theme.textDim);
		int barX = x + 28;
		int barWidth = width - 30;
		int barY = rowY + 3;
		Render2D.rect(g, barX - 1, barY - 1, barWidth + 2, 7, Theme.borderDark);
		if (rainbow) {
			for (int i = 0; i < barWidth; i++) {
				g.fill(barX + i, barY, barX + i + 1, barY + 5, ColorUtil.hsb((float) i / barWidth, 1.0f, 1.0f, 255));
			}
		} else {
			Render2D.rect(g, barX, barY, barWidth, 5, Theme.surface);
			int filled = (int) (barWidth * value);
			Render2D.rect(g, barX, barY, filled, 5, setting.get() | 0xFF000000);
		}
		int handleX = barX + (int) (value * (barWidth - 1));
		Render2D.rect(g, handleX, barY - 1, 1, 7, Theme.text);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (Render2D.hovered(mouseX, mouseY, x, y, width, ROW)) {
			if (button == 0 || button == 1) {
				open = !open;
				return true;
			}
		}
		if (open && button == 0) {
			for (int row = 0; row < SLIDER_ROWS; row++) {
				int rowY = y + ROW + row * ROW;
				if (Render2D.hovered(mouseX, mouseY, x, rowY, width, ROW)) {
					draggingRow = row;
					applyMouse(mouseX);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void mouseDragged(double mouseX, double mouseY) {
		if (draggingRow >= 0) {
			applyMouse(mouseX);
		}
	}

	@Override
	public void mouseReleased() {
		draggingRow = -1;
	}

	private void applyMouse(double mouseX) {
		int barX = x + 28;
		int barWidth = width - 30;
		float fraction = (float) Math.clamp((mouseX - barX) / barWidth, 0.0, 1.0);
		switch (draggingRow) {
			case 0 -> hue = fraction;
			case 1 -> saturation = fraction;
			case 2 -> brightness = fraction;
			default -> {
			}
		}
		setting.setHsb(hue, saturation, brightness, setting.alpha());
	}
}
