package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import unlucky.utility.client.module.modules.render.XRay;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Centered popup for a BlockListSetting: a search field, category tabs, then a
 * scrollable list of blocks with their item icons and checkboxes.
 *
 * <p><b>All</b> is the whole block registry, which is the point — the picker used
 * to offer only the three XRay presets plus whatever was already selected, so any
 * block nobody had thought of in advance could not be added from the GUI at all.
 * The preset tabs stay as filters over the same list.
 *
 * <p>The catalog is built once and reused across opens (~1.1k blocks), rebuilt only
 * when item components appear — with no world the icons come back empty
 * ({@link ItemUtil}), and a catalog built on the title screen would otherwise keep
 * its blank icons after joining. Names come off the block, so they are right either
 * way and the list stays usable from the main menu.
 */
public final class BlockPickerPopup {
	private static final int WIDTH = 230;
	private static final int HEIGHT = 220;
	private static final int ROW = 18;
	private static final int HEADER = 50;

	/** Filters over the one catalog; ALL is the registry. */
	private enum Tab {
		ALL("All", null),
		ORES("Ores", XRay.PRESET_ORES),
		STORAGE("Storage", XRay.PRESET_STORAGE),
		VALUABLES("Valuables", XRay.PRESET_VALUABLES);

		private final String label;
		private final Set<String> preset;

		Tab(String label, Set<String> preset) {
			this.label = label;
			this.preset = preset;
		}

		boolean accepts(String id) {
			return preset == null || preset.contains(id);
		}
	}

	private record Entry(Block block, String id, ItemStack icon, String name, String search) {
	}

