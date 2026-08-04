package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
 * What the whole schematic still needs, largest first — the shopping list half of the
 * Printer read-out, kept separate so it can live on its own screen edge. Counts come
 * from the Printer's cycling whole-region tally, so they self-correct as blocks land.
 */
public class MaterialsWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Materials", "What the whole schematic still needs, largest first", true));
	public final BooleanSetting bg = add(new BooleanSetting("Materials bg", "Backing behind the materials list", true));
	public final ColorSetting color = add(new ColorSetting("Materials color", "Materials list text color", Theme.text));
	public final BooleanSetting showTitle = add(new BooleanSetting("Materials title", "Show the widget's title row", true));
	public final BooleanSetting showIcons = add(new BooleanSetting("Materials icons", "Show each item's icon beside its count", true));
	public final NumberSetting maxRows = add(new NumberSetting("Materials rows", "How many materials to list before collapsing the rest", 12, 1, 30, 1));
	public final BooleanSetting showTotal = add(new BooleanSetting("Materials total", "Show a total row under the list", true));
	public final BooleanSetting showFetching = add(new BooleanSetting("Materials getting", "While refilling, list the blocks being fetched from shulkers and how far along", true));
	public final ModeSetting sort = add(new ModeSetting("Materials sort", "Order materials by remaining count or item name", "Count", "Count", "Name"));
	public final ColorSetting completeColor = add(new ColorSetting("Materials complete color", "Color used for completed refill rows", 0xFF3FD46A));
	public final NumberSetting rowSpacing = add(new NumberSetting("Materials spacing", "Extra spacing between rows", 0, 0, 4, 1));

	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private static final int ICON = 10; // icons are drawn scaled to sit on one row

	/** A line, plus the stack whose icon leads it (empty for headings and totals). */
	private record Row(String text, int color, ItemStack icon) {
	}

	public MaterialsWidget() {
		super("Materials");
	}

	private Printer printer() {
		return UnluckyClient.INSTANCE.modules.get(Printer.class);
	}

	@Override
	public boolean isVisible() {
		return enabled.get() && printer().isEnabled();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(1.0, 0.7);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		int base = color.get();
		boolean icons = showIcons.get();
		int limit = maxRows.getInt();
		List<Map.Entry<Item, Integer>> needed = new ArrayList<>(printer().materials());
		if (sort.is("Name")) {
			needed.sort(java.util.Comparator.comparing(entry -> entry.getKey().getDefaultInstance().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
		} else {
			needed.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
		}
		List<Row> rows = new ArrayList<>();

		if (showTitle.get()) {
			rows.add(new Row("Materials", base, ItemStack.EMPTY));
		}
		// While a refill is running, show exactly what it is pulling from the shulkers and
		// how far along — "cobblestone 128 / 448" — so the fetch is legible instead of a
		// guess from a full inventory. Straight off the restock's own budget and progress.
		if (showFetching.get()) {
			for (var fetch : printer().restockPlan()) {
				ItemStack stack = fetch.item().getDefaultInstance();
				rows.add(new Row(stack.getHoverName().getString() + "  "
						+ PrinterWidget.format(fetch.got()) + " / " + PrinterWidget.format(fetch.want()),
						fetch.got() >= fetch.want() ? completeColor.get() : Theme.textDim,
						icons ? stack : ItemStack.EMPTY));
			}
		}
		if (needed.isEmpty()) {
			rows.add(new Row(printer().missingTotal() == 0 ? "nothing left" : "counting...",
					Theme.textDim, ItemStack.EMPTY));
		}
		long total = 0;
		for (Map.Entry<Item, Integer> entry : needed) {
			total += entry.getValue();
		}
		for (int i = 0; i < needed.size() && i < limit; i++) {
			Map.Entry<Item, Integer> entry = needed.get(i);
			ItemStack stack = entry.getKey().getDefaultInstance();
			rows.add(new Row(stack.getHoverName().getString() + "  "
					+ PrinterWidget.format(entry.getValue()), base,
					icons ? stack : ItemStack.EMPTY));
		}
		if (needed.size() > limit) {
			rows.add(new Row("+ " + (needed.size() - limit) + " more", Theme.textDim,
					ItemStack.EMPTY));
		}
		if (showTotal.get() && total > 0) {
			rows.add(new Row("total  " + PrinterWidget.format((int) Math.min(total,
					Integer.MAX_VALUE)), Theme.textDim, ItemStack.EMPTY));
		}

		int width = 0;
		for (Row row : rows) {
			width = Math.max(width, Render2D.width(row.text())
					+ (row.icon().isEmpty() ? 0 : ICON + 2));
		}
		width += PAD + 5;
		int rowHeight = Math.max(ROW, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int stride = rowHeight + rowSpacing.getInt();
		int height = rows.size() * stride - rowSpacing.getInt() + 4;
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int lead = row.icon().isEmpty() ? 0 : ICON + 2;
			int rowWidth = Render2D.width(row.text()) + lead;
			int x = alignedX(rowWidth, PAD);
			int y = getY() + 3 + i * stride;
			if (lead > 0) {
				// vanilla item icons are 16px; scale to the row height so a long list
				// stays a list instead of becoming a column of sprites
				g.pose().pushMatrix();
				g.pose().translate(x, y - 1);
				g.pose().scale(ICON / 16.0f, ICON / 16.0f);
				g.item(row.icon(), 0, 0);
				g.pose().popMatrix();
			}
			Render2D.text(g, row.text(), x + lead, y, row.color());
		}
	}

	private void drawAccentBar(GuiGraphicsExtractor g, int height) {
		int barX = anchorRight() ? getX() + getContentWidth() - 4 : getX() + 2;
		Render2D.hudAccentBar(g, barX, getY() + 2, 2, height - 4);
	}
}
