package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.gui.clickgui.component.BindComponent;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Animation;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Easing;
import unlucky.utility.client.util.Render2D;

/**
 * Skeet-style ClickGUI: one window with a rainbow strip on top, a dark tab
 * sidebar on the left (search cell + category cells) and two columns of module
 * group boxes. Hovering a module title shows its description.
 */
public class ClickGuiScreen extends Screen {
	private static final int SIDEBAR = 34;
	private static final int TAB_HEIGHT = 34;
	private static final int PAD = 10;
	private static final int SEARCH_FIELD_HEIGHT = 16;
	/**
	 * Module boxes are a fixed width — widening the window adds columns rather than
	 * stretching the boxes. A setting row is a label and a control, and stretching that
	 * to 400px just parks the control a long way from its name; the components were laid
	 * out against this width, so keeping it means resizing can't reflow them badly.
	 */
	private static final int COLUMN_W = 186;
	/** Narrowest window that still fits one full-width column with its padding. */
	private static final int MIN_W = SIDEBAR + 3 + COLUMN_W + 2 * PAD;
	/** Shortest window that still shows the whole tab strip (search cell + categories). */
	private static final int MIN_H = 10 + TAB_HEIGHT * (1 + Category.values().length) + 4;
	private static final int GRIP = 10;
	/** The window's shape in Zoom mode — the size it has always opened at. */
	private static final int BASE_W = 440;
	private static final int BASE_H = 280;
	private static final float MIN_ZOOM = 0.5f;
	private static final float MAX_ZOOM = 3.0f;
	/** The subtle diagonal hatching skeet draws over every surface. */
	private static final Identifier STRIPES = UnluckyClientMod.id("stripes");

	// Pixel-art icons: white-on-transparent PNGs, tinted at draw time (so one
	// texture serves the dim/hover/active states). Sized in GUI pixels below.
	private static final int TAB_ICON = 16;
	private static final Identifier ICON_SEARCH = icon("search");
	private static final Map<Category, Identifier> CATEGORY_ICONS = new EnumMap<>(Map.of(
			Category.COMBAT, icon("combat"),
			Category.PLAYER, icon("player"),
			Category.MOVEMENT, icon("movement"),
			Category.RENDER, icon("render"),
			Category.WORLD, icon("world"),
			Category.MISC, icon("misc")));

	private static Identifier icon(String name) {
		return UnluckyClientMod.id("textures/gui/icons/" + name + ".png");
	}

	// window state survives closing the GUI
	private static int windowX = Integer.MIN_VALUE;
	private static int windowY;
	/**
	 * The window's size in GUI units, before {@link #zoom}.
	 *
	 * <p>Two modes, two pieces of state, kept apart on purpose. Reflow resizes the window and
	 * lets the column count follow; Zoom leaves the window the shape it has always been and
	 * magnifies it. Switching between them would otherwise carry one mode's 700x400 into the
	 * other, where it means something quite different — so Reflow's size is remembered
	 * separately and restored when you switch back.
	 */
	private static int windowWidth = BASE_W;
	private static int windowHeight = BASE_H;
	private static int reflowW = BASE_W;
	private static int reflowH = BASE_H;
	private static float windowZoom = 1.0f;
	private static Category activeTab = Category.RENDER;
	/** See {@code applyDefaultPage()}: set from Theme's "GUI opens on" once per launch. */
	private static boolean searchActive = true;
	/** First open this launch applies the configured page; after that the GUI remembers. */
	private static boolean firstOpen = true;
	private static final unlucky.utility.client.ui.TextBox SEARCH = new unlucky.utility.client.ui.TextBox();
	private static final Map<Category, Integer> SCROLL = new EnumMap<>(Category.class);
	private static int searchScroll;
	private boolean draggingSearch;

	static {
		SEARCH.onChange(() -> searchScroll = 0); // new query starts at the top
	}

