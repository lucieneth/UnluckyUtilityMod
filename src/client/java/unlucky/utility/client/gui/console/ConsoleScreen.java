package unlucky.utility.client.gui.console;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.command.CommandManager;
import unlucky.utility.client.gui.clickgui.ClickGuiToolbar;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.Render2D;

/**
 * CS:GO-style developer console (default key: semicolon, with a layout
 * fallback for keyboards that put {@code ;} elsewhere). A movable, resizable
 * window — drag the title bar, drag the bottom-right grip, close on the
 * title-bar {@code x} — defaulting to the ClickGUI's 440x280. Commands are
 * bare words ("bind", "friend add x" — see {@link CommandManager}), unlike
 * the chat's dot prefix. Log, input history, scroll and window geometry are
 * all static so the console picks up exactly where it left off.
 */
public class ConsoleScreen extends Screen {
	private static final int PAD = 10;
	private static final int TITLE_H = 18;
	private static final int INPUT_H = 16;
	private static final int LINE_H = 11;
	private static final int MAX_LINES = 500;
	private static final int MIN_W = 280;
	private static final int MIN_H = 160;

	private record Line(String text, int color) {
	}

	private static final List<Line> LOG = new ArrayList<>();
	private static final TextBox INPUT = new TextBox();
	private static final List<String> HISTORY = new ArrayList<>();
	private static int historyIndex = -1;
	private static int scroll; // lines up from the bottom
	private static boolean greeted;

	// window geometry survives closing the console (ClickGUI-style defaults)
	private static int winX = Integer.MIN_VALUE;
	private static int winY;
	private static int winW = 440;
	private static int winH = 280;

	private final long openedAt = System.currentTimeMillis();
	private boolean draggingInput;
	private boolean draggingWindow;
	private boolean resizing;
	private int dragOffsetX;
	private int dragOffsetY;

	public ConsoleScreen() {
		super(Component.literal("Console"));
		if (!greeted) {
			greeted = true;
			print("Unlucky console - type help for commands", Theme.textDim);
		}
	}

	/** Appends a line; callable from async command feedback at any time. */
	public static void print(String text, int color) {
		LOG.add(new Line(text, color));
		if (LOG.size() > MAX_LINES) {
			LOG.remove(0);
		}
		scroll = 0; // new output snaps the view back to the bottom
	}

	@Override
	protected void init() {
		winW = Math.clamp(winW, MIN_W, Math.max(width - 20, MIN_W));
		winH = Math.clamp(winH, MIN_H, Math.max(height - 40, MIN_H));
		if (winX == Integer.MIN_VALUE) {
			winX = (width - winW) / 2;
			winY = (height - winH) / 2;
		}
		winX = Math.clamp(winX, 0, Math.max(width - winW, 0));
		winY = Math.clamp(winY, 0, Math.max(height - winH, 0));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (UnluckyClient.INSTANCE.modules.get(ThemeModule.class).blur.get()) {
			g.blurBeforeThisStratum();
		}
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x50 << 24);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// frame + title bar
		Render2D.rect(g, winX - 2, winY - 2, winW + 4, winH + 4, Theme.frame);
		Render2D.rect(g, winX, winY, winW, winH, Theme.window);
		g.outline(winX, winY, winW, winH, Theme.frameBevel);
		Render2D.rect(g, winX + 1, winY + TITLE_H - 1, winW - 2, 1, Theme.borderDark);
		Render2D.text(g, "Console", winX + PAD, winY + 5, Theme.text);

		// close cross, CS-style top right
		boolean closeHover = Render2D.hovered(mouseX, mouseY, closeX(), winY + 3, 12, 12);
		Render2D.textNoShadow(g, "x", closeX() + 3, winY + 5, closeHover ? 0xFFFF5555 : Theme.textDim);

		// output pane
		int outTop = winY + TITLE_H + 4;
		int outBottom = winY + winH - PAD - INPUT_H - 6;
		int outX = winX + PAD;
		int outW = winW - 2 * PAD;
		Render2D.rect(g, outX - 1, outTop - 1, outW + 2, outBottom - outTop + 2, Theme.borderDark);
		Render2D.rect(g, outX, outTop, outW, outBottom - outTop, Theme.sidebar);

		int visible = Math.max(1, (outBottom - outTop - 6) / LINE_H);
		scroll = Math.clamp(scroll, 0, Math.max(0, LOG.size() - visible));
		g.enableScissor(outX, outTop, outX + outW, outBottom);
		int start = Math.max(0, LOG.size() - visible - scroll);
		int end = Math.min(LOG.size(), start + visible);
		int lineY = outBottom - 4 - (end - start) * LINE_H;
		for (int i = start; i < end; i++) {
			Line line = LOG.get(i);
			Render2D.textNoShadow(g, line.text(), outX + 4, lineY + 2, line.color());
			lineY += LINE_H;
		}
		g.disableScissor();

		// scrollbar
		if (LOG.size() > visible) {
			int viewH = outBottom - outTop;
			int barH = Math.max(viewH * visible / LOG.size(), 10);
			int maxScroll = LOG.size() - visible;
			int barY = outTop + (viewH - barH) * (maxScroll - scroll) / maxScroll;
			Render2D.rect(g, outX + outW - 3, outTop, 2, viewH, Theme.surface);
			Render2D.verticalGradient(g, outX + outW - 3, barY, 2, barH, Theme.accent1, Theme.accent2);
		}

