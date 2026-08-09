package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.world.Printer;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
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
	public final BooleanSetting compact = add(new BooleanSetting("Printer compact",
			"Pack the numbers onto shared rows and drop their labels. Off keeps the "
					+ "one-stat-per-row read-out.", false));
	public final NumberSetting maxWidth = add(new NumberSetting("Printer max width",
			"Wrap rows wider than this onto another line, so a long status cannot stretch "
					+ "the widget across the screen.", 150, 80, 400, 10));
	public final ModeSetting progressStyle = add(new ModeSetting("Printer progress", "Show text, a progress bar, or both", "Both", "Text", "Bar", "Both"));
	public final ColorSetting successColor = add(new ColorSetting("Printer success color", "Color used when the print is complete", 0xFF3FD46A));
	public final ColorSetting errorColor = add(new ColorSetting("Printer error color", "Color used for error states", 0xFFE04545));
	public final BooleanSetting autoHide = add(new BooleanSetting("Printer auto hide", "Hide the widget shortly after completing", false));
	public final NumberSetting autoHideDelay = add(new NumberSetting("Printer hide delay", "Seconds to remain visible after completion", 4, 0, 30, 1));

	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private long doneSince;

	private record Row(String text, int color) {
	}

	public PrinterWidget() {
		super("Printer");
		autoHideDelay.showWhen(autoHide::get);
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
			rows.add(new Row(printer.hudStatus(), printer.hudStatus().startsWith("\u00A7c") ? errorColor.get() : Theme.textDim));
		}
		int missing = printer.missingTotal();
		if (missing == 0) {
			if (doneSince == 0L) {
				doneSince = System.currentTimeMillis();
			}
			if (!editing && autoHide.get() && System.currentTimeMillis() - doneSince >= autoHideDelay.get() * 1000.0) {
				setSize(0, 0);
				return;
			}
		} else {
			doneSince = 0L;
		}
		double rate = printer.placeRate();
		long eta = printer.etaSeconds();
		if (compact.get()) {
			// Two facts to a row and no labels the numbers do not need. "placed"/"missing"
			// spelled out on separate lines is most of the width for none of the meaning.
			String counts = join(
					showPlaced.get() ? format(printer.placedTotal()) + " laid" : "",
					showMissing.get()
							? (missing < 0 ? "counting..." : format(missing) + " left") : "");
			if (!counts.isEmpty()) {
				rows.add(new Row(counts, missing == 0 ? successColor.get() : base));
			}
			String pace = join(
					showRate.get() && rate >= 0.01 ? String.format("%.1f/s", rate) : "",
					showEta.get() ? (missing == 0 ? "done" : eta < 0 ? "-" : clock(eta)) : "",
					showElapsed.get() ? clock(printer.elapsedSeconds()) : "");
			if (!pace.isEmpty()) {
				rows.add(new Row(pace, Theme.textDim));
			}
		} else {
			if (showPlaced.get()) {
				rows.add(new Row("placed  " + format(printer.placedTotal()), base));
			}
			if (showMissing.get()) {
				rows.add(new Row("missing  " + (missing < 0 ? "counting..." : format(missing)),
						missing == 0 ? successColor.get() : base));
			}
			if (showElapsed.get()) {
				rows.add(new Row("elapsed  " + clock(printer.elapsedSeconds()), Theme.textDim));
			}
			if (showRate.get() && rate >= 0.01) {
				rows.add(new Row(String.format("%.1f blocks/s", rate), Theme.textDim));
			}
			if (showEta.get()) {
				rows.add(new Row("ETA  " + (missing == 0 ? "done" : eta < 0 ? "-" : clock(eta)),
						missing == 0 ? successColor.get() : base));
			}
		}
		if (progressStyle.is("Bar")) {
			// A real bar-only mode: retain only an optional heading (and an error while
			// progress is not measurable) instead of silently rendering the full text view.
			rows.clear();
			if (showTitle.get()) {
				rows.add(new Row("Printer", base));
			}
			if (missing < 0 && showStatus.get()) {
				rows.add(new Row(printer.hudStatus(), Theme.textDim));
			}
		}
		// every row switched off would leave an empty box floating on screen
		if (rows.isEmpty() && !progressStyle.is("Bar")) {
			rows.add(new Row("Printer", base));
		}

		// Wrapped, not cut. Clipping kept the box narrow by throwing the sentence away —
		// "returning what is not nee..." tells you nothing, and the status line is the one row
		// worth reading. Wrapping bounds the width and keeps every word; it costs a row only
		// when a line would have been too long anyway.
		int cap = maxWidth.getInt();
		List<Row> wrapped = new ArrayList<>();
		for (Row row : rows) {
			for (String line : wrap(row.text(), cap)) {
				wrapped.add(new Row(line, row.color()));
			}
		}
		rows = wrapped;
		boolean bar = !progressStyle.is("Text") && missing >= 0;
		int width = 0;
		for (Row row : rows) {
			width = Math.max(width, Render2D.width(row.text()));
		}
		width = Math.max(width + PAD + 5, bar ? Math.min(cap, 120) : PAD + 5);
		int rowHeight = Math.max(ROW, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int height = rows.size() * rowHeight + 4 + (bar ? 7 : 0);
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			Render2D.text(g, row.text(), alignedX(Render2D.width(row.text()), PAD),
					getY() + 3 + i * rowHeight, row.color());
		}
		if (bar) {
			int placed = Math.max(printer.placedTotal(), 0);
			float progress = placed + missing <= 0 ? 1.0f : placed / (float) (placed + missing);
			int x = getX() + PAD;
			int y = getY() + height - 5;
			int w = Math.max(width - PAD - 5, 1);
			Render2D.rect(g, x, y, w, 2, 0x70000000);
			Render2D.rect(g, x, y, Math.round(w * progress), 2,
					missing == 0 ? successColor.get() : accentAt(x, g.guiWidth()));
		}
	}

	/** Joins the non-empty parts with a thin separator, so a missing part leaves no gap. */
	private static String join(String... parts) {
		StringBuilder text = new StringBuilder();
		for (String part : parts) {
			if (part == null || part.isEmpty()) {
				continue;
			}
			if (text.length() > 0) {
				text.append("  ·  ");
			}
			text.append(part);
		}
		return text.toString();
	}

	/**
	 * Breaks a line at spaces so no part is wider than {@code max} pixels.
	 *
	 * <p>Falls back to breaking mid-word only for a single word that cannot fit on its own,
	 * which keeps a stray long block name from defeating the whole cap.
	 */
	private static List<String> wrap(String text, int max) {
		List<String> lines = new ArrayList<>();
		if (Render2D.width(text) <= max) {
			lines.add(text);
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.length() == 0 ? word : line + " " + word;
			if (Render2D.width(candidate) <= max) {
				line.setLength(0);
				line.append(candidate);
				continue;
			}
			if (line.length() > 0) {
				lines.add(line.toString());
				line.setLength(0);
			}
			while (Render2D.width(word) > max && word.length() > 1) {
				int cut = word.length();
				while (cut > 1 && Render2D.width(word.substring(0, cut)) > max) {
					cut--;
				}
				lines.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			line.append(word);
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines;
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
		drawDockedAccentBar(g, getContentWidth(), getY() + 2, height - 4);
	}
}