	private final Map<Category, List<GroupBox>> tabs = new EnumMap<>(Category.class);
	private final List<GroupBox> allBoxes = new ArrayList<>();
	private final Animation openAnim = new Animation(220, false, Easing.EXPO_OUT);
	private int contentHeightCache;
	/** Height of the scrolling flow area, as the renderer last measured it. */
	private int flowHeightCache;
	private String hoveredDescription;
	/** Window-relative Y of the accent indicator; slides toward the active tab. */
	private static float indicatorRel = Float.NaN;

	/** Screen to return to on close, or null to close to the game (the in-game default). */
	private final net.minecraft.client.gui.screens.Screen parent;

	public ClickGuiScreen() {
		this(null);
	}

	/** Opened from a menu (e.g. the title screen) — closes back to {@code parent} instead of null. */
	public ClickGuiScreen(net.minecraft.client.gui.screens.Screen parent) {
		super(Component.literal("ClickGUI"));
		this.parent = parent;
		openAnim.setDirection(true);
		applyDefaultPage();
	}

	/**
	 * Theme's "GUI opens on" (a user suggestion): the page the GUI starts on.
	 * Applied only on the FIRST open per launch — mid-session the GUI keeps
	 * remembering your last page, so bouncing to the HUD editor and back through
	 * the toolbar doesn't keep yanking you to the configured page.
	 */
	private void applyDefaultPage() {
		if (!firstOpen) {
			return;
		}
		firstOpen = false;
		String page = UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.client.ThemeModule.class).guiOpensOn.get();
		searchActive = page.equals("Search");
		for (Category category : Category.values()) {
			if (category.displayName().equals(page)) {
				activeTab = category;
			}
		}
	}

	@Override
	protected void init() {
		applyMode();
		if (zoomMode()) {
			// the window keeps its shape here, so it is the magnification that has to fit
			windowZoom = Math.clamp(windowZoom, MIN_ZOOM, Math.max(MIN_ZOOM,
					Math.min((float) (width - 20) / BASE_W, (float) (height - 20) / BASE_H)));
		} else {
			// clamp, don't reset: the size the user dragged the window to has to survive
			// closing the GUI and any screen resize, exactly as the position does
			reflowW = Math.clamp(reflowW, MIN_W, Math.max(width - 20, MIN_W));
			reflowH = Math.clamp(reflowH, MIN_H, Math.max(height - 20, MIN_H));
			applyMode();
		}
		if (windowX == Integer.MIN_VALUE) {
			windowX = (width - screenWidth()) / 2;
			windowY = (height - screenHeight()) / 2;
		}
		windowX = Math.clamp(windowX, 0, Math.max(width - screenWidth(), 0));
		windowY = Math.clamp(windowY, 0, Math.max(height - screenHeight(), 0));

		tabs.clear();
		allBoxes.clear();
		for (Category category : Category.values()) {
			List<GroupBox> boxes = new ArrayList<>();
			for (Module module : UnluckyClient.INSTANCE.modules.byCategory(category)) {
				GroupBox box = new GroupBox(module);
				boxes.add(box);
				allBoxes.add(box);
			}
			tabs.put(category, boxes);
		}
	}

	private boolean draggingWindow;
	private boolean resizing;
	private int dragOffsetX;
	private int dragOffsetY;

	/** How many fixed-width columns fit in the content area — at least one. */
	private static int columnsFor(int contentWidth) {
		return Math.max(1, (contentWidth - PAD) / (COLUMN_W + PAD));
	}

	private boolean zoomMode() {
		return UnluckyClient.INSTANCE.modules.get(ThemeModule.class).guiScaling.is("Zoom");
	}

	/** Magnification the window is drawn at; always 1 in Reflow, where size is the knob. */
	private float zoom() {
		return zoomMode() ? windowZoom : 1.0f;
	}

	/** Puts {@link #windowWidth} in step with the mode, remembering Reflow's own size. */
	private void applyMode() {
		if (zoomMode()) {
			windowWidth = BASE_W;
			windowHeight = BASE_H;
		} else {
			windowWidth = reflowW;
			windowHeight = reflowH;
		}
		// Switching mode changes the footprint under a window that is already placed, so it
		// has to be re-seated here as well as in init — otherwise going from a small Reflow
		// window to a 3x zoom leaves half the frame off the edge until the GUI is reopened.
		if (windowX != Integer.MIN_VALUE) {
			windowX = Math.clamp(windowX, 0, Math.max(width - screenWidth(), 0));
			windowY = Math.clamp(windowY, 0, Math.max(height - screenHeight(), 0));
		}
	}

	/** The window's footprint on screen, which is what has to fit and be clamped. */
	private int screenWidth() {
		return Math.round(windowWidth * zoom());
	}

	private int screenHeight() {
		return Math.round(windowHeight * zoom());
	}

	/**
	 * Screen coordinate to window coordinate.
	 *
	 * <p>Everything inside the frame is laid out and hit-tested in unzoomed units and drawn
	 * through one scale about the window's top-left corner, so the two only have to be
	 * reconciled here. The alternative — scaling every rectangle at the point it is drawn —
	 * gets the drawing right and the hit tests wrong, which is the classic way a zoomed menu
	 * ends up looking fine and clicking an inch off.
	 */
	private double toLocalX(double screenX) {
		return windowX + (screenX - windowX) / zoom();
	}

	private double toLocalY(double screenY) {
		return windowY + (screenY - windowY) / zoom();
	}

	/** Boxes shown in the current view (a category's, or the search results). */
	private List<GroupBox> activeBoxes() {
		if (searchActive) {
			String query = SEARCH.text().toLowerCase(Locale.ROOT).trim();
			if (query.isEmpty()) {
				return allBoxes;
			}
			List<GroupBox> result = new ArrayList<>();
			for (GroupBox box : allBoxes) {
				Module module = box.getModule();
				if (module.getName().toLowerCase(Locale.ROOT).contains(query)
						|| module.getCategory().displayName().toLowerCase(Locale.ROOT).contains(query)
						|| module.getDescription().toLowerCase(Locale.ROOT).contains(query)) {
					result.add(box);
				}
			}
			return result;
		}
		return tabs.get(activeTab);
	}

	private int activeScroll() {
		return searchActive ? searchScroll : SCROLL.getOrDefault(activeTab, 0);
	}

	private void setActiveScroll(int value) {
		int clamped = Math.clamp(value, 0, maxScroll());
		if (searchActive) {
			searchScroll = clamped;
		} else {
			SCROLL.put(activeTab, clamped);
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (UnluckyClient.INSTANCE.modules.get(ThemeModule.class).blur.get()) {
			g.blurBeforeThisStratum();
		}
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), (int) (0x50 * openAnim.value()) << 24);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		hoveredDescription = null;
		applyMode();
		// The toolbar and the tooltip live outside the frame and never zoom, so both readings
		// of the cursor are needed: screen for those, window-local for everything inside.
		int screenX = mouseX;
		int screenY = mouseY;
		mouseX = (int) Math.round(toLocalX(mouseX));
		mouseY = (int) Math.round(toLocalY(mouseY));
		float open = openAnim.value();
		var pose = g.pose();
		pose.pushMatrix();
		float zoom = zoom();
		if (zoom != 1.0f) {
			pose.translate(windowX, windowY);
			pose.scale(zoom, zoom);
			pose.translate(-windowX, -windowY);
		}
		float scale = 0.92f + 0.08f * open;
		pose.translate(windowX + windowWidth / 2.0f, windowY + windowHeight / 2.0f);
		pose.scale(scale, scale);
		pose.translate(-(windowX + windowWidth / 2.0f), -(windowY + windowHeight / 2.0f));

		// skeet-style frame: 2px dark-navy outer border + a 1px inner bevel highlight
		// gives the window real depth against the world (not a flat black outline)
		Render2D.rect(g, windowX - 2, windowY - 2, windowWidth + 4, windowHeight + 4, Theme.frame);
		Render2D.rect(g, windowX, windowY, windowWidth, windowHeight, Theme.window);

		// clean sidebar + content backgrounds — skeet only hatches the SELECTED tab
		// (done in the tab loop below), so the body itself stays plain dark
		int sidebarTop = windowY + 4;
		Render2D.rect(g, windowX + 1, sidebarTop, SIDEBAR, windowHeight - 5, Theme.sidebar);
		Render2D.rect(g, windowX + 1 + SIDEBAR, sidebarTop, 1, windowHeight - 5, 0xFF1E1E1E);
		g.outline(windowX, windowY, windowWidth, windowHeight, Theme.frameBevel);

		// thin strip at the very top, in whichever style Theme is set to
		drawTopBar(g);
		// crisp dark seam seats the bar cleanly against the content below it
		Render2D.rect(g, windowX + 1, windowY + 3, windowWidth - 2, 1, Theme.borderDark);

		// search cell, then the category cells
		int tabY = sidebarTop + 6;
		boolean searchHover = Render2D.hovered(mouseX, mouseY, windowX + 1, tabY, SIDEBAR, TAB_HEIGHT);
		if (searchActive) {
			drawActiveTab(g, tabY);
		}
		drawSearchIcon(g, windowX + 1 + SIDEBAR / 2, tabY + TAB_HEIGHT / 2,
				searchActive ? Theme.text : (searchHover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : 0xFF525258));
		tabY += TAB_HEIGHT;

		for (Category category : Category.values()) {
			boolean active = !searchActive && category == activeTab;
			boolean hover = Render2D.hovered(mouseX, mouseY, windowX + 1, tabY, SIDEBAR, TAB_HEIGHT);
			if (active) {
				drawActiveTab(g, tabY);
			}
			int iconColor = active ? Theme.text : (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.5f) : 0xFF525258);
			drawCategoryIcon(g, category, windowX + 1 + SIDEBAR / 2, tabY + TAB_HEIGHT / 2, iconColor);
			if (hover) {
				hoveredDescription = category.displayName();
			}
			tabY += TAB_HEIGHT;
		}

		// flowing accent indicator that slides to whichever cell is active (search
		// cell, or the current category). Tracked window-relative so dragging the
		// window doesn't make it lag behind the tabs.
		int searchCellY = sidebarTop + 6;
		int activeCellY = searchActive ? searchCellY : searchCellY + TAB_HEIGHT * (1 + activeTab.ordinal());
		float indicatorTarget = activeCellY - windowY;
		if (Float.isNaN(indicatorRel)) {
			indicatorRel = indicatorTarget;
		}
		indicatorRel += (indicatorTarget - indicatorRel) * 0.3f;
		int indY = windowY + Math.round(indicatorRel);
		Render2D.verticalGradient(g, windowX + 1 + SIDEBAR - 2, indY + 8, 2, TAB_HEIGHT - 16,
				Theme.flowingAccent(0.0f), Theme.flowingAccent(0.5f));

		// content region
		int contentX = windowX + 1 + SIDEBAR + 1;
		int contentTop = windowY + 3;
		int contentWidth = windowWidth - SIDEBAR - 3;
		int contentBottom = windowY + windowHeight - 1;

		// diagonal hatching is the content-area background; module boxes paint an
		// opaque interior over it, so the stripes only show in the gaps between them
		g.blitSprite(RenderPipelines.GUI_TEXTURED, STRIPES, contentX, sidebarTop, contentWidth, contentBottom - sidebarTop);

		int flowTop = contentTop;
		if (searchActive) {
			drawSearchField(g, contentX + PAD, contentTop + 4, contentWidth - 2 * PAD);
			flowTop = contentTop + SEARCH_FIELD_HEIGHT + 4;
		}
		int flowHeight = contentBottom - flowTop;
		flowHeightCache = flowHeight; // what the scroll clamp must measure against

		g.enableScissor(contentX, flowTop, contentX + contentWidth, contentBottom);
		List<GroupBox> boxes = activeBoxes();
		if (boxes.isEmpty()) {
			String empty = searchActive ? "No matches" : "No modules here yet";
			Render2D.textNoShadow(g, empty, contentX + (contentWidth - Render2D.width(empty)) / 2,
					flowTop + flowHeight / 2 - 4, Theme.textDim);
			contentHeightCache = 0;
		} else {
			int scroll = Math.clamp(activeScroll(), 0, maxScroll());
			int columns = columnsFor(contentWidth);
			// whatever width the columns don't use is shared out between the gutters,
			// so the grid stays centred instead of leaving one ragged strip on the right
			int gutter = PAD + (contentWidth - columns * COLUMN_W - (columns + 1) * PAD) / (columns + 1);
			int[] columnY = new int[columns];
			Arrays.fill(columnY, flowTop + PAD - scroll);
			for (GroupBox box : boxes) {
				// shortest column wins, so boxes of different heights still pack tightly
				int column = 0;
				for (int c = 1; c < columns; c++) {
					if (columnY[c] < columnY[column]) {
						column = c;
					}
				}
				int boxX = contentX + gutter + column * (COLUMN_W + gutter);
				box.setBounds(boxX, columnY[column], COLUMN_W);
				box.render(g, mouseX, mouseY);
				if (box.titleHovered(mouseX, mouseY)) {
					hoveredDescription = box.getModule().getDescription();
				}
				columnY[column] += box.getHeight() + PAD;
			}
			int lowest = columnY[0];
			for (int c = 1; c < columns; c++) {
				lowest = Math.max(lowest, columnY[c]);
			}
			contentHeightCache = lowest + scroll - flowTop;

			if (contentHeightCache > flowHeight) {
				int barHeight = Math.max(flowHeight * flowHeight / contentHeightCache, 12);
				int barTravel = flowHeight - barHeight;
				int max = Math.max(0, contentHeightCache - flowHeight);
				int barY = flowTop + (max == 0 ? 0 : barTravel * scroll / max);
				Render2D.rect(g, contentX + contentWidth - 3, flowTop, 2, flowHeight, Theme.surface);
				Render2D.verticalGradient(g, contentX + contentWidth - 3, barY, 2, barHeight, Theme.accent1, Theme.accent2);
			}
		}
		g.disableScissor();

		// resize grip: three diagonal ticks in the bottom-right corner, same as the console
		boolean gripHover = resizing
				|| Render2D.hovered(mouseX, mouseY, gripX(), gripY(), GRIP, GRIP);
		int gripColor = gripHover ? Theme.text : Theme.textDim;
		for (int i = 0; i < 3; i++) {
			Render2D.rect(g, windowX + windowWidth - 3 - i * 3, windowY + windowHeight - 3, 2, 1, gripColor);
			Render2D.rect(g, windowX + windowWidth - 3, windowY + windowHeight - 3 - i * 3, 1, 2, gripColor);
		}
		pose.popMatrix();

		// icon toolbar, unscaled, above the window
		String toolbarLabel = ClickGuiToolbar.draw(g, screenX, screenY, width, ClickGuiToolbar.CLICKGUI);
		if (toolbarLabel != null) {
			hoveredDescription = toolbarLabel;
		}

		// tooltip renders unscaled, on top of everything
		if (hoveredDescription != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen() && !BrewQueuePopup.isOpen()) {
			drawTooltip(g, hoveredDescription, screenX, screenY);
		}
		BlockPickerPopup.render(g, screenX, screenY);
		MobPickerPopup.render(g, screenX, screenY);
		ItemPickerPopup.render(g, screenX, screenY);
		BrewQueuePopup.render(g, screenX, screenY);
	}

	/**
	 * The strip along the top of the window, in the style Theme's "Top bar" selects.
	 *
	 * <p>Rainbow keeps the soft saturation it has always had — a full-value wheel up here
	 * reads as a neon glow and fights the window for attention. Accent is the same band
	 * drawn out of your two accent colors instead of the spectrum, and it ping-pongs across
	 * the width so the two ends meet in the same color rather than at a hard seam.
	 */
	private void drawTopBar(GuiGraphicsExtractor g) {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		int span = windowWidth - 2;
		if (theme.barStyle.is("Static")) {
			Render2D.rect(g, windowX + 1, windowY + 1, span, 2, theme.barColor.get());
			return;
		}
		boolean rainbow = theme.barStyle.is("Rainbow");
		float period = Math.max(1000.0f, 8000.0f / Math.max(0.05f, theme.barSpeed.getFloat()));
		float flow = (System.currentTimeMillis() % (long) period) / period;
		for (int i = 0; i < span; i++) {
			float t = ((float) i / span + flow) % 1.0f;
			g.fill(windowX + 1 + i, windowY + 1, windowX + 2 + i, windowY + 3,
					rainbow ? ColorUtil.hsb(t, 0.6f, 0.92f, 255)
							: Theme.accent(t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f));
		}
	}

	/** The selected tab cell: lighter body + skeet hatching + top/bottom border edges. */
	private void drawActiveTab(GuiGraphicsExtractor g, int tabY) {
		Render2D.rect(g, windowX + 1, tabY, SIDEBAR + 1, TAB_HEIGHT, Theme.window);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, STRIPES, windowX + 1, tabY, SIDEBAR + 1, TAB_HEIGHT);
		Render2D.rect(g, windowX + 1, tabY, SIDEBAR + 1, 1, Theme.frameBevel);
		Render2D.rect(g, windowX + 1, tabY + TAB_HEIGHT - 1, SIDEBAR + 1, 1, Theme.frameBevel);
	}

	/**
	 * Draws a white-on-transparent icon texture centered at (cx, cy), multiplied by
	 * {@code color} — so the same PNG serves the dim / hover / active tints. Passing
	 * the whole texture size as the "texture dimensions" makes the UVs span 0..1, so
	 * the icon scales to {@code size} regardless of the PNG's native resolution.
	 */
	private void drawIcon(GuiGraphicsExtractor g, Identifier icon, int cx, int cy, int size, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, icon, cx - size / 2, cy - size / 2,
				0.0f, 0.0f, size, size, size, size, color);
	}

	private void drawSearchIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_SEARCH, cx, cy, TAB_ICON, color);
	}

	private void drawCategoryIcon(GuiGraphicsExtractor g, Category category, int cx, int cy, int color) {
		drawIcon(g, CATEGORY_ICONS.get(category), cx, cy, TAB_ICON, color);
	}

	private void drawSearchField(GuiGraphicsExtractor g, int x, int y, int w) {
		Render2D.rect(g, x - 1, y - 1, w + 2, SEARCH_FIELD_HEIGHT + 2, Theme.borderDark);
		Render2D.rect(g, x, y, w, SEARCH_FIELD_HEIGHT, Theme.surface);
		SEARCH.render(g, x + 4, y + 4, w - 8, true, "Search modules...");
	}

	// field geometry mirrored from extractRenderState's content-region math, so
	// clicks can be mapped onto the text
	private int searchFieldX() {
		return windowX + 1 + SIDEBAR + 1 + PAD;
	}

	private int searchFieldY() {
		return windowY + 3 + 4;
	}

	private int searchFieldW() {
		return windowWidth - SIDEBAR - 3 - 2 * PAD;
	}

	private int gripX() {
		return windowX + windowWidth - GRIP;
	}

	private int gripY() {
		return windowY + windowHeight - GRIP;
	}

	private void drawTooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
		int w = Render2D.width(text);
		int tx = mouseX + 10;
		int ty = mouseY - 4;
		if (tx + w + 6 > g.guiWidth()) {
			tx = mouseX - w - 12;
		}
		Render2D.rect(g, tx - 3, ty - 3, w + 6, 15, Theme.panel);
		g.outline(tx - 3, ty - 3, w + 6, 15, Theme.border);
		Render2D.textNoShadow(g, text, tx, ty, Theme.text);
	}

	/**
	 * How far the content can scroll, against the same view height the renderer uses.
	 *
	 * <p>It used to be measured against {@code windowHeight - 4} while the renderer
	 * measured against the flow area, and on the search page those differ by the height
	 * of the search field: scrolling stopped ~20px early and the last row of a tall
	 * module box could not be reached. Plain tabs never showed it, because there the two
	 * happen to be equal.
	 */
	private int maxScroll() {
		return Math.max(contentHeightCache - flowHeightCache, 0);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (BlockPickerPopup.mouseScrolled(scrollY, height)) {
			return true;
		}
		if (MobPickerPopup.mouseScrolled(scrollY)) {
			return true;
		}
		if (BrewQueuePopup.mouseScrolled(scrollY)) {
			return true;
		}
		if (ItemPickerPopup.mouseScrolled(scrollY)) {
			return true;
		}
		// an open dropdown scrolls its own list instead of the panel
		applyMode();
		for (GroupBox box : activeBoxes()) {
			if (box.mouseScrolled(toLocalX(mouseX), toLocalY(mouseY), scrollY)) {
				return true;
			}
		}
		setActiveScroll(activeScroll() - (int) (scrollY * 24));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		applyMode();
		// sx/sy address the unscaled surfaces — the popups and the toolbar; mx/my address
		// everything inside the frame, which zoom moves out from under the cursor
		double sx = event.x();
		double sy = event.y();
		double mx = toLocalX(sx);
		double my = toLocalY(sy);

		// an open picker popup eats all clicks first
		if (BlockPickerPopup.mouseClicked(null, sx, sy, event.button(), width, height)) {
			return true;
		}
		if (MobPickerPopup.mouseClicked(sx, sy, event.button(), width, height)) {
			return true;
		}
		if (BrewQueuePopup.mouseClicked(sx, sy, event.button(), width, height)) {
			return true;
		}
		if (ItemPickerPopup.mouseClicked(sx, sy, event.button(), width, height)) {
			return true;
		}

		// icon toolbar (above the window, so it gets first pick)
		int toolbarButton = ClickGuiToolbar.buttonAt(sx, sy, width);
		if (toolbarButton >= 0) {
			if (toolbarButton != ClickGuiToolbar.CLICKGUI) {
				ClickGuiToolbar.activate(toolbarButton, parent);
			}
			return true;
		}

		// resize grip, ahead of the boxes it sits on top of
		if (event.button() == 0 && Render2D.hovered(mx, my, gripX(), gripY(), GRIP, GRIP)) {
			resizing = true;
			return true;
		}

		// search cell (sidebarTop + 6, matching the render)
		int tabY = windowY + 4 + 6;
		if (Render2D.hovered(mx, my, windowX + 1, tabY, SIDEBAR, TAB_HEIGHT)) {
			searchActive = true;
			SEARCH.selectAll(); // returning to search offers the old query up for replacement
			return true;
		}
		tabY += TAB_HEIGHT;

		// category cells
		for (Category category : Category.values()) {
			if (Render2D.hovered(mx, my, windowX + 1, tabY, SIDEBAR, TAB_HEIGHT)) {
				activeTab = category;
				searchActive = false;
				return true;
			}
			tabY += TAB_HEIGHT;
		}

		// search field: place the caret / start a drag-selection
		if (searchActive && event.button() == 0
				&& Render2D.hovered(mx, my, searchFieldX(), searchFieldY(), searchFieldW(), SEARCH_FIELD_HEIGHT)) {
			SEARCH.click(mx - (searchFieldX() + 4));
			draggingSearch = true;
			return true;
		}

		// content
		for (GroupBox box : activeBoxes()) {
			if (box.mouseClicked(mx, my, event.button())) {
				return true;
			}
		}

		// anywhere else on the window drags it. The grab offset is in screen units — the
		// window's position is, and a zoomed offset would make it jump on the first drag.
		if (event.button() == 0 && Render2D.hovered(mx, my, windowX, windowY, windowWidth, windowHeight)) {
			draggingWindow = true;
			dragOffsetX = (int) sx - windowX;
			dragOffsetY = (int) sy - windowY;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		applyMode();
		double sx = event.x();
		double sy = event.y();
		if (BlockPickerPopup.mouseDragged(sx, sy, width, height)
				|| MobPickerPopup.mouseDragged(sx, sy, width, height)
				|| ItemPickerPopup.mouseDragged(sx, sy, width, height)
				|| BrewQueuePopup.mouseDragged(sx, sy, width, height)) {
			return true;
		}
		if (resizing) {
			// The grip tracks the cursor in screen units either way; what changes is what it
			// writes to — the window's size, or the magnification of a fixed-shape window.
			if (zoomMode()) {
				float fitting = Math.min((float) (width - windowX) / BASE_W,
						(float) (height - windowY) / BASE_H);
				windowZoom = Math.clamp((float) (sx - windowX + 2) / BASE_W, MIN_ZOOM,
						Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, fitting)));
			} else {
				reflowW = Math.clamp((int) sx - windowX + 2, MIN_W, Math.max(width - windowX, MIN_W));
				reflowH = Math.clamp((int) sy - windowY + 2, MIN_H, Math.max(height - windowY, MIN_H));
				applyMode();
			}
			// a taller window can leave the view scrolled past the end of a short page
			setActiveScroll(activeScroll());
			return true;
		}
		if (draggingSearch) {
			SEARCH.drag(toLocalX(sx) - (searchFieldX() + 4));
			return true;
		}
		if (draggingWindow) {
			windowX = Math.clamp((int) sx - dragOffsetX, 0, Math.max(width - screenWidth(), 0));
			windowY = Math.clamp((int) sy - dragOffsetY, 0, Math.max(height - screenHeight(), 0));
			return true;
		}
		for (GroupBox box : activeBoxes()) {
			box.mouseDragged(toLocalX(sx), toLocalY(sy));
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		BlockPickerPopup.mouseReleased();
		MobPickerPopup.mouseReleased();
		ItemPickerPopup.mouseReleased();
		BrewQueuePopup.mouseReleased();
		draggingWindow = false;
		draggingSearch = false;
		resizing = false;
		for (GroupBox box : activeBoxes()) {
			box.mouseReleased();
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (ItemPickerPopup.isOpen()) {
			return ItemPickerPopup.charTyped(event);
		}
		if (BrewQueuePopup.isOpen()) {
			return BrewQueuePopup.charTyped(event);
		}
		for (GroupBox box : activeBoxes()) {
			if (box.charTyped(event)) {
				return true;
			}
		}
		// swallow the charTyped that trails a just-completed keybind so the search
		// field doesn't type the bound letter
		if (BindComponent.recentlyBound()) {
			return true;
		}
		if (searchActive && SEARCH.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	/**
	 * True when the keyboard belongs to a text field here — the search cell (which
	 * owns the keys whenever its tab is up) or any open picker's filter. InventoryMove
	 * asks before turning WASD into movement, so typing always wins.
	 */
	public boolean isTyping() {
		return searchActive || BlockPickerPopup.isOpen() || MobPickerPopup.isOpen() || ItemPickerPopup.isOpen()
				|| BrewQueuePopup.isOpen();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BlockPickerPopup.isOpen() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			BlockPickerPopup.close();
			return true;
		}
		if (MobPickerPopup.isOpen() && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			MobPickerPopup.close();
			return true;
		}
		if (ItemPickerPopup.isOpen()) {
			// its search field owns the keyboard while it's up
			if (ItemPickerPopup.keyPressed(event)) {
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) {
				ItemPickerPopup.close();
			}
			return true;
		}
		if (BrewQueuePopup.isOpen()) {
			if (BrewQueuePopup.keyPressed(event)) {
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) {
				BrewQueuePopup.close();
			}
			return true;
		}
		// boxes first so a focused text field swallows its keys (incl. Ctrl+F)
		for (GroupBox box : activeBoxes()) {
			if (box.keyPressed(event)) {
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown()) {
			searchActive = true;
			SEARCH.selectAll();
			return true;
		}
		if (searchActive) {
			if (SEARCH.keyPressed(event)) {
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE && !SEARCH.isEmpty()) {
				SEARCH.clear();
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == UnluckyClient.INSTANCE.clickGuiKey) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		UnluckyClient.INSTANCE.config.save();
		if (parent != null) {
			minecraft.gui.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
