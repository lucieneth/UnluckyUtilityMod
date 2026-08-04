package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * What the <em>layers being built right now</em> still need — the Materials widget narrowed
 * to the active band.
 *
 * <p>Materials answers "how big is this job"; on a map art that is twenty thousand blocks and
 * barely moves, which makes it useless for telling whether the last few minutes went well.
 * This answers the two questions actually being asked at the time: <b>how much is left of the
 * bit we are on</b>, and <b>is the refill bringing back the right thing</b>.
 *
 * <p>The second is why the rows are marked rather than merely listed. Under material passes
 * the printer commits to a set of block types and routes only through where those go, so the
 * materials it is allowed to place are exactly the ones a supply run should be fetching.
 * Highlighted rows are that set; a fetch of anything else, or a highlighted row that never
 * goes down, is visible at a glance instead of needing a report.
 */
public class LayerWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Layers",
			"What the layers being built right now still need", true));
	public final BooleanSetting bg = add(new BooleanSetting("Layers bg",
			"Backing behind the layer list", true));
	public final ColorSetting color = add(new ColorSetting("Layers color",
			"Layer list text color", Theme.text));
	public final BooleanSetting showTitle = add(new BooleanSetting("Layers title",
			"Show the widget's title row", true));
	public final BooleanSetting showIcons = add(new BooleanSetting("Layers icons",
			"Show each item's icon beside its count", true));
	public final NumberSetting maxRows = add(new NumberSetting("Layers rows",
			"How many materials to list before collapsing the rest", 14, 1, 30, 1));
	public final BooleanSetting showTotal = add(new BooleanSetting("Layers total",
			"Show a total row under the list", true));
	public final BooleanSetting activeOnly = add(new BooleanSetting("Layers building only",
			"List only the block types this pass is building, instead of the whole band", false));
	public final BooleanSetting showTotals = add(new BooleanSetting("Layers of total",
			"Show each count as \"left of total\" — the total being what these layers hold in "
					+ "the schematic, so a small number can be told from a wrong one", true));
	public final ModeSetting sort = add(new ModeSetting("Layers sort", "Order rows by remaining count or block name", "Count", "Count", "Name"));
	public final ColorSetting activeColor = add(new ColorSetting("Layers active color", "Color of blocks being built in the current pass", 0xFF7FC7FF));
	public final NumberSetting rowSpacing = add(new NumberSetting("Layers spacing", "Extra spacing between rows", 0, 0, 4, 1));

	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private static final int ICON = 10; // icons are drawn scaled to sit on one row
	/** A block type the current pass is committed to — what a refill should be bringing. */

	/** A line, plus the stack whose icon leads it (empty for headings and totals). */
	private record Row(String text, int color, ItemStack icon) {
	}

	public LayerWidget() {
		super("Layers");
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
		setFractions(1.0, 0.34);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		int base = color.get();
		boolean icons = showIcons.get();
		int limit = maxRows.getInt();
		List<Printer.BandItem> band = new ArrayList<>(printer().bandMaterials());
		if (activeOnly.get()) {
			List<Printer.BandItem> only = new ArrayList<>();
			for (Printer.BandItem item : band) {
				if (item.active()) {
					only.add(item);
				}
			}
			band = only;
		}
		if (sort.is("Name")) {
			band.sort(java.util.Comparator.comparing(item -> item.item().getDefaultInstance().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
		} else {
			band.sort(java.util.Comparator.comparingLong(Printer.BandItem::left).reversed());
		}
		List<Row> rows = new ArrayList<>();

		if (showTitle.get()) {
			String label = printer().bandLabel();
			rows.add(new Row(label.isEmpty() ? "Layers" : "Layers " + label, base,
					ItemStack.EMPTY));
		}
		if (band.isEmpty()) {
			rows.add(new Row(printer().bandLabel().isEmpty() ? "no band yet" : "counting...",
					Theme.textDim, ItemStack.EMPTY));
		}
		long total = 0;
		for (Printer.BandItem item : band) {
			total += item.left();
		}
		for (int i = 0; i < band.size() && i < limit; i++) {
			Printer.BandItem item = band.get(i);
			ItemStack stack = item.item().getDefaultInstance();
			String count = PrinterWidget.format(item.left());
			if (showTotals.get() && item.total() > 0) {
				count += "/" + PrinterWidget.format(item.total());
			}
			rows.add(new Row(stack.getHoverName().getString() + "  " + count,
					item.active() ? activeColor.get() : base,
					icons ? stack : ItemStack.EMPTY));
		}
		if (band.size() > limit) {
			rows.add(new Row("+ " + (band.size() - limit) + " more", Theme.textDim,
					ItemStack.EMPTY));
		}
		if (showTotal.get() && total > 0) {
			rows.add(new Row("left  " + PrinterWidget.format((int) Math.min(total,
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
