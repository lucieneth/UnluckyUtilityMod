package unlucky.utility.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.util.Render2D;

/**
 * Editing state + rendering for a single-line text field: caret, selection
 * (shift+arrows, ctrl+A, click/drag, double-click), clipboard (ctrl+C/X/V),
 * word jumps (ctrl+arrows/backspace/delete) and horizontal scrolling that
 * keeps the caret visible. Call sites draw their own field chrome, translate
 * mouse X to be relative to the text origin, and feed events here; every
 * text mutation fires the change listener.
 */
public class TextBox {
	private static final int FONT_HEIGHT = 9;
	private static final long DOUBLE_CLICK_MS = 300;

	private String text = "";
	private int caret;
	private int anchor; // other end of the selection; == caret when none
	private int scroll; // index of the first visible character
	private long lastClickTime;
	private Runnable changeListener;

	public String text() {
		return text;
	}

	/** External sync (setting changed elsewhere); does not fire the listener. */
	public void setText(String s) {
		String next = s == null ? "" : s;
		if (!text.equals(next)) {
			text = next;
			caret = Math.min(caret, text.length());
			anchor = caret;
			scroll = Math.min(scroll, text.length());
		}
	}

	public void onChange(Runnable listener) {
		this.changeListener = listener;
	}

	public boolean isEmpty() {
		return text.isEmpty();
	}

	public void clear() {
		if (!text.isEmpty()) {
			text = "";
			caret = anchor = scroll = 0;
			changed();
		}
	}

	public void moveCaretToEnd() {
		caret = anchor = text.length();
	}

	public void selectAll() {
		anchor = 0;
		caret = text.length();
	}

	public boolean hasSelection() {
		return caret != anchor;
	}

	// --- events ---------------------------------------------------------------

	public boolean charTyped(CharacterEvent event) {
		if (!event.isAllowedChatCharacter()) {
			return false;
		}
		insert(event.codepointAsString());
		return true;
	}

