package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

public class WatermarkWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Watermark", "Client name and version", true));
	public final BooleanSetting bg = add(new BooleanSetting("Watermark bg", "Backing behind the watermark", true));
	public final ModeSetting mode = add(new ModeSetting("Watermark style", "Full client mark, minimal name, or compact pill", "Classic", "Classic", "Minimal", "Pill"));
	public final ModeSetting nameStyle = add(new ModeSetting("Watermark name", "Full, abbreviated, cased, or custom client name", "Brand", "Brand", "Short", "Lowercase", "Uppercase", "Custom"));
	public final StringSetting customName = add(new StringSetting("Custom watermark", "Name used by the Custom watermark mode", UnluckyClient.NAME, 32));
	public final BooleanSetting showIcon = add(new BooleanSetting("Watermark icon", "Show a compact themed client icon", false));
	public final BooleanSetting showVersion = add(new BooleanSetting("Watermark version", "Append the client version", true));
	public final BooleanSetting showServer = add(new BooleanSetting("Watermark server", "Append the current server or Singleplayer", false));
	public final BooleanSetting showFps = add(new BooleanSetting("Watermark FPS", "Append the current FPS", false));
	public final BooleanSetting showPing = add(new BooleanSetting("Watermark ping", "Append current connection latency", false));
	public final BooleanSetting line = add(new BooleanSetting("Watermark accent", "Show an animated accent line", true));
	public final ModeSetting lineStyle = add(new ModeSetting("Watermark accent side", "Put the accent beside or underneath the watermark", "Underline", "Underline", "Side"));
	public final ColorSetting color1 = add(new ColorSetting("Watermark color 1", "Watermark gradient start", Theme.hudAccent1));
	public final ColorSetting color2 = add(new ColorSetting("Watermark color 2", "Watermark gradient end", Theme.hudAccent2));
	public final BooleanSetting animate = add(new BooleanSetting("Watermark animation", "Sweep the gradient along a \\ diagonal", true));
	public final NumberSetting speed = add(new NumberSetting("Watermark speed", "Gradient sweep speed", 1.0, 0.1, 5.0, 0.1));

	public WatermarkWidget() {
		super("Watermark");
		customName.showWhen(() -> nameStyle.is("Custom"));
		lineStyle.showWhen(line::get);
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
		String name = displayName();
		java.util.List<String> details = new java.util.ArrayList<>();
		if (showVersion.get()) {
			details.add(UnluckyClient.VERSION);
		}
		if (showServer.get()) {
			var server = mc().getCurrentServer();
			details.add(server == null ? "Singleplayer" : server.ip);
		}
		if (showFps.get()) {
			details.add(mc().getFps() + " FPS");
		}
		if (showPing.get()) {
			details.add(ping() + " ms");
		}
		String detail = String.join(" | ", details);
		boolean minimal = mode.is("Minimal");
		boolean pill = mode.is("Pill");

		float nameScale = minimal || pill ? 1.0f : 1.5f;
		int nameWidth = Math.round(Render2D.width(name) * nameScale);
		int iconSpace = showIcon.get() ? 14 : 0;
		int width = iconSpace + nameWidth + (detail.isEmpty() ? 0 : 4 + Render2D.width(detail)) + 10;
		int nameHeight = (int) Math.ceil(Render2D.FONT_HEIGHT * nameScale * textScale());
		int detailHeight = (int) Math.ceil(Render2D.FONT_HEIGHT * textScale());
		int height = Math.max(16, Math.max(nameHeight, detailHeight) + 4);
		setSize(width, height);
		int nameY = getY() + (height - nameHeight) / 2;

		if (!hasExplicitBackgroundOverride() && bg.get() && !pill) {
			Render2D.hudPanel(g, getX(), getY(), width, height, true);
		} else if (!hasExplicitBackgroundOverride() && bg.get()) {
			Render2D.roundedRect(g, getX(), getY(), width, height, 8, Theme.hudBg(true));
		}
		float phase = 0.0f;
		float wave = 0.0f;
		if (animate.get()) {
			double seconds = (System.currentTimeMillis() % 1_000_000L) / 1000.0;
			phase = (float) (seconds * speed.getFloat() * 30.0);
			wave = 0.5f + 0.5f * (float) Math.sin(seconds * speed.getFloat() * 2.4);
		}
		int leftAccent = ColorUtil.lerp(color1.get(), color2.get(), wave);
		int rightAccent = ColorUtil.lerp(color2.get(), color1.get(), wave);
		if (line.get()) {
			if (lineStyle.is("Side")) {
				Render2D.verticalGradient(g, getX() + 1, nameY, 2, nameHeight, leftAccent, rightAccent);
			} else {
				Render2D.horizontalGradient(g, getX() + 3, getY() + height - 2, width - 6, 1, leftAccent, rightAccent);
			}
		}

		int contentX = getX() + 5;
		if (showIcon.get()) {
			Render2D.roundedGradient(g, contentX, getY() + (height - 10) / 2, 10, 10, 3, leftAccent, rightAccent);
			String glyph = name.isBlank() ? "U" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			Render2D.textNoShadow(g, glyph, contentX + (10 - Render2D.width(glyph)) / 2,
					getY() + (height - detailHeight) / 2, 0xFFFFFFFF);
			contentX += iconSpace;
		}
		Render2D.diagonalGradientText(g, name, contentX, nameY, nameScale,
				color1.get(), color2.get(), phase);

		if (!detail.isEmpty()) {
			// The version's separator must share the enlarged name's baseline; centering it
			// independently leaves the | visibly suspended beside the logo.
			int detailY = nameY + nameHeight - detailHeight;
			Render2D.text(g, detail, contentX + nameWidth + 4, detailY, Theme.textDim);
		}
	}

	private String displayName() {
		return switch (nameStyle.get()) {
			case "Short" -> UnluckyClient.NAME.isBlank() ? "U" : UnluckyClient.NAME.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
			case "Lowercase" -> UnluckyClient.NAME.toLowerCase(java.util.Locale.ROOT);
			case "Uppercase" -> UnluckyClient.NAME.toUpperCase(java.util.Locale.ROOT);
			case "Custom" -> customName.get().isBlank() ? UnluckyClient.NAME : customName.get();
			default -> UnluckyClient.NAME;
		};
	}

	private int ping() {
		if (mc().player == null || mc().getConnection() == null) {
			return 0;
		}
		var info = mc().getConnection().getPlayerInfo(mc().player.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}
}
