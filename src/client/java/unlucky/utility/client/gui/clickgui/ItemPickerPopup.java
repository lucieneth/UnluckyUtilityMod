package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Centered popup for an {@link ItemListSetting}: a search field, then a
 * scrollable list of items with icons and checkboxes.
 *
 * <p>Unlike the block picker, the catalog comes from the whole item registry
 * narrowed by the setting's filter — food-only, tools-only, and so on — which
 * is why the search box exists: even filtered, the list can be long. The
 * catalog (icons included) is built once on open; search only re-filters it.
 */
public final class ItemPickerPopup {
	private static final int WIDTH = 210;
	private static final int HEIGHT = 200;
	private static final int ROW = 18;
	private static final int HEADER = 34;

	private record Entry(Item item, ItemStack icon, String name, String search) {
	}

	private static ItemListSetting target;
	private static List<Entry> catalog = List.of();
	private static List<Entry> shown = List.of();
	private static final TextBox SEARCH = new TextBox();
	private static int scroll;
	// draggable position; MIN_VALUE = not placed yet, centered on first render
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static boolean draggingSearch;
	private static int dragOffX;
	private static int dragOffY;

	static {
		SEARCH.onChange(ItemPickerPopup::refilter);
	}

	private ItemPickerPopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(ItemListSetting setting) {
		target = setting;
		scroll = 0;
		SEARCH.clear();
		List<Entry> built = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (!setting.filter().test(item)) {
				continue;
			}
			ItemStack icon = item.getDefaultInstance();
			if (icon.isEmpty()) {
				continue; // air, and anything else with no real stack
			}
			String name = icon.getHoverName().getString();
			built.add(new Entry(item, icon, name, name.toLowerCase(Locale.ROOT)));
		}
		built.sort(Comparator.comparing(Entry::name));
		catalog = built;
		refilter();
	}

	public static void close() {
		target = null;
		catalog = List.of();
		shown = List.of();
	}

	private static void refilter() {
		String query = SEARCH.text().toLowerCase(Locale.ROOT).trim();
		if (query.isEmpty()) {
			shown = catalog;
		} else {
			List<Entry> matches = new ArrayList<>();
			for (Entry entry : catalog) {
				if (entry.search().contains(query)) {
					matches.add(entry);
				}
			}
			shown = matches;
		}
		scroll = 0;
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
		String title = target.getName() + "  (" + target.get().size() + ")";
		Render2D.textNoShadow(g, title, x + 6, y + 5, Theme.text);

		// search field, with a Clear button beside it
		int searchWidth = WIDTH - 12 - 40;
		Render2D.rect(g, x + 5, y + 15, searchWidth + 2, 15, Theme.borderDark);
		Render2D.rect(g, x + 6, y + 16, searchWidth, 13, Theme.surface);
		SEARCH.render(g, x + 9, y + 18, searchWidth - 6, true, "Search...");

		int clearX = x + WIDTH - 42;
		boolean clearHover = Render2D.hovered(mouseX, mouseY, clearX, y + 16, 36, 13);
		Render2D.rect(g, clearX - 1, y + 15, 38, 15, Theme.borderDark);
		Render2D.rect(g, clearX, y + 16, 36, 13, clearHover ? Theme.panel : Theme.surface);
		Render2D.textNoShadow(g, "Clear", clearX + 7, y + 19, clearHover ? Theme.text : Theme.textDim);

		// item list
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		g.enableScissor(x, listTop, x + WIDTH, listTop + listHeight);
		int rowY = listTop - scroll;
		for (Entry entry : shown) {
			if (rowY + ROW >= listTop && rowY <= listTop + listHeight) {
				boolean selected = target.contains(entry.item());
				boolean hover = Render2D.hovered(mouseX, mouseY, x + 2, rowY, WIDTH - 8, ROW);
				if (hover) {
					Render2D.rect(g, x + 2, rowY, WIDTH - 8, ROW, 0x18FFFFFF);
				}
				g.item(entry.icon(), x + 5, rowY + 1);
				Render2D.textNoShadow(g, entry.name(), x + 25, rowY + 5, selected ? Theme.text : Theme.textDim);
				int boxX = x + WIDTH - 18;
				Render2D.rect(g, boxX - 1, rowY + 3, 11, 11, Theme.borderDark);
				Render2D.rect(g, boxX, rowY + 4, 9, 9,
						selected ? Theme.accent1 : ColorUtil.withAlpha(Theme.textDim, 70));
			}
			rowY += ROW;
		}
		if (shown.isEmpty()) {
			String empty = "No matches";
			Render2D.textNoShadow(g, empty, x + (WIDTH - Render2D.width(empty)) / 2, listTop + listHeight / 2 - 4,
					Theme.textDim);
		}
		g.disableScissor();

		// scrollbar
		int contentHeight = shown.size() * ROW;
		if (contentHeight > listHeight) {
			int barHeight = Math.max(listHeight * listHeight / contentHeight, 10);
			int barY = listTop + (listHeight - barHeight) * scroll / (contentHeight - listHeight);
			Render2D.rect(g, x + WIDTH - 4, listTop, 2, listHeight, Theme.surface);
			Render2D.verticalGradient(g, x + WIDTH - 4, barY, 2, barHeight, Theme.accent1, Theme.accent2);
		}
	}

	/** Consumes every click while open; clicking outside the panel closes it. */
	public static boolean mouseClicked(double mouseX, double mouseY, int button, int guiWidth, int guiHeight) {
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
		// drag by the title strip (above the search row)
		if (mouseY < y + 15) {
			draggingPopup = true;
			dragOffX = (int) mouseX - x;
			dragOffY = (int) mouseY - y;
			return true;
		}
		int searchWidth = WIDTH - 12 - 40;
		if (Render2D.hovered(mouseX, mouseY, x + 6, y + 16, searchWidth, 13)) {
			SEARCH.click(mouseX - (x + 9));
			draggingSearch = true;
			return true;
		}
		if (Render2D.hovered(mouseX, mouseY, x + WIDTH - 42, y + 16, 36, 13)) {
			target.clear();
			return true;
		}

		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		if (mouseY >= listTop && mouseY < listTop + listHeight) {
			int index = ((int) mouseY - listTop + scroll) / ROW;
			if (index >= 0 && index < shown.size()) {
				target.toggle(shown.get(index).item());
			}
		}
		return true;
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int guiWidth, int guiHeight) {
		if (draggingSearch) {
			SEARCH.drag(mouseX - (popX + 9));
			return true;
		}
		if (!draggingPopup) {
			return false;
		}
		popX = Math.clamp((int) mouseX - dragOffX, 0, Math.max(guiWidth - WIDTH, 0));
		popY = Math.clamp((int) mouseY - dragOffY, 0, Math.max(guiHeight - HEIGHT, 0));
		return true;
	}

	public static void mouseReleased() {
		draggingPopup = false;
		draggingSearch = false;
	}

	public static boolean mouseScrolled(double scrollY) {
		if (target == null) {
			return false;
		}
		int listHeight = HEIGHT - HEADER - 4;
		int max = Math.max(shown.size() * ROW - listHeight, 0);
		scroll = Math.clamp(scroll - (int) (scrollY * 18), 0, max);
		return true;
	}

	public static boolean charTyped(CharacterEvent event) {
		return target != null && SEARCH.charTyped(event);
	}

	public static boolean keyPressed(KeyEvent event) {
		return target != null && SEARCH.keyPressed(event);
	}
}
