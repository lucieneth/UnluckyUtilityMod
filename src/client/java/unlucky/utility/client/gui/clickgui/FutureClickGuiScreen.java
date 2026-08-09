package unlucky.utility.client.gui.clickgui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.BlursBackground;
import unlucky.utility.client.gui.FrameBlur;
import unlucky.utility.client.gui.clickgui.component.BindComponent;
import unlucky.utility.client.gui.clickgui.component.BooleanComponent;
import unlucky.utility.client.gui.clickgui.component.ColorComponent;
import unlucky.utility.client.gui.clickgui.component.GuiComponent;
import unlucky.utility.client.gui.clickgui.component.ModeComponent;
import unlucky.utility.client.gui.clickgui.component.ScrollingText;
import unlucky.utility.client.gui.clickgui.component.SliderComponent;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.Setting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Classic Future-inspired ClickGUI. It deliberately does not share layout code
 * with {@link ClickGuiScreen}: Future's identity is all categories visible at
 * once, whereas Skeet is a resizable, tabbed window. Both screens consume the
 * same settings and components, so their behaviour cannot drift apart.
 */
public class FutureClickGuiScreen extends Screen implements BlursBackground {
	/* Future's original columns are compact 100x12 GUI-pixel controls. */
	private static final int MARGIN = 2;
	private static final int GAP = 2;
	private static final int PANEL_W = 100;
	private static final int MIN_W = 96;
	private static final int HEADER_H = 12;
	private static final int ROW_H = 14;
	private static final int PANEL_TOP = 2;
	private static final int PANEL_BOTTOM = 4;
	/* v4 intentionally discards positions saved for the old/interim layouts. */
	private static final int LAYOUT_VERSION = 4;
	/** Classic GUIs are arranged by the player, not reflowed into a fixed dashboard. */
	private static final Map<Category, Position> POSITIONS = new EnumMap<>(Category.class);
	private static Position searchPosition;
	/*
	 * Future is glass over the game, not a modal dialog.  The stock GUI blur API
	 * is deliberately frame-wide (it has no clipped/per-rectangle variant), so
	 * using it here made every pixel behind the ClickGUI blurry.  Keep the world
	 * sharp and let these translucent layers provide the Future look instead.
	 */
	/* Matches Future's visible "Window Alpha 121" backdrop setting. */
	private static final int FUTURE_WINDOW_ALPHA = 121;
	private static final int FUTURE_PANEL_ALPHA = 153;

	private final Screen parent;
	private final Map<Category, FuturePanel> panels = new EnumMap<>(Category.class);
	private FutureSearchPanel searchPanel;
	private String hoveredDescription;
	private FuturePanel draggingPanel;
	private boolean draggingSearchPanel;
	private int dragOffsetX, dragOffsetY;

	public FutureClickGuiScreen() {
		this(null);
	}