	private static BlockListSetting target;
	private static List<Entry> catalog = List.of();
	private static List<Entry> shown = List.of();
	private static boolean catalogHasIcons;
	private static Tab tab = Tab.ALL;
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
		SEARCH.onChange(BlockPickerPopup::refilter);
	}

	private BlockPickerPopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(BlockListSetting setting) {
		target = setting;
		scroll = 0;
		tab = Tab.ALL;
		SEARCH.clear();
		buildCatalog();
		refilter();
	}

	/** Every registered block, once. Air is skipped — it is not a thing you can pick. */
	private static void buildCatalog() {
		boolean icons = ItemUtil.componentsBound();
		if (!catalog.isEmpty() && icons == catalogHasIcons) {
			return;
		}
		// Display names are not unique: 51 of them cover 102 blocks, and every one is a
		// wall-mounted variant sharing its free-standing twin's name ("Acacia Sign" is
		// both acacia_sign and acacia_wall_sign). Two identical rows with different
		// checkboxes are worse than none, so ambiguous names say which is which.
		Map<String, Integer> nameCounts = new HashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (!block.defaultBlockState().isAir()) {
				nameCounts.merge(block.getName().getString(), 1, Integer::sum);
			}
		}

		List<Entry> built = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (block.defaultBlockState().isAir()) {
				continue;
			}
			Identifier key = BuiltInRegistries.BLOCK.getKey(block);
			if (key == null) {
				continue;
			}
			String id = key.toString();
			String plain = block.getName().getString();
			// Marking one of the pair is enough, and the wall variant is the one whose
			// name is the lie — every ambiguous group measured is exactly {x, x_wall_*}.
			String name = nameCounts.getOrDefault(plain, 0) > 1 && key.getPath().contains("wall")
					? plain + " (wall)"
					: plain;
			// search the plain name and the id, so "diamond" and
			// "minecraft:deepslate_diamond_ore" both land
			built.add(new Entry(block, id, ItemUtil.icon(block), name,
					(plain + ' ' + id).toLowerCase(Locale.ROOT)));
		}
		built.sort(Comparator.comparing(Entry::name));
		catalog = List.copyOf(built);
		catalogHasIcons = icons;
	}

	public static void close() {
		target = null;
		shown = List.of();
		SEARCH.clear();
	}

	private static void refilter() {
		String query = SEARCH.text().toLowerCase(Locale.ROOT).trim();
		List<Entry> matches = new ArrayList<>();
		for (Entry entry : catalog) {
			if (tab.accepts(entry.id()) && (query.isEmpty() || entry.search().contains(query))) {
				matches.add(entry);
			}
		}
		shown = matches;
		scroll = 0;
	}

	private static int tabWidth() {
		return (WIDTH - 12 - 3 * 3) / Tab.values().length;
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

		// title-row actions: add everything currently listed, or empty the selection
		drawButton(g, mouseX, mouseY, x + WIDTH - 84, y + 3, 44, "Add all");
		drawButton(g, mouseX, mouseY, x + WIDTH - 38, y + 3, 32, "Clear");

		// search
		int searchWidth = WIDTH - 12;
		Render2D.rect(g, x + 5, y + 15, searchWidth + 2, 15, Theme.borderDark);
		Render2D.rect(g, x + 6, y + 16, searchWidth, 13, Theme.surface);
		SEARCH.render(g, x + 9, y + 18, searchWidth - 6, true, "Search all blocks...");

		// category tabs
		int tw = tabWidth();
		for (int i = 0; i < Tab.values().length; i++) {
			Tab entry = Tab.values()[i];
			int bx = x + 6 + i * (tw + 3);
			boolean active = entry == tab;
			boolean hover = Render2D.hovered(mouseX, mouseY, bx, y + 33, tw, 13);
			Render2D.rect(g, bx, y + 33, tw, 13, active ? Theme.panel : Theme.surface);
			if (active) {
				Render2D.rect(g, bx, y + 45, tw, 1, Theme.accent1);
			}
			Render2D.textNoShadow(g, entry.label, bx + (tw - Render2D.width(entry.label)) / 2, y + 36,
					active ? Theme.text : hover ? Theme.text : Theme.textDim);
		}

		// block list
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		g.enableScissor(x, listTop, x + WIDTH, listTop + listHeight);
		int labelWidth = WIDTH - 25 - 22;
		int rowY = listTop - scroll;
		for (Entry entry : shown) {
			if (rowY + ROW >= listTop && rowY <= listTop + listHeight) {
				boolean selected = target.get().contains(entry.id());
				boolean hover = Render2D.hovered(mouseX, mouseY, x + 2, rowY, WIDTH - 8, ROW);
				if (hover) {
					Render2D.rect(g, x + 2, rowY, WIDTH - 8, ROW, 0x18FFFFFF);
				}
				g.item(entry.icon(), x + 5, rowY + 1);
				// clipped, not wrapped: a long name (modded blocks are not bounded by
				// vanilla's) must never render under the checkbox
				Render2D.textNoShadow(g, Render2D.font().plainSubstrByWidth(entry.name(), labelWidth),
						x + 25, rowY + 5, selected ? Theme.text : Theme.textDim);
				int boxX = x + WIDTH - 18;
				Render2D.rect(g, boxX - 1, rowY + 3, 11, 11, Theme.borderDark);
				Render2D.rect(g, boxX, rowY + 4, 9, 9,
						selected ? Theme.accent1 : ColorUtil.withAlpha(Theme.textDim, 70));
			}
			rowY += ROW;
		}
		if (shown.isEmpty()) {
			String empty = "No matches";
			Render2D.textNoShadow(g, empty, x + (WIDTH - Render2D.width(empty)) / 2,
					listTop + listHeight / 2 - 4, Theme.textDim);
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

	private static void drawButton(GuiGraphicsExtractor g, int mouseX, int mouseY,
			int bx, int by, int bw, String label) {
		boolean hover = Render2D.hovered(mouseX, mouseY, bx, by, bw, 13);
		Render2D.rect(g, bx - 1, by - 1, bw + 2, 15, Theme.borderDark);
		Render2D.rect(g, bx, by, bw, 13, hover ? Theme.panel : Theme.surface);
		Render2D.textNoShadow(g, label, bx + (bw - Render2D.width(label)) / 2, by + 3,
				hover ? Theme.text : Theme.textDim);
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

		// title-row buttons come before the drag strip they sit in
		if (Render2D.hovered(mouseX, mouseY, x + WIDTH - 84, y + 3, 44, 13)) {
			for (Entry entry : shown) {
				if (!target.get().contains(entry.id())) {
					target.toggle(entry.block());
				}
			}
			XRay.refresh();
			return true;
		}
		if (Render2D.hovered(mouseX, mouseY, x + WIDTH - 38, y + 3, 32, 13)) {
			target.setAll(Set.of());
			XRay.refresh();
			return true;
		}
		if (mouseY < y + 15) {
			draggingPopup = true;
			dragOffX = (int) mouseX - x;
			dragOffY = (int) mouseY - y;
			return true;
		}

		int searchWidth = WIDTH - 12;
		if (Render2D.hovered(mouseX, mouseY, x + 6, y + 16, searchWidth, 13)) {
			SEARCH.click(mouseX - (x + 9));
			draggingSearch = true;
			return true;
		}

		int tw = tabWidth();
		for (int i = 0; i < Tab.values().length; i++) {
			if (Render2D.hovered(mouseX, mouseY, x + 6 + i * (tw + 3), y + 33, tw, 13)) {
				tab = Tab.values()[i];
				refilter();
				return true;
			}
		}

		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		if (mouseY >= listTop && mouseY < listTop + listHeight) {
			int index = ((int) mouseY - listTop + scroll) / ROW;
			if (index >= 0 && index < shown.size()) {
				target.toggle(shown.get(index).block());
				XRay.refresh();
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

	public static boolean mouseScrolled(double scrollY, int guiHeight) {
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
