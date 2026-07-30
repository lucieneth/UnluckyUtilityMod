package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

public class WatermarkWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Watermark", "Client name and version", true));
	public final BooleanSetting bg = add(new BooleanSetting("Watermark bg", "Backing behind the watermark", true));
	public final BooleanSetting line = add(new BooleanSetting("Watermark line", "Accent bar down the side", true));
	public final ColorSetting color1 = add(new ColorSetting("Watermark color 1", "Watermark gradient start", Theme.hudAccent1));
	public final ColorSetting color2 = add(new ColorSetting("Watermark color 2", "Watermark gradient end", Theme.hudAccent2));
	public final BooleanSetting animate = add(new BooleanSetting("Watermark animation", "Sweep the gradient along a \\ diagonal", true));
	public final NumberSetting speed = add(new NumberSetting("Watermark speed", "Gradient sweep speed", 1.0, 0.1, 5.0, 0.1));

	public WatermarkWidget() {
		super("Watermark");
	}

	@Override
	public boolean requiresPlayer() {
		return false; // draws fine with no world, so the editor shows it in the main menu
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.0);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		String name = UnluckyClient.NAME;
		String version = UnluckyClient.VERSION;

		int nameWidth = Render2D.width(name) * 3 / 2;
		int width = nameWidth + 4 + Render2D.width(version) + 8;
		int height = 16;
		setSize(width, height);

		if (bg.get()) {
			Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(true));
		}
		if (line.get()) {
			Render2D.verticalGradient(g, getX() + 2, getY() + 2, 2, height - 4,
					color1.get(), color2.get());
		}

		float phase = 0.0f;
		if (animate.get()) {
			double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
			phase = (float) (seconds * speed.getFloat() * 30.0);
		}
		Render2D.diagonalGradientText(g, name, getX() + 5, getY() + 2, 1.5f,
				color1.get(), color2.get(), phase);

		Render2D.text(g, version, getX() + 5 + nameWidth + 4, getY() + 6, Theme.textDim);
	}
}
