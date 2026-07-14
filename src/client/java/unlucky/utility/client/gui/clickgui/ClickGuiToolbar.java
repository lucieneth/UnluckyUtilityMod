package unlucky.utility.client.gui.clickgui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.gui.hud.HudEditorScreen;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * The floating icon toolbar pinned to the top-centre of the screen. Shared by the
 * ClickGUI and the HUD editor so you can switch between them (and close) from
 * either — pass the index of the view you're currently on as {@code active}.
 */
public final class ClickGuiToolbar {
	public static final int CLICKGUI = 0;
	public static final int HUD_EDITOR = 1;
	public static final int FRIENDS = 2;
	public static final int CONSOLE = 3;

	private static final int BTN = 28;
	private static final int H = 22;
	private static final int PAD = 6;
	private static final int ICON = 14;
	private static final int Y = 6;
	private static final String[] LABELS = {"ClickGUI", "HUD Editor", "Friends", "Console", "Configs (soon)", "Close"};
	private static final Identifier[] ICONS = {
			icon("mouse"), icon("hud_editor"), icon("friends"), icon("console"), icon("settings"), icon("close")};

	private ClickGuiToolbar() {
	}

	private static Identifier icon(String name) {
		return UnluckyClientMod.id("textures/gui/icons/" + name + ".png");
	}

	public static int width() {
		return LABELS.length * BTN + PAD * 2;
	}

	private static int x(int screenWidth) {
		return (screenWidth - width()) / 2;
	}

	/** Index of the toolbar button under the cursor, or -1. */
	public static int buttonAt(double mx, double my, int screenWidth) {
		int cellX = x(screenWidth) + PAD;
		for (int i = 0; i < LABELS.length; i++) {
			if (Render2D.hovered(mx, my, cellX, Y, BTN, H)) {
				return i;
			}
			cellX += BTN;
		}
		return -1;
	}

	/**
	 * Runs the navigation for a button; caller should skip the currently-active one.
	 * The {@code parent} travels with you across every view (and is what Close
	 * returns to), so a ClickGUI opened from the title screen goes back to the
	 * title screen instead of a blank one. In-game it's simply null.
	 */
	public static void activate(int button, Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (button == LABELS.length - 1) { // Close is always last
			mc.gui.setScreen(parent);
			return;
		}
		switch (button) {
			case CLICKGUI -> mc.gui.setScreen(new ClickGuiScreen(parent));
			case HUD_EDITOR -> mc.gui.setScreen(new HudEditorScreen(parent));
			case FRIENDS -> mc.gui.setScreen(new unlucky.utility.client.gui.friends.FriendsScreen(parent));
			case CONSOLE -> mc.gui.setScreen(new unlucky.utility.client.gui.console.ConsoleScreen(parent));
			default -> { } // Configs — placeholder
		}
	}

	/** Draws the bar and returns the hovered button's label (for a tooltip), or null. */
	public static String draw(GuiGraphicsExtractor g, int mouseX, int mouseY, int screenWidth, int active) {
		int barW = width();
		int barX = x(screenWidth);
		Render2D.roundedRect(g, barX - 1, Y - 1, barW + 2, H + 2, 7, Theme.borderDark);
		Render2D.roundedRect(g, barX, Y, barW, H, 6, Theme.panel);
		g.outline(barX, Y, barW, H, Theme.border);

		int cellX = barX + PAD;
		int cy = Y + H / 2;
		String hovered = null;
		for (int i = 0; i < LABELS.length; i++) {
			int cx = cellX + BTN / 2;
			boolean hover = Render2D.hovered(mouseX, mouseY, cellX, Y, BTN, H);
			boolean isActive = i == active;
			boolean close = i == LABELS.length - 1;
			if (hover && !isActive) {
				Render2D.roundedRect(g, cellX + 2, Y + 2, BTN - 4, H - 4, 4, Theme.surface);
			}
			int color = isActive ? Theme.text : (hover ? ColorUtil.lerp(Theme.textDim, Theme.text, 0.6f) : 0xFF63636A);
			if (close && hover) {
				color = 0xFFFF5555;
			}
			drawIcon(g, ICONS[i], cx, cy, color);
			if (isActive) { // flowing accent underline marks the current view
				Render2D.rect(g, cx - 6, Y + H - 3, 12, 2, Theme.flowingAccent(0.0f));
			}
			if (i < LABELS.length - 1) { // divider
				Render2D.rect(g, cellX + BTN, Y + 5, 1, H - 10, Theme.border);
			}
			if (hover) {
				hovered = LABELS[i];
			}
			cellX += BTN;
		}
		return hovered;
	}

	private static void drawIcon(GuiGraphicsExtractor g, Identifier icon, int cx, int cy, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, icon, cx - ICON / 2, cy - ICON / 2,
				0.0f, 0.0f, ICON, ICON, ICON, ICON, color);
	}

	/** A small label tooltip near the cursor, matching the ClickGUI style. */
	public static void tooltip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
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
}
