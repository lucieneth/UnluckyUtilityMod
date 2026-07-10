package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.ServerStats;

/**
 * Configurable stats readout: FPS, ping, TPS and time. Labels flow through the
 * HUD accent gradient; performance values are graded green→yellow→red so they
 * catch the eye and mean something at a glance. An accent bar hugs the docked edge.
 */
public class InfoWidget extends HudWidget {
	private static final int GREEN = 0xFF3FD46A;
	private static final int YELLOW = 0xFFE0C020;
	private static final int RED = 0xFFE04545;
	private static final int PAD = 7; // clears the accent bar
	private static final String SEP = ":";

	private record Stat(String label, String value, int color) {
	}

	public InfoWidget() {
		super("Info");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	@Override
	public boolean isVisible() {
		return hud().info.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 1.0);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		List<Stat> stats = new ArrayList<>();
		if (hud.infoFps.get()) {
			int fps = mc().getFps();
			stats.add(new Stat("FPS", Integer.toString(fps), grade(fps, 60, 30)));
		}
		if (hud.infoPing.get()) {
			int ping = ping();
			stats.add(new Stat("Ping", ping + "ms", ping <= 50 ? GREEN : ping <= 120 ? YELLOW : RED));
		}
		if (hud.infoTps.get()) {
			stats.add(new Stat("TPS", String.format("%.1f", ServerStats.tps), grade(ServerStats.tps, 19f, 14f)));
		}
		if (hud.infoTime.get()) {
			String pattern = hud.infoTime24h.get()
					? (hud.infoSeconds.get() ? "HH:mm:ss" : "HH:mm")
					: (hud.infoSeconds.get() ? "h:mm:ss a" : "h:mm a");
			stats.add(new Stat("TIME", java.time.LocalTime.now()
					.format(java.time.format.DateTimeFormatter.ofPattern(pattern)), Theme.text));
		}
		if (hud.infoMcTime.get() && mc().level != null) {
			long ticks = mc().level.getOverworldClockTime();
			long day = ticks / 24000;
			long tod = ticks % 24000;
			int hh = (int) ((tod / 1000 + 6) % 24);
			int mm = (int) ((tod % 1000) * 60 / 1000);
			stats.add(new Stat("World", day + "d " + String.format("%02d:%02d", hh, mm), Theme.text));
		}
		if (stats.isEmpty()) {
			setSize(0, 0);
			return;
		}

		int space = Render2D.width(" ");
		if (hud.infoLayout.is("List")) {
			drawList(g, hud, stats, space);
		} else {
			drawFlat(g, hud, stats, space);
		}
	}

	private void drawFlat(GuiGraphicsExtractor g, HudModule hud, List<Stat> stats, int space) {
		int gap = Render2D.width("  ");
		int content = 0;
		for (int i = 0; i < stats.size(); i++) {
			content += statWidth(stats.get(i), space);
			if (i > 0) {
				content += gap;
			}
		}
		int width = content + PAD + 5;
		setSize(width, 13);
		Render2D.roundedRect(g, getX(), getY(), width, 13, 4, Theme.hudBg(hud.infoBg.get()));
		drawAccentBar(g, 13);

		int x = alignedX(content, PAD);
		int y = getY() + 3;
		for (int i = 0; i < stats.size(); i++) {
			if (i > 0) {
				x += gap;
			}
			drawStat(g, stats.get(i), x, y, space, Theme.hudFlowingAccent(i * 0.15f));
			x += statWidth(stats.get(i), space);
		}
	}

	private void drawList(GuiGraphicsExtractor g, HudModule hud, List<Stat> stats, int space) {
		sortBySize(stats, s -> statWidth(s, space));
		int width = 0;
		for (Stat s : stats) {
			width = Math.max(width, statWidth(s, space));
		}
		width += PAD + 5;
		int height = stats.size() * 10 + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud.infoBg.get()));
		drawAccentBar(g, height);

		for (int i = 0; i < stats.size(); i++) {
			drawStat(g, stats.get(i), alignedX(statWidth(stats.get(i), space), PAD), getY() + 3 + i * 10, space,
					Theme.hudFlowingAccent(i * 0.15f));
		}
	}

	private int statWidth(Stat s, int space) {
		return Render2D.width(s.label()) + Render2D.width(SEP) + space + Render2D.width(s.value());
	}

	/** Draws "label: value" — label in the accent, a dim separator, value in its grade color. */
	private void drawStat(GuiGraphicsExtractor g, Stat s, int x, int y, int space, int labelColor) {
		Render2D.text(g, s.label(), x, y, labelColor);
		int lx = x + Render2D.width(s.label());
		Render2D.text(g, SEP, lx, y, Theme.textDim);
		Render2D.text(g, s.value(), lx + Render2D.width(SEP) + space, y, s.color());
	}

	/** A 2px accent bar flowing down whichever edge the widget is docked against. */
	private void drawAccentBar(GuiGraphicsExtractor g, int height) {
		int barX = anchorRight() ? getX() + getWidth() - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, height - 4,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
	}

	private static int grade(float value, float good, float mid) {
		return value >= good ? GREEN : value >= mid ? YELLOW : RED;
	}

	private int ping() {
		if (mc().player == null || mc().getConnection() == null) {
			return 0;
		}
		PlayerInfo info = mc().getConnection().getPlayerInfo(mc().player.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}
}
