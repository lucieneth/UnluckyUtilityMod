package unlucky.utility.client.gui.chests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.world.ChestManager;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ChestDatabase;
import unlucky.utility.client.util.ChestDatabase.Entry;
import unlucky.utility.client.util.ChestDatabase.Query;
import unlucky.utility.client.util.ChestDatabase.Stored;
import unlucky.utility.client.util.ChestDatabase.Tally;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * ChestManager's database as a place instead of a chat log, drawn inside a ClickGUI: Skeet's
 * Chests tab and Future's Chests panel are both this.
 *
 * <p>The search lists every item the recorded chests hold, most first, filtered live as you type.
 * Picking one lights up the chests that hold it, here and in the world. Given the room,
 * {@link ChestScene} shows the whole base in 3D; picking a chest there opens its slots over the
 * scene's corner as they were last read. Skeet stacks the scene over the search and its buttons;
 * Future sets the scene beside them, and folded to one column the search is all there is.
 *
 * <p>Every world keeps its own records, one file per dimension; the switch beside the search
 * reads the other dimensions of this world without leaving the one you are in. Rescans only
 * queue here; ChestManager reads chests while no screen is open, so they run once the GUI closes.
 */
public final class ChestPanel {
	/** Skeet: flat, owns the keyboard, scene on top. Future: glass, focus on click, scene beside. */
	public enum Style { SKEET, FUTURE }

	private enum Shape { STACKED, SIDE, NARROW }

	private record Rect(int x, int y, int w, int h) {
		boolean has(double mouseX, double mouseY) {
			return Render2D.hovered(mouseX, mouseY, x, y, w, h);
		}

		int right() {
			return x + w;
		}

		int bottom() {
			return y + h;
		}
	}

	/** One item summed over the viewed dimension's chests, with where it lies, most first. */
	private record ItemRow(String id, ItemStack icon, String name, long total, List<Map.Entry<Entry, Long>> where) {}

	private static final int PAD = 6;
	private static final int GAP = 4;
	private static final int FIELD_H = 16;
	private static final int TABS_H = 12;
	private static final int HEADER_H = 12;
	private static final int ITEM_H = 18;
	private static final int SLOT = 18;
	private static final int BUTTONS_W = 92;
	private static final int RESULT_MIN_W = 110;
	private static final long STATUS_MS = 5000;

	// what you were looking at survives closing the GUI and switching between the two styles
	private static final TextBox SEARCH = new TextBox();
	private static final ChestScene SCENE = new ChestScene();
	private static int scroll;
	/** The dimension being viewed as its file name; null follows the player. */
	private static String viewing;
	private static String lastCurrent;
	/** The result picked to light up, as its short id; null shows the typed search instead. */
	private static String pickedItem;

	private final Style style;
	private boolean focused;
	private int x, y, w, h;
	private int accent;
	private String status = "";
	private long statusAt;
	private boolean draggingSearch;
	private boolean draggingScene;

	// laid out once per frame, read back by the clicks that follow it
	private Shape shape = Shape.NARROW;
	private Rect scene, search, tabs, results;
	private Rect[] buttons = new Rect[0];
	private int resultCols = 1;

	private int listRevision = -1;
	private Query listQuery;
	private String listText;
	private String listDimension;
	private List<String> dimensions = List.of();
	private List<Entry> entries = List.of();
	private List<ItemRow> items = List.of();
	private Map<Entry, Tally> tallies;
	private Entry stacksFor;
	private ItemStack[] stacks = new ItemStack[0];

