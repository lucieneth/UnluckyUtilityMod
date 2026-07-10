package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import unlucky.utility.client.module.modules.render.XRay;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Centered popup for a BlockListSetting: preset buttons on top, then a
 * scrollable list of blocks with their item icons and checkboxes. The entry
 * catalog (icons included) is built once on open, not per frame.
 */
public final class BlockPickerPopup {
	private static final int WIDTH = 210;
	private static final int HEIGHT = 200;
	private static final int ROW = 18;
	private static final int HEADER = 34;

	private record Entry(Block block, ItemStack icon, String name) {
	}

	private static BlockListSetting target;
	private static List<Entry> entries = List.of();
	private static int scroll;
	// draggable position; MIN_VALUE = not placed yet, centered on first render
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static int dragOffX;
	private static int dragOffY;

	private BlockPickerPopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(BlockListSetting setting) {
		target = setting;
		scroll = 0;
		Set<String> catalog = new TreeSet<>();
		catalog.addAll(XRay.PRESET_ORES);
		catalog.addAll(XRay.PRESET_STORAGE);
		catalog.addAll(XRay.PRESET_VALUABLES);
		catalog.addAll(setting.get());
		List<Entry> built = new ArrayList<>();
		for (String id : catalog) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed == null) {
				continue;
			}
			BuiltInRegistries.BLOCK.getOptional(parsed).ifPresent(block ->
					built.add(new Entry(block, new ItemStack(block), block.getName().getString())));
		}
		built.sort(Comparator.comparing(Entry::name));
		entries = built;
	}

	public static void close() {
		target = null;
		entries = List.of();
	}

	public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (target == null) {
			return;
		}
		if (popX == Integer.MIN_VALUE) {
			popX = (g.guiWidth() - WIDTH) / 2;
			popY = (g.guiHeight() - HEIGHT) / 2;
		}
		int x = popX;
		int y = popY;

		Render2D.rect(g, x - 1, y - 1, WIDTH + 2, HEIGHT + 2, Theme.borderDark);
		Render2D.rect(g, x, y, WIDTH, HEIGHT, Theme.window);
		g.outline(x, y, WIDTH, HEIGHT, Theme.border);
		Render2D.textNoShadow(g, target.getName(), x + 6, y + 5, Theme.text);

		// preset buttons
		String[] labels = {"Storage", "Ores", "Valuables"};
		int buttonWidth = (WIDTH - 24) / 3;
		for (int i = 0; i < 3; i++) {
			int bx = x + 6 + i * (buttonWidth + 6);
			int by = y + 16;
			boolean hover = Render2D.hovered(mouseX, mouseY, bx, by, buttonWidth, 13);
			Render2D.rect(g, bx - 1, by - 1, buttonWidth + 2, 15, Theme.borderDark);
			Render2D.rect(g, bx, by, buttonWidth, 13, hover ? Theme.panel : Theme.surface);
			Render2D.textNoShadow(g, labels[i], bx + (buttonWidth - Render2D.width(labels[i])) / 2, by + 3,
					hover ? Theme.text : Theme.textDim);
		}

		// block list
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		g.enableScissor(x, listTop, x + WIDTH, listTop + listHeight);
		int rowY = listTop - scroll;
		for (Entry entry : entries) {
			if (rowY + ROW >= listTop && rowY <= listTop + listHeight) {
				boolean selected = target.get().contains(BuiltInRegistries.BLOCK.getKey(entry.block()).toString());
				boolean hover = Render2D.hovered(mouseX, mouseY, x + 2, rowY, WIDTH - 8, ROW);
				if (hover) {
					Render2D.rect(g, x + 2, rowY, WIDTH - 8, ROW, 0x18FFFFFF);
				}
				g.item(entry.icon(), x + 5, rowY + 1);
				Render2D.textNoShadow(g, entry.name(), x + 25, rowY + 5,
						selected ? Theme.text : Theme.textDim);
				int boxX = x + WIDTH - 18;
				Render2D.rect(g, boxX - 1, rowY + 3, 11, 11, Theme.borderDark);
				Render2D.rect(g, boxX, rowY + 4, 9, 9,
						selected ? Theme.accent1 : ColorUtil.withAlpha(Theme.textDim, 70));
			}
			rowY += ROW;
		}
		g.disableScissor();

		// scrollbar
		int contentHeight = entries.size() * ROW;
		if (contentHeight > listHeight) {
			int barHeight = Math.max(listHeight * listHeight / contentHeight, 10);
			int barY = listTop + (listHeight - barHeight) * scroll / (contentHeight - listHeight);
			Render2D.rect(g, x + WIDTH - 4, listTop, 2, listHeight, Theme.surface);
			Render2D.verticalGradient(g, x + WIDTH - 4, barY, 2, barHeight, Theme.accent1, Theme.accent2);
		}
	}

	/** Consumes every click while open; clicking outside the panel closes it. */
	public static boolean mouseClicked(GuiGraphicsExtractor unused, double mouseX, double mouseY, int button, int guiWidth, int guiHeight) {
		if (target == null) {
			return false;
		}
		int x = popX;
		int y = popY;
		if (!Render2D.hovered(mouseX, mouseY, x, y, WIDTH, HEIGHT)) {
			close();
			return true;
		}
		if (button != 0) {
			return true;
		}
		// drag by the title strip (above the preset buttons)
		if (mouseY < y + 15) {
			draggingPopup = true;
			dragOffX = (int) mouseX - x;
			dragOffY = (int) mouseY - y;
			return true;
		}

		// presets
		int buttonWidth = (WIDTH - 24) / 3;
		Set<String>[] presets = new Set[] {XRay.PRESET_STORAGE, XRay.PRESET_ORES, XRay.PRESET_VALUABLES};
		for (int i = 0; i < 3; i++) {
			int bx = x + 6 + i * (buttonWidth + 6);
			if (Render2D.hovered(mouseX, mouseY, bx, y + 16, buttonWidth, 13)) {
				target.setAll(presets[i]);
				XRay.refresh();
				return true;
			}
		}

		// rows
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		if (mouseY >= listTop && mouseY < listTop + listHeight) {
			int index = ((int) mouseY - listTop + scroll) / ROW;
			if (index >= 0 && index < entries.size()) {
				target.toggle(entries.get(index).block());
				XRay.refresh();
			}
		}
		return true;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int guiWidth, int guiHeight) {
		if (!draggingPopup) {
			return false;
		}
		popX = Math.clamp((int) mouseX - dragOffX, 0, Math.max(guiWidth - WIDTH, 0));
		popY = Math.clamp((int) mouseY - dragOffY, 0, Math.max(guiHeight - HEIGHT, 0));
		return true;
	}

	public static void mouseReleased() {
		draggingPopup = false;
	}

	public static boolean mouseScrolled(double scrollY, int guiHeight) {
		if (target == null) {
			return false;
		}
		int listHeight = HEIGHT - HEADER - 4;
		int max = Math.max(entries.size() * ROW - listHeight, 0);
		scroll = Math.clamp(scroll - (int) (scrollY * 18), 0, max);
		return true;
	}
}
