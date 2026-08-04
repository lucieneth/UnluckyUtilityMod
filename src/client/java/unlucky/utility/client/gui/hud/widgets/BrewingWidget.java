package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.world.AutoBrew;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.BrewingSolver.State;
import unlucky.utility.client.util.BrewingSolver;
import unlucky.utility.client.util.Render2D;

/**
 * Live read-out of what AutoBrew is doing: the order in hand and its progress, the
 * job right now, what's queued next, and every stand and chest it knows about.
 *
 * <p>Built because AutoBrew is a state machine spread across containers you can't see
 * into — when it wedges, "nothing is happening" is all you get, and that's the same
 * whether the queue is empty, a chest is out of reach, or a reagent has run out. It
 * reads AutoBrew's own state, so what's on screen is what the machine believes; if the
 * two disagree, the belief is the bug.
 *
 * <p>Purely a viewer — it holds no state and decides nothing.
 */
public class BrewingWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("Brewing", "AutoBrew progress: current job, stands and chests", true));
	public final BooleanSetting bg = add(new BooleanSetting("Brewing bg", "Backing behind the brewing read-out", true));
	public final ModeSetting progressStyle = add(new ModeSetting("Brewing progress", "Show progress as text, a bar, or both", "Both", "Text", "Bar", "Both"));
	public final BooleanSetting compact = add(new BooleanSetting("Brewing compact", "Hide stand/chest detail and keep queue progress only", false));
	public final NumberSetting queueRows = add(new NumberSetting("Brewing queue rows", "Maximum queued potion orders shown", 6, 1, 20, 1));
	public final NumberSetting locationRows = add(new NumberSetting("Brewing location rows", "Maximum stands/chests shown per section", 6, 1, 20, 1));
	public final ColorSetting successColor = add(new ColorSetting("Brewing success color", "Color for finished and idle states", 0xFF3FD46A));
	public final ColorSetting errorColor = add(new ColorSetting("Brewing error color", "Color for brewing errors", 0xFFE04545));

	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private static final int YELLOW = 0xFFE0C020;

	private record Row(String text, int color, boolean indent) {
	}

	public BrewingWidget() {
		super("Brewing");
	}

	private AutoBrew brew() {
		return UnluckyClient.INSTANCE.modules.get(AutoBrew.class);
	}

	@Override
	public boolean isVisible() {
		// no point taking up screen while the thing it reports on is off
		return enabled.get() && brew().isEnabled();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.35);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		AutoBrew brew = brew();
		List<Row> rows = new ArrayList<>();
		int totalDone = 0;
		int totalGoal = 0;

		// every order, not just one — they run in parallel now
		for (int order = 0; order < brew.queue.get().size(); order++) {
			State target = brew.targetOfOrder(order);
			if (target == null) {
				continue;
			}
			int done = brew.producedOf(order);
			int goal = brew.goalOf(order);
			totalDone += done;
			totalGoal += goal;
			if (!progressStyle.is("Bar") && order < queueRows.getInt()) {
				rows.add(new Row(BrewingSolver.label(target) + "  " + done + "/" + goal,
						done >= goal ? successColor.get() : Theme.text, false));
			}
		}
		rows.add(new Row(brew.status(), brew.status().startsWith("\u00A7c") ? errorColor.get() : Theme.textDim, false));

		if (!compact.get() && !brew.standList().isEmpty()) {
			rows.add(new Row("Stands", Theme.text, false));
			for (BlockPos stand : brew.standList().stream().limit(locationRows.getInt()).toList()) {
				int seconds = brew.standSeconds(stand);
				State owns = brew.targetOfOrder(brew.orderOfStand(stand));
				rows.add(new Row(pretty(stand) + "  "
						+ (seconds > 0 ? seconds + "s" : "idle")
						+ (brew.standLoad(stand) > 0 ? "  " + brew.standLoad(stand) + " in" : "")
						+ (owns != null ? "  " + BrewingSolver.label(owns) : ""),
						seconds > 0 ? YELLOW : successColor.get(), true));
			}
		}
		if (!compact.get() && !brew.storageList().isEmpty()) {
			rows.add(new Row("Storage", Theme.text, false));
			for (BlockPos chest : brew.storageList().stream().limit(locationRows.getInt()).toList()) {
				rows.add(new Row(pretty(chest), Theme.textDim, true));
			}
		}
		if (!compact.get() && !brew.chestList().isEmpty()) {
			rows.add(new Row("Chests", Theme.text, false));
			for (BlockPos chest : brew.chestList().stream().limit(locationRows.getInt()).toList()) {
				rows.add(new Row(pretty(chest), Theme.textDim, true));
				String contents = summarise(brew.chestContents(chest));
				if (!contents.isEmpty()) {
					rows.add(new Row("  " + contents, Theme.textDim, true));
				}
			}
		}

		int width = 0;
		for (Row row : rows) {
			width = Math.max(width, Render2D.width(row.text()) + (row.indent() ? 6 : 0));
		}
		width += PAD + 5;
		boolean bar = !progressStyle.is("Text") && totalGoal > 0;
		int rowHeight = Math.max(ROW, (int) Math.ceil(Render2D.FONT_HEIGHT * textScale()) + 1);
		int height = rows.size() * rowHeight + 4 + (bar ? 7 : 0);
		setSize(width, height);
		Render2D.hudPanel(g, getX(), getY(), width, height, bg.get());
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int textWidth = Render2D.width(row.text()) + (row.indent() ? 6 : 0);
			Render2D.text(g, row.text(), alignedX(textWidth, PAD) + (row.indent() ? 6 : 0),
					getY() + 3 + i * rowHeight, row.color());
		}
		if (bar) {
			float progress = Math.clamp(totalDone / (float) totalGoal, 0.0f, 1.0f);
			int x = getX() + PAD;
			int y = getY() + height - 5;
			int w = Math.max(width - PAD - 5, 1);
			Render2D.rect(g, x, y, w, 2, 0x70000000);
			Render2D.rect(g, x, y, Math.round(w * progress), 2,
					progress >= 1.0f ? successColor.get() : accentAt(x, g.guiWidth()));
		}
	}

	/** "64x Glass Bottle, 64x Nether Wart" — the first few, so the widget stays a widget. */
	private String summarise(List<ItemStack> contents) {
		StringBuilder out = new StringBuilder();
		int shown = 0;
        for (ItemStack stack : contents) {
			if (shown == 3) {
				out.append(", ...");
				break;
			}
			if (shown > 0) {
				out.append(", ");
			}
			out.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
			shown++;
		}
		return out.toString();
	}

	private static String pretty(BlockPos pos) {
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}

	private void drawAccentBar(GuiGraphicsExtractor g, int height) {
		int barX = anchorRight() ? getX() + getContentWidth() - 4 : getX() + 2;
		Render2D.hudAccentBar(g, barX, getY() + 2, 2, height - 4);
	}
}
