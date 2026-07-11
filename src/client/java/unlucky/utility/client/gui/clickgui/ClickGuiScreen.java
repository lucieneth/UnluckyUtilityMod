package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
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
import unlucky.utility.client.gui.hud.HudEditorScreen;
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
	/** The subtle diagonal hatching skeet draws over every surface. */
	private static final Identifier STRIPES = UnluckyClientMod.id("stripes");

	// Pixel-art icons: white-on-transparent PNGs, tinted at draw time (so one
	// texture serves the dim/hover/active states). Sized in GUI pixels below.
	private static final int TAB_ICON = 16;
	private static final int TB_ICON = 14;
	private static final Identifier ICON_SEARCH = icon("search");
	private static final Identifier ICON_MOUSE = icon("mouse");
	private static final Identifier ICON_HUD_EDITOR = icon("hud_editor");
	private static final Identifier ICON_FRIENDS = icon("friends");
	private static final Identifier ICON_SETTINGS = icon("settings");
	private static final Identifier ICON_CLOSE = icon("close");
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

	// floating icon toolbar pinned to the top-centre of the screen
	private static final int TB_BTN = 28;
	private static final int TB_H = 22;
	private static final int TB_PAD = 6;
	private static final String[] TB_LABELS = {"ClickGUI", "HUD Editor", "Friends (soon)", "Configs (soon)", "Close"};

	// window state survives closing the GUI
	private static int windowX = Integer.MIN_VALUE;
	private static int windowY;
	private static Category activeTab = Category.RENDER;
	/** Opens on the search tab; after that the sidebar remembers whatever you last picked. */
	private static boolean searchActive = true;
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
	private String hoveredDescription;
	/** Window-relative Y of the accent indicator; slides toward the active tab. */
	private static float indicatorRel = Float.NaN;

	public ClickGuiScreen() {
		super(Component.literal("ClickGUI"));
		openAnim.setDirection(true);
	}

	@Override
	protected void init() {
		windowWidth = Math.min(440, width - 20);
		windowHeight = Math.min(280, height - 20);
		if (windowX == Integer.MIN_VALUE) {
			windowX = (width - windowWidth) / 2;
			windowY = (height - windowHeight) / 2;
		}
		windowX = Math.clamp(windowX, 0, Math.max(width - windowWidth, 0));
		windowY = Math.clamp(windowY, 0, Math.max(height - windowHeight, 0));

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

	private int windowWidth;
	private int windowHeight;
	private boolean draggingWindow;
	private int dragOffsetX;
	private int dragOffsetY;

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
		int clamped = Math.clamp(value, 0, maxScroll(windowHeight - 4));
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
		float open = openAnim.value();
		var pose = g.pose();
		pose.pushMatrix();
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

		// thin rainbow strip at the very top (flows slowly left-to-right); soft
		// saturation so it reads as a subtle band, not a neon glow
		float stripFlow = (System.currentTimeMillis() % 8000L) / 8000.0f;
		for (int i = 0; i < windowWidth - 2; i++) {
			g.fill(windowX + 1 + i, windowY + 1, windowX + 2 + i, windowY + 3,
					ColorUtil.hsb(((float) i / (windowWidth - 2) + stripFlow) % 1.0f, 0.6f, 0.92f, 255));
		}
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

		g.enableScissor(contentX, flowTop, contentX + contentWidth, contentBottom);
		List<GroupBox> boxes = activeBoxes();
		if (boxes.isEmpty()) {
			String empty = searchActive ? "No matches" : "No modules here yet";
			Render2D.textNoShadow(g, empty, contentX + (contentWidth - Render2D.width(empty)) / 2,
					flowTop + flowHeight / 2 - 4, Theme.textDim);
			contentHeightCache = 0;
		} else {
			int scroll = Math.clamp(activeScroll(), 0, Math.max(0, contentHeightCache - flowHeight));
			int columnWidth = (contentWidth - 3 * PAD) / 2;
			int[] columnY = {flowTop + PAD - scroll, flowTop + PAD - scroll};
			for (GroupBox box : boxes) {
				int column = columnY[0] <= columnY[1] ? 0 : 1;
				int boxX = contentX + PAD + column * (columnWidth + PAD);
				box.setBounds(boxX, columnY[column], columnWidth);
				box.render(g, mouseX, mouseY);
				if (box.titleHovered(mouseX, mouseY)) {
					hoveredDescription = box.getModule().getDescription();
				}
				columnY[column] += box.getHeight() + PAD;
			}
			contentHeightCache = Math.max(columnY[0], columnY[1]) + scroll - flowTop;

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
		pose.popMatrix();

		// icon toolbar, unscaled, above the window
		drawToolbar(g, mouseX, mouseY);

		// tooltip renders unscaled, on top of everything
		if (hoveredDescription != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen()) {
			drawTooltip(g, hoveredDescription, mouseX, mouseY);
		}
		BlockPickerPopup.render(g, mouseX, mouseY);
		MobPickerPopup.render(g, mouseX, mouseY);
		ItemPickerPopup.render(g, mouseX, mouseY);
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

	// --- top icon toolbar -----------------------------------------------------

	private int toolbarWidth() {
		return TB_LABELS.length * TB_BTN + TB_PAD * 2;
	}

	private int toolbarX() {
		return (width - toolbarWidth()) / 2;
	}

	/** Index of the toolbar button under the cursor, or -1. */
	private int toolbarButtonAt(double mx, double my) {
		int cellX = toolbarX() + TB_PAD;
		for (int i = 0; i < TB_LABELS.length; i++) {
			if (Render2D.hovered(mx, my, cellX, 6, TB_BTN, TB_H)) {
				return i;
			}
			cellX += TB_BTN;
		}
		return -1;
	}

	private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int barW = toolbarWidth();
		int barX = toolbarX();
		int barY = 6;
		Render2D.roundedRect(g, barX - 1, barY - 1, barW + 2, TB_H + 2, 7, Theme.borderDark);
		Render2D.roundedRect(g, barX, barY, barW, TB_H, 6, Theme.panel);
		g.outline(barX, barY, barW, TB_H, Theme.border);

		int cellX = barX + TB_PAD;
		int cy = barY + TB_H / 2;
		for (int i = 0; i < TB_LABELS.length; i++) {
			int cx = cellX + TB_BTN / 2;
			boolean hover = Render2D.hovered(mouseX, mouseY, cellX, barY, TB_BTN, TB_H);
			boolean active = i == 0; // ClickGUI is the current view
			boolean close = i == TB_LABELS.length - 1;
			if (hover && !active) {
				Render2D.roundedRect(g, cellX + 2, barY + 2, TB_BTN - 4, TB_H - 4, 4, Theme.surface);
			}
			int color = active ? Theme.text : (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.6f) : 0xFF63636A);
			if (close && hover) {
				color = 0xFFFF5555;
			}
			switch (i) {
				case 0 -> drawCursorIcon(g, cx, cy, color);
				case 1 -> drawHudIcon(g, cx, cy, color);
				case 2 -> drawFriendsIcon(g, cx, cy, color);
				case 3 -> drawGearIcon(g, cx, cy, color);
				case 4 -> drawCloseIcon(g, cx, cy, color);
			}
			if (active) { // flowing accent underline marks the current view
				Render2D.rect(g, cx - 6, barY + TB_H - 3, 12, 2, Theme.flowingAccent(0.0f));
			}
			if (i < TB_LABELS.length - 1) { // divider
				Render2D.rect(g, cellX + TB_BTN, barY + 5, 1, TB_H - 10, Theme.border);
			}
			if (hover) {
				hoveredDescription = TB_LABELS[i];
			}
			cellX += TB_BTN;
		}
	}

	private void drawCursorIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_MOUSE, cx, cy, TB_ICON, color);
	}

	private void drawHudIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_HUD_EDITOR, cx, cy, TB_ICON, color);
	}

	private void drawFriendsIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_FRIENDS, cx, cy, TB_ICON, color);
	}

	private void drawGearIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_SETTINGS, cx, cy, TB_ICON, color);
	}

	private void drawCloseIcon(GuiGraphicsExtractor g, int cx, int cy, int color) {
		drawIcon(g, ICON_CLOSE, cx, cy, TB_ICON, color);
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

	private int maxScroll(int viewHeight) {
		return Math.max(contentHeightCache - viewHeight, 0);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (BlockPickerPopup.mouseScrolled(scrollY, height)) {
			return true;
		}
		if (MobPickerPopup.mouseScrolled(scrollY)) {
			return true;
		}
		if (ItemPickerPopup.mouseScrolled(scrollY)) {
			return true;
		}
		// an open dropdown scrolls its own list instead of the panel
		for (GroupBox box : activeBoxes()) {
			if (box.mouseScrolled(mouseX, mouseY, scrollY)) {
				return true;
			}
		}
		setActiveScroll(activeScroll() - (int) (scrollY * 24));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();

		// an open picker popup eats all clicks first
		if (BlockPickerPopup.mouseClicked(null, mx, my, event.button(), width, height)) {
			return true;
		}
		if (MobPickerPopup.mouseClicked(mx, my, event.button(), width, height)) {
			return true;
		}
		if (ItemPickerPopup.mouseClicked(mx, my, event.button(), width, height)) {
			return true;
		}

		// icon toolbar (above the window, so it gets first pick)
		int toolbarButton = toolbarButtonAt(mx, my);
		if (toolbarButton >= 0) {
			switch (toolbarButton) {
				case 0 -> { /* ClickGUI — already here */ }
				case 1 -> Minecraft.getInstance().gui.setScreen(new HudEditorScreen());
				case 2 -> { /* Friends manager — placeholder */ }
				case 3 -> { /* Config manager — placeholder */ }
				case 4 -> Minecraft.getInstance().gui.setScreen(null);
			}
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

		// anywhere else on the window drags it
		if (event.button() == 0 && Render2D.hovered(mx, my, windowX, windowY, windowWidth, windowHeight)) {
			draggingWindow = true;
			dragOffsetX = (int) mx - windowX;
			dragOffsetY = (int) my - windowY;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (BlockPickerPopup.mouseDragged(event.x(), event.y(), width, height)
				|| MobPickerPopup.mouseDragged(event.x(), event.y(), width, height)
				|| ItemPickerPopup.mouseDragged(event.x(), event.y(), width, height)) {
			return true;
		}
		if (draggingSearch) {
			SEARCH.drag(event.x() - (searchFieldX() + 4));
			return true;
		}
		if (draggingWindow) {
			windowX = Math.clamp((int) event.x() - dragOffsetX, 0, Math.max(width - windowWidth, 0));
			windowY = Math.clamp((int) event.y() - dragOffsetY, 0, Math.max(height - windowHeight, 0));
			return true;
		}
		for (GroupBox box : activeBoxes()) {
			box.mouseDragged(event.x(), event.y());
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		BlockPickerPopup.mouseReleased();
		MobPickerPopup.mouseReleased();
		ItemPickerPopup.mouseReleased();
		draggingWindow = false;
		draggingSearch = false;
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
		for (GroupBox box : activeBoxes()) {
			if (box.charTyped(event)) {
				return true;
			}
		}
		if (searchActive && SEARCH.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
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
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