	public FutureClickGuiScreen(Screen parent) {
		super(Component.literal("Future ClickGUI"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		panels.clear();
		ensureDefaultPositions();
		for (Category category : Category.values()) {
			panels.put(category, new FuturePanel(category, UnluckyClient.INSTANCE.modules.byCategory(category)));
		}
		searchPanel = new FutureSearchPanel(UnluckyClient.INSTANCE.modules.all());
	}

	private void ensureDefaultPositions() {
		int available = width - MARGIN * 2 - GAP * (Category.values().length - 1);
		int panelWidth = Math.max(MIN_W, Math.min(PANEL_W, available / Category.values().length));
		// Future starts its strip at the top-left rather than centering it.
		int x = MARGIN;
		for (Category category : Category.values()) {
			POSITIONS.putIfAbsent(category, new Position(x, PANEL_TOP));
			x += panelWidth + GAP;
		}
		if (searchPosition == null) searchPosition = new Position(x, PANEL_TOP);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		// The GuiRenderer mixin converts this stock full-frame blur into the
		// registered panel rectangles before this screen is composited.
		if (theme != null && theme.blur.get() && FrameBlur.claim(g)) {
			FuturePanelBlur.beginFrame();
		}
		// Future keeps its distinct full-screen black shade over the sharp world.
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), ColorUtil.withAlpha(0xFF000000, FUTURE_WINDOW_ALPHA));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		hoveredDescription = null;
		ScrollingText.beginFutureRender();
		try {
			for (Category category : Category.values()) {
				FuturePanel panel = panels.get(category);
				Position position = POSITIONS.get(category);
				panel.setBounds(position.x, position.y, Math.max(MIN_W, Math.min(PANEL_W, width - 2 * MARGIN)), width, height);
				panel.render(g, mouseX, mouseY);
				if (panel.hoveredModuleDescription != null) {
					hoveredDescription = panel.hoveredModuleDescription;
				}
			}
			searchPanel.setBounds(searchPosition.x, searchPosition.y,
					Math.max(MIN_W, Math.min(PANEL_W, width - 2 * MARGIN)), width, height);
			searchPanel.render(g, mouseX, mouseY);
			if (searchPanel.hoveredModuleDescription != null) {
				hoveredDescription = searchPanel.hoveredModuleDescription;
			}
		} finally {
			ScrollingText.endFutureRender();
		}
		String toolbarLabel = FutureClickGuiToolbar.draw(g, mouseX, mouseY, width, height,
				FutureClickGuiToolbar.CLICKGUI);

		if (hoveredDescription != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen() && !BrewQueuePopup.isOpen()) {
			drawTooltip(g, hoveredDescription, mouseX, mouseY);
		}
		BlockPickerPopup.render(g, mouseX, mouseY);
		MobPickerPopup.render(g, mouseX, mouseY);
		ItemPickerPopup.render(g, mouseX, mouseY);
		BrewQueuePopup.render(g, mouseX, mouseY);
		if (toolbarLabel != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen() && !BrewQueuePopup.isOpen()) {
			FutureClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
		}
	}

