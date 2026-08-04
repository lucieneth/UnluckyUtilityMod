package unlucky.utility.client.gui.clickgui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * Future's navigation rail: the same destinations as the Skeet toolbar, but
 * rendered as a compact, sharp-edged vertical control on the right-hand side.
 * Keeping it separate means Future styling never leaks into the Skeet UI.
 */
public final class FutureClickGuiToolbar {
	public static final int CLICKGUI = ClickGuiToolbar.CLICKGUI;
	public static final int HUD_EDITOR = ClickGuiToolbar.HUD_EDITOR;
	public static final int FRIENDS = ClickGuiToolbar.FRIENDS;
	public static final int CONSOLE = ClickGuiToolbar.CONSOLE;
	public static final int CONFIGS = ClickGuiToolbar.CONFIGS;
	public static final int CLOSE = 5;

	private static final int CELL = 22;
	private static final int BAR_W = 26;
	private static final int PAD = 2;
	private static final int GAP = 1;
	private static final int EDGE = 4;
	private static final int ICON = 12;
	private static final String[] LABELS = {"ClickGUI", "HUD Editor", "Friends", "Console", "Configs", "Close"};
	private static final Identifier[] ICONS = {
			icon("mouse"), icon("hud_editor"), icon("friends"), icon("console"), icon("settings"), icon("close")};

	private FutureClickGuiToolbar() {
	}

	private static Identifier icon(String name) {
		return UnluckyClientMod.id("textures/gui/icons/" + name + ".png");
	}

	public static int height() {
		return PAD * 2 + LABELS.length * CELL + (LABELS.length - 1) * GAP;
	}

	private static int x(int screenWidth) {
		return Math.max(2, screenWidth - BAR_W - EDGE);
	}

	private static int y(int screenHeight) {
		return Math.max(2, (screenHeight - height()) / 2);
	}

	/** Button beneath the cursor, or {@code -1} when it is outside the rail. */
	public static int buttonAt(double mouseX, double mouseY, int screenWidth, int screenHeight) {
		int x = x(screenWidth);
		int y = y(screenHeight) + PAD;
		for (int i = 0; i < LABELS.length; i++) {
			if (Render2D.hovered(mouseX, mouseY, x + PAD, y, BAR_W - PAD * 2, CELL)) {
				return i;
			}
			y += CELL + GAP;
		}
		return -1;
	}

	/** Draws the rail and returns the hovered action label, if any. */
	public static String draw(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenWidth, int screenHeight, int active) {
		int barX = x(screenWidth);
		int barY = y(screenHeight);
		int barH = height();
		int accent = FuturePalette.accent();

		// It is a Future window too: blur only its local backing rectangle, never
		// the full world. This stays in the same clipped compositor as the columns.
		FuturePanelBlur.registerPanel(barX, barY, BAR_W, barH);
		Render2D.rect(g, barX, barY, BAR_W, barH, FuturePalette.chromeSurface(179));
		g.outline(barX, barY, BAR_W, barH, ColorUtil.withAlpha(accent, 212));

		String hovered = null;
		int cellX = barX + PAD;
		int cellY = barY + PAD;
		int cellW = BAR_W - PAD * 2;
		for (int i = 0; i < LABELS.length; i++) {
			boolean isActive = i == active;
			boolean isHover = Render2D.hovered(mouseX, mouseY, cellX, cellY, cellW, CELL);
			boolean close = i == CLOSE;
			if (isActive) {
				Render2D.rect(g, cellX, cellY, cellW, CELL, ColorUtil.withAlpha(accent, 136));
			} else if (isHover) {
				Render2D.rect(g, cellX, cellY, cellW, CELL, ColorUtil.withAlpha(accent, close ? 95 : 68));
			}

			int iconColor = isActive ? 0xFFF2E8E8 : (isHover ? 0xFFE1D2D2 : 0xFF7E7E7E);
			if (close && isHover) {
				iconColor = 0xFFFF8585;
			}
			g.blit(RenderPipelines.GUI_TEXTURED, ICONS[i], cellX + (cellW - ICON) / 2,
					cellY + (CELL - ICON) / 2, 0.0f, 0.0f, ICON, ICON, ICON, ICON, iconColor);
			if (i < LABELS.length - 1) {
				Render2D.rect(g, barX + 3, cellY + CELL, BAR_W - 6, 1, FuturePalette.seam(112));
			}
			if (isHover) {
				hovered = LABELS[i];
			}
			cellY += CELL + GAP;
		}
		return hovered;
	}

	/** Opens the selected destination, preserving the caller's original parent. */
	public static void activate(int button, Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (button == CLOSE) {
			mc.gui.setScreen(parent);
			return;
		}
		// ClickGuiScreen.create() respects the currently selected GUI style, so
		// routing through it returns to Future rather than silently changing skins.
		ClickGuiToolbar.activate(button, parent);
	}

	/** Future-glass tooltip, deliberately not the rounded Skeet tooltip. */
	public static void tooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
		int width = Render2D.width(text);
		int x = mouseX - width - 12;
		int y = mouseY - 4;
		if (x < 3) {
			x = mouseX + 10;
		}
		Render2D.rect(g, x - 3, y - 3, width + 6, 15, FuturePalette.chromeSurface(208));
		g.outline(x - 3, y - 3, width + 6, 15, ColorUtil.withAlpha(FuturePalette.accent(), 212));
		Render2D.textNoShadow(g, text, x, y, 0xFFF2E8E8);
	}

	/** Whether companion screens should show the same rail rather than Skeet's bar. */
	public static boolean isSelected() {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		return theme != null && theme.clickGuiStyle.is("Future");
	}

}
