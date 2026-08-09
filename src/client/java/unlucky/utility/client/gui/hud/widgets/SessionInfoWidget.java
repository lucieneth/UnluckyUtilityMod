package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudManager;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.SessionTracker;

/** Session time plus approximate kills, deaths and K/D, styled to match Info. */
public class SessionInfoWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("SessionInfo", "Session time, kills and deaths", false));
	public final BooleanSetting bg = add(new BooleanSetting("Session bg", "Backing behind the session info", true));
	public final BooleanSetting time = add(new BooleanSetting("Session time", "Show elapsed session time", true));
	public final BooleanSetting kills = add(new BooleanSetting("Session kills", "Show approximate kills", true));
	public final BooleanSetting deaths = add(new BooleanSetting("Session deaths", "Show deaths", true));
	public final BooleanSetting kd = add(new BooleanSetting("Session K/D", "Show kill/death ratio", true));

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
		setFractions(1.0, 0.72);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		SessionTracker session = UnluckyClient.INSTANCE.session;
		boolean preview = editing && HudManager.isPreviewData()
				&& session.kills() == 0 && session.deaths() == 0;
		int shownKills = preview ? 7 : session.kills();
		int shownDeaths = preview ? 2 : session.deaths();
		long shownTime = preview && session.sessionMs() < 1_000L ? 14 * 60_000L + 32_000L : session.sessionMs();

		List<Row> rows = new ArrayList<>();
		if (time.get()) {
			rows.add(new Row("Time", duration(shownTime), Theme.text));
		}
		if (kills.get()) {
			rows.add(new Row("Kills", Integer.toString(shownKills), GREEN));
		}
		if (deaths.get()) {
			rows.add(new Row("Deaths", Integer.toString(shownDeaths), shownDeaths > 0 ? RED : Theme.text));
		}
		if (kd.get()) {
			float ratio = shownDeaths == 0 ? shownKills : (float) shownKills / shownDeaths;
			rows.add(new Row("K/D", kd(shownKills, shownDeaths), ratio >= 1.0f ? GREEN : ratio >= 0.5f ? YELLOW : RED));
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
		int rowHeight = styledLineHeight(10);
		int height = rows.size() * rowHeight + 4;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			drawRow(g, rows.get(i), alignedX(rowWidth(rows.get(i), space), PAD), getY() + 3 + i * rowHeight, space,
					accentAt(getY() + 3 + i * rowHeight, g.guiHeight()));
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
		drawDockedAccentBar(g, getContentWidth(), getY() + 2, height - 4);
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
