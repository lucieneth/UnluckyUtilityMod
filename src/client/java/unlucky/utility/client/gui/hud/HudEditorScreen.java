package unlucky.utility.client.gui.hud;

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
import unlucky.utility.client.gui.clickgui.ClickGuiToolbar;
import unlucky.utility.client.gui.clickgui.FutureClickGuiToolbar;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Drag HUD widgets around; right-click one for its settings. Positions save on close. */
public class HudEditorScreen extends Screen implements BlursBackground {
	/** 16x16 GUI sprite (mcmeta scaling "tile"): one white dot at (8,8), tinted at draw time. */
	private static final net.minecraft.resources.Identifier GRID_SPRITE =
			unlucky.utility.client.UnluckyClientMod.id("hud_grid");
	private static final int POPUP_WIDTH = 180;
	private static final int ROW_HEIGHT = 15;
	private static final int SLIDER_W = 44;
	private static final int PICKER_INSET = 4; // margin between the popup edge and the color picker body

	private static final int PANEL_W = 132;
	private static final int PANEL_H = 190;
	private static final int PANEL_ROW = 12;
	private static final int SAFE_COLOR = 0x305F9EFF;
	private static final int ACTION_W = 48;
	private static final int ACTION_CELL = 22;
	private static final String[] ACTION_LABELS = {"L", "C", "R", "T", "M", "B", "Lock", "Hide", "Reset", "Preview", "Grid", "Guides", "Duplicate"};

	private HudWidget dragging;
	private HudWidget selectedWidget;
	private int dragOffsetX;
	private int dragOffsetY;
	private int snapGuideX = -1;
	private int snapGuideY = -1;
	private boolean previewData = true;
	// right-click settings popup
	private HudWidget settingsTarget;
	private int popupX;
	private int popupY;
	private int popupScroll;
	private unlucky.utility.client.settings.NumberSetting draggingNumber;
	private unlucky.utility.client.settings.ColorSetting expandedColor; // color row showing the picker
	private final unlucky.utility.client.ui.ColorPicker colorPicker = new unlucky.utility.client.ui.ColorPicker();
	private unlucky.utility.client.settings.StringSetting focusedText; // text row being typed into
	private final unlucky.utility.client.ui.TextBox textBox = new unlucky.utility.client.ui.TextBox();
	private boolean draggingText;
	private int textFieldTextX; // screen X of the focused field's text origin, for drag-selection
	// mini panel listing every HUD widget: quick toggles + right-click settings
	private static int panelX = Integer.MIN_VALUE; // remembered across opens
	private static int panelY;
	private static int panelScroll;
	private boolean draggingPanel;
	private int panelDragX;
	private int panelDragY;

	/** Menu we return to on close — null in-game, the title screen when opened from it. */
	private final Screen parent;

	public HudEditorScreen() {
		this(null);
	}

