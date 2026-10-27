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
import com.mojang.blaze3d.platform.InputConstants;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.BlursBackground;
import unlucky.utility.client.gui.FrameBlur;
import unlucky.utility.client.gui.chests.ChestPanel;
import unlucky.utility.client.gui.clickgui.component.ActionComponent;
import unlucky.utility.client.gui.clickgui.component.BindComponent;
import unlucky.utility.client.gui.clickgui.component.BooleanComponent;
import unlucky.utility.client.gui.clickgui.component.ColorComponent;
import unlucky.utility.client.gui.clickgui.component.GuiComponent;
import unlucky.utility.client.gui.clickgui.component.ModeComponent;
import unlucky.utility.client.gui.clickgui.component.PreviewComponent;
import unlucky.utility.client.gui.clickgui.component.ScrollingText;
import unlucky.utility.client.gui.clickgui.component.SliderComponent;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.PreviewSetting;
import unlucky.utility.client.settings.Setting;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.Keys;

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
	/** The model viewport pinned under the Preview panel's header, above its module rows. */
	private static final int PREVIEW_H = 120;
	/** The Character / Blocks switch under the Preview header. */
	private static final int SCENE_H = 12;
	private static final String[] PREVIEW_SCENES = {"Character", "Blocks"};
	/** Which Preview scene is showing; remembered across opens like the panel positions. */
	private static boolean previewBlocks;
	/** Preview panel at two columns' width, with a taller viewport to match; saved with the layout. */
	private static boolean previewWide = true;
	private static final int PREVIEW_WIDE_H = 180;
	/** Chests panel at three columns' width with its 3D scene, or one with just the list; saved with the layout. */
	private static boolean chestsWide = true;
	/* v5 discards older layouts so everyone picks up the corner-pinned Preview and Chests. */
	private static final int LAYOUT_VERSION = 5;
	/** Classic GUIs are arranged by the player, not reflowed into a fixed dashboard. */
	private static final Map<Category, Position> POSITIONS = new EnumMap<>(Category.class);
	private static Position searchPosition;
	private static Position previewPosition;
	private static Position chestsPosition;
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
	/** A live model with the render modules that change its look — Future's Preview tab. */
	private FuturePanel previewPanel;
	private final FutureChestsPanel chestsPanel = new FutureChestsPanel();
	private boolean draggingChestsPanel;
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
		TextBox.acceptTyping(this); // SDL only sends typed characters on request
		panels.clear();
		ensureDefaultPositions();
		for (Category category : Category.values()) {
			panels.put(category, new FuturePanel(category, UnluckyClient.INSTANCE.modules.byCategory(category), null));
		}
		searchPanel = new FutureSearchPanel(UnluckyClient.INSTANCE.modules.all());
		List<Module> previewModules = new ArrayList<>();
		for (Class<? extends Module> type : RenderPreview.MODULES) {
			previewModules.add(UnluckyClient.INSTANCE.modules.get(type));
		}
		List<Module> blockModules = new ArrayList<>();
		for (Class<? extends Module> type : BlockScene.MODULES) {
			blockModules.add(UnluckyClient.INSTANCE.modules.get(type));
		}
		previewPanel = new FuturePanel(null, previewModules, new RenderPreview(null, 25.0f))
				.withBlocks(blockModules, new BlockScene());
		chestsPanel.content.open();
	}

	/** Shows the Preview panel's block scene or character. Used by screen tests. */
	public static void selectPreviewScene(boolean blocks) {
		previewBlocks = blocks;
	}

	/** Every draggable module panel: the categories, then Preview. */
	private List<FuturePanel> allPanels() {
		List<FuturePanel> all = new ArrayList<>(panels.values());
		all.add(previewPanel);
		return all;
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
		x += panelWidth + GAP;
		// Preview pins to the top-right corner, two columns wide when that clears the strip
		if (previewPosition == null) {
			previewWide = x + 2 * PANEL_W + GAP + MARGIN <= width;
			int previewWidth = previewWide ? 2 * PANEL_W + GAP : PANEL_W;
			previewPosition = new Position(Math.max(x, width - previewWidth - MARGIN), PANEL_TOP);
		}
		// and Chests sits in the bottom-right corner under it
		if (chestsPosition == null) {
			int chestsWidth = chestsWide ? 3 * PANEL_W + 2 * GAP : PANEL_W;
			int chestsHeight = chestsWide ? FutureChestsPanel.WIDE_H : FutureChestsPanel.NARROW_H;
			chestsPosition = new Position(width - chestsWidth - MARGIN, height - PANEL_BOTTOM - chestsHeight);
		}
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
			int previewWidth = previewWide ? 2 * PANEL_W + GAP : PANEL_W;
			previewPanel.setBounds(previewPosition.x, previewPosition.y,
					Math.max(MIN_W, Math.min(previewWidth, width - 2 * MARGIN)), width, height);
			previewPanel.render(g, mouseX, mouseY);
			if (previewPanel.hoveredModuleDescription != null) {
				hoveredDescription = previewPanel.hoveredModuleDescription;
			}
			chestsPanel.setBounds(width, height);
			chestsPanel.render(g, mouseX, mouseY);
		} finally {
			ScrollingText.endFutureRender();
		}
		String toolbarLabel = FutureClickGuiToolbar.draw(g, mouseX, mouseY, width, height,
				FutureClickGuiToolbar.CLICKGUI);

		if (hoveredDescription != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen() && !BrewQueuePopup.isOpen() && !PatternPopup.isOpen() && !AreaPopup.isOpen()) {
			drawTooltip(g, hoveredDescription, mouseX, mouseY);
		}
		BlockPickerPopup.render(g, mouseX, mouseY);
		MobPickerPopup.render(g, mouseX, mouseY);
		ItemPickerPopup.render(g, mouseX, mouseY);
		BrewQueuePopup.render(g, mouseX, mouseY);
		PatternPopup.render(g, mouseX, mouseY);
		AreaPopup.render(g, mouseX, mouseY);
		if (toolbarLabel != null && !BlockPickerPopup.isOpen() && !MobPickerPopup.isOpen()
				&& !ItemPickerPopup.isOpen() && !BrewQueuePopup.isOpen() && !PatternPopup.isOpen() && !AreaPopup.isOpen()) {
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
				|| BrewQueuePopup.mouseScrolled(scrollY) || ItemPickerPopup.mouseScrolled(scrollY)
				|| PatternPopup.mouseScrolled(scrollY) || AreaPopup.mouseScrolled(scrollY)) return true;
		// the Chests panel is drawn over the others, so it hears the wheel first
		if (chestsPanel.content.mouseScrolled(mouseX, mouseY, scrollY)) return true;
		if (searchPanel.mouseScrolled(mouseX, mouseY, scrollY)) return true;
		for (FuturePanel panel : allPanels()) {
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
				|| PatternPopup.mouseClicked(x, y, event.button(), width, height)
				|| AreaPopup.mouseClicked(x, y, event.button(), width, height)
				|| ItemPickerPopup.mouseClicked(x, y, event.button(), width, height)) return true;
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (previewPanel.widthToggleHovered(x, y)) {
				previewWide = !previewWide;
				return true;
			}
			if (chestsPanel.widthToggleHovered(x, y)) {
				chestsWide = !chestsWide;
				return true;
			}
			if (chestsPanel.headerHovered(x, y)) {
				searchPanel.unfocus();
				chestsPanel.content.unfocus();
				draggingChestsPanel = true;
				dragOffsetX = (int) x - chestsPanel.x;
				dragOffsetY = (int) y - chestsPanel.y;
				return true;
			}
			if (searchPanel.headerHovered(x, y)) {
				searchPanel.unfocus();
				chestsPanel.content.unfocus();
				draggingSearchPanel = true;
				dragOffsetX = (int) x - searchPanel.x;
				dragOffsetY = (int) y - searchPanel.y;
				return true;
			}
			for (FuturePanel panel : allPanels()) {
				if (panel.headerHovered(x, y)) {
					searchPanel.unfocus();
					chestsPanel.content.unfocus();
					draggingPanel = panel;
					dragOffsetX = (int) x - panel.x;
					dragOffsetY = (int) y - panel.y;
					return true;
				}
			}
		}
		if (chestsPanel.content.mouseClicked(x, y, event.button())) {
			searchPanel.unfocus();
			return true;
		}
		if (searchPanel.mouseClicked(x, y, event.button())) return true;
		for (FuturePanel panel : allPanels()) {
			if (panel.mouseClicked(x, y, event.button())) return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		double x = event.x(), y = event.y();
		if (BlockPickerPopup.mouseDragged(x, y, width, height) || MobPickerPopup.mouseDragged(x, y, width, height)
				|| ItemPickerPopup.mouseDragged(x, y, width, height) || BrewQueuePopup.mouseDragged(x, y, width, height)
				|| PatternPopup.mouseDragged(x, y, width, height)
				|| AreaPopup.mouseDragged(x, y, width, height)) return true;
		if (draggingPanel != null) {
			draggingPanel.moveTo((int) x - dragOffsetX, (int) y - dragOffsetY, width, height);
			return true;
		}
		if (draggingSearchPanel) {
			searchPanel.moveTo((int) x - dragOffsetX, (int) y - dragOffsetY, width, height);
			return true;
		}
		if (draggingChestsPanel) {
			chestsPanel.moveTo((int) x - dragOffsetX, (int) y - dragOffsetY, width, height);
			return true;
		}
		if (chestsPanel.content.mouseDragged(x, y)) return true;
		searchPanel.mouseDragged(x, y);
		for (FuturePanel panel : allPanels()) panel.mouseDragged(x, y);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		BlockPickerPopup.mouseReleased();
		MobPickerPopup.mouseReleased();
		ItemPickerPopup.mouseReleased();
		BrewQueuePopup.mouseReleased();
		PatternPopup.mouseReleased();
		AreaPopup.mouseReleased();
		draggingPanel = null;
		draggingSearchPanel = false;
		draggingChestsPanel = false;
		chestsPanel.content.mouseReleased(event.x(), event.y());
		searchPanel.mouseReleased();
		for (FuturePanel panel : allPanels()) panel.mouseReleased();
		return super.mouseReleased(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (BlockPickerPopup.isOpen()) return BlockPickerPopup.charTyped(event);
		if (ItemPickerPopup.isOpen()) return ItemPickerPopup.charTyped(event);
		if (BrewQueuePopup.isOpen()) return BrewQueuePopup.charTyped(event);
		if (chestsPanel.content.charTyped(event)) return true;
		if (searchPanel.charTyped(event)) return true;
		for (FuturePanel panel : allPanels()) if (panel.charTyped(event)) return true;
		return BindComponent.recentlyBound() || super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (BlockPickerPopup.isOpen()) {
			if (BlockPickerPopup.keyPressed(event)) return true;
			if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) BlockPickerPopup.close();
			return true;
		}
		if (MobPickerPopup.isOpen() && event.key() == InputConstants.KEY_ESCAPE) { MobPickerPopup.close(); return true; }
		if (ItemPickerPopup.isOpen()) {
			if (ItemPickerPopup.keyPressed(event)) return true;
			if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) ItemPickerPopup.close();
			return true;
		}
		if (BrewQueuePopup.isOpen()) {
			if (BrewQueuePopup.keyPressed(event)) return true;
			if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) BrewQueuePopup.close();
			return true;
		}
		if (AreaPopup.isOpen()) {
			if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) AreaPopup.close();
			return true;
		}
		if (PatternPopup.isOpen()) {
			if (PatternPopup.keyPressed(event)) return true;
			if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) PatternPopup.close();
			return true;
		}
		if (chestsPanel.content.keyPressed(event)) return true;
		if (searchPanel.keyPressed(event)) return true;
		for (FuturePanel panel : allPanels()) if (panel.keyPressed(event)) return true;
		if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
			chestsPanel.content.unfocus();
			searchPanel.focus();
			return true;
		}
		int key = event.key();
		if (key == InputConstants.KEY_ESCAPE
				|| (key != Keys.NONE && key == UnluckyClient.INSTANCE.clickGuiKey)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Used by InventoryMove so focused HEX/RGB and string fields still own WASD. */
	public boolean isTyping() {
		if (BlockPickerPopup.isOpen() || MobPickerPopup.isOpen() || ItemPickerPopup.isOpen() || BrewQueuePopup.isOpen()
				|| PatternPopup.isOpen() || AreaPopup.isOpen()) return true;
		if (searchPanel.typing() || chestsPanel.content.typing()) return true;
		for (FuturePanel panel : allPanels()) if (panel.typing()) return true;
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
		if (previewPosition != null) {
			JsonObject point = new JsonObject();
			point.addProperty("x", previewPosition.x);
			point.addProperty("y", previewPosition.y);
			result.add("preview", point);
		}
		result.addProperty("previewWide", previewWide);
		if (chestsPosition != null) {
			JsonObject point = new JsonObject();
			point.addProperty("x", chestsPosition.x);
			point.addProperty("y", chestsPosition.y);
			result.add("chests", point);
		}
		result.addProperty("chestsWide", chestsWide);
		return result;
	}

	public static void loadPositions(JsonObject saved) {
		// Older layouts used wide columns and different offsets. Ignore their
		// geometry so the compact Future baseline starts clean.
		if (!saved.has("version") || saved.get("version").getAsInt() != LAYOUT_VERSION) {
			POSITIONS.clear();
			searchPosition = null;
			previewPosition = null;
			chestsPosition = null;
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
		previewWide = !saved.has("previewWide") || saved.get("previewWide").getAsBoolean();
		chestsWide = !saved.has("chestsWide") || saved.get("chestsWide").getAsBoolean();
		if (saved.has("chests") && saved.get("chests").isJsonObject()) {
			JsonObject point = saved.getAsJsonObject("chests");
			if (point.has("x") && point.has("y")) {
				chestsPosition = new Position(point.get("x").getAsInt(), point.get("y").getAsInt());
			}
		}
		// absent from layouts saved before the Preview panel existed; ensureDefaultPositions fills it
		if (saved.has("preview") && saved.get("preview").isJsonObject()) {
			JsonObject point = saved.getAsJsonObject("preview");
			if (point.has("x") && point.has("y")) {
				previewPosition = new Position(point.get("x").getAsInt(), point.get("y").getAsInt());
			}
		}
	}

	private static final class Position {
		int x, y;
		Position(int x, int y) { this.x = x; this.y = y; }
	}

	/**
	 * ChestManager's records as a Future window: three columns wide with the 3D scene beside the
	 * list, or folded to one column of just the list. The same {@code ><}/{@code <>} switch as
	 * Preview sits where the other panels count their modules.
	 */
	private static final class FutureChestsPanel {
		private static final int WIDE_H = 240;
		private static final int NARROW_H = 250;
		private static final String NARROW = "><", WIDE = "<>";
		final ChestPanel content = new ChestPanel(ChestPanel.Style.FUTURE);
		int x, y, width, height;

		void setBounds(int screenWidth, int screenHeight) {
			width = Math.max(MIN_W, Math.min(chestsWide ? 3 * PANEL_W + 2 * GAP : PANEL_W, screenWidth - 2 * MARGIN));
			// its own size wherever it sits: it stops at the screen's edge rather than shrinking into it,
			// and only a screen too small for it cuts it down
			height = Math.min(chestsWide ? WIDE_H : NARROW_H, Math.max(HEADER_H + 80, screenHeight - PANEL_BOTTOM));
			x = Math.clamp(chestsPosition.x, 0, Math.max(0, screenWidth - width));
			y = Math.clamp(chestsPosition.y, 0, Math.max(0, screenHeight - PANEL_BOTTOM - height));
		}

		void moveTo(int x, int y, int screenWidth, int screenHeight) {
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - PANEL_BOTTOM - height));
			chestsPosition.x = this.x;
			chestsPosition.y = this.y;
		}

		private String widthToggle() {
			return chestsWide ? NARROW : WIDE;
		}

		boolean widthToggleHovered(double mouseX, double mouseY) {
			int toggleW = Render2D.width(widthToggle()) + 6;
			return Render2D.hovered(mouseX, mouseY, x + width - toggleW, y, toggleW, HEADER_H);
		}

		boolean headerHovered(double mouseX, double mouseY) {
			return Render2D.hovered(mouseX, mouseY, x, y, width, HEADER_H);
		}

		void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
			FuturePanelBlur.registerPanel(x, y, width, height);
			int accent = futureColor();
			g.outline(x, y, width, height, ColorUtil.withAlpha(accent, 212));
			Render2D.rect(g, x + 1, y + 1, width - 2, height - 2, futurePanelBody());
			Render2D.rect(g, x + 1, y + 1, width - 2, HEADER_H - 1, ColorUtil.withAlpha(accent, 135));
			Render2D.rect(g, x + 1, y + HEADER_H, width - 2, 1, FuturePalette.seam(150));
			String toggle = widthToggle();
			ScrollingText.draw(g, "Chests", x + 5, y + 2, width - Render2D.width(toggle) - 11, Theme.text);
			Render2D.textNoShadow(g, toggle, x + width - Render2D.width(toggle) - 3, y + 2,
					widthToggleHovered(mouseX, mouseY) ? Theme.text : Theme.textDim);
			content.render(g, x + 4, y + HEADER_H + 3, width - 8, height - HEADER_H - 7, mouseX, mouseY, accent);
		}
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
			if (button == InputConstants.MOUSE_BUTTON_LEFT && Render2D.hovered(mouseX, mouseY, x + 1, fieldY, width - 2, ROW_H)) {
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
				if (event.key() == InputConstants.KEY_ESCAPE) {
					if (!input.isEmpty()) {
						input.clear();
						return true;
					}
					focused = false;
					return false;
				}
				if (event.key() == InputConstants.KEY_RETURN) {
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
		/** Null for the Preview panel, which is not a category. */
		private final Category category;
		private final List<FutureModule> modules = new ArrayList<>();
		/** Pinned above the rows when set; they scroll beneath it. */
		private final RenderPreview preview;
		/** The Preview panel's second scene and its rows, swapped in by the scene switch. */
		private final List<FutureModule> blockModules = new ArrayList<>();
		private BlockScene blockScene;
		private int x, y, width, height, scroll;
		private String hoveredModuleDescription;

		FuturePanel(Category category, List<Module> modules, RenderPreview preview) {
			this.category = category;
			this.preview = preview;
			for (Module module : modules) this.modules.add(new FutureModule(module));
		}

		FuturePanel withBlocks(List<Module> modules, BlockScene scene) {
			for (Module module : modules) blockModules.add(new FutureModule(module));
			this.blockScene = scene;
			return this;
		}

		private boolean showingBlocks() {
			return blockScene != null && previewBlocks;
		}

		/** The rows under the preview: the block scene's modules while it is showing. */
		private List<FutureModule> rows() {
			return showingBlocks() ? blockModules : modules;
		}

		private int sceneTabsHeight() {
			return blockScene == null ? 0 : SCENE_H;
		}

		private Position position() {
			return category == null ? previewPosition : POSITIONS.get(category);
		}

		private int previewHeight() {
			return preview == null ? 0 : sceneHeight() + sceneTabsHeight();
		}

		/** The viewport itself: taller when the panel is double wide, so the scene grows both ways. */
		private int sceneHeight() {
			return previewWide ? PREVIEW_WIDE_H : PREVIEW_H;
		}

		/** The width switch where the other panels show their module count. */
		private static final String NARROW = "><", WIDE = "<>";

		private String widthToggle() {
			return previewWide ? NARROW : WIDE;
		}

		boolean widthToggleHovered(double mouseX, double mouseY) {
			if (preview == null) return false;
			int toggleW = Render2D.width(widthToggle()) + 6;
			return Render2D.hovered(mouseX, mouseY, x + width - toggleW, y, toggleW, HEADER_H);
		}

		/** Where the module rows start: under the header and the preview, if any. */
		private int rowsTop() {
			return y + HEADER_H + 1 + previewHeight();
		}

		void setBounds(int x, int y, int width, int screenWidth, int screenHeight) {
			this.width = width;
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			this.height = Math.min(HEADER_H + 2 + previewHeight() + contentHeight(),
					Math.max(55 + previewHeight(), screenHeight - this.y - PANEL_BOTTOM));
		}

		boolean headerHovered(double mouseX, double mouseY) {
			return Render2D.hovered(mouseX, mouseY, x, y, width, HEADER_H);
		}

		void moveTo(int x, int y, int screenWidth, int screenHeight) {
			this.x = Math.clamp(x, 0, Math.max(0, screenWidth - width));
			this.y = Math.clamp(y, 0, Math.max(0, screenHeight - HEADER_H));
			Position position = position();
			position.x = this.x;
			position.y = this.y;
		}

		private int contentHeight() {
			int total = 0;
			for (FutureModule module : rows()) total += module.height();
			return total;
		}

		private int maxScroll() { return Math.max(0, contentHeight() - (height - HEADER_H - 2 - previewHeight())); }

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
			String title = category == null ? "Preview" : category.displayName();
			String amount = preview != null ? widthToggle() : "[" + rows().size() + "]";
			ScrollingText.draw(g, title, x + 5, y + 2,
					width - Render2D.width(amount) - 11, Theme.text);
			Render2D.textNoShadow(g, amount, x + width - Render2D.width(amount) - 3, y + 2,
					preview != null && !widthToggleHovered(mouseX, mouseY) ? Theme.textDim : Theme.text);

			if (blockScene != null) {
				drawSceneTabs(g, accent, mouseX, mouseY);
			}
			int sceneY = y + HEADER_H + 3 + sceneTabsHeight();
			if (showingBlocks()) {
				blockScene.render(g, x + 3, sceneY, width - 6, sceneHeight() - 4, mouseX, mouseY);
			} else if (preview != null) {
				preview.render(g, x + 3, sceneY, width - 6, sceneHeight() - 4, mouseX, mouseY);
			}
			int viewTop = rowsTop();
			int viewBottom = y + height - 1;
			scroll = Math.clamp(scroll, 0, maxScroll());
			g.enableScissor(x + 1, viewTop, x + width - 1, viewBottom);
			int rowY = viewTop - scroll;
			for (FutureModule module : rows()) {
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

		/** Two halves under the header, the live one lit in the panel's accent. */
		private void drawSceneTabs(GuiGraphicsExtractor g, int accent, int mouseX, int mouseY) {
			int tabY = y + HEADER_H + 1;
			int half = (width - 2) / 2;
			for (int i = 0; i < PREVIEW_SCENES.length; i++) {
				int cellX = x + 1 + i * half;
				int cellW = i == 0 ? half : width - 2 - half;
				boolean active = previewBlocks == (i == 1);
				boolean hover = Render2D.hovered(mouseX, mouseY, cellX, tabY, cellW, SCENE_H - 1);
				if (active || hover) {
					Render2D.rect(g, cellX, tabY, cellW, SCENE_H - 1, ColorUtil.withAlpha(accent, active ? 90 : 40));
				}
				String label = PREVIEW_SCENES[i];
				Render2D.textNoShadow(g, label, cellX + (cellW - Render2D.width(label)) / 2, tabY + 2,
						active ? Theme.text : Theme.textDim);
			}
			Render2D.rect(g, x + 1 + half, tabY + 2, 1, SCENE_H - 5, FuturePalette.seam(150));
			Render2D.rect(g, x + 1, tabY + SCENE_H - 1, width - 2, 1, FuturePalette.seam(150));
		}

		boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) return false;
			if (blockScene != null && button == InputConstants.MOUSE_BUTTON_LEFT
					&& Render2D.hovered(mouseX, mouseY, x + 1, y + HEADER_H + 1, width - 2, SCENE_H - 1)) {
				boolean blocks = mouseX >= x + 1 + (width - 2) / 2;
				if (blocks != previewBlocks) {
					previewBlocks = blocks;
					scroll = 0; // the other scene's rows are a different list
				}
				return true;
			}
			if (showingBlocks() ? blockScene.mouseClicked(mouseX, mouseY, button)
					: preview != null && preview.mouseClicked(mouseX, mouseY, button)) return true;
			if (mouseY < rowsTop()) return true;
			int rowY = rowsTop() - scroll;
			for (FutureModule module : rows()) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseClicked(mouseX, mouseY, button)) return true;
				rowY += module.height();
			}
			return true;
		}

		boolean mouseScrolled(double mouseX, double mouseY, double amount) {
			if (!Render2D.hovered(mouseX, mouseY, x, y, width, height)) return false;
			int rowY = rowsTop() - scroll;
			for (FutureModule module : rows()) {
				module.setBounds(x + 1, rowY, width - 2);
				if (module.mouseScrolled(mouseX, mouseY, amount)) return true;
				rowY += module.height();
			}
			scroll = Math.clamp(scroll - (int) (amount * 20), 0, maxScroll());
			return true;
		}

		void mouseDragged(double mouseX, double mouseY) {
			if (preview != null) preview.mouseDragged(mouseX);
			if (blockScene != null) blockScene.mouseDragged(mouseX, mouseY);
			for (FutureModule module : rows()) module.mouseDragged(mouseX, mouseY);
		}
		void mouseReleased() {
			if (preview != null) preview.mouseReleased();
			if (blockScene != null) blockScene.mouseReleased();
			for (FutureModule module : rows()) module.mouseReleased();
		}
		boolean charTyped(CharacterEvent event) { for (FutureModule module : rows()) if (module.charTyped(event)) return true; return false; }
		boolean keyPressed(KeyEvent event) { for (FutureModule module : rows()) if (module.keyPressed(event)) return true; return false; }
		boolean typing() { for (FutureModule module : rows()) if (module.typing()) return true; return false; }
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
				case ActionSetting s -> new ActionComponent(s);
				case PreviewSetting s -> new PreviewComponent(s);
				case unlucky.utility.client.settings.GroupSetting s -> new unlucky.utility.client.gui.clickgui.component.GroupComponent(s);
				case unlucky.utility.client.settings.ToggleGroupSetting s -> new unlucky.utility.client.gui.clickgui.component.ToggleGroupComponent(s);
				case BooleanSetting s -> new BooleanComponent(s);
				case NumberSetting s -> new SliderComponent(s);
				case ModeSetting s -> new ModeComponent(s);
				case ColorSetting s -> new ColorComponent(s);
				case KeybindSetting s -> new BindComponent(s);
				case unlucky.utility.client.settings.BlockListSetting s -> new unlucky.utility.client.gui.clickgui.component.BlockListComponent(s);
				case unlucky.utility.client.settings.ItemListSetting s -> new unlucky.utility.client.gui.clickgui.component.ItemListComponent(s);
				case unlucky.utility.client.settings.BrewQueueSetting s -> new unlucky.utility.client.gui.clickgui.component.BrewQueueComponent(s);
				case unlucky.utility.client.settings.PatternSetting s -> new unlucky.utility.client.gui.clickgui.component.PatternComponent(s);
				case unlucky.utility.client.settings.StringSetting s -> new unlucky.utility.client.gui.clickgui.component.StringComponent(s);
				case unlucky.utility.client.settings.StringListSetting s -> new unlucky.utility.client.gui.clickgui.component.StringListComponent(s);
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
				if (button == InputConstants.MOUSE_BUTTON_LEFT) { module.toggle(); return true; }
				if (button == InputConstants.MOUSE_BUTTON_RIGHT && !components.isEmpty()) { expanded = !expanded; return true; }
			}
			if (!expanded) return false;
			int rowY = y + ROW_H;
			for (GuiComponent component : components) {
				if (!component.isVisible()) continue;
				component.setBounds(x + 5, rowY, width - 10);
				if (component.mouseClicked(mouseX, mouseY, button)) return true;
				rowY += component.getHeight();
			}
			if (button == InputConstants.MOUSE_BUTTON_LEFT && Render2D.hovered(mouseX, mouseY, x + 5, rowY, width - 10, ROW_H)) {
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
				if (key == Keys.NONE) return true;
				module.setKeyBind(key == InputConstants.KEY_ESCAPE ? Keys.NONE : key);
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
