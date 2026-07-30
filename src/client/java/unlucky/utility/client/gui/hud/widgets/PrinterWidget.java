package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.world.Printer;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/**
 * Live read-out of the Printer: what it is doing, blocks placed and still missing,
 * the current rate, and the ETA for the whole schematic — every layer, not just the
 * band being built. Purely a viewer of the module's own counters; if the numbers on
 * screen are wrong, the counters are the bug.
 */
public class PrinterWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Printer", "Printer progress: placed, missing, rate and whole-schematic ETA", true));
	public final BooleanSetting bg = add(new BooleanSetting("Printer bg", "Backing behind the printer read-out", true));
	public final ColorSetting color = add(new ColorSetting("Printer color", "Printer read-out text color", Theme.text));
	public final BooleanSetting showTitle = add(new BooleanSetting("Printer title", "Show the widget's title row", true));
	public final BooleanSetting showStatus = add(new BooleanSetting("Printer status", "Show what the printer is doing right now", true));
	public final BooleanSetting showPlaced = add(new BooleanSetting("Printer placed", "Show blocks placed on this print", true));
	public final BooleanSetting showElapsed = add(new BooleanSetting("Printer elapsed", "Show time spent working on this print, pauses excluded", true));
	public final BooleanSetting showMissing = add(new BooleanSetting("Printer missing", "Show blocks still missing across every layer", true));
	public final BooleanSetting showRate = add(new BooleanSetting("Printer rate", "Show blocks placed per second", true));
	public final BooleanSetting showEta = add(new BooleanSetting("Printer ETA", "Show the estimated time for every remaining layer", true));

	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private static final int GREEN = 0xFF3FD46A;

	private record Row(String text, int color) {
	}

	public PrinterWidget() {
		super("Printer");
	}

	private Printer printer() {
		return UnluckyClient.INSTANCE.modules.get(Printer.class);
	}

	@Override
	public boolean isVisible() {
		// no point taking up screen while the thing it reports on is off
		return enabled.get() && printer().isEnabled();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.4);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		Printer printer = printer();
		int base = color.get();
		List<Row> rows = new ArrayList<>();

		if (showTitle.get()) {
			rows.add(new Row("Printer", base));
		}
		if (showStatus.get()) {
			rows.add(new Row(printer.hudStatus(), Theme.textDim));
		}
		if (showPlaced.get()) {
			rows.add(new Row("placed  " + format(printer.placedTotal()), base));
		}
		int missing = printer.missingTotal();
		if (showMissing.get()) {
			rows.add(new Row("missing  " + (missing < 0 ? "counting..." : format(missing)),
					missing == 0 ? GREEN : base));
		}
		if (showElapsed.get()) {
			rows.add(new Row("elapsed  " + clock(printer.elapsedSeconds()), Theme.textDim));
		}
		double rate = printer.placeRate();
		if (showRate.get() && rate >= 0.01) {
			rows.add(new Row(String.format("%.1f blocks/s", rate), Theme.textDim));
		}
		if (showEta.get()) {
			long eta = printer.etaSeconds();
			rows.add(new Row("ETA  " + (missing == 0 ? "done" : eta < 0 ? "-" : clock(eta)),
					missing == 0 ? GREEN : base));
		}
		// every row switched off would leave an empty box floating on screen
		if (rows.isEmpty()) {
			rows.add(new Row("Printer", base));
		}

		int width = 0;
		for (Row row : rows) {
			width = Math.max(width, Render2D.width(row.text()));
		}
		width += PAD + 5;
		int height = rows.size() * ROW + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(bg.get()));
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			Render2D.text(g, row.text(), alignedX(Render2D.width(row.text()), PAD),
					getY() + 3 + i * ROW, row.color());
		}
	}

	/** 12345 -> "12,345" — the counts get big enough to misread without it. */
	static String format(int value) {
		return String.format("%,d", value);
	}

	/** Seconds -> "1h 23m" / "4m 05s" / "37s". */
	static String clock(long seconds) {
		if (seconds >= 3600) {
			return seconds / 3600 + "h " + (seconds % 3600) / 60 + "m";
		}
		if (seconds >= 60) {
			return seconds / 60 + "m " + String.format("%02ds", seconds % 60);
		}
		return seconds + "s";
	}

	private void drawAccentBar(GuiGraphicsExtractor g, int height) {
		int barX = anchorRight() ? getX() + getWidth() - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, height - 4,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
	}
}