		// input line + submit
		int inY = inputY();
		int submitW = 44;
		int inW = inputW();
		Render2D.rect(g, outX - 1, inY - 1, inW + 2, INPUT_H + 2, Theme.borderDark);
		Render2D.rect(g, outX, inY, inW, INPUT_H, Theme.surface);
		INPUT.render(g, outX + 4, inY + 4, inW - 8, true, "help");
		boolean submitHover = Render2D.hovered(mouseX, mouseY, submitX(), inY, submitW, INPUT_H);
		Render2D.rect(g, submitX() - 1, inY - 1, submitW + 2, INPUT_H + 2, Theme.borderDark);
		Render2D.rect(g, submitX(), inY, submitW, INPUT_H, submitHover ? Theme.surface : Theme.sidebar);
		Render2D.textNoShadow(g, "Submit", submitX() + (submitW - Render2D.width("Submit")) / 2, inY + 4,
				submitHover ? Theme.text : Theme.textDim);

		// resize grip: three diagonal ticks in the bottom-right corner
		boolean gripHover = resizing || Render2D.hovered(mouseX, mouseY, winX + winW - 10, winY + winH - 10, 10, 10);
		int gripColor = gripHover ? Theme.text : Theme.textDim;
		for (int i = 0; i < 3; i++) {
			Render2D.rect(g, winX + winW - 3 - i * 3, winY + winH - 3, 2, 1, gripColor);
			Render2D.rect(g, winX + winW - 3, winY + winH - 3 - i * 3, 1, 2, gripColor);
		}

		String toolbarLabel = ClickGuiToolbar.draw(g, mouseX, mouseY, width, ClickGuiToolbar.CONSOLE);
		if (toolbarLabel != null) {
			ClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
		}
	}

	private int closeX() {
		return winX + winW - 15;
	}

	private int inputY() {
		return winY + winH - PAD - INPUT_H;
	}

	private int inputW() {
		return winW - 2 * PAD - 44 - 4;
	}

	private int submitX() {
		return winX + winW - PAD - 44;
	}

	private void submit() {
		String line = INPUT.text().trim();
		if (line.isEmpty()) {
			return;
		}
		print("> " + line, Theme.textDim);
		HISTORY.add(line);
		historyIndex = -1;
		INPUT.clear();
		if (line.equalsIgnoreCase("clear")) {
			LOG.clear();
			return;
		}
		CommandManager.execute(line, text -> print(text, Theme.text));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int toolbarButton = ClickGuiToolbar.buttonAt(mx, my, width);
		if (toolbarButton >= 0) {
			if (toolbarButton != ClickGuiToolbar.CONSOLE) {
				ClickGuiToolbar.activate(toolbarButton);
			}
			return true;
		}
		if (event.button() == 0) {
			if (Render2D.hovered(mx, my, closeX(), winY + 3, 12, 12)) {
				onClose();
				return true;
			}
			if (Render2D.hovered(mx, my, winX + winW - 10, winY + winH - 10, 10, 10)) {
				resizing = true;
				return true;
			}
			if (Render2D.hovered(mx, my, submitX(), inputY(), 44, INPUT_H)) {
				submit();
				return true;
			}
			if (Render2D.hovered(mx, my, winX + PAD, inputY(), inputW(), INPUT_H)) {
				INPUT.click(mx - (winX + PAD + 4));
				draggingInput = true;
				return true;
			}
			if (Render2D.hovered(mx, my, winX, winY, winW, TITLE_H)) {
				draggingWindow = true;
				dragOffsetX = (int) mx - winX;
				dragOffsetY = (int) my - winY;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (resizing) {
			winW = Math.clamp((int) event.x() - winX + 2, MIN_W, Math.max(width - winX, MIN_W));
			winH = Math.clamp((int) event.y() - winY + 2, MIN_H, Math.max(height - winY, MIN_H));
			return true;
		}
		if (draggingWindow) {
			winX = Math.clamp((int) event.x() - dragOffsetX, 0, Math.max(width - winW, 0));
			winY = Math.clamp((int) event.y() - dragOffsetY, 0, Math.max(height - winH, 0));
			return true;
		}
		if (draggingInput) {
			INPUT.drag(event.x() - (winX + PAD + 4));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingInput = false;
		draggingWindow = false;
		resizing = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll += (int) scrollY * 3;
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// swallow the ';' that trails the open keypress so it doesn't type itself
		if (System.currentTimeMillis() - openedAt < 200 && ";".equals(event.codepointAsString())) {
			return true;
		}
		if (INPUT.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		switch (event.key()) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				submit();
				return true;
			}
			case GLFW.GLFW_KEY_UP -> {
				if (!HISTORY.isEmpty()) {
					historyIndex = historyIndex < 0 ? HISTORY.size() - 1 : Math.max(0, historyIndex - 1);
					INPUT.setText(HISTORY.get(historyIndex));
					INPUT.moveCaretToEnd();
				}
				return true;
			}
			case GLFW.GLFW_KEY_DOWN -> {
				if (historyIndex >= 0) {
					historyIndex++;
					if (historyIndex >= HISTORY.size()) {
						historyIndex = -1;
						INPUT.clear();
					} else {
						INPUT.setText(HISTORY.get(historyIndex));
						INPUT.moveCaretToEnd();
					}
				}
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				scroll += 10;
				return true;
			}
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				scroll -= 10;
				return true;
			}
			default -> {
			}
		}
		if (INPUT.keyPressed(event)) {
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE
				|| (event.key() == UnluckyClient.INSTANCE.consoleKey && INPUT.isEmpty())) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
