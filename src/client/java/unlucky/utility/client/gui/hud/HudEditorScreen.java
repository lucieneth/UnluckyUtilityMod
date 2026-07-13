package unlucky.utility.client.gui.hud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.ClickGuiToolbar;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/** Drag HUD widgets around; right-click one for its settings. Positions save on close. */
public class HudEditorScreen extends Screen {
	/** 16x16 GUI sprite (mcmeta scaling "tile"): one white dot at (8,8), tinted at draw time. */
	private static final net.minecraft.resources.Identifier GRID_SPRITE =
			unlucky.utility.client.UnluckyClientMod.id("hud_grid");
	private static final int POPUP_WIDTH = 150;
	private static final int ROW_HEIGHT = 15;
	private static final int SLIDER_W = 44;
	private static final int COLOR_BAR = 11; // height of each HSB bar when a color row is expanded

	private static final int PANEL_W = 132;
	private static final int PANEL_H = 190;
	private static final int PANEL_ROW = 12;

	private HudWidget dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	// right-click settings popup
	private HudWidget settingsTarget;
	private int popupX;
	private int popupY;
	private unlucky.utility.client.settings.NumberSetting draggingNumber;
	private unlucky.utility.client.settings.ColorSetting expandedColor; // color row showing its HSB bars
	private unlucky.utility.client.settings.ColorSetting draggingColor;
	private int draggingColorBar = -1; // 0 hue, 1 sat, 2 val
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

	public HudEditorScreen() {
		super(Component.literal("HUD Editor"));
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
			g.blurBeforeThisStratum();
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
		// ~14k at 1440p scale 1) tanked the editor to 30 fps (plan.md Phase 10).
		g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, GRID_SPRITE,
				0, 0, g.guiWidth(), g.guiHeight(), 0x20FFFFFF);

		UnluckyClient.INSTANCE.hud.render(g, true);

		for (HudWidget widget : UnluckyClient.INSTANCE.hud.widgets()) {
			boolean hovered = Render2D.hovered(mouseX, mouseY, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
			int outline = hovered || widget == dragging
					? Theme.accent1
					: ColorUtil.withAlpha(Theme.textDim, widget.isVisible() ? 120 : 45);
			g.outline(widget.getX() - 2, widget.getY() - 2, widget.getWidth() + 4, widget.getHeight() + 4, outline);
		}

		String hint = "Drag widgets to move them — right-click for settings";
		Render2D.text(g, hint, (g.guiWidth() - Render2D.width(hint)) / 2, g.guiHeight() - 20, Theme.textDim);

		drawPanel(g, mouseX, mouseY);
		if (settingsTarget != null) {
			drawPopup(g, mouseX, mouseY);
		}

		// shared top toolbar (HUD editor highlighted) so you can jump back to the ClickGUI
		String toolbarLabel = ClickGuiToolbar.draw(g, mouseX, mouseY, width, ClickGuiToolbar.HUD_EDITOR);
		if (toolbarLabel != null) {
			ClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
		}
		if (unlucky.utility.client.util.PerfDebug.ENABLED) {
			unlucky.utility.client.util.PerfDebug.end("editor.extract", perfStart);
		}
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
				Render2D.text(g, widget.getName(), panelX + 6, rowY + 2,
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
			if (button == 0) {
				// the first popup row is always the widget's own toggle
				if (popupRows(widget).get(0) instanceof unlucky.utility.client.settings.BooleanSetting toggle) {
					toggle.set(!toggle.get());
				}
			} else if (button == 1) {
				settingsTarget = widget;
				popupX = (int) mx;
				popupY = (int) my;
			}
		}
		return true;
	}