	/** Handles editing keys; returns false for keys the caller owns (Enter/Escape/...). */
	public boolean keyPressed(KeyEvent event) {
		if (event.isSelectAll()) {
			selectAll();
			return true;
		}
		if (event.isCopy()) {
			copySelection();
			return true;
		}
		if (event.isCut()) {
			copySelection();
			deleteRange(selStart(), selEnd());
			return true;
		}
		if (event.isPaste()) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard());
			return true;
		}
		switch (event.key()) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if (hasSelection()) {
					deleteRange(selStart(), selEnd());
				} else if (caret > 0) {
					deleteRange(event.hasControlDown() ? prevWord(caret) : caret - 1, caret);
				}
				return true;
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if (hasSelection()) {
					deleteRange(selStart(), selEnd());
				} else if (caret < text.length()) {
					deleteRange(caret, event.hasControlDown() ? nextWord(caret) : caret + 1);
				}
				return true;
			}
			case GLFW.GLFW_KEY_LEFT -> {
				moveCaret(event.hasControlDown() ? prevWord(caret) : caret - 1, event.hasShiftDown());
				return true;
			}
			case GLFW.GLFW_KEY_RIGHT -> {
				moveCaret(event.hasControlDown() ? nextWord(caret) : caret + 1, event.hasShiftDown());
				return true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				moveCaret(0, event.hasShiftDown());
				return true;
			}
			case GLFW.GLFW_KEY_END -> {
				moveCaret(text.length(), event.hasShiftDown());
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** Left-click at relX pixels right of the text origin. Double-click selects all. */
	public void click(double relX) {
		long now = System.currentTimeMillis();
		if (now - lastClickTime < DOUBLE_CLICK_MS) {
			lastClickTime = 0;
			selectAll();
			return;
		}
		lastClickTime = now;
		caret = anchor = indexAt(relX);
	}

	/** Drag-select: moves the caret, keeps the anchor. */
	public void drag(double relX) {
		caret = indexAt(relX);
	}

	// --- rendering ------------------------------------------------------------

	/**
	 * Draws selection highlight, text (or dim placeholder) and blinking caret
	 * inside an innerWidth-wide window at (x, y). Also updates the scroll so
	 * the caret stays visible — call every frame while the field is showing.
	 */
	public void render(GuiGraphicsExtractor g, int x, int y, int innerWidth, boolean focused, String placeholder) {
		caret = Math.clamp(caret, 0, text.length());
		anchor = Math.clamp(anchor, 0, text.length());

		// keep the caret in view, then backfill slack on the right
		scroll = Math.clamp(scroll, 0, text.length());
		if (caret < scroll) {
			scroll = caret;
		}
		while (scroll < caret && Render2D.width(text.substring(scroll, caret)) > innerWidth) {
			scroll++;
		}
		while (scroll > 0 && Render2D.width(text.substring(scroll - 1)) <= innerWidth) {
			scroll--;
		}

		String visible = visibleText(innerWidth);
		if (text.isEmpty()) {
			if (placeholder != null) {
				Render2D.textNoShadow(g, placeholder, x, y, Theme.textDim);
			}
		} else {
			if (focused && hasSelection()) {
				int s = Math.max(selStart(), scroll);
				int e = Math.min(selEnd(), scroll + visible.length());
				if (s < e) {
					int x1 = x + Render2D.width(text.substring(scroll, s));
					int x2 = x + Render2D.width(text.substring(scroll, e));
					Render2D.rect(g, x1, y, x2 - x1, FONT_HEIGHT, (Theme.accent1 & 0x00FFFFFF) | 0x66000000);
				}
			}
			Render2D.textNoShadow(g, visible, x, y, Theme.text);
		}
		if (focused && (System.currentTimeMillis() % 1000) < 500) {
			int cx = x + Render2D.width(text.substring(scroll, Math.min(caret, scroll + visible.length())));
			Render2D.rect(g, cx, y, 1, FONT_HEIGHT, Theme.accent1);
		}
	}

	/** Plain unfocused text row: start of the string, trimmed to fit, dim placeholder when empty. */
	public static void renderStatic(GuiGraphicsExtractor g, String text, int x, int y, int innerWidth, String placeholder) {
		if (text.isEmpty()) {
			if (placeholder != null) {
				Render2D.textNoShadow(g, placeholder, x, y, Theme.textDim);
			}
			return;
		}
		String shown = text;
		while (Render2D.width(shown) > innerWidth && !shown.isEmpty()) {
			shown = shown.substring(0, shown.length() - 1);
		}
		Render2D.textNoShadow(g, shown, x, y, Theme.text);
	}

	// --- internals ------------------------------------------------------------

	private int selStart() {
		return Math.min(caret, anchor);
	}

	private int selEnd() {
		return Math.max(caret, anchor);
	}

	private void insert(String raw) {
		String filtered = StringUtil.filterText(raw);
		int start = selStart();
		text = text.substring(0, start) + filtered + text.substring(selEnd());
		caret = anchor = start + filtered.length();
		changed();
	}

	private void deleteRange(int start, int end) {
		if (start >= end) {
			return;
		}
		text = text.substring(0, start) + text.substring(end);
		caret = anchor = start;
		changed();
	}

	private void moveCaret(int to, boolean extend) {
		// plain left/right with a selection collapses to its edge, like any editor
		if (!extend && hasSelection() && Math.abs(to - caret) == 1) {
			caret = anchor = to < caret ? selStart() : selEnd();
			return;
		}
		caret = Math.clamp(to, 0, text.length());
		if (!extend) {
			anchor = caret;
		}
	}

	private void copySelection() {
		if (hasSelection()) {
			Minecraft.getInstance().keyboardHandler.setClipboard(text.substring(selStart(), selEnd()));
		}
	}

	private int prevWord(int from) {
		int i = from;
		while (i > 0 && text.charAt(i - 1) == ' ') {
			i--;
		}
		while (i > 0 && text.charAt(i - 1) != ' ') {
			i--;
		}
		return i;
	}

	private int nextWord(int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) != ' ') {
			i++;
		}
		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}
		return i;
	}

	private int indexAt(double relX) {
		int i = scroll;
		double acc = 0;
		while (i < text.length()) {
			int w = Render2D.width(String.valueOf(text.charAt(i)));
			if (acc + w / 2.0 > relX) {
				break;
			}
			acc += w;
			i++;
		}
		return i;
	}

	private String visibleText(int innerWidth) {
		int end = scroll;
		while (end < text.length() && Render2D.width(text.substring(scroll, end + 1)) <= innerWidth) {
			end++;
		}
		return text.substring(scroll, end);
	}

	private void changed() {
		if (changeListener != null) {
			changeListener.run();
		}
	}
}
