package unlucky.utility.client.gui.clickgui.component;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.gui.clickgui.RenderPreview;
import unlucky.utility.client.settings.PreviewSetting;

/** A {@link RenderPreview} as an option row: your skin, the setting's cape, drag to spin. */
public final class PreviewComponent extends GuiComponent {
	public static final int HEIGHT = 96;

	private final RenderPreview preview;

	public PreviewComponent(PreviewSetting setting) {
		// opens showing the back, since a cape is the usual reason to have one of these
		this.preview = new RenderPreview(setting.cape(), 160.0f);
	}

	@Override
	public int getHeight() {
		return HEIGHT;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		preview.render(g, x, y + 1, width, HEIGHT - 3, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return preview.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void mouseDragged(double mouseX, double mouseY) {
		preview.mouseDragged(mouseX);
	}

	@Override
	public void mouseReleased() {
		preview.mouseReleased();
	}
}
