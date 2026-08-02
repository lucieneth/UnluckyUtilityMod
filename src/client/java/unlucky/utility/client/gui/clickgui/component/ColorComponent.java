package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.ui.ColorPicker;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Color row with a bordered swatch on the right; clicking expands the shared
 * {@link ColorPicker} body (bars / hex / RGB).
 */
public class ColorComponent extends GuiComponent {
	private static final int ROW = 13;

	private final ColorSetting setting;
	private final ColorPicker picker = new ColorPicker();
	private final Animation expand = new Animation(180, false, Easing.CUBIC_OUT);
	private boolean open;

	public ColorComponent(ColorSetting setting) {
		this.setting = setting;
	}

	@Override
	public int getHeight() {
		return ROW + (int) (expand.value() * ColorPicker.height());
	}

	@Override
	public boolean isExpanded() {
		return open || expand.value() > 0.0f;
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
			picker.render(g, setting, x, y + ROW, width, mouseX, mouseY);
			g.disableScissor();
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (Render2D.hovered(mouseX, mouseY, x, y, width, ROW)) {
			if (button == 0 || button == 1) {
				open = !open;
				if (!open) {
					picker.close();
				}
				return true;
			}
			return false;
		}
		if (open && button == 0
				&& Render2D.hovered(mouseX, mouseY, x, y + ROW, width, ColorPicker.height())) {
			return picker.mouseClicked(mouseX, mouseY, setting, x, y + ROW, width);
		}
		picker.close(); // clicking anywhere else drops text focus
		return false;
	}

	@Override
	public void mouseDragged(double mouseX, double mouseY) {
		picker.mouseDragged(mouseX);
	}

	@Override
	public void mouseReleased() {
		picker.mouseReleased();
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return picker.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		return picker.keyPressed(event);
	}

	@Override
	public boolean typing() {
		return picker.typing();
	}
}