	public HudEditorScreen(Screen parent) {
		super(Component.literal("HUD Editor"));
		this.parent = parent;
		textBox.onChange(() -> {
			if (focusedText != null) {
				focusedText.set(textBox.text());
			}
		});
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// replaces the vanilla menu background: optional blur, a lighter dim
		if (UnluckyClient.INSTANCE.modules.get(ThemeModule.class).blur.get()) {
			FrameBlur.claim(g);
		}
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x38000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		long perfStart = unlucky.utility.client.util.PerfDebug.ENABLED
				? unlucky.utility.client.util.PerfDebug.begin() : 0L;
		// subtle dot grid so positioning feels intentional — ONE tiled-sprite blit.
		// Never draw this dot-by-dot with g.fill: each fill is its own render state
		// in the 26.2 extract pipeline, and a full screen of dots (~1.6k at dev size,
		// ~14k at 1440p scale 1) tanked the editor to 30 fps (done.md Phase 10).
		var hud = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.hud.HudModule.class);
		if (hud.editorGrid.get()) {
			g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, GRID_SPRITE,
					0, 0, g.guiWidth(), g.guiHeight(), 0x20FFFFFF);
		}
		if (hud.editorSafeAreas.get()) {
			drawSafeAreas(g);
		}

		HudManager.setPreviewData(previewData);
		UnluckyClient.INSTANCE.hud.render(g, true);
		if (previewData) drawPreviewNotification(g);
		if (snapGuideX >= 0) g.fill(snapGuideX, 0, snapGuideX + 1, g.guiHeight(), 0x905F9EFF);
		if (snapGuideY >= 0) g.fill(0, snapGuideY, g.guiWidth(), snapGuideY + 1, 0x905F9EFF);

		for (HudWidget widget : UnluckyClient.INSTANCE.hud.widgets()) {
			boolean hovered = Render2D.hovered(mouseX, mouseY, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
			int outline = hovered || widget == dragging || widget == selectedWidget
					? Theme.accent1
					: ColorUtil.withAlpha(Theme.textDim, widget.isVisible() ? 120 : 45);
			g.outline(widget.getX() - 2, widget.getY() - 2, widget.getWidth() + 4, widget.getHeight() + 4, outline);
		}

		String hint = "Drag widgets to move them — right-click for settings — hold Ctrl for precise placement";
		Render2D.text(g, hint, (g.guiWidth() - Render2D.width(hint)) / 2, g.guiHeight() - 20, Theme.textDim);

		drawPanel(g, mouseX, mouseY);
		drawActionToolbar(g, mouseX, mouseY);
		if (settingsTarget != null) {
			drawPopup(g, mouseX, mouseY);
		}

		// shared top toolbar (HUD editor highlighted) so you can jump back to the ClickGUI
		if (FutureClickGuiToolbar.isSelected()) {
			String toolbarLabel = FutureClickGuiToolbar.draw(g, mouseX, mouseY, width, height,
					FutureClickGuiToolbar.HUD_EDITOR);
			if (toolbarLabel != null) {
				FutureClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
			}
		} else {
			String toolbarLabel = ClickGuiToolbar.draw(g, mouseX, mouseY, width, ClickGuiToolbar.HUD_EDITOR);
			if (toolbarLabel != null) {
				ClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
			}
		}
		if (unlucky.utility.client.util.PerfDebug.ENABLED) {
			unlucky.utility.client.util.PerfDebug.end("editor.extract", perfStart);
		}
	}

	/** Editor-only guides, clipped to the actual Minecraft GUI window. */
	private void drawSafeAreas(GuiGraphicsExtractor g) {
		int w = g.guiWidth();
		int h = g.guiHeight();
		safeRect(g, w / 2 - 91, 2, 182, 48, "Boss bars");
		safeRect(g, w - 102, 2, 100, 28, "Potions");
		safeRect(g, w - 122, Math.max(34, h / 4), 120, Math.min(110, h / 2), "Scoreboard");
		safeRect(g, w / 2 - 91, h - 24, 182, 20, "Hotbar");
		safeRect(g, 2, Math.max(2, h - 128), Math.min(320, w - 4), 110, "Chat");
		safeRect(g, 2, h - 15, Math.max(0, w - 4), 12, "Chat input");
	}

	private void safeRect(GuiGraphicsExtractor g, int x, int y, int w, int h, String label) {
		x = Math.clamp(x, 0, g.guiWidth());
		y = Math.clamp(y, 0, g.guiHeight());
		w = Math.clamp(w, 0, g.guiWidth() - x);
		h = Math.clamp(h, 0, g.guiHeight() - y);
		if (w <= 0 || h <= 0) return;
		g.fill(x, y, x + w, y + h, 0x105F9EFF);
		g.outline(x, y, w, h, SAFE_COLOR);
		if (w > Render2D.width(label) + 6 && h >= 10) Render2D.textNoShadow(g, label, x + 3, y + 2, 0x805F9EFF);
	}

	private void drawPreviewNotification(GuiGraphicsExtractor g) {
		int w = 142;
		int x = Math.max(4, g.guiWidth() - w - 64);
		int y = 36;
		Render2D.roundedRect(g, x, y, w, 30, 4, 0xD0101218);
		g.outline(x, y, w, 30, ColorUtil.withAlpha(Theme.accent1, 130));
		Render2D.text(g, "Unlucky Client", x + 8, y + 5, Theme.accent1);
		Render2D.text(g, "Preview notification", x + 8, y + 16, Theme.text);
	}

	private int actionX() { return Math.max(2, width - ACTION_W - 34); }
	private int actionY() { return Math.max(2, (height - actionHeight()) / 2); }
	private int actionHeight() { return 18 + ((ACTION_LABELS.length + 1) / 2) * ACTION_CELL + 4; }

	private void drawActionToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = actionX();
		int y = actionY();
		Render2D.roundedRect(g, x, y, ACTION_W, actionHeight(), 4, 0xE811141A);
		g.outline(x, y, ACTION_W, actionHeight(), ColorUtil.withAlpha(Theme.accent1, 145));
		Render2D.textNoShadow(g, selectedWidget == null ? "Tools" : selectedWidget.getDisplayName(), x + 4, y + 4,
				selectedWidget == null ? Theme.textDim : Theme.accent1);
		String tooltip = null;
		for (int i = 0; i < ACTION_LABELS.length; i++) {
			int bx = x + 2 + (i % 2) * ACTION_CELL;
			int by = y + 17 + (i / 2) * ACTION_CELL;
			boolean hovered = Render2D.hovered(mouseX, mouseY, bx, by, ACTION_CELL, ACTION_CELL - 1);
			boolean needsWidget = i <= 8 || i == 12;
			boolean disabled = needsWidget && selectedWidget == null
					|| i == 12 && !UnluckyClient.INSTANCE.hud.canDuplicate(selectedWidget);
			if (hovered) Render2D.rect(g, bx, by, ACTION_CELL, ACTION_CELL - 1, disabled ? 0x20222222 : 0x385F9EFF);
			String shortLabel = switch (i) {
				case 6 -> "LK"; case 7 -> "HI"; case 8 -> "RS"; case 9 -> "PV";
				case 10 -> "#"; case 11 -> "GD"; case 12 -> "DP"; default -> ACTION_LABELS[i];
			};
			int color = disabled ? 0xFF55555B : actionActive(i) ? Theme.accent1 : Theme.textDim;
			Render2D.textNoShadow(g, shortLabel, bx + (ACTION_CELL - Render2D.width(shortLabel)) / 2, by + 6, color);
			if (hovered) tooltip = actionTooltip(i);
		}
		if (tooltip != null) {
			int tw = Render2D.width(tooltip) + 8;
			int tx = Math.max(2, x - tw - 4);
			int ty = Math.clamp(mouseY - 6, 2, height - 16);
			Render2D.roundedRect(g, tx, ty, tw, 14, 3, 0xF014141A);
			Render2D.textNoShadow(g, tooltip, tx + 4, ty + 3, Theme.text);
		}
	}

	private boolean actionActive(int action) {
		var hud = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.hud.HudModule.class);
		return switch (action) {
			case 6 -> selectedWidget != null && selectedWidget.isLayoutLocked();
			case 9 -> previewData;
			case 10 -> hud.editorGrid.get();
			case 11 -> hud.editorSafeAreas.get();
			default -> false;
		};
	}

	private String actionTooltip(int action) {
		return switch (action) {
			case 0 -> "Align left"; case 1 -> "Center horizontally"; case 2 -> "Align right";
			case 3 -> "Align top"; case 4 -> "Center vertically"; case 5 -> "Align bottom";
			case 6 -> "Lock placement"; case 7 -> "Hide widget"; case 8 -> "Reset position";
			case 9 -> "Preview data"; case 10 -> "Placement grid"; case 11 -> "Safe-area guides";
			case 12 -> selectedWidget != null && !UnluckyClient.INSTANCE.hud.canDuplicate(selectedWidget)
					? "This widget cannot be duplicated" : "Duplicate widget";
			default -> "HUD editor action";
		};
	}

	private int actionAt(double mx, double my) {
		int x = actionX();
		int y = actionY() + 17;
		if (!Render2D.hovered(mx, my, x, y, ACTION_W, actionHeight() - 17)) return -1;
		int col = ((int) mx - x - 2) / ACTION_CELL;
		int row = ((int) my - y) / ACTION_CELL;
		if (col < 0 || col > 1 || row < 0) return -1;
		int action = row * 2 + col;
		return action < ACTION_LABELS.length ? action : -1;
	}

	private boolean handleActionClick(double mx, double my) {
		int action = actionAt(mx, my);
		if (action < 0) return false;
		var hud = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.hud.HudModule.class);
		if (action <= 2 && selectedWidget != null) selectedWidget.alignHorizontal(action == 0 ? "Left" : action == 2 ? "Right" : "Center", width, height);
		else if (action <= 5 && selectedWidget != null) selectedWidget.alignVertical(action == 3 ? "Top" : action == 5 ? "Bottom" : "Middle", width, height);
		else if (action == 6 && selectedWidget != null) selectedWidget.toggleLayoutLocked();
		else if (action == 7 && selectedWidget != null) selectedWidget.hide();
		else if (action == 8 && selectedWidget != null) selectedWidget.resetPosition();
		else if (action == 9) previewData = !previewData;
		else if (action == 10) hud.editorGrid.set(!hud.editorGrid.get());
		else if (action == 11) hud.editorSafeAreas.set(!hud.editorSafeAreas.get());
		else if (action == 12 && selectedWidget != null) {
			HudWidget copy = UnluckyClient.INSTANCE.hud.duplicate(selectedWidget, width, height);
			if (copy != null) {
				selectedWidget = copy;
				settingsTarget = null;
				// Persist the new identity immediately; normal editor close still saves
				// subsequent placement and styling changes as before.
				UnluckyClient.INSTANCE.config.save();
			}
		}
		return true;
	}

	/** Panel height shrinks to fit the widget count (capped at PANEL_H). */
	private int panelHeight() {
		return Math.min(19 + UnluckyClient.INSTANCE.hud.widgets().size() * PANEL_ROW + 2, PANEL_H);
	}

	private void drawPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (panelX == Integer.MIN_VALUE) {
			panelX = (g.guiWidth() - PANEL_W) / 2;
			panelY = (g.guiHeight() - panelHeight()) / 2;
		}
		var widgets = UnluckyClient.INSTANCE.hud.widgets();
		int height = panelHeight();
		Render2D.roundedRect(g, panelX, panelY, PANEL_W, height, 4, 0xF014141A);
		g.outline(panelX, panelY, PANEL_W, height, ColorUtil.withAlpha(Theme.accent1, 160));
		Render2D.text(g, "HUD", panelX + 6, panelY + 4, Theme.accent1);

		int listTop = panelY + 15;
		int listHeight = height - 19;
		g.enableScissor(panelX, listTop, panelX + PANEL_W, listTop + listHeight);
		int rowY = listTop - panelScroll;
		for (HudWidget widget : widgets) {
			if (rowY + PANEL_ROW >= listTop && rowY <= listTop + listHeight) {
				if (Render2D.hovered(mouseX, mouseY, panelX + 2, rowY, PANEL_W - 8, PANEL_ROW)) {
					g.fill(panelX + 2, rowY, panelX + PANEL_W - 6, rowY + PANEL_ROW, 0x30FFFFFF);
				}
				Render2D.text(g, widget.getDisplayName(), panelX + 6, rowY + 2,
						widget.isVisible() ? Theme.accent1 : Theme.textDim);
			}
			rowY += PANEL_ROW;
		}
		g.disableScissor();

		int contentHeight = widgets.size() * PANEL_ROW;
		if (contentHeight > listHeight) {
			int barHeight = Math.max(listHeight * listHeight / contentHeight, 10);
			int barY = listTop + (listHeight - barHeight) * panelScroll / (contentHeight - listHeight);
			Render2D.rect(g, panelX + PANEL_W - 4, listTop, 2, listHeight, 0x40FFFFFF);
			Render2D.rect(g, panelX + PANEL_W - 4, barY, 2, barHeight, Theme.accent1);
		}
	}

	/** Left = toggle the widget, right = its settings popup, title strip drags. */
	private boolean panelClicked(double mx, double my, int button) {
		if (!Render2D.hovered(mx, my, panelX, panelY, PANEL_W, panelHeight())) {
			return false;
		}
		if (my < panelY + 15) {
			if (button == 0) {
				draggingPanel = true;
				panelDragX = (int) mx - panelX;
				panelDragY = (int) my - panelY;
			}
			return true;
		}
		var widgets = UnluckyClient.INSTANCE.hud.widgets();
		int index = ((int) my - panelY - 15 + panelScroll) / PANEL_ROW;
		if (index >= 0 && index < widgets.size()) {
			HudWidget widget = widgets.get(index);
			selectedWidget = widget;
			if (button == 0) {
				var toggle = widget.toggle();
				if (toggle != null) {
					toggle.set(!toggle.get());
				}
			} else if (button == 1) {
				settingsTarget = widget;
				popupX = (int) mx;
				popupY = (int) my;
				popupScroll = 0;
			}
		}
		return true;
	}

	/** The widget's settings minus any currently hidden by their condition. */
	private java.util.List<unlucky.utility.client.settings.Setting<?>> popupRows(HudWidget widget) {
		java.util.List<unlucky.utility.client.settings.Setting<?>> rows = new java.util.ArrayList<>();
		for (var setting : widget.settings()) {
			if (setting.isVisible()) {
				rows.add(setting);
			}
		}
		return rows;
	}

	private int sliderX() {
		return popupX + POPUP_WIDTH - 70;
	}

	private int rowHeight(unlucky.utility.client.settings.Setting<?> setting) {
		return ROW_HEIGHT + (setting == expandedColor ? unlucky.utility.client.ui.ColorPicker.height() : 0);
	}

	private int pickerWidth() {
		return POPUP_WIDTH - 2 * PICKER_INSET;
	}

	private int popupHeight(java.util.List<unlucky.utility.client.settings.Setting<?>> rows) {
		int height = ROW_HEIGHT + 4;
		for (var setting : rows) {
			height += rowHeight(setting);
		}
		return height;
	}

	private void drawPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		var rows = popupRows(settingsTarget);
		int fullHeight = popupHeight(rows);
		int height = Math.min(fullHeight, Math.max(ROW_HEIGHT * 4, g.guiHeight() - 4));
		popupX = Math.min(popupX, g.guiWidth() - POPUP_WIDTH - 2);
		popupY = Math.min(popupY, g.guiHeight() - height - 2);
		popupX = Math.max(2, popupX);
		popupY = Math.max(2, popupY);
		popupScroll = Math.clamp(popupScroll, 0, Math.max(0, fullHeight - height));

		Render2D.roundedRect(g, popupX, popupY, POPUP_WIDTH, height, 4, 0xF014141A);
		g.outline(popupX, popupY, POPUP_WIDTH, height, ColorUtil.withAlpha(Theme.accent1, 160));
		Render2D.text(g, settingsTarget.getDisplayName(), popupX + 6, popupY + 4, Theme.accent1);
		Render2D.text(g, "R", popupX + POPUP_WIDTH - 12, popupY + 4, Theme.textDim);

		g.enableScissor(popupX, popupY + ROW_HEIGHT, popupX + POPUP_WIDTH, popupY + height);
		int y = popupY + ROW_HEIGHT + 2 - popupScroll;
		for (var setting : rows) {
			if (Render2D.hovered(mouseX, mouseY, popupX, y, POPUP_WIDTH, ROW_HEIGHT)) {
				g.fill(popupX + 2, y, popupX + POPUP_WIDTH - 2, y + ROW_HEIGHT, 0x30FFFFFF);
			}
			Render2D.text(g, setting.getName(), popupX + 6, y + 3, Theme.text);
			switch (setting) {
				case unlucky.utility.client.settings.BooleanSetting b -> {
					int boxX = popupX + POPUP_WIDTH - 14;
					Render2D.roundedRect(g, boxX, y + 3, 9, 9, 2,
							b.get() ? Theme.accent1 : ColorUtil.withAlpha(Theme.textDim, 90));
				}
				case unlucky.utility.client.settings.NumberSetting n -> {
					float frac = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
					Render2D.rect(g, sliderX(), y + 6, SLIDER_W, 3, Theme.surface);
					Render2D.rect(g, sliderX(), y + 6, (int) (SLIDER_W * frac), 3, Theme.accent1);
					Render2D.text(g, n.display(), popupX + POPUP_WIDTH - 6 - Render2D.width(n.display()), y + 3, Theme.textDim);
				}
				case unlucky.utility.client.settings.ModeSetting m ->
						Render2D.text(g, m.label(), popupX + POPUP_WIDTH - 6 - Render2D.width(m.label()), y + 3, Theme.accent2);
				case unlucky.utility.client.settings.ColorSetting c -> {
					int sw = popupX + POPUP_WIDTH - 20;
					Render2D.rect(g, sw - 1, y + 3, 14, 9, Theme.borderDark);
					Render2D.rect(g, sw, y + 4, 12, 7, c.get() | 0xFF000000);
					if (c == expandedColor) {
						colorPicker.render(g, c, popupX + PICKER_INSET, y + ROW_HEIGHT, pickerWidth(),
								mouseX, mouseY);
					}
				}
				case unlucky.utility.client.settings.StringSetting s -> {
					int fieldX = popupX + 42;
					int fieldW = POPUP_WIDTH - 42 - 6;
					Render2D.rect(g, fieldX, y + 3, fieldW, 9, Theme.surface);
					if (s == focusedText) {
						g.outline(fieldX, y + 3, fieldW, 9, Theme.accent1);
						textBox.render(g, fieldX + 3, y + 3, fieldW - 6, true, "click to type");
					} else {
						unlucky.utility.client.ui.TextBox.renderStatic(g, s.get(), fieldX + 3, y + 3,
								fieldW - 6, "click to type");
					}
				}
				default -> {
				}
			}
			y += rowHeight(setting);
		}
		g.disableScissor();
		if (fullHeight > height) {
			int track = height - ROW_HEIGHT - 4;
			int thumb = Math.max(12, track * height / fullHeight);
			int thumbY = popupY + ROW_HEIGHT + (track - thumb) * popupScroll / Math.max(1, fullHeight - height);
			Render2D.rect(g, popupX + POPUP_WIDTH - 3, popupY + ROW_HEIGHT, 2, track, 0x402F3138);
			Render2D.rect(g, popupX + POPUP_WIDTH - 3, thumbY, 2, thumb, Theme.accent1);
		}
	}

	/** Left click inside the open popup; acts on the row (or expanded color picker) under the cursor. */
	private boolean popupClicked(double mx, double my) {
		var rows = popupRows(settingsTarget);
		int fullHeight = popupHeight(rows);
		int visibleHeight = Math.min(fullHeight, Math.max(ROW_HEIGHT * 4, this.height - 4));
		if (!Render2D.hovered(mx, my, popupX, popupY, POPUP_WIDTH, visibleHeight)) {
			return false;
		}
		if (my < popupY + ROW_HEIGHT && mx >= popupX + POPUP_WIDTH - 18) {
			settingsTarget.resetPosition();
			return true;
		}
		int y = popupY + ROW_HEIGHT + 2 - popupScroll;
		for (var setting : rows) {
			if (my >= y && my < y + ROW_HEIGHT) {
				colorPicker.close(); // any row click drops the picker's text focus
				switch (setting) {
					case unlucky.utility.client.settings.BooleanSetting b -> b.set(!b.get());
					case unlucky.utility.client.settings.ModeSetting m -> m.cycle();
					case unlucky.utility.client.settings.NumberSetting n -> {
						if (mx >= sliderX() - 6) {
							draggingNumber = n;
							setNumberFromMouse(n, mx);
						}
					}
					case unlucky.utility.client.settings.ColorSetting c ->
							expandedColor = (expandedColor == c) ? null : c;
					case unlucky.utility.client.settings.StringSetting s -> {
						int fieldX = popupX + 42;
						textFieldTextX = fieldX + 3;
						boolean inField = mx >= fieldX;
						if (focusedText != s) {
							focusedText = s;
							textBox.setText(s.get());
							if (inField) {
								textBox.click(mx - textFieldTextX);
							} else {
								textBox.moveCaretToEnd();
							}
						} else if (inField) {
							textBox.click(mx - textFieldTextX);
						} else {
							focusedText = null; // clicking the label side finishes editing
						}
						draggingText = focusedText != null && inField;
					}
					default -> {
					}
				}
				if (!(setting instanceof unlucky.utility.client.settings.StringSetting)) {
					focusedText = null; // clicking any other row drops text focus
				}
				return true;
			}
			if (setting == expandedColor && my >= y + ROW_HEIGHT && my < y + rowHeight(setting)) {
				focusedText = null;
				return colorPicker.mouseClicked(mx, my, expandedColor,
						popupX + PICKER_INSET, y + ROW_HEIGHT, pickerWidth());
			}
			y += rowHeight(setting);
		}
		return true;
	}

	private void setNumberFromMouse(unlucky.utility.client.settings.NumberSetting n, double mx) {
		double frac = Math.clamp((mx - sliderX()) / SLIDER_W, 0.0, 1.0);
		n.set(n.getMin() + frac * (n.getMax() - n.getMin()));
	}

	private int[] snappedPosition(int x, int y, HudWidget widget) {
		snapGuideX = -1;
		snapGuideY = -1;
		if (precisePlacement()) return new int[]{x, y};
		var hud = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.hud.HudModule.class);
		if (hud.editorGrid.get()) {
			x = Math.round(x / 16.0f) * 16;
			y = Math.round(y / 16.0f) * 16;
		}
		int threshold = 10; // comfortably catches an edge after the 16px grid rounds it
		// Snap to the same gap the HUD actually renders at, so an edge-snapped widget
		// does not jump once the screen padding is changed.
		int snapMargin = hud.screenPadding.getInt();
		int left = snapMargin;
		int right = width - widget.getWidth() - snapMargin;
		int centered = (width - widget.getWidth()) / 2;
		if (Math.abs(x - left) <= threshold) { x = left; snapGuideX = snapMargin; }
		else if (Math.abs(x - right) <= threshold) { x = right; snapGuideX = width - snapMargin; }
		else if (Math.abs(x - centered) <= threshold) { x = centered; snapGuideX = width / 2; }
		int top = snapMargin;
		int bottom = height - widget.getHeight() - snapMargin;
		int middle = (height - widget.getHeight()) / 2;
		if (Math.abs(y - top) <= threshold) { y = top; snapGuideY = snapMargin; }
		else if (Math.abs(y - bottom) <= threshold) { y = bottom; snapGuideY = height - snapMargin; }
		else if (Math.abs(y - middle) <= threshold) { y = middle; snapGuideY = height / 2; }
		return new int[]{Math.clamp(x, 0, Math.max(0, width - widget.getWidth())),
				Math.clamp(y, 0, Math.max(0, height - widget.getHeight()))};
	}

	private boolean precisePlacement() {
		long handle = minecraft.getWindow().handle();
		return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// top toolbar (above the window) gets first pick — lets you switch back / close
		boolean futureToolbar = FutureClickGuiToolbar.isSelected();
		int toolbarButton = futureToolbar
				? FutureClickGuiToolbar.buttonAt(event.x(), event.y(), width, height)
				: ClickGuiToolbar.buttonAt(event.x(), event.y(), width);
		if (toolbarButton >= 0) {
			if (toolbarButton != (futureToolbar ? FutureClickGuiToolbar.HUD_EDITOR : ClickGuiToolbar.HUD_EDITOR)) {
				if (futureToolbar) {
					FutureClickGuiToolbar.activate(toolbarButton, parent);
				} else {
					ClickGuiToolbar.activate(toolbarButton, parent);
				}
			}
			return true;
		}
		if (event.button() == 0 && handleActionClick(event.x(), event.y())) return true;
		// open popup gets first dibs; any click outside it closes it
		if (settingsTarget != null) {
			if (event.button() == 0 && popupClicked(event.x(), event.y())) {
				return true;
			}
			settingsTarget = null;
			expandedColor = null;
			focusedText = null;
			colorPicker.close();
		}
		if (panelClicked(event.x(), event.y(), event.button())) {
			return true;
		}
		for (HudWidget widget : UnluckyClient.INSTANCE.hud.widgets()) {
			if (Render2D.hovered(event.x(), event.y(), widget.getX() - 2, widget.getY() - 2, widget.getWidth() + 4, widget.getHeight() + 4)) {
				selectedWidget = widget;
				if (event.button() == 1) {
					settingsTarget = widget;
					popupX = (int) event.x();
					popupY = (int) event.y();
					popupScroll = 0;
				} else if (!widget.isLayoutLocked()) {
					dragging = widget;
					dragOffsetX = (int) event.x() - widget.getX();
					dragOffsetY = (int) event.y() - widget.getY();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (colorPicker.dragging()) {
			colorPicker.mouseDragged(event.x());
			return true;
		}
		if (draggingNumber != null) {
			setNumberFromMouse(draggingNumber, event.x());
			return true;
		}
		if (draggingText && focusedText != null) {
			textBox.drag(event.x() - textFieldTextX);
			return true;
		}
		if (draggingPanel) {
			panelX = Math.clamp((int) event.x() - panelDragX, 0, Math.max(this.width - PANEL_W, 0));
			panelY = Math.clamp((int) event.y() - panelDragY, 0, Math.max(this.height - PANEL_H, 0));
			return true;
		}
		if (dragging != null) {
			int[] position = snappedPosition((int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY, dragging);
			dragging.moveTo(position[0], position[1], this.width, this.height);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		snapGuideX = -1;
		snapGuideY = -1;
		draggingNumber = null;
		colorPicker.mouseReleased();
		draggingPanel = false;
		draggingText = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (settingsTarget != null) {
			var rows = popupRows(settingsTarget);
			int fullHeight = popupHeight(rows);
			int visibleHeight = Math.min(fullHeight, Math.max(ROW_HEIGHT * 4, this.height - 4));
			if (Render2D.hovered(mouseX, mouseY, popupX, popupY, POPUP_WIDTH, visibleHeight)) {
				popupScroll = Math.clamp(popupScroll - (int) (scrollY * 24), 0, Math.max(0, fullHeight - visibleHeight));
				return true;
			}
		}
		if (Render2D.hovered(mouseX, mouseY, panelX, panelY, PANEL_W, panelHeight())) {
			int listHeight = panelHeight() - 19;
			int max = Math.max(UnluckyClient.INSTANCE.hud.widgets().size() * PANEL_ROW - listHeight, 0);
			panelScroll = Math.clamp(panelScroll - (int) (scrollY * 18), 0, max);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (colorPicker.charTyped(event)) {
			return true;
		}
		if (focusedText != null && textBox.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (colorPicker.typing()) {
			return colorPicker.keyPressed(event);
		}
		if (focusedText != null) {
			if (!textBox.keyPressed(event)
					&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_ESCAPE)) {
				focusedText = null;
			}
			return true; // swallow keys while typing so hotkeys don't fire
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		HudManager.setPreviewData(false);
		UnluckyClient.INSTANCE.config.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		HudManager.setPreviewData(false);
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
