package unlucky.utility.client.gui.hud.widgets;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.module.modules.hud.HudModule;
import unlucky.utility.client.module.modules.world.AutoBrew;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.BrewingSolver;
import unlucky.utility.client.util.BrewingSolver.State;
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
	private static final int PAD = 7; // clears the accent bar
	private static final int ROW = 10;
	private static final int GREEN = 0xFF3FD46A;
	private static final int YELLOW = 0xFFE0C020;

	private record Row(String text, int color, boolean indent) {
	}

	public BrewingWidget() {
		super("Brewing");
	}

	private HudModule hud() {
		return UnluckyClient.INSTANCE.modules.get(HudModule.class);
	}

	private AutoBrew brew() {
		return UnluckyClient.INSTANCE.modules.get(AutoBrew.class);
	}

	@Override
	public boolean isVisible() {
		// no point taking up screen while the thing it reports on is off
		return hud().brewing.get() && brew().isEnabled();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.0, 0.35);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		AutoBrew brew = brew();
		List<Row> rows = new ArrayList<>();

		// every order, not just one — they run in parallel now
		for (int order = 0; order < brew.queue.get().size(); order++) {
			State target = brew.targetOfOrder(order);
			if (target == null) {
				continue;
			}
			int done = brew.producedOf(order);
			int goal = brew.goalOf(order);
			rows.add(new Row(BrewingSolver.label(target) + "  " + done + "/" + goal,
					done >= goal ? GREEN : Theme.text, false));
		}
		rows.add(new Row(brew.status(), brew.status().startsWith("§c") ? 0xFFE04545 : Theme.textDim, false));

		if (!brew.standList().isEmpty()) {
			rows.add(new Row("Stands", Theme.text, false));
			for (BlockPos stand : brew.standList()) {
				int seconds = brew.standSeconds(stand);
				State owns = brew.targetOfOrder(brew.orderOfStand(stand));
				rows.add(new Row(pretty(stand) + "  "
						+ (seconds > 0 ? seconds + "s" : "idle")
						+ (brew.standLoad(stand) > 0 ? "  " + brew.standLoad(stand) + " in" : "")
						+ (owns != null ? "  " + BrewingSolver.label(owns) : ""),
						seconds > 0 ? YELLOW : GREEN, true));
			}
		}
		if (!brew.storageList().isEmpty()) {
			rows.add(new Row("Storage", Theme.text, false));
			for (BlockPos chest : brew.storageList()) {
				rows.add(new Row(pretty(chest), Theme.textDim, true));
			}
		}
		if (!brew.chestList().isEmpty()) {
			rows.add(new Row("Chests", Theme.text, false));
			for (BlockPos chest : brew.chestList()) {
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
		int height = rows.size() * ROW + 4;
		setSize(width, height);
		Render2D.roundedRect(g, getX(), getY(), width, height, 4, Theme.hudBg(hud().brewingBg.get()));
		drawAccentBar(g, height);

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int textWidth = Render2D.width(row.text()) + (row.indent() ? 6 : 0);
			Render2D.text(g, row.text(), alignedX(textWidth, PAD) + (row.indent() ? 6 : 0),
					getY() + 3 + i * ROW, row.color());
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
		int barX = anchorRight() ? getX() + getWidth() - 4 : getX() + 2;
		Render2D.verticalGradient(g, barX, getY() + 2, 2, height - 4,
				Theme.hudFlowingAccent(0.0f), Theme.hudFlowingAccent(0.5f));
	}
}
