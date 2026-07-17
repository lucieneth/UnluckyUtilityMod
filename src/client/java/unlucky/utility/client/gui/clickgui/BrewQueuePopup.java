package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.settings.BrewQueueSetting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.BrewingSolver;
import unlucky.utility.client.util.BrewingSolver.State;
import unlucky.utility.client.util.Render2D;

/**
 * Popup for a {@link BrewQueueSetting}: search, then every potion this server can
 * actually brew. <b>Left-click adds one, right-click removes one</b> — the count
 * sits on the row, so the queue is built by clicking the thing you want as many
 * times as you want it.
 *
 * <p>The catalog is {@link BrewingSolver}'s reachable set, so it can't offer
 * something the stand would refuse. Each row's icon is the <b>real potion stack</b>,
 * which means vanilla tints it for free and you pick by the colour you're used to
 * rather than by reading a list of names.
 *
 * <p>Needs a level: the brewing rules are per-world. Opened without one it just says
 * so instead of showing an empty list that looks like a bug.
 */
public final class BrewQueuePopup {
	private static final int WIDTH = 230;
	private static final int HEIGHT = 210;
	private static final int ROW = 18;
	private static final int HEADER = 34;

	private record Entry(String key, ItemStack icon, String name, String search) {
	}

	private static BrewQueueSetting target;
	private static List<Entry> catalog = List.of();
	private static List<Entry> shown = List.of();
	private static final TextBox SEARCH = new TextBox();
	private static int scroll;
	private static int popX = Integer.MIN_VALUE;
	private static int popY;
	private static boolean draggingPopup;
	private static boolean draggingSearch;
	private static int dragOffX;
	private static int dragOffY;

	static {
		SEARCH.onChange(BrewQueuePopup::refilter);
	}

	private BrewQueuePopup() {
	}

	public static boolean isOpen() {
		return target != null;
	}

	public static void open(BrewQueueSetting setting) {
		target = setting;
		scroll = 0;
		SEARCH.clear();
		List<Entry> built = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			for (State state : BrewingSolver.solve(mc.level.potionBrewing()).keySet()) {
				if (state.equals(BrewingSolver.WATER_BOTTLE)) {
					continue; // "brew a water bottle" isn't a job
				}
				String name = BrewingSolver.label(state);
				built.add(new Entry(BrewingSolver.key(state), state.stack(), name, name.toLowerCase(Locale.ROOT)));
			}
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
		String title = target.getName() + "  (" + target.total() + " bottles)";
		Render2D.textNoShadow(g, title, x + 6, y + 5, Theme.text);

		int searchWidth = WIDTH - 12 - 40;
		Render2D.rect(g, x + 5, y + 15, searchWidth + 2, 15, Theme.borderDark);
		Render2D.rect(g, x + 6, y + 16, searchWidth, 13, Theme.surface);
		SEARCH.render(g, x + 9, y + 18, searchWidth - 6, true, "Search...");

		int clearX = x + WIDTH - 42;
		boolean clearHover = Render2D.hovered(mouseX, mouseY, clearX, y + 16, 36, 13);
		Render2D.rect(g, clearX - 1, y + 15, 38, 15, Theme.borderDark);
		Render2D.rect(g, clearX, y + 16, 36, 13, clearHover ? Theme.panel : Theme.surface);
		Render2D.textNoShadow(g, "Clear", clearX + 7, y + 19, clearHover ? Theme.text : Theme.textDim);

		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		g.enableScissor(x, listTop, x + WIDTH, listTop + listHeight);
		int rowY = listTop - scroll;
		for (Entry entry : shown) {
			if (rowY + ROW >= listTop && rowY <= listTop + listHeight) {
				int count = target.countOf(entry.key());
				boolean hover = Render2D.hovered(mouseX, mouseY, x + 2, rowY, WIDTH - 8, ROW);
				if (hover) {
					Render2D.rect(g, x + 2, rowY, WIDTH - 8, ROW, 0x18FFFFFF);
				}
				g.item(entry.icon(), x + 5, rowY + 1);
				Render2D.textNoShadow(g, entry.name(), x + 25, rowY + 5, count > 0 ? Theme.text : Theme.textDim);
				if (count > 0) {
					String amount = count + "x";
					Render2D.textNoShadow(g, amount, x + WIDTH - 12 - Render2D.width(amount), rowY + 5, Theme.accent1);
				}
			}
			rowY += ROW;
		}
		if (shown.isEmpty()) {
			String empty = Minecraft.getInstance().level == null ? "Join a world first" : "No matches";
			Render2D.textNoShadow(g, empty, x + (WIDTH - Render2D.width(empty)) / 2, listTop + listHeight / 2 - 4,
					Theme.textDim);
		}
		g.disableScissor();

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
		int listTop = y + HEADER;
		int listHeight = HEIGHT - HEADER - 4;
		// right-click only means anything on a row, so it's handled before the
		// left-click-only guard below
		if (mouseY >= listTop && mouseY < listTop + listHeight && (button == 0 || button == 1)) {
			int index = ((int) mouseY - listTop + scroll) / ROW;
			if (index >= 0 && index < shown.size()) {
				target.add(shown.get(index).key(), button == 0 ? 1 : -1);
			}
			return true;
		}
		if (button != 0) {
			return true;
		}
		if (mouseY < y + 15) { // drag by the title strip
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
