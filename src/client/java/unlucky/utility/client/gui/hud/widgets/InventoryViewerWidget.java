package unlucky.utility.client.gui.hud.widgets;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.gui.hud.HudWidget;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** The 9×3 main inventory (slots 9–35) drawn as item icons over a translucent panel. */
public class InventoryViewerWidget extends HudWidget {
	public final BooleanSetting enabled = add(new BooleanSetting("InventoryViewer", "Your main inventory on screen", false));
	public final NumberSetting opacity = add(new NumberSetting("Inventory opacity", "Panel background opacity", 60, 0, 100, 5));
	public final BooleanSetting hideEmpty = add(new BooleanSetting("Inventory hide empty", "Hide when the inventory is empty", false));

	private static final int CELL = 18;
	private static final int COLS = 9;
	private static final int ROWS = 3;

	public InventoryViewerWidget() {
		super("InventoryViewer");
	}

	@Override
	public boolean isVisible() {
		return enabled.get();
	}

	@Override
	protected void applyDefaultPosition() {
		setFractions(0.5, 0.5);
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, boolean editing) {
		if (mc().player == null) {
			setSize(0, 0);
			return;
		}
		Inventory inv = mc().player.getInventory();

		boolean any = false;
		for (int i = 9; i <= 35; i++) {
			if (!inv.getItem(i).isEmpty()) {
				any = true;
				break;
			}
		}
		if (!any && !editing && hideEmpty.get()) {
			setSize(0, 0);
			return;
		}

		int width = COLS * CELL + 4;
		int height = ROWS * CELL + 4;
		setSize(width, height);
		int op = opacity.getInt();
		if (op > 0) {
			Render2D.hudPanel(g, getX(), getY(), width, height,
					ColorUtil.multiplyAlpha(ColorUtil.withAlpha(0xFF14141A, op * 255 / 100), Theme.hudPanelOpacity));
		}
		for (int i = 0; i < COLS * ROWS; i++) {
			ItemStack stack = inv.getItem(9 + i);
			if (stack.isEmpty()) {
				continue;
			}
			int col = i % COLS;
			int row = i / COLS;
			g.item(stack, getX() + 2 + col * CELL + 1, getY() + 2 + row * CELL + 1);
		}
	}
}
