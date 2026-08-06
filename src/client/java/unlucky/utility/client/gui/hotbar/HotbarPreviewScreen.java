package unlucky.utility.client.gui.hotbar;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.player.DonkeyRitual;
import unlucky.utility.client.module.modules.player.HotbarLoadout;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.HotbarVault;
import unlucky.utility.client.util.Render2D;

/**
 * What is actually in your nine saved creative hotbars.
 *
 * <p>Icons only, no names: the point is to recognise a loadout at a glance, and nine
 * rows of item names is a wall of text you have to read rather than see. Clicking a
 * row picks it for both {@link DonkeyRitual} and {@link HotbarLoadout}, so this is
 * the picker as well as the preview.
 */
public class HotbarPreviewScreen extends Screen {
	private static final int SLOT = 20;
	private static final int ROW_HEIGHT = 24;
	private static final int PAD = 10;
	private static final int LABEL_WIDTH = 18;
	private static final int WIDTH = LABEL_WIDTH + SLOT * 9 + PAD * 2;
	private static final int HEADER = 26;

	private List<List<HotbarVault.Entry>> groups;

	public HotbarPreviewScreen() {
		super(Component.literal("Saved hotbars"));
	}

	@Override
	protected void init() {
		groups = HotbarVault.readAll();
	}

	/** Which hotbar the modules are currently pointed at. */
	private int selected() {
		return UnluckyClient.INSTANCE.modules.get(DonkeyRitual.class).hotbar.getInt();
	}

	private void select(int group) {
		UnluckyClient.INSTANCE.modules.get(DonkeyRitual.class).hotbar.set((double) group);
		UnluckyClient.INSTANCE.modules.get(HotbarLoadout.class).group.set((double) group);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x50 << 24);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int height = HEADER + HotbarVault.GROUPS * ROW_HEIGHT + PAD;
		int x = (this.width - WIDTH) / 2;
		int y = (this.height - height) / 2;

		Render2D.rect(g, x - 1, y - 1, WIDTH + 2, height + 2, Theme.borderDark);
		Render2D.rect(g, x, y, WIDTH, height, Theme.window);
		g.outline(x, y, WIDTH, height, Theme.border);
		Render2D.textNoShadow(g, "Saved hotbars", x + PAD, y + 8, Theme.text);

		String hint = "click to pick";
		Render2D.textNoShadow(g, hint, x + WIDTH - PAD - Render2D.width(hint), y + 8, Theme.textDim);

		for (int group = 1; group <= HotbarVault.GROUPS; group++) {
			int rowY = y + HEADER + (group - 1) * ROW_HEIGHT;
			List<HotbarVault.Entry> entries = groups.get(group - 1);
			boolean hover = Render2D.hovered(mouseX, mouseY, x + PAD, rowY, WIDTH - PAD * 2, ROW_HEIGHT - 2);
			boolean active = group == selected();

			if (active) {
				Render2D.rect(g, x + PAD - 2, rowY - 1, WIDTH - PAD * 2 + 4, ROW_HEIGHT - 1,
						Theme.accent1 & 0x40FFFFFF);
			} else if (hover) {
				Render2D.rect(g, x + PAD - 2, rowY - 1, WIDTH - PAD * 2 + 4, ROW_HEIGHT - 1, 0x18FFFFFF);
			}

			Render2D.textNoShadow(g, String.valueOf(group), x + PAD, rowY + 6,
					active ? Theme.accent1 : Theme.textDim);

			// one cell per hotbar slot, so gaps stay visible — a loadout with holes in
			// it should look different from a full one
			for (int slot = 0; slot < 9; slot++) {
				int cellX = x + PAD + LABEL_WIDTH + slot * SLOT;
				Render2D.rect(g, cellX, rowY + 1, SLOT - 2, SLOT - 2, Theme.surface);
				HotbarVault.Entry entry = entryAt(entries, slot);
				if (entry != null) {
					g.item(entry.stack(), cellX + 1, rowY + 2);
				}
			}

			if (entries.isEmpty()) {
				Render2D.textNoShadow(g, "empty", x + PAD + LABEL_WIDTH + 4, rowY + 6, Theme.textDim);
			}
		}
	}

	private static HotbarVault.Entry entryAt(List<HotbarVault.Entry> entries, int slot) {
		for (HotbarVault.Entry entry : entries) {
			if (entry.slot() == slot) {
				return entry;
			}
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int height = HEADER + HotbarVault.GROUPS * ROW_HEIGHT + PAD;
		int x = (this.width - WIDTH) / 2;
		int y = (this.height - height) / 2;
		if (event.button() == 0) {
			for (int group = 1; group <= HotbarVault.GROUPS; group++) {
				int rowY = y + HEADER + (group - 1) * ROW_HEIGHT;
				if (Render2D.hovered(event.x(), event.y(), x + PAD, rowY, WIDTH - PAD * 2, ROW_HEIGHT - 2)) {
					select(group);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