	private void drawTooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
		int w = Render2D.width(text);
		int x = mouseX + 10;
		if (x + w + 6 > width) x = mouseX - w - 12;
		Render2D.rect(g, x - 3, mouseY - 7, w + 6, 15, FuturePalette.chromeSurface(208));
		g.outline(x - 3, mouseY - 7, w + 6, 15, ColorUtil.withAlpha(FuturePalette.accent(), 212));
		Render2D.textNoShadow(g, text, x, mouseY - 4, 0xFFF2E8E8);
	}

	private static int futureColor() {
		return FuturePalette.accent();
	}

	/**
	 * The important part of the Future look: this is smoky, translucent glass,
	 * not an accent rectangle behind every row. It follows the configured
	 * Future hue at a deliberately dark, low-saturation value so disabled rows
	 * remain readable rather than looking like opaque plastic.
	 */
	private static int futurePanelBody() {
		return FuturePalette.panelSurface(FUTURE_PANEL_ALPHA);
	}

	/** The reference uses the raw accent at a translucent 44% for enabled rows. */
	private static int futureEnabledFill(int accent) {
		return ColorUtil.withAlpha(accent, 112);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (BlockPickerPopup.mouseScrolled(scrollY, height) || MobPickerPopup.mouseScrolled(scrollY)
				|| BrewQueuePopup.mouseScrolled(scrollY) || ItemPickerPopup.mouseScrolled(scrollY)) return true;
		if (searchPanel.mouseScrolled(mouseX, mouseY, scrollY)) return true;
		for (FuturePanel panel : panels.values()) {
			if (panel.mouseScrolled(mouseX, mouseY, scrollY)) return true;
		}
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double x = event.x(), y = event.y();
		int toolbarButton = FutureClickGuiToolbar.buttonAt(x, y, width, height);
		if (toolbarButton >= 0) {
			if (toolbarButton == FutureClickGuiToolbar.CLOSE) {
				onClose();
			} else if (toolbarButton != FutureClickGuiToolbar.CLICKGUI) {
				FutureClickGuiToolbar.activate(toolbarButton, parent);
			}
			return true;
		}
		if (BlockPickerPopup.mouseClicked(null, x, y, event.button(), width, height)
				|| MobPickerPopup.mouseClicked(x, y, event.button(), width, height)
				|| BrewQueuePopup.mouseClicked(x, y, event.button(), width, height)
				|| ItemPickerPopup.mouseClicked(x, y, event.button(), width, height)) return true;
		if (event.button() == 0) {
			if (searchPanel.headerHovered(x, y)) {
				searchPanel.unfocus();
				draggingSearchPanel = true;
				dragOffsetX = (int) x - searchPanel.x;
				dragOffsetY = (int) y - searchPanel.y;
				return true;
			}
			for (FuturePanel panel : panels.values()) {
				if (panel.headerHovered(x, y)) {
					searchPanel.unfocus();
					draggingPanel = panel;
					dragOffsetX = (int) x - panel.x;
					dragOffsetY = (int) y - panel.y;
					return true;
				}
			}
		}
		if (searchPanel.mouseClicked(x, y, event.button())) return true;
		for (FuturePanel panel : panels.values()) {
			if (panel.mouseClicked(x, y, event.button())) return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double x = event.x(), y = event.y();
		if (BlockPickerPopup.mouseDragged(x, y, width, height) || MobPickerPopup.mouseDragged(x, y, width, height)
				|| ItemPickerPopup.mouseDragged(x, y, width, height) || BrewQueuePopup.mouseDragged(x, y, width, height)) return true;
		if (draggingPanel != null) {
			draggingPanel.moveTo((int) x - dragOffsetX, (int) y - dragOffsetY, width, height);
			return true;
		}
		if (draggingSearchPanel) {
			searchPanel.moveTo((int) x - dragOffsetX, (int) y - dragOffsetY, width, height);
			return true;
		}
		searchPanel.mouseDragged(x, y);
		for (FuturePanel panel : panels.values()) panel.mouseDragged(x, y);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		BlockPickerPopup.mouseReleased();
		MobPickerPopup.mouseReleased();
		ItemPickerPopup.mouseReleased();
		BrewQueuePopup.mouseReleased();
		draggingPanel = null;
		draggingSearchPanel = false;
		searchPanel.mouseReleased();
		for (FuturePanel panel : panels.values()) panel.mouseReleased();
		return super.mouseReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (BlockPickerPopup.isOpen()) return BlockPickerPopup.charTyped(event);
		if (ItemPickerPopup.isOpen()) return ItemPickerPopup.charTyped(event);
		if (BrewQueuePopup.isOpen()) return BrewQueuePopup.charTyped(event);
		if (searchPanel.charTyped(event)) return true;
		for (FuturePanel panel : panels.values()) if (panel.charTyped(event)) return true;
		return BindComponent.recentlyBound() || super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BlockPickerPopup.isOpen()) {
			if (BlockPickerPopup.keyPressed(event)) return true;
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) BlockPickerPopup.close();
			return true;
		}
		if (MobPickerPopup.isOpen() && event.key() == GLFW.GLFW_KEY_ESCAPE) { MobPickerPopup.close(); return true; }
		if (ItemPickerPopup.isOpen()) {
			if (ItemPickerPopup.keyPressed(event)) return true;
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) ItemPickerPopup.close();
			return true;
		}
		if (BrewQueuePopup.isOpen()) {
			if (BrewQueuePopup.keyPressed(event)) return true;
			if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) BrewQueuePopup.close();
			return true;
		}
		if (searchPanel.keyPressed(event)) return true;
		for (FuturePanel panel : panels.values()) if (panel.keyPressed(event)) return true;
		if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown()) {
			searchPanel.focus();
			return true;
		}
		int key = event.key();
		if (key == GLFW.GLFW_KEY_ESCAPE
				|| (key != GLFW.GLFW_KEY_UNKNOWN && key == UnluckyClient.INSTANCE.clickGuiKey)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Used by InventoryMove so focused HEX/RGB and string fields still own WASD. */
	public boolean isTyping() {
		if (BlockPickerPopup.isOpen() || MobPickerPopup.isOpen() || ItemPickerPopup.isOpen() || BrewQueuePopup.isOpen()) return true;
		if (searchPanel.typing()) return true;
		for (FuturePanel panel : panels.values()) if (panel.typing()) return true;
		return false;
	}

	@Override
	public void onClose() {
		UnluckyClient.INSTANCE.config.save();
		if (parent != null) minecraft.gui.setScreen(parent);
		else super.onClose();
	}

	@Override
	public boolean isPauseScreen() { return false; }

	/** Stored in normal configs and named profiles, exactly like HUD positions. */
	public static JsonObject positionsJson() {
		JsonObject result = new JsonObject();
		result.addProperty("version", LAYOUT_VERSION);
		for (Category category : Category.values()) {
			Position position = POSITIONS.get(category);
			if (position == null) continue;
			JsonObject point = new JsonObject();
			point.addProperty("x", position.x);
			point.addProperty("y", position.y);
			result.add(category.name(), point);
		}
		if (searchPosition != null) {
			JsonObject point = new JsonObject();
			point.addProperty("x", searchPosition.x);
			point.addProperty("y", searchPosition.y);
			result.add("search", point);
		}
		return result;
	}

	public static void loadPositions(JsonObject saved) {
		// Older layouts used wide columns and different offsets. Ignore their
		// geometry so the compact Future baseline starts clean.
		if (!saved.has("version") || saved.get("version").getAsInt() != LAYOUT_VERSION) {
			POSITIONS.clear();
			searchPosition = null;
			return;
		}
		for (Category category : Category.values()) {
			if (!saved.has(category.name()) || !saved.get(category.name()).isJsonObject()) continue;
			JsonObject point = saved.getAsJsonObject(category.name());
			if (point.has("x") && point.has("y")) {
				POSITIONS.put(category, new Position(point.get("x").getAsInt(), point.get("y").getAsInt()));
			}
		}
		if (saved.has("search") && saved.get("search").isJsonObject()) {
			JsonObject point = saved.getAsJsonObject("search");
			if (point.has("x") && point.has("y")) {
				searchPosition = new Position(point.get("x").getAsInt(), point.get("y").getAsInt());
			}
		}
	}

	private static final class Position {
		int x, y;
		Position(int x, int y) { this.x = x; this.y = y; }
	}

	/** A compact Future column whose first row is an editor and whose remaining rows are matches. */
	private static final class FutureSearchPanel {
		private final TextBox input = new TextBox();
		private final List<FutureModule> allModules = new ArrayList<>();
		private final List<FutureModule> matches = new ArrayList<>();
		private int x, y, width, height, scroll;
		private boolean focused;
		private boolean draggingText;
		private String hoveredModuleDescription;

		FutureSearchPanel(List<Module> modules) {
			for (Module module : modules) allModules.add(new FutureModule(module));
			input.onChange(() -> {
				scroll = 0;
				refreshMatches();
			});
			refreshMatches();
		}

		private void refreshMatches() {
			matches.clear();
			String query = input.text().toLowerCase(Locale.ROOT).trim();
			if (query.isEmpty()) return;
			for (FutureModule candidate : allModules) {
				Module module = candidate.module;
				if (module.getName().toLowerCase(Locale.ROOT).contains(query)
						|| module.getCategory().displayName().toLowerCase(Locale.ROOT).contains(query)
						|| module.getDescription().toLowerCase(Locale.ROOT).contains(query)) {
					matches.add(candidate);
				}
			}
		}

		void setBounds(int x, int y, int width, int screenWidth, int screenHeight) {
			this.width = width;
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			this.height = Math.min(HEADER_H + 2 + contentHeight(), Math.max(55, screenHeight - this.y - PANEL_BOTTOM));
		}

		boolean headerHovered(double mouseX, double mouseY) {
			return Render2D.hovered(mouseX, mouseY, x, y, width, HEADER_H);
		}

		void moveTo(int x, int y, int screenWidth, int screenHeight) {
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			searchPosition.x = this.x;
			searchPosition.y = this.y;
		}

		private int contentHeight() {
			return ROW_H + resultContentHeight();
		}

		private int resultContentHeight() {
			if (!input.isEmpty() && matches.isEmpty()) return ROW_H;
			int total = 0;
			for (FutureModule module : matches) total += module.height();
			return total;
		}

		private int maxScroll() {
			int resultViewHeight = Math.max(0, height - HEADER_H - 2 - ROW_H);
			return Math.max(0, resultContentHeight() - resultViewHeight);
		}

		private int inputY() {
			return y + HEADER_H + 1;
		}

		private int resultY() {
			return inputY() + ROW_H - scroll;
		}

		void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			hoveredModuleDescription = null;
			FuturePanelBlur.registerPanel(x, y, width, height);
			int accent = futureColor();
			int outline = ColorUtil.withAlpha(accent, 212);
			g.outline(x, y, width, height, outline);
			Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, futurePanelBody());
			Render2D.rect(g, x + 1, y + 1, width - 2, HEADER_H - 1, ColorUtil.withAlpha(accent, 135));
			Render2D.rect(g, x + 1, y + HEADER_H, width - 2, 1, FuturePalette.seam(150));
			String amount = "[" + matches.size() + "]";
			ScrollingText.draw(g, "Search", x + 5, y + 2,
					width - Render2D.width(amount) - 11, Theme.text);
			Render2D.textNoShadow(g, amount, x + width - Render2D.width(amount) - 3, y + 2, Theme.text);

			int viewTop = y + HEADER_H + 1;
			int viewBottom = y + height - 1;
			scroll = Math.clamp(scroll, 0, maxScroll());
			int fieldY = inputY();
			boolean fieldHovered = Render2D.hovered(mouseX, mouseY, x + 1, fieldY, width - 2, ROW_H);
			if (focused || fieldHovered) {
				Render2D.rect(g, x + 1, fieldY, width - 2, ROW_H - 1,
						ColorUtil.withAlpha(focused ? accent : Theme.text, focused ? 54 : 24));
			}
			Render2D.rect(g, x + 1, fieldY + ROW_H - 1, width - 2, 1, FuturePalette.seam(150));
			input.render(g, x + 6, fieldY + 3, width - 12, focused, "Search...");

			int rowY = resultY();
			int resultTop = viewTop + ROW_H;
			if (resultTop < viewBottom) {
				g.enableScissor(x + 1, resultTop, x + width - 1, viewBottom);
				if (!input.isEmpty() && matches.isEmpty()) {
					String empty = "No matches";
					Render2D.textNoShadow(g, empty, x + (width - Render2D.width(empty)) / 2, rowY + 3, Theme.textDim);
				} else {
					for (FutureModule module : matches) {
						module.setBounds(x + 1, rowY, width - 2);
						module.render(g, mouseX, mouseY);
						if (module.titleHovered(mouseX, mouseY)) hoveredModuleDescription = module.module.getDescription();
						rowY += module.height();
					}
				}
				g.disableScissor();
			}

			if (maxScroll() > 0) {
				int viewH = viewBottom - resultTop;
				int thumbH = Math.max(10, viewH * viewH / resultContentHeight());
				int thumbY = resultTop + (viewH - thumbH) * scroll / maxScroll();
				Render2D.rect(g, x + width - 2, resultTop, 2, viewH, ColorUtil.withAlpha(Theme.borderDark, 190));
				Render2D.rect(g, x + width - 2, thumbY, 2, thumbH, outline);
			}
		}

		boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) {
				unfocus();
				return false;
			}
			int fieldY = inputY();
			if (button == 0 && Render2D.hovered(mouseX, mouseY, x + 1, fieldY, width - 2, ROW_H)) {
				focused = true;
				input.click(mouseX - (x + 6));
				draggingText = true;
				return true;
			}
			unfocus();
			int resultTop = inputY() + ROW_H;
			if (mouseY < resultTop || mouseY >= y + height - 1) return true;
			int rowY = resultY();
			for (FutureModule module : matches) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseClicked(mouseX, mouseY, button)) return true;
				rowY += module.height();
			}
			return true;
		}

		boolean mouseScrolled(double mouseX, double mouseY, double amount) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) return false;
			int rowY = resultY();
			for (FutureModule module : matches) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseScrolled(mouseX, mouseY, amount)) return true;
				rowY += module.height();
			}
			scroll = Math.clamp(scroll - (int) (amount * 20), 0, maxScroll());
			return true;
		}

		void mouseDragged(double mouseX, double mouseY) {
			if (draggingText) input.drag(mouseX - (x + 6));
			else for (FutureModule module : matches) module.mouseDragged(mouseX, mouseY);
		}

		void mouseReleased() {
			draggingText = false;
			for (FutureModule module : matches) module.mouseReleased();
		}

		boolean charTyped(CharacterEvent event) {
			if (focused) return BindComponent.recentlyBound() || input.charTyped(event);
			for (FutureModule module : matches) if (module.charTyped(event)) return true;
			return false;
		}

		boolean keyPressed(KeyEvent event) {
			if (focused) {
				if (input.keyPressed(event)) return true;
				if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
					if (!input.isEmpty()) {
						input.clear();
						return true;
					}
					focused = false;
					return false;
				}
				if (event.key() == GLFW.GLFW_KEY_ENTER) {
					focused = false;
					return true;
				}
				return true;
			}
			for (FutureModule module : matches) if (module.keyPressed(event)) return true;
			return false;
		}

		void focus() {
			focused = true;
			input.selectAll();
		}

		void unfocus() {
			focused = false;
			draggingText = false;
		}

		boolean typing() {
			if (focused) return true;
			for (FutureModule module : matches) if (module.typing()) return true;
			return false;
		}
	}

	private static final class FuturePanel {
		private final Category category;
		private final List<FutureModule> modules = new ArrayList<>();
		private int x, y, width, height, scroll;
		private String hoveredModuleDescription;

		FuturePanel(Category category, List<Module> modules) {
			this.category = category;
			for (Module module : modules) this.modules.add(new FutureModule(module));
		}

		void setBounds(int x, int y, int width, int screenWidth, int screenHeight) {
			this.width = width;
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			this.height = Math.min(HEADER_H + 2 + contentHeight(), Math.max(55, screenHeight - this.y - PANEL_BOTTOM));
		}

		boolean headerHovered(double mouseX, double mouseY) {
			return Render2D.hovered(mouseX, mouseY, x, y, width, HEADER_H);
		}

		void moveTo(int x, int y, int screenWidth, int screenHeight) {
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			Position position = POSITIONS.get(category);
			position.x = this.x;
			position.y = this.y;
		}

		private int contentHeight() {
			int total = 0;
			for (FutureModule module : modules) total += module.height();
			return total;
		}

		private int maxScroll() { return Math.max(0, contentHeight() - (height - HEADER_H - 2)); }

		void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			hoveredModuleDescription = null;
			FuturePanelBlur.registerPanel(x, y, width, height);
			int accent = futureColor();
			int outline = ColorUtil.withAlpha(accent, 212);
			// Outline, do not fill: the body needs to composite directly over the
			// game world to retain the transparent Future glass effect.
			g.outline(x, y, width, height, outline);
			Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, futurePanelBody());
			Render2D.rect(g, x + 1, y + 1, width - 2, HEADER_H - 1, ColorUtil.withAlpha(accent, 135));
			Render2D.rect(g, x + 1, y + HEADER_H, width - 2, 1, FuturePalette.seam(150));
			String title = category.displayName();
			String amount = "[" + modules.size() + "]";
			ScrollingText.draw(g, title, x + 5, y + 2,
					width - Render2D.width(amount) - 11, Theme.text);
			Render2D.textNoShadow(g, amount, x + width - Render2D.width(amount) - 3, y + 2, Theme.text);

			int viewTop = y + HEADER_H + 1;
			int viewBottom = y + height - 1;
			scroll = Math.clamp(scroll, 0, maxScroll());
			g.enableScissor(x + 1, viewTop, x + width - 1, viewBottom);
			int rowY = viewTop - scroll;
			for (FutureModule module : modules) {
				module.setBounds(x + 1, rowY, width - 2);
				module.render(g, mouseX, mouseY);
				if (module.titleHovered(mouseX, mouseY)) hoveredModuleDescription = module.module.getDescription();
				rowY += module.height();
			}
			g.disableScissor();

			if (maxScroll() > 0) {
				int viewH = viewBottom - viewTop;
				int thumbH = Math.max(10, viewH * viewH / contentHeight());
				int thumbY = viewTop + (viewH - thumbH) * scroll / maxScroll();
				Render2D.rect(g, x + width - 2, viewTop, 2, viewH, ColorUtil.withAlpha(Theme.borderDark, 190));
				Render2D.rect(g, x + width - 2, thumbY, 2, thumbH, outline);
			}
		}

		boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) return false;
			int rowY = y + HEADER_H + 1 - scroll;
			for (FutureModule module : modules) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseClicked(mouseX, mouseY, button)) return true;
				rowY += module.height();
			}
			return true;
		}

		boolean mouseScrolled(double mouseX, double mouseY, double amount) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) return false;
			int rowY = y + HEADER_H + 1 - scroll;
			for (FutureModule module : modules) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseScrolled(mouseX, mouseY, amount)) return true;
				rowY += module.height();
			}
			scroll = Math.clamp(scroll - (int) (amount * 20), 0, maxScroll());
			return true;
		}

		void mouseDragged(double mouseX, double mouseY) { for (FutureModule module : modules) module.mouseDragged(mouseX, mouseY); }
		void mouseReleased() { for (FutureModule module : modules) module.mouseReleased(); }
		boolean charTyped(CharacterEvent event) { for (FutureModule module : modules) if (module.charTyped(event)) return true; return false; }
		boolean keyPressed(KeyEvent event) { for (FutureModule module : modules) if (module.keyPressed(event)) return true; return false; }
		boolean typing() { for (FutureModule module : modules) if (module.typing()) return true; return false; }
	}

	private static final class FutureModule {
		private final Module module;
		private final List<GuiComponent> components = new ArrayList<>();
		private boolean expanded;
		private boolean listeningForBind;
		private int x, y, width;

		FutureModule(Module module) {
			this.module = module;
			for (Setting<?> setting : module.getSettings()) {
				GuiComponent component = componentFor(setting);
				if (component != null) { component.owns(setting); components.add(component); }
			}
		}

		private static GuiComponent componentFor(Setting<?> setting) {
			return switch (setting) {
				case BooleanSetting s -> new BooleanComponent(s);
				case NumberSetting s -> new SliderComponent(s);
				case ModeSetting s -> new ModeComponent(s);
				case ColorSetting s -> new ColorComponent(s);
				case KeybindSetting s -> new BindComponent(s);
				case unlucky.utility.client.settings.BlockListSetting s -> new unlucky.utility.client.gui.clickgui.component.BlockListComponent(s);
				case unlucky.utility.client.settings.ItemListSetting s -> new unlucky.utility.client.gui.clickgui.component.ItemListComponent(s);
				case unlucky.utility.client.settings.BrewQueueSetting s -> new unlucky.utility.client.gui.clickgui.component.BrewQueueComponent(s);
				case unlucky.utility.client.settings.StringSetting s -> new unlucky.utility.client.gui.clickgui.component.StringComponent(s);
				default -> null;
			};
		}

		void setBounds(int x, int y, int width) { this.x = x; this.y = y; this.width = width; }
		boolean titleHovered(double mouseX, double mouseY) { return Render2D.hovered(mouseX, mouseY, x, y, width, ROW_H); }
		int height() {
			if (!expanded) return ROW_H;
			int total = ROW_H + ROW_H;
			for (GuiComponent component : components) if (component.isVisible()) total += component.getHeight();
			return total;
		}

		void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			boolean hover = titleHovered(mouseX, mouseY);
			int accent = futureColor();
			if (module.isEnabled()) {
				Render2D.rect(g, x, y, width, ROW_H - 1, futureEnabledFill(accent));
			} else if (hover) {
				Render2D.rect(g, x, y, width, ROW_H - 1, ColorUtil.withAlpha(Theme.text, 24));
			}
			Render2D.rect(g, x, y + ROW_H - 1, width, 1, FuturePalette.seam(150));
			ScrollingText.draw(g, module.getName(), x + 5, y + 3, width - 10,
					module.isEnabled() ? accent : hover ? Theme.text : 0xFF7E7E7E);
			if (!expanded) return;

			int rowY = y + ROW_H;
			Render2D.rect(g, x, rowY, width, height() - ROW_H, futurePanelBody());
			for (GuiComponent component : components) {
				if (!component.isVisible()) continue;
				component.setBounds(x + 5, rowY, width - 10);
				component.render(g, mouseX, mouseY);
				rowY += component.getHeight();
			}
			String bind = listeningForBind ? "Bind: [...]" : "Bind: " + BindComponent.keyName(module.getKeyBind());
			ScrollingText.draw(g, bind, x + 5, rowY + 3, width - 10,
					listeningForBind ? accent : Theme.textDim);
		}

		boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (titleHovered(mouseX, mouseY)) {
				if (button == 0) { module.toggle(); return true; }
				if (button == 1 && !components.isEmpty()) { expanded = !expanded; return true; }
			}
			if (!expanded) return false;
			int rowY = y + ROW_H;
			for (GuiComponent component : components) {
				if (!component.isVisible()) continue;
				component.setBounds(x + 5, rowY, width - 10);
				if (component.mouseClicked(mouseX, mouseY, button)) return true;
				rowY += component.getHeight();
			}
			if (button == 0 && Render2D.hovered(mouseX, mouseY, x + 5, rowY, width - 10, ROW_H)) {
				listeningForBind = !listeningForBind;
				return true;
			}
			return false;
		}

		boolean mouseScrolled(double mouseX, double mouseY, double amount) {
			if (!expanded) return false;
			int rowY = y + ROW_H;
			for (GuiComponent component : components) {
				if (!component.isVisible()) continue;
				component.setBounds(x + 5, rowY, width - 10);
				if (component.mouseScrolled(mouseX, mouseY, amount)) return true;
				rowY += component.getHeight();
			}
			return false;
		}

		void mouseDragged(double mouseX, double mouseY) { for (GuiComponent component : components) component.mouseDragged(mouseX, mouseY); }
		void mouseReleased() { for (GuiComponent component : components) component.mouseReleased(); }
		boolean charTyped(CharacterEvent event) { for (GuiComponent component : components) if (component.isVisible() && component.charTyped(event)) return true; return false; }
		boolean keyPressed(KeyEvent event) {
			if (listeningForBind) {
				int key = event.key();
				// Unmapped media/consumer keys arrive as KEY_UNKNOWN, which also
				// represents an unbound module. Leave the existing bind alone.
				if (key == GLFW.GLFW_KEY_UNKNOWN) return true;
				module.setKeyBind(key == GLFW.GLFW_KEY_ESCAPE ? GLFW.GLFW_KEY_UNKNOWN : key);
				listeningForBind = false;
				BindComponent.markBound();
				return true;
			}
			for (GuiComponent component : components) if (component.isVisible() && component.keyPressed(event)) return true;
			return false;
		}
		boolean typing() { for (GuiComponent component : components) if (component.isVisible() && component.typing()) return true; return false; }
	}
}