	private java.util.List<unlucky.utility.client.settings.Setting<?>> popupRows(HudWidget widget) {
		var hud = UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.hud.HudModule.class);
		return switch (widget.getName()) {
			case "Watermark" -> java.util.List.of(hud.watermark, hud.watermarkBg, hud.watermarkLine,
					hud.watermarkColor1, hud.watermarkColor2, hud.watermarkAnimate, hud.watermarkSpeed);
			case "Info" -> java.util.List.of(hud.info, hud.infoBg, hud.infoLayout, hud.infoFps,
					hud.infoPing, hud.infoTps, hud.infoTime, hud.infoTime24h, hud.infoSeconds, hud.infoMcTime);
			case "ArrayList" -> java.util.List.of(
					hud.arrayList, hud.arrayBg, hud.arrayAnimate, hud.arraySpeed, hud.arrayDirection);
			case "TargetHUD" -> java.util.List.of(hud.targetHud, hud.targetHudBg, hud.targetHudSource,
					hud.targetHudModel, hud.targetHudHealthText, hud.targetHudHurtFlash,
					hud.targetHudGear, hud.targetHudEnchants, hud.targetHudPotions);
			case "PlayerModel" -> java.util.List.of(hud.playerModel, hud.playerModelBg,
					hud.playerModelFollow, hud.playerModelArmor, hud.playerModelHeld);
			case "Keystrokes" -> java.util.List.of(hud.keystrokes, hud.keystrokesBg, hud.keystrokesMouse,
					hud.keystrokesSpace, hud.keystrokesCps, hud.keystrokesSize);
			case "ArmorHUD" -> java.util.List.of(hud.armorHud, hud.armorHudBg, hud.armorHudVertical,
					hud.armorHudHeld, hud.armorHudPercent, hud.armorHudBlink);
			case "PotionHUD" -> java.util.List.of(hud.potionHud, hud.potionHudBg, hud.potionHudCompact,
					hud.potionHudAmbient);
			case "Coords" -> java.util.List.of(hud.coords, hud.coordsBg, hud.coordsNether,
					hud.coordsCompact, hud.coordsDegrees);
			case "Speedometer" -> java.util.List.of(hud.speed, hud.speedBg, hud.speedUnit,
					hud.speedSpark, hud.speedDecimals);
			case "InventoryViewer" -> java.util.List.of(hud.invView, hud.invViewOpacity, hud.invViewHideEmpty);
			case "PopCounter" -> java.util.List.of(hud.popCounter, hud.popCounterBg,
					hud.popCounterTarget, hud.popCounterAnnounce);
			case "SessionInfo" -> java.util.List.of(hud.sessionInfo, hud.sessionBg, hud.sessionTime,
					hud.sessionKills, hud.sessionDeaths, hud.sessionKd);
			case "ItemCounter" -> java.util.List.of(hud.itemCounter, hud.itemCounterBg,
					hud.itemCounterItem, hud.itemCounterIcon, hud.itemCounterWarn);
			case "Radar" -> java.util.List.of(hud.radar, hud.radarBg, hud.radarRange, hud.radarSize,
					hud.radarRotate, hud.radarPlayers, hud.radarHostiles, hud.radarPassives);
			case "CompassBar" -> java.util.List.of(hud.compass, hud.compassBg, hud.compassWidth,
					hud.compassFov, hud.compassPlayers, hud.compassFriendsOnly, hud.compassRange);
			case "ItemPickups" -> java.util.List.of(hud.itemPickups, hud.itemPickupsBg, hud.itemPickupsDuration);
			case "CustomText" -> java.util.List.of(hud.customText, hud.customTextValue, hud.customTextBg, hud.customTextColor);
			case "Greeter" -> java.util.List.of(hud.greeter, hud.greeterBg, hud.greeterColor);
			default -> java.util.List.of();
		};
	}

	private int sliderX() {
		return popupX + POPUP_WIDTH - 70;
	}

	private int rowHeight(unlucky.utility.client.settings.Setting<?> setting) {
		return ROW_HEIGHT + (setting == expandedColor ? 3 * COLOR_BAR : 0);
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
		int height = popupHeight(rows);
		popupX = Math.min(popupX, g.guiWidth() - POPUP_WIDTH - 2);
		popupY = Math.min(popupY, g.guiHeight() - height - 2);

		Render2D.roundedRect(g, popupX, popupY, POPUP_WIDTH, height, 4, 0xF014141A);
		g.outline(popupX, popupY, POPUP_WIDTH, height, ColorUtil.withAlpha(Theme.accent1, 160));
		Render2D.text(g, settingsTarget.getName(), popupX + 6, popupY + 4, Theme.accent1);

		int y = popupY + ROW_HEIGHT + 2;
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
						Render2D.text(g, m.get(), popupX + POPUP_WIDTH - 6 - Render2D.width(m.get()), y + 3, Theme.accent2);
				case unlucky.utility.client.settings.ColorSetting c -> {
					int sw = popupX + POPUP_WIDTH - 20;
					Render2D.rect(g, sw - 1, y + 3, 14, 9, Theme.borderDark);
					Render2D.rect(g, sw, y + 4, 12, 7, c.get() | 0xFF000000);
					if (c == expandedColor) {
						drawColorBars(g, c, y + ROW_HEIGHT);
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
	}

	private void drawColorBars(GuiGraphicsExtractor g, unlucky.utility.client.settings.ColorSetting c, int barsY) {
		float[] hsb = c.hsb();
		drawColorBar(g, c, barsY, "Hue", hsb[0], true);
		drawColorBar(g, c, barsY + COLOR_BAR, "Sat", hsb[1], false);
		drawColorBar(g, c, barsY + 2 * COLOR_BAR, "Val", hsb[2], false);
	}

	private void drawColorBar(GuiGraphicsExtractor g, unlucky.utility.client.settings.ColorSetting c, int rowY,
			String label, float value, boolean rainbow) {
		Render2D.textNoShadow(g, label, popupX + 8, rowY + 2, Theme.textDim);
		int barX = popupX + 34;
		int barW = POPUP_WIDTH - 42;
		int barY = rowY + 2;
		Render2D.rect(g, barX - 1, barY - 1, barW + 2, 7, Theme.borderDark);
		if (rainbow) {
			for (int i = 0; i < barW; i++) {
				g.fill(barX + i, barY, barX + i + 1, barY + 5, ColorUtil.hsb((float) i / barW, 1.0f, 1.0f, 255));
			}
		} else {
			Render2D.rect(g, barX, barY, barW, 5, Theme.surface);
			Render2D.rect(g, barX, barY, (int) (barW * value), 5, c.get() | 0xFF000000);
		}
		Render2D.rect(g, barX + (int) (value * (barW - 1)), barY - 1, 1, 7, Theme.text);
	}

	/** Left click inside the open popup; acts on the row (or color bar) under the cursor. */
	private boolean popupClicked(double mx, double my) {
		var rows = popupRows(settingsTarget);
		if (!Render2D.hovered(mx, my, popupX, popupY, POPUP_WIDTH, popupHeight(rows))) {
			return false;
		}
		int y = popupY + ROW_HEIGHT + 2;
		for (var setting : rows) {
			if (my >= y && my < y + ROW_HEIGHT) {
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
				draggingColor = expandedColor;
				draggingColorBar = Math.min(2, (int) ((my - (y + ROW_HEIGHT)) / COLOR_BAR));
				setColorFromMouse(mx);
				return true;
			}
			y += rowHeight(setting);
		}
		return true;
	}

	private void setColorFromMouse(double mx) {
		int barX = popupX + 34;
		int barW = POPUP_WIDTH - 42;
		float frac = (float) Math.clamp((mx - barX) / barW, 0.0, 1.0);
		float[] hsb = draggingColor.hsb();
		switch (draggingColorBar) {
			case 0 -> hsb[0] = frac;
			case 1 -> hsb[1] = frac;
			case 2 -> hsb[2] = frac;
			default -> {
			}
		}
		draggingColor.setHsb(hsb[0], hsb[1], hsb[2], draggingColor.alpha());
	}

	private void setNumberFromMouse(unlucky.utility.client.settings.NumberSetting n, double mx) {
		double frac = Math.clamp((mx - sliderX()) / SLIDER_W, 0.0, 1.0);
		n.set(n.getMin() + frac * (n.getMax() - n.getMin()));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// top toolbar (above the window) gets first pick — lets you switch back / close
		int toolbarButton = ClickGuiToolbar.buttonAt(event.x(), event.y(), width);
		if (toolbarButton >= 0) {
			if (toolbarButton != ClickGuiToolbar.HUD_EDITOR) {
				ClickGuiToolbar.activate(toolbarButton);
			}
			return true;
		}
		// open popup gets first dibs; any click outside it closes it
		if (settingsTarget != null) {
			if (event.button() == 0 && popupClicked(event.x(), event.y())) {
				return true;
			}
			settingsTarget = null;
			expandedColor = null;
			focusedText = null;
		}
		if (panelClicked(event.x(), event.y(), event.button())) {
			return true;
		}
		for (HudWidget widget : UnluckyClient.INSTANCE.hud.widgets()) {
			if (Render2D.hovered(event.x(), event.y(), widget.getX() - 2, widget.getY() - 2, widget.getWidth() + 4, widget.getHeight() + 4)) {
				if (event.button() == 1) {
					settingsTarget = widget;
					popupX = (int) event.x();
					popupY = (int) event.y();
				} else {
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
		if (draggingColor != null) {
			setColorFromMouse(event.x());
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
			dragging.moveTo((int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY, this.width, this.height);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		draggingNumber = null;
		draggingColor = null;
		draggingColorBar = -1;
		draggingPanel = false;
		draggingText = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
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
		if (focusedText != null && textBox.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
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
		UnluckyClient.INSTANCE.config.save();
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
