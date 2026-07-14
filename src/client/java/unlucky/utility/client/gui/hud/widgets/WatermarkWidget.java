package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

public class WatermarkWidget extends HudWidget {
	public WatermarkWidget() {
		super("Watermark");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return hud().watermark.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.0);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		String name = UnluckyClient.NAME;
		String version = UnluckyClient.VERSION;

		int nameWidth = Render2D.width(name) * 3 / 2;
		int width = nameWidth + 4 + Render2D.width(version) + 8;
		int height = 16;
		setSize(width, height);

		if (hud.watermarkBg.get()) {
			Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(true));
		}
		if (hud.watermarkLine.get()) {
			Render2D.verticalGradient(g, getX() + 2, getY() + 2, 2, height - 4,
					hud.watermarkColor1.get(), hud.watermarkColor2.get());
		}

		float phase = 0.0f;
		if (hud.watermarkAnimate.get()) {
			double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
			phase = (float) (seconds * hud.watermarkSpeed.getFloat() * 30.0);
		}
		Render2D.diagonalGradientText(g, name, getX() + 5, getY() + 2, 1.5f,
				hud.watermarkColor1.get(), hud.watermarkColor2.get(), phase);

		Render2D.text(g, version, getX() + 5 + nameWidth + 4, getY() + 6, Theme.textDim);
	}
}