	/**
	 * The results and search counts are worked out here, off the render thread, so opening the GUI
	 * never waits on a big base; the panel shows loading dots until they land.
	 */
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "UnluckyClient chest panel");
		thread.setDaemon(true);
		return thread;
	});

	private record Built(int generation, List<Entry> entries, Map<Entry, Tally> tallies, List<ItemRow> items) {}

	private int generation;
	private volatile Built built;
	/** Whether what's shown belongs to the viewed dimension; false until its first results land. */
	private boolean loaded;

	public ChestPanel(Style style) {
		this.style = style;
	}

	private static ChestManager cm() {
		return UnluckyClient.INSTANCE.modules.get(ChestManager.class);
	}

	/** Called from the screen's init: catches up with the search typed in chat and the world. */
	public void open() {
		ChestDatabase.sync();
		Query search = cm().search();
		if (search != null && search.isExact()) {
			pickedItem = search.raw();
		} else {
			pickedItem = null;
			String raw = search == null ? "" : search.raw();
			if (!SEARCH.text().equals(raw)) {
				SEARCH.setText(raw);
			}
		}
		SEARCH.onChange(() -> {
			// typing goes back to the search; a pick from the old results means nothing now
			pickedItem = null;
			cm().setSearch(SEARCH.text());
		});
		listRevision = -1;
		loaded = false;
	}

	// ------------------------------------------------------------------ data

	private static boolean live() {
		return viewed() != null && viewed().equals(ChestDatabase.currentDimension());
	}

	private static String viewed() {
		return viewing != null ? viewing : ChestDatabase.currentDimension();
	}

	/**
	 * Takes in the worker's latest results, and starts it on new ones when the database, the
	 * search or the dimension changed. Until a dimension's first results land, it is "loading".
	 */
	private void refresh() {
		ChestDatabase.sync();
		String current = ChestDatabase.currentDimension();
		if (!Objects.equals(current, lastCurrent)) {
			// walking through a portal follows you; the old dimension is a click away
			lastCurrent = current;
			viewing = null;
			SCENE.select(null, false);
			SCENE.frameAll();
		}
		Built done = built;
		if (done != null && done.generation() == generation) {
			built = null;
			entries = done.entries();
			tallies = done.tallies();
			items = done.items();
			Entry selected = SCENE.selected();
			if (selected != null) {
				SCENE.select(find(selected), false);
			}
			stacksFor = null;
			if (!loaded) {
				loaded = true;
				SCENE.reveal();
			}
		}
		Query query = cm().search();
		String text = SEARCH.text();
		String dimension = viewed();
		if (ChestDatabase.revision() == listRevision && query == listQuery && text.equals(listText)
				&& Objects.equals(dimension, listDimension)) {
			return;
		}
		if (!Objects.equals(dimension, listDimension)) {
			loaded = false;
		}
		if (!loaded) {
			// another dimension's chests mean nothing here; dots until this one's are in
			entries = List.of();
			items = List.of();
			tallies = null;
		}
		listRevision = ChestDatabase.revision();
		listQuery = query;
		listText = text;
		listDimension = dimension;
		dimensions = ChestDatabase.dimensions();
		// copied here, where the database is written; everything after reads only the copy
		List<Entry> snapshot = List.copyOf(live() ? ChestDatabase.all() : ChestDatabase.entriesIn(dimension));
		Query filter = text.isBlank() ? null : new Query(text);
		int job = ++generation;
		WORKER.execute(() -> {
			Map<Entry, Tally> found = null;
			if (query != null) {
				found = new HashMap<>();
				for (Entry entry : snapshot) {
					Tally tally = ChestDatabase.tally(entry, query);
					if (tally != null) {
						found.put(entry, tally);
					}
				}
			}
			built = new Built(job, snapshot, found, collect(snapshot, filter));
		});
	}

	/** "Loading" with one to three dots, ticking over. */
	static String loadingText() {
		return "Loading" + ".".repeat(1 + (int) (System.currentTimeMillis() / 350 % 3));
	}

	/** Every item in these chests, boxes' contents included, that the search lets through. */
	private static List<ItemRow> collect(List<Entry> entries, Query filter) {
		Map<String, Map<Entry, Long>> found = new LinkedHashMap<>();
		for (Entry entry : entries) {
			for (Stored stored : entry.items()) {
				add(found, entry, stored, filter);
			}
		}
		List<ItemRow> rows = new ArrayList<>(found.size());
		for (var item : found.entrySet()) {
			List<Map.Entry<Entry, Long>> where = new ArrayList<>(item.getValue().entrySet());
			where.sort(Map.Entry.<Entry, Long>comparingByValue().reversed());
			long total = 0;
			for (var place : where) {
				total += place.getValue();
			}
			Item kind = ChestDatabase.item(item.getKey());
			ItemStack icon = kind == null ? new ItemStack(Items.BARRIER) : new ItemStack(kind);
			String name = kind == null ? item.getKey() : icon.getHoverName().getString();
			rows.add(new ItemRow(item.getKey(), icon, name, total, where));
		}
		rows.sort(Comparator.comparingLong(ItemRow::total).reversed()
				.thenComparing(row -> row.name().toLowerCase(Locale.ROOT)));
		return rows;
	}

	private static void add(Map<String, Map<Entry, Long>> found, Entry entry, Stored stored, Query filter) {
		if (filter == null || filter.matches(stored)) {
			found.computeIfAbsent(stored.id(), id -> new LinkedHashMap<>())
					.merge(entry, (long) Math.max(1, stored.count()), Long::sum);
		}
		if (stored.inner() != null) {
			for (Stored inner : stored.inner()) {
				add(found, entry, inner, filter);
			}
		}
	}

	/** The same chest in the fresh list, or null once it is gone. */
	private Entry find(Entry old) {
		for (Entry entry : entries) {
			if (entry.pos().equals(old.pos())) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Where the player is, in the viewed dimension's coordinates: the Nether is an eighth of the
	 * Overworld, so planning a trip from one to the other still measures what is near. Null when
	 * the two don't map onto each other, like the End.
	 */
	private static Vec3 eye() {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return null;
		}
		String here = ChestDatabase.currentDimension();
		String there = viewed();
		if (here == null || there == null) {
			return null;
		}
		if (here.equals(there)) {
			return player.getEyePosition();
		}
		double from = coordinateScale(here), to = coordinateScale(there);
		if (from < 0 || to < 0) {
			return null;
		}
		Vec3 eye = player.getEyePosition();
		return new Vec3(eye.x * from / to, eye.y, eye.z * from / to);
	}

	private static double coordinateScale(String dimension) {
		return switch (dimension) {
			case "minecraft_overworld" -> 1.0;
			case "minecraft_the_nether" -> 8.0;
			default -> -1.0;
		};
	}

	private static Vec3 centre(Entry entry) {
		Vec3 centre = Vec3.atCenterOf(entry.pos());
		return entry.other() == null ? centre : centre.add(Vec3.atCenterOf(entry.other())).scale(0.5);
	}

	/** The picked chest's slots as item stacks, rebuilt from the record once per pick. */
	private ItemStack[] stacks(Entry entry) {
		if (entry != stacksFor) {
			stacksFor = entry;
			stacks = new ItemStack[Math.max(entry.size(), 1)];
			Arrays.fill(stacks, ItemStack.EMPTY);
			for (Stored stored : entry.items()) {
				if (stored.slot() >= 0 && stored.slot() < stacks.length) {
					stacks[stored.slot()] = stack(stored);
				}
			}
		}
		return stacks;
	}

	private static ItemStack stack(Stored stored) {
		Item item = ChestDatabase.item(stored.id());
		if (item == null) {
			ItemStack unknown = new ItemStack(Items.BARRIER);
			unknown.set(DataComponents.CUSTOM_NAME, Component.literal(stored.id()));
			return unknown;
		}
		ItemStack stack = new ItemStack(item, Math.max(1, stored.count()));
		if (stored.name() != null) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(stored.name()));
		}
		if (stored.ench() != null && !stored.ench().isEmpty()) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		if (stored.inner() != null && stack.has(DataComponents.CONTAINER)) {
			int size = 0;
			for (Stored inner : stored.inner()) {
				size = Math.max(size, inner.slot() + 1);
			}
			List<ItemStack> contents = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				contents.add(ItemStack.EMPTY);
			}
			for (Stored inner : stored.inner()) {
				contents.set(inner.slot(), stack(inner));
			}
			stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
		}
		return stack;
	}

	/** The world the records belong to, as the player knows it. */
	private static String worldName() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
			return mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		ServerData server = mc.getCurrentServer();
		if (server == null) {
			return "";
		}
		return server.name == null || server.name.isBlank() ? server.ip : server.name;
	}

	static String dimensionName(String safe) {
		return switch (safe) {
			case "minecraft_overworld" -> "Overworld";
			case "minecraft_the_nether" -> "Nether";
			case "minecraft_the_end" -> "End";
			default -> safe.startsWith("minecraft_") ? safe.substring("minecraft_".length()) : safe;
		};
	}

	// ------------------------------------------------------------------ layout

	/**
	 * Skeet, given the height, puts the scene across the top and the search under it: the field
	 * top-left, results filling up to three columns from there, the buttons in their own box on
	 * the right. Otherwise the search is a column with its buttons at the foot, the scene beside it
	 * when there is room for one.
	 */
	private void layout() {
		int top = y + HEADER_H + 2;
		if (style == Style.SKEET && h >= 180 && w >= 260) {
			shape = Shape.STACKED;
			int strip = Math.clamp((h - HEADER_H - 2) * 38 / 100, 4 * ITEM_H + FIELD_H + 4, h - HEADER_H - 2 - 80);
			int stripY = y + h - strip;
			scene = new Rect(x, top, w, stripY - PAD - top);
			int greenW = w - BUTTONS_W - PAD;
			resultCols = Math.clamp(greenW / RESULT_MIN_W, 1, 3);
			int cellW = (greenW - (resultCols - 1) * GAP) / resultCols;
			search = new Rect(x, stripY, cellW, FIELD_H);
			tabs = resultCols > 1 ? new Rect(x + cellW + GAP, stripY, greenW - cellW - GAP, FIELD_H) : null;
			int resultsY = stripY + FIELD_H + 4;
			if (tabs == null) {
				// one column: the switch drops under the field instead of beside it
				tabs = new Rect(x, resultsY, greenW, TABS_H);
				resultsY += TABS_H + 4;
			}
			results = new Rect(x, resultsY, greenW, y + h - resultsY);
			int bx = x + w - BUTTONS_W;
			buttons = new Rect[4];
			for (int i = 0; i < buttons.length; i++) {
				buttons[i] = new Rect(bx, stripY + i * (FIELD_H + GAP), BUTTONS_W, FIELD_H);
			}
			return;
		}
		if (w >= 220) {
			shape = Shape.SIDE;
			int colW = Math.clamp(w / 3, 100, 200);
			column(x, top, colW, y + h);
			scene = new Rect(x + colW + PAD, top, w - colW - PAD, y + h - top);
		} else {
			shape = Shape.NARROW;
			column(x, top, w, y + h);
			scene = null;
		}
	}

	private void column(int cx, int top, int cw, int bottom) {
		search = new Rect(cx, top, cw, FIELD_H);
		tabs = new Rect(cx, search.bottom() + 4, cw, TABS_H);
		// the modes side by side, Rescan under them
		int by = bottom - 2 * FIELD_H - GAP;
		int bw = (cw - GAP) / 2;
		buttons = new Rect[] {new Rect(cx, by, bw, FIELD_H), new Rect(cx + bw + GAP, by, cw - bw - GAP, FIELD_H),
				new Rect(cx, by + FIELD_H + GAP, cw, FIELD_H)};
		int resultsY = tabs.bottom() + 4;
		results = new Rect(cx, resultsY, cw, Math.max(ITEM_H, by - 4 - resultsY));
		resultCols = 1;
	}

	/** The picked chest's slots, over the scene's bottom-left corner; null when nothing is picked. */
	private Rect overlay() {
		Entry selected = SCENE.selected();
		if (scene == null || selected == null) {
			return null;
		}
		int cols = Math.clamp((scene.w() - 14) / SLOT, 1, 9);
		int rows = (Math.max(selected.size(), 1) + cols - 1) / cols;
		int ow = Math.min(scene.w() - 8, Math.max(cols * SLOT + 6, 150));
		int oh = Math.min(16 + rows * SLOT + 3, scene.h() - 8);
		return new Rect(scene.x() + 4, scene.bottom() - 4 - oh, ow, oh);
	}

	public boolean contains(double mouseX, double mouseY) {
		return Render2D.hovered(mouseX, mouseY, x, y, w, h);
	}

	// ------------------------------------------------------------------ drawing

	private int fieldColor(boolean hover) {
		int color = hover ? Theme.surface : Theme.sidebar;
		return style == Style.FUTURE ? ColorUtil.withAlpha(color, hover ? 150 : 110) : color;
	}

	private static String fit(String text, int width) {
		if (Render2D.width(text) <= width) {
			return text;
		}
		return Render2D.font().plainSubstrByWidth(text, Math.max(0, width - Render2D.width(".."))) + "..";
	}

	/** 1234 as 1.2k: a column of counts reads at a glance and fits beside the names. */
	private static String compact(long count) {
		if (count < 1000) {
			return Long.toString(count);
		}
		if (count < 100_000) {
			String k = String.format(Locale.ROOT, "%.1f", count / 1000.0);
			return (k.endsWith(".0") ? k.substring(0, k.length() - 2) : k) + "k";
		}
		if (count < 1_000_000) {
			return count / 1000 + "k";
		}
		return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
	}

	/** Lays the panel out in this rectangle and draws it. {@code accent} lights the live controls. */
	public void render(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY, int accent) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.accent = accent;
		if (!ChestDatabase.ready()) {
			String empty = "Join a world to see its chests";
			Render2D.textNoShadow(g, fit(empty, w), x + Math.max(0, (w - Render2D.width(empty)) / 2),
					y + h / 2 - 4, Theme.textDim);
			return;
		}
		refresh();
		layout();
		ChestManager cm = cm();

		drawHeader(g, cm);

		boolean typing = typing();
		Render2D.rect(g, search.x() - 1, search.y() - 1, search.w() + 2, search.h() + 2,
				typing ? ColorUtil.withAlpha(accent, 200) : Theme.borderDark);
		Render2D.rect(g, search.x(), search.y(), search.w(), search.h(), fieldColor(false));
		SEARCH.render(g, search.x() + 4, search.y() + 4, search.w() - 8, typing, "Find an item...");

		drawDimensions(g, mouseX, mouseY);
		drawResults(g, mouseX, mouseY, cm);
		drawButtons(g, mouseX, mouseY, cm);

		if (scene != null) {
			Rect overlay = overlay();
			boolean overOverlay = overlay != null && overlay.has(mouseX, mouseY);
			// the slots box sits on the scene, so the chest behind it is not what the mouse is on
			int sceneMouseX = overOverlay ? -1 : mouseX, sceneMouseY = overOverlay ? -1 : mouseY;
			if (loaded) {
				SCENE.render(g, scene.x(), scene.y(), scene.w(), scene.h(), sceneMouseX, sceneMouseY, entries,
						tallies, cm.glowColor.get(), live());
			} else {
				SCENE.loading(g, scene.x(), scene.y(), scene.w(), scene.h());
			}
			if (overlay != null) {
				drawOverlay(g, overlay, mouseX, mouseY, cm);
			}
			Entry hovered = SCENE.hovered();
			if (hovered != null && !overOverlay) {
				g.setTooltipForNextFrame(Component.literal(describe(hovered)), mouseX, mouseY);
			}
		}
	}

	/** The world on the left, the count (or what just happened) on the right. */
	private void drawHeader(GuiGraphicsExtractor g, ChestManager cm) {
		String right;
		int rightColor = Theme.textDim;
		if (!status.isEmpty() && System.currentTimeMillis() - statusAt < STATUS_MS) {
			right = status;
			rightColor = Theme.text;
		} else if (!loaded) {
			right = loadingText();
		} else {
			right = tallies != null ? matchSummary()
					: entries.size() + (entries.size() == 1 ? " chest" : " chests")
							+ " · " + items.size() + (items.size() == 1 ? " item" : " items");
			int pending = cm.pending();
			if (pending > 0) {
				right = pending + " queued, close to scan · " + right;
				rightColor = accent;
			}
		}
		String world = worldName();
		int worldW = Math.min(Render2D.width(world), Math.max(w / 3, w - Render2D.width(right) - 8));
		right = fit(right, w - worldW - 8);
		Render2D.textNoShadow(g, right, x + w - Render2D.width(right), y + 2, rightColor);
		Render2D.textNoShadow(g, fit(world, worldW), x, y + 2, Theme.text);
	}

	private void drawDimensions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int count = Math.max(1, dimensions.size());
		String viewed = viewed();
		int textY = tabs.y() + (tabs.h() - 8) / 2;
		for (int i = 0; i < dimensions.size(); i++) {
			int cellX = tabs.x() + i * tabs.w() / count;
			int cellW = tabs.x() + (i + 1) * tabs.w() / count - cellX;
			String dimension = dimensions.get(i);
			boolean active = dimension.equals(viewed);
			boolean hover = Render2D.hovered(mouseX, mouseY, cellX, tabs.y(), cellW, tabs.h());
			Render2D.rect(g, cellX, tabs.y(), cellW, tabs.h(), fieldColor(hover && !active));
			if (active) {
				Render2D.rect(g, cellX, tabs.bottom() - 1, cellW, 1, accent);
			}
			String label = dimensionName(dimension);
			if (dimension.equals(ChestDatabase.currentDimension()) && Render2D.width(label + " •") <= cellW - 4) {
				label += " •"; // you are here
			}
			label = fit(label, cellW - 4);
			Render2D.textNoShadow(g, label, cellX + (cellW - Render2D.width(label)) / 2, textY,
					active ? Theme.text : hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : Theme.textDim);
			if (i > 0) {
				Render2D.rect(g, cellX, tabs.y() + 2, 1, tabs.h() - 4, Theme.borderDark);
			}
		}
	}

	private int cellW() {
		return (results.w() - (resultCols - 1) * GAP) / resultCols;
	}

	/** The result under the mouse, or -1. */
	private int resultAt(double mouseX, double mouseY) {
		if (!results.has(mouseX, mouseY)) {
			return -1;
		}
		int col = (int) ((mouseX - results.x()) / (cellW() + GAP));
		if (col >= resultCols || mouseX - results.x() - col * (cellW() + GAP) >= cellW()) {
			return -1;
		}
		int row = (int) ((mouseY - results.y() + scroll) / ITEM_H);
		int index = row * resultCols + col;
		return index >= 0 && index < items.size() ? index : -1;
	}

	private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY, ChestManager cm) {
		int cellW = cellW();
		int lines = (items.size() + resultCols - 1) / resultCols;
		int contentH = lines * ITEM_H;
		scroll = Math.clamp(scroll, 0, Math.max(0, contentH - results.h()));
		int hoveredIndex = resultAt(mouseX, mouseY);
		g.enableScissor(results.x(), results.y(), results.right(), results.bottom());
		if (!loaded) {
			Render2D.textNoShadow(g, loadingText(), results.x() + 4, results.y() + 4, Theme.textDim);
		} else if (items.isEmpty()) {
			String empty = !entries.isEmpty() ? "Nothing here holds that"
					: live() ? "Open a chest to start tracking" : "No chests recorded here";
			Render2D.textNoShadow(g, fit(empty, results.w() - 8), results.x() + 4, results.y() + 4, Theme.textDim);
		}
		int glow = cm.glowColor.get() | 0xFF000000;
		for (int i = 0; i < items.size(); i++) {
			int cx = results.x() + (i % resultCols) * (cellW + GAP);
			int cy = results.y() + (i / resultCols) * ITEM_H - scroll;
			if (cy + ITEM_H < results.y() || cy > results.bottom()) {
				continue;
			}
			ItemRow row = items.get(i);
			boolean picked = row.id().equals(pickedItem);
			if (picked) {
				Render2D.rect(g, cx, cy, cellW, ITEM_H, fieldColor(true));
				Render2D.rect(g, cx, cy, 2, ITEM_H, accent);
			} else if (i == hoveredIndex) {
				Render2D.rect(g, cx, cy, cellW, ITEM_H, ColorUtil.withAlpha(Theme.surface, 150));
			}
			g.item(row.icon(), cx + 3, cy + 1);
			String count = compact(row.total());
			Render2D.textNoShadow(g, count, cx + cellW - 4 - Render2D.width(count), cy + 5, picked ? glow : Theme.textDim);
			Render2D.textNoShadow(g, fit(row.name(), cellW - 30 - Render2D.width(count)), cx + 22, cy + 5,
					picked ? Theme.text : ColorUtil.lerp(Theme.textDim, Theme.text, 0.75f));
		}
		g.disableScissor();
		if (contentH > results.h()) {
			int viewH = results.h();
			int barH = Math.max(viewH * viewH / contentH, 10);
			int barY = results.y() + (viewH - barH) * scroll / Math.max(1, contentH - viewH);
			int barX = results.right() - 2;
			Render2D.rect(g, barX, results.y(), 2, viewH, ColorUtil.withAlpha(Theme.surface, 200));
			Render2D.rect(g, barX, barY, 2, barH, accent);
		}
		if (hoveredIndex >= 0) {
			g.setComponentTooltipForNextFrame(Render2D.font(), tooltip(items.get(hoveredIndex)), mouseX, mouseY);
		}
	}

	/** The item, how much there is, and the chests holding the most of it. */
	private List<Component> tooltip(ItemRow row) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal(row.name()));
		int chests = row.where().size();
		lines.add(Component.literal(row.total() + " in " + chests + (chests == 1 ? " chest" : " chests"))
				.withStyle(ChatFormatting.GRAY));
		Vec3 eye = eye();
		int shown = Math.min(chests, 4);
		for (int i = 0; i < shown; i++) {
			var place = row.where().get(i);
			String distance = eye == null ? "" : " · " + Math.round(centre(place.getKey()).distanceTo(eye)) + "m";
			lines.add(Component.literal(coords(place.getKey()) + "  x" + place.getValue() + distance)
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		if (chests > shown) {
			lines.add(Component.literal("+" + (chests - shown) + " more").withStyle(ChatFormatting.DARK_GRAY));
		}
		lines.add(Component.literal(row.id().equals(pickedItem) ? "Click to stop showing it" : "Click to light them up")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		return lines;
	}

	/** The first label that fits the button, longest first. */
	private static String label(int width, String... options) {
		for (String option : options) {
			if (Render2D.width(option) <= width - 6) {
				return option;
			}
		}
		return fit(options[options.length - 1], width - 4);
	}

	private String buttonLabel(int index, int width, ChestManager cm) {
		return switch (index) {
			case 0 -> {
				String state = cm.clickMode() ? "on" : "off";
				yield label(width, "Click mode: " + state, "Click: " + state, "Click");
			}
			case 1 -> {
				String state = cm.autoMode() ? "on" : "off";
				yield label(width, "Auto mode: " + state, "Auto: " + state, "Auto");
			}
			case 2 -> label(width, "Rescan in reach", "Rescan");
			default -> label(width, "Reset view", "Reset");
		};
	}

	private boolean buttonEnabled(int index) {
		return index != 2 || live();
	}

	private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY, ChestManager cm) {
		for (int i = 0; i < buttons.length; i++) {
			Rect b = buttons[i];
			boolean enabled = buttonEnabled(i);
			boolean hover = enabled && b.has(mouseX, mouseY);
			boolean on = i == 0 ? cm.clickMode() : i == 1 && cm.autoMode();
			Render2D.rect(g, b.x() - 1, b.y() - 1, b.w() + 2, b.h() + 2, on ? accent : Theme.borderDark);
			Render2D.rect(g, b.x(), b.y(), b.w(), b.h(), fieldColor(hover));
			String label = buttonLabel(i, b.w(), cm);
			Render2D.textNoShadow(g, label, b.x() + (b.w() - Render2D.width(label)) / 2, b.y() + 4,
					on ? accent : !enabled ? ColorUtil.withAlpha(Theme.textDim, 110) : hover ? Theme.text : Theme.textDim);
			if (b.has(mouseX, mouseY)) {
				String tip = switch (i) {
					case 0 -> "Click mode " + (cm.clickMode() ? "on" : "off")
							+ ": right-click a chest to tag it without opening it";
					case 1 -> "Auto mode " + (cm.autoMode() ? "on" : "off")
							+ ": reads every chest in reach that has no record yet";
					case 2 -> live() ? "Reads every chest in reach once the GUI closes" : "Only in the dimension you are in";
					default -> "Put the picked chest down and frame the whole base";
				};
				g.setTooltipForNextFrame(Component.literal(tip), mouseX, mouseY);
			}
		}
	}

	/** Where the picked chest's actions sit in its box, right to left. */
	private static final String[] ACTIONS = {"Forget", "Rescan"};

	private void drawOverlay(GuiGraphicsExtractor g, Rect box, int mouseX, int mouseY, ChestManager cm) {
		Entry selected = SCENE.selected();
		g.outline(box.x() - 1, box.y() - 1, box.w() + 2, box.h() + 2, Theme.borderDark);
		Render2D.rect(g, box.x(), box.y(), box.w(), box.h(),
				ColorUtil.withAlpha(Theme.sidebar, style == Style.FUTURE ? 215 : 235));
		int ax = box.right() - 2;
		if (live()) {
			for (String action : ACTIONS) {
				ax -= Render2D.width(action) + 6;
				boolean hover = Render2D.hovered(mouseX, mouseY, ax - 2, box.y() + 1, Render2D.width(action) + 4, 12);
				Render2D.textNoShadow(g, action, ax, box.y() + 4,
						hover ? (action.equals("Forget") ? 0xFFFF5555 : accent) : Theme.textDim);
			}
		}
		String title = coords(selected) + " · " + ChestManager.ago(System.currentTimeMillis() - selected.scanned());
		Render2D.textNoShadow(g, fit(title, ax - box.x() - 8), box.x() + 4, box.y() + 4, Theme.text);

		ItemStack[] slots = stacks(selected);
		Tally tally = tallies == null ? null : tallies.get(selected);
		int highlight = cm.highlightColor.get();
		int cols = Math.clamp((box.w() - 6) / SLOT, 1, 9);
		int gridX = box.x() + 3, gridY = box.y() + 16;
		ItemStack hovered = null;
		g.enableScissor(box.x(), box.y(), box.right(), box.bottom());
		for (int slot = 0; slot < slots.length; slot++) {
			int sx = gridX + (slot % cols) * SLOT;
			int sy = gridY + (slot / cols) * SLOT;
			Render2D.rect(g, sx, sy, SLOT - 1, SLOT - 1, Theme.borderDark);
			Render2D.rect(g, sx + 1, sy + 1, SLOT - 3, SLOT - 3, fieldColor(false));
			if (tally != null && tally.slots().contains(slot)) {
				Render2D.rect(g, sx + 1, sy + 1, SLOT - 3, SLOT - 3, highlight);
			}
			if (!slots[slot].isEmpty()) {
				g.item(slots[slot], sx, sy);
				g.itemDecorations(Render2D.font(), slots[slot], sx, sy);
				if (mouseY < box.bottom() && Render2D.hovered(mouseX, mouseY, sx, sy, SLOT - 1, SLOT - 1)) {
					hovered = slots[slot];
				}
			}
		}
		g.disableScissor();
		if (hovered != null) {
			g.setTooltipForNextFrame(Render2D.font(), hovered, mouseX, mouseY);
		}
	}

	private String matchSummary() {
		long total = 0;
		for (Tally tally : tallies.values()) {
			total += tally.total();
		}
		return compact(total) + " in " + tallies.size() + " of " + entries.size();
	}

	private String describe(Entry entry) {
		Tally tally = tallies == null ? null : tallies.get(entry);
		String contents = tally != null
				? tally.total() + " " + name(tally.top())
				: entry.usedSlots() + "/" + entry.size() + " slots";
		return coords(entry) + " · " + contents + " · " + ChestManager.ago(System.currentTimeMillis() - entry.scanned());
	}

	private static String coords(Entry entry) {
		return entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ();
	}

	private static String name(String id) {
		Item item = ChestDatabase.item(id);
		return item == null ? id : item.getDefaultInstance().getHoverName().getString();
	}

	// ------------------------------------------------------------------ input

	/** Lights up one item's chests, or with null goes back to showing the typed search. */
	private void pickItem(String id) {
		pickedItem = id;
		cm().setSearch(id == null ? new Query(SEARCH.text()) : Query.exact(id));
	}

	private void say(String message) {
		status = message == null ? "" : message;
		statusAt = System.currentTimeMillis();
	}

	/** True when the click landed on the panel, whether or not it did anything. */
	public boolean mouseClicked(double mx, double my, int button) {
		if (!contains(mx, my) || !ChestDatabase.ready() || search == null) {
			focused = false;
			return false;
		}
		Rect overlay = overlay();
		if (overlay != null && overlay.has(mx, my)) {
			focused = false;
			if (button == InputConstants.MOUSE_BUTTON_LEFT && live()) {
				clickAction(overlay, mx, my);
			}
			return true;
		}
		if (scene != null && SCENE.mouseClicked(mx, my, button)) {
			focused = false;
			draggingScene = true;
			return true;
		}
		if (search.has(mx, my)) {
			if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
				SEARCH.clear();
			} else {
				SEARCH.click(mx - (search.x() + 4));
				draggingSearch = true;
			}
			focused = true;
			return true;
		}
		focused = false;
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return true;
		}
		if (tabs.has(mx, my) && !dimensions.isEmpty()) {
			int index = Math.clamp((int) ((mx - tabs.x()) * dimensions.size() / tabs.w()), 0, dimensions.size() - 1);
			String dimension = dimensions.get(index);
			if (!dimension.equals(viewed())) {
				viewing = dimension.equals(ChestDatabase.currentDimension()) ? null : dimension;
				SCENE.select(null, false);
				SCENE.frameAll();
				scroll = 0;
			}
			return true;
		}
		for (int i = 0; i < buttons.length; i++) {
			if (buttons[i].has(mx, my)) {
				switch (i) {
					case 0 -> cm().toggleClickMode();
					case 1 -> cm().toggleAutoMode();
					case 2 -> {
						if (live()) {
							cm().rescan(this::say);
						}
					}
					default -> {
						SCENE.select(null, false);
						SCENE.frameAll();
					}
				}
				return true;
			}
		}
		int index = resultAt(mx, my);
		if (index >= 0) {
			String id = items.get(index).id();
			// a second click on the picked item puts it down again
			pickItem(id.equals(pickedItem) ? null : id);
		}
		return true;
	}

	private void clickAction(Rect overlay, double mx, double my) {
		Entry selected = SCENE.selected();
		int ax = overlay.right() - 2;
		for (String action : ACTIONS) {
			ax -= Render2D.width(action) + 6;
			if (Render2D.hovered(mx, my, ax - 2, overlay.y() + 1, Render2D.width(action) + 4, 12)) {
				if (action.equals("Forget")) {
					ChestDatabase.remove(selected.pos());
					SCENE.select(null, false);
					say("forgot " + coords(selected));
				} else {
					say(cm().rescan(selected));
				}
				return;
			}
		}
	}

	public boolean mouseDragged(double mx, double my) {
		if (draggingSearch) {
			SEARCH.drag(mx - (search.x() + 4));
			return true;
		}
		if (draggingScene) {
			SCENE.mouseDragged(mx, my);
			return true;
		}
		return false;
	}

	public void mouseReleased(double mx, double my) {
		draggingSearch = false;
		if (draggingScene) {
			draggingScene = false;
			Entry clicked = SCENE.mouseReleased(mx, my);
			if (clicked != null) {
				SCENE.select(clicked, true);
			}
		}
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!contains(mx, my) || search == null) {
			return false;
		}
		Rect overlay = overlay();
		if (overlay != null && overlay.has(mx, my)) {
			return true;
		}
		if (scene != null && SCENE.mouseScrolled(mx, my, amount)) {
			return true;
		}
		if (results.has(mx, my)) {
			scroll -= (int) (amount * ITEM_H);
		}
		return true;
	}

	/** Whether the search field owns the keyboard. */
	public boolean typing() {
		return style == Style.SKEET || focused;
	}

	public void unfocus() {
		focused = false;
	}

	public boolean charTyped(CharacterEvent event) {
		return typing() && SEARCH.charTyped(event);
	}

	/**
	 * Esc puts the picked chest down, then the picked item, then clears the search, and only then
	 * is the screen's to close with. Enter picks the top result.
	 */
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_ESCAPE) {
			if (SCENE.selected() != null) {
				SCENE.select(null, false);
				SCENE.frameAll();
				return true;
			}
			if (pickedItem != null) {
				pickItem(null);
				return true;
			}
			if (typing() && !SEARCH.isEmpty()) {
				SEARCH.clear();
				return true;
			}
			if (focused) {
				focused = false;
				return true;
			}
			return false;
		}
		if (!typing()) {
			return false;
		}
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			if (!items.isEmpty()) {
				pickItem(items.getFirst().id());
			}
			return true;
		}
		return SEARCH.keyPressed(event);
	}
}
