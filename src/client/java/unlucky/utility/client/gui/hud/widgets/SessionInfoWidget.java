package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.SessionTracker;

/** Session time plus approximate kills, deaths and K/D, styled to match Info. */
public class SessionInfoWidget extends HudWidget {
	private static final int GREEN = 0xFF3FD46A;
	private static final int YELLOW = 0xFFE0C020;
	private static final int RED = 0xFFE04545;
	private static final int PAD = 7; // clears the accent bar
	private static final String SEP = ":";

	private record Row(String label, String value, int color) {
	}

	public SessionInfoWidget() {
		super("SessionInfo");
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
		return hud().sessionInfo.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.72);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		HudModule hud = hud();
		SessionTracker session = UnluckyClient.INSTANCE.session;

		List<Row> rows = new ArrayList<>();
		if (hud.sessionTime.get()) {
			rows.add(new Row("Time", duration(session.sessionMs()), Theme.text));
		}
		if (hud.sessionKills.get()) {
			rows.add(new Row("Kills", Integer.toString(session.kills()), GREEN));
		}
		if (hud.sessionDeaths.get()) {
			rows.add(new Row("Deaths", Integer.toString(session.deaths()), session.deaths() > 0 ? RED : Theme.text));
		}
		if (hud.sessionKd.get()) {
			float ratio = session.deaths() == 0 ? session.kills() : (float) session.kills() / session.deaths();
			rows.add(new Row("K/D", kd(session.kills(), session.deaths()), ratio >= 1.0f ? GREEN : ratio >= 0.5f ? YELLOW : RED));
		}
		if (rows.isEmpty()) {
			setSize(0, 0);
			return;
		}

		int space = Render2D.width(" ");
		sortBySize(rows, r -> rowWidth(r, space));
		int width = 0;
		for (Row row : rows) {
			width = Math.max(width, rowWidth(row, space));
		}
		width += PAD + 5;
		int height = rows.size() * 10 + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud.sessionBg.get()));
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			drawRow(g, rows.get(i), alignedX(rowWidth(rows.get(i), space), PAD), getY() + 3 + i * 10, space,
					Theme.hudFlowingAccent(i * 0.15f));
		}
	}

	private int rowWidth(Row r, int space) {
		return Render2D.width(r.label()) + Render2D.width(SEP) + space + Render2D.width(r.value());
	}

	/** Draws "label: value" — label in the accent, a dim separator, value in its color. */
	private void drawRow(GuiGraphicsExtractor g, Row r, int x, int y, int space, int labelColor) {
		Render2D.text(g, r.label(), x, y, labelColor);
		int lx = x + Render2D.width(r.label());
		Render2D.text(g, SEP, lx, y, Theme.textDim);
		Render2D.text(g, r.value(), lx + Render2D.width(SEP) + space, y, r.color());
	}

	/** A 2px accent bar flowing down whichever edge the widget is docked against. */
	private void drawAccentBar(GuiGraphicsExtractor g, int height) {
		int barX = anchorRight() ? getX() + getWidth() - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, height - 4,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
	}

	private static String duration(long ms) {
		long s = ms / 1000;
		long h = s / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		return h > 0
				? h + ":" + String.format("%02d:%02d", m, sec)
				: m + ":" + String.format("%02d", sec);
	}

	private static String kd(int kills, int deaths) {
		return deaths == 0 ? String.format("%.2f", (double) kills) : String.format("%.2f", (double) kills / deaths);
	}
}
