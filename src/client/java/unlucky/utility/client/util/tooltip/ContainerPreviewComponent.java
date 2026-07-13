package unlucky.utility.client.util.tooltip;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Renders a container's contents as a grid inside the item tooltip, in one of two
 * looks selected by InventoryInfo:
 * <ul>
 *   <li><b>Slot</b> — a compact grid where each item sits on the {@code slot.png}
 *       cell (the default, subtle look).</li>
 *   <li><b>GUI</b> — the full {@code container.png} panel (a 9×3, 176×68 window with
 *       a 7px border), items laid onto its slots, for a proper container-window
 *       look over the tooltip.</li>
 * </ul>
 * Sizing follows the {@link ClientTooltipComponent} contract; {@link #extractImage}
 * draws at the top-left the tooltip layout hands us.
 */
public class ContainerPreviewComponent implements ClientTooltipComponent {
	private static final int SLOT = 18;
	private static final int COLS = 9;
	private static final int WHITE = 0xFFFFFFFF;

	// container.png geometry: 9×3 slots inside a 7px border
	private static final int PANEL_W = 176;
	private static final int PANEL_H = 68;
	private static final int PANEL_SLOTS = 27;
	private static final int PANEL_ITEM_INSET = 8; // 7px border + 1px slot margin

	private static final Identifier SLOT_TEX = UnluckyClientMod.id("textures/gui/slot.png");
	private static final Identifier PANEL_TEX = UnluckyClientMod.id("textures/gui/container.png");
	private static final Identifier ENDER_TEX = UnluckyClientMod.id("textures/gui/enderchest.png");

	private final List<ItemStack> items;
	private final boolean guiStyle;
	private final boolean enderChest;
	private final int cols;
	private final int rows;

	public ContainerPreviewComponent(List<ItemStack> items, boolean guiStyle, boolean enderChest) {
		this.items = items;
		this.guiStyle = guiStyle;
		this.enderChest = enderChest;
		this.cols = Math.max(1, Math.min(COLS, items.size()));
		this.rows = Math.max(1, (items.size() + COLS - 1) / COLS);
	}

	@Override
	public int getWidth(Font font) {
		return guiStyle ? PANEL_W : cols * SLOT;
	}

	@Override
	public int getHeight(Font font) {
		return guiStyle ? PANEL_H : rows * SLOT + 2;
	}

	@Override
	public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor g) {
		if (guiStyle) {
			Identifier panel = enderChest ? ENDER_TEX : PANEL_TEX;
			g.blit(RenderPipelines.GUI_TEXTURED, panel, x, y, 0.0f, 0.0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H, WHITE);
			int shown = Math.min(items.size(), PANEL_SLOTS);
			for (int i = 0; i < shown; i++) {
				int ix = x + PANEL_ITEM_INSET + (i % COLS) * SLOT;
				int iy = y + PANEL_ITEM_INSET + (i / COLS) * SLOT;
				drawItem(g, font, items.get(i), ix, iy);
			}
		} else {
			for (int i = 0; i < items.size(); i++) {
				int sx = x + (i % COLS) * SLOT;
				int sy = y + (i / COLS) * SLOT;
				g.blit(RenderPipelines.GUI_TEXTURED, SLOT_TEX, sx, sy, 0.0f, 0.0f, SLOT, SLOT, SLOT, SLOT, WHITE);
				drawItem(g, font, items.get(i), sx + 1, sy + 1);
			}
		}
	}

	private static void drawItem(GuiGraphicsExtractor g, Font font, ItemStack stack, int x, int y) {
		g.item(stack, x, y);
		g.itemDecorations(font, stack, x, y);
	}
}
