package unlucky.utility.client.gui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.command.CommandManager;
import unlucky.utility.client.command.CommandManager.Completion;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ChatAnim;
import unlucky.utility.client.util.ColorUtil;

/**
 * Client-command-only chat affordances. Vanilla keeps ownership of normal chat
 * and slash-command suggestions; this class becomes active solely for the dot
 * command syntax claimed by {@code ChatCommandMixin}.
 */
public final class ClientCommandChatUi {
	private static final int ROW_H = 12;
	private static final int ROW_LIMIT = 8;
	private static final float INPUT_SLIDE_PX = 16f;
	private static final Map<EditBox, State> STATES = new WeakHashMap<>();

	private ClientCommandChatUi() {
	}

	/** Refreshes client completion state before vanilla extracts the EditBox. */
	public static void prepare(EditBox input) {
		refresh(input);
	}

	/**
	 * Draws the animated frame before ChatScreen paints its normal input fill.
	 * ChatInputSlideMixin calls this while the translated input pose is active,
	 * so opening-chat animation and hitboxes stay in lockstep.
	 */
	public static void extractInputAccent(GuiGraphicsExtractor graphics, EditBox input) {
		State state = refresh(input);
		if (!state.clientInput) {
			return;
		}

		int x = 1;
		int y = graphics.guiHeight() - 15;
		int width = Math.max(1, graphics.guiWidth() - 2);
		int height = 14;
		float phase = (System.currentTimeMillis() % 2600L) / 2600.0f;
		int left = ColorUtil.withAlpha(accent(phase), 235);
		int right = ColorUtil.withAlpha(accent(phase + 0.5f), 235);
		int side = ColorUtil.withAlpha(accent(phase + 0.25f), 225);

		// Two opposing gradient rails read as a moving outline rather than a static
		// recolor, while the one-pixel sides keep the vanilla chat bar crisp.
		graphics.fillGradient(x, y, x + width, y + 1, left, right);
		graphics.fillGradient(x, y + height - 1, x + width, y + height, right, left);
		graphics.fill(x, y + 1, x + 1, y + height - 1, side);
		graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, side);
	}

	/** Draws the contextual list after the vanilla input and server suggestions. */
	public static void extractSuggestions(GuiGraphicsExtractor graphics, EditBox input, int mouseX, int mouseY) {
		State state = refresh(input);
		if (!state.clientInput || state.hidden || state.completions.isEmpty()) {
			return;
		}

		Layout layout = layout(input, graphics.guiHeight(), state);
		int localMouseY = mouseY - slideOffset();
		int base = darkSurface(accent(0.17f));
		int outline = ColorUtil.withAlpha(accent(0.55f), 220);
		graphics.fill(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height(), base);
		graphics.outline(layout.x, layout.y, layout.width, layout.height(), outline);
		if (state.offset > 0) {
			graphics.fill(layout.x + 1, layout.y + 1, layout.x + layout.width - 1, layout.y + 2,
					ColorUtil.withAlpha(accent(0.25f), 180));
		}
		if (state.offset + layout.rows < state.completions.size()) {
			graphics.fill(layout.x + 1, layout.y + layout.height() - 2, layout.x + layout.width - 1,
					layout.y + layout.height() - 1, ColorUtil.withAlpha(accent(0.75f), 180));
		}

		Font font = Minecraft.getInstance().font;
		for (int line = 0; line < layout.rows; line++) {
			int index = state.offset + line;
			Completion completion = state.completions.get(index);
			int rowY = layout.y + line * ROW_H;
			boolean hovered = contains(layout.x, rowY, layout.width, ROW_H, mouseX, localMouseY);
			if (hovered && state.selected != index) {
				state.selected = index;
				ensureVisible(state);
				updateGhost(input, state);
			}
			boolean selected = state.selected == index;
			if (selected) {
				graphics.fill(layout.x + 1, rowY + 1, layout.x + layout.width - 1, rowY + ROW_H - 1,
						ColorUtil.withAlpha(accent(0.45f), hovered ? 105 : 78));
			}

			int textX = layout.x + 4;
			int textY = rowY + 2;
			int nameColor = selected ? 0xFFF4FAFA : 0xFFD5DCE0;
			graphics.text(font, completion.value(), textX, textY, nameColor, false);
			int descriptionX = textX + font.width(completion.value()) + 7;
			int descriptionWidth = layout.x + layout.width - 4 - descriptionX;
			if (descriptionWidth > 8 && !completion.description().isEmpty()) {
				String description = font.plainSubstrByWidth(completion.description(), descriptionWidth);
				graphics.text(font, description, descriptionX, textY, selected ? 0xFFC6D3D8 : 0xFF849096, false);
			}
		}

		if (contains(layout.x, layout.y, layout.width, layout.height(), mouseX, localMouseY)) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
	}

	/** Consumes only the completion keys while a dot command is being typed. */
	public static boolean keyPressed(EditBox input, KeyEvent event) {
		State state = refresh(input);
		if (!state.clientInput || state.hidden && !event.isCycleFocus()) {
			return false;
		}
		if (event.isEscape() && !state.completions.isEmpty() && !state.hidden) {
			state.hidden = true;
			input.setSuggestion(null);
			return true;
		}
		if (state.completions.isEmpty()) {
			return false;
		}
		if (event.isUp()) {
			cycle(state, -1);
			updateGhost(input, state);
			return true;
		}
		if (event.isDown()) {
			cycle(state, 1);
			updateGhost(input, state);
			return true;
		}
		if (event.isCycleFocus()) {
			if (state.hidden) {
				state.hidden = false;
				updateGhost(input, state);
				return true;
			}
			if (event.hasShiftDown()) {
				cycle(state, -1);
				updateGhost(input, state);
			} else {
				useSelected(input, state);
			}
			return true;
		}
		return false;
	}

	/** Accepts a clicked client completion and leaves all other chat clicks alone. */
	public static boolean mouseClicked(EditBox input, MouseButtonEvent event, int screenHeight) {
		State state = refresh(input);
		if (!state.clientInput || state.hidden || state.completions.isEmpty() || event.button() != 0) {
			return false;
		}
		Layout layout = layout(input, screenHeight, state);
		int x = (int) event.x();
		int y = (int) event.y() - slideOffset();
		if (!contains(layout.x, layout.y, layout.width, layout.height(), x, y)) {
			return false;
		}
		int index = state.offset + (y - layout.y) / ROW_H;
		if (index >= 0 && index < state.completions.size()) {
			state.selected = index;
			useSelected(input, state);
		}
		return true;
	}

	/** Scrolls a long client completion list only while the pointer is over it. */
	public static boolean mouseScrolled(EditBox input, double mouseX, double mouseY, double scroll, int screenHeight) {
		State state = refresh(input);
		if (!state.clientInput || state.hidden || state.completions.size() <= ROW_LIMIT) {
			return false;
		}
		Layout layout = layout(input, screenHeight, state);
		if (!contains(layout.x, layout.y, layout.width, layout.height(), (int) mouseX,
				(int) mouseY - slideOffset())) {
			return false;
		}
		int maxOffset = Math.max(0, state.completions.size() - ROW_LIMIT);
		state.offset = Math.clamp(state.offset - (int) Math.signum(scroll), 0, maxOffset);
		return true;
	}

	private static State refresh(EditBox input) {
		State state = STATES.computeIfAbsent(input, ignored -> new State());
		String value = input.getValue();
		int cursor = input.getCursorPosition();
		if (!isClientCommand(value)) {
			// Do not clear vanilla's slash-command ghost text every frame. We only
			// clear the ghost that this class previously owned.
			if (state.clientInput) {
				input.setSuggestion(null);
			}
			state.reset();
			return state;
		}

		String signature = value + '\u0000' + cursor;
		if (!signature.equals(state.signature)) {
			state.signature = signature;
			state.hidden = false;
			state.selected = 0;
			state.offset = 0;
			state.clientInput = true;
			state.completions = matchingCompletions(value, cursor);
		}
		state.clientInput = true;
		ensureVisible(state);
		updateGhost(input, state);
		return state;
	}

	private static List<Completion> matchingCompletions(String value, int cursor) {
		String bare = value.substring(1);
		int bareCursor = Math.clamp(cursor - 1, 0, bare.length());
		List<Completion> candidates = CommandManager.completions(bare, bareCursor);
		List<Completion> matches = new ArrayList<>();
		for (Completion candidate : candidates) {
			int start = Math.clamp(candidate.replaceStart(), 0, bareCursor);
			String typed = bare.substring(start, bareCursor);
			if (candidate.value().regionMatches(true, 0, typed, 0, typed.length())) {
				matches.add(candidate);
			}
		}
		return matches;
	}

	private static void updateGhost(EditBox input, State state) {
		if (!state.clientInput || state.hidden || state.completions.isEmpty()) {
			input.setSuggestion(null);
			return;
		}
		Completion completion = state.completions.get(state.selected);
		int cursor = input.getCursorPosition();
		int start = Math.clamp(completion.replaceStart() + 1, 0, cursor);
		String typed = input.getValue().substring(start, cursor);
		input.setSuggestion(completion.value().regionMatches(true, 0, typed, 0, typed.length())
				? completion.value().substring(typed.length()) : null);
	}

	private static void useSelected(EditBox input, State state) {
		if (state.completions.isEmpty()) {
			return;
		}
		Completion completion = state.completions.get(state.selected);
		int cursor = input.getCursorPosition();
		int start = Math.clamp(completion.replaceStart() + 1, 0, cursor);
		input.setCursorPosition(start);
		input.setHighlightPos(cursor);
		input.insertText(completion.value());
	}

	private static void cycle(State state, int direction) {
		int size = state.completions.size();
		state.selected = Math.floorMod(state.selected + direction, size);
		ensureVisible(state);
	}

	private static void ensureVisible(State state) {
		if (state.completions.isEmpty()) {
			state.selected = 0;
			state.offset = 0;
			return;
		}
		state.selected = Math.clamp(state.selected, 0, state.completions.size() - 1);
		if (state.selected < state.offset) {
			state.offset = state.selected;
		} else if (state.selected >= state.offset + ROW_LIMIT) {
			state.offset = state.selected - ROW_LIMIT + 1;
		}
		state.offset = Math.clamp(state.offset, 0, Math.max(0, state.completions.size() - ROW_LIMIT));
	}

	private static Layout layout(EditBox input, int screenHeight, State state) {
		Font font = Minecraft.getInstance().font;
		int rows = Math.min(ROW_LIMIT, state.completions.size());
		int widest = 100;
		for (int line = 0; line < rows; line++) {
			Completion completion = state.completions.get(state.offset + line);
			widest = Math.max(widest, font.width(completion.value()) + 7 + font.width(completion.description()) + 8);
		}
		int maxWidth = Math.max(100, input.getInnerWidth());
		int width = Math.min(widest, maxWidth);
		int x = Math.clamp(input.getScreenX(state.completions.get(state.selected).replaceStart() + 1) - 1,
				input.getX() - 1, input.getX() + input.getInnerWidth() - width);
		return new Layout(x, screenHeight - 15 - rows * ROW_H, width, rows);
	}

	/**
	 * Whether the field is (or is about to be) a dot command.
	 *
	 * <p>A bare {@code "."} counts, so the accent and the command list appear on the
	 * dot itself rather than a letter later — at that point every command is still a
	 * candidate, which is exactly when the list is most worth showing. It stops
	 * counting the moment the next character rules a command out, so we never dress
	 * up a line that {@code ChatCommandMixin} would let through to the server: ".."
	 * and ". hi" are ordinary chat and look like it.
	 */
	private static boolean isClientCommand(String value) {
		return !value.isEmpty() && value.charAt(0) == '.'
				&& (value.length() == 1 || Character.isLetter(value.charAt(1)));
	}

	private static int slideOffset() {
		return Math.round(INPUT_SLIDE_PX * ChatAnim.entrance(true));
	}

	private static boolean contains(int x, int y, int width, int height, int mouseX, int mouseY) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private static int accent(float position) {
		ThemeModule theme = UnluckyClient.INSTANCE.modules.get(ThemeModule.class);
		float phase = position - (float) Math.floor(position);
		if (theme != null && theme.clickGuiStyle.is("Future")) {
			int base = theme.futureColor.get();
			// Future has a single accent, so animate brightness rather than injecting
			// an unrelated rainbow hue into a deliberately restrained palette.
			float glow = phase < 0.5f ? phase * 2.0f : (1.0f - phase) * 2.0f;
			return ColorUtil.lerp(ColorUtil.withAlpha(base, 255), 0xFFFFFFFF, 0.24f * glow);
		}
		float pingPong = phase < 0.5f ? phase * 2.0f : (1.0f - phase) * 2.0f;
		return Theme.accent(pingPong);
	}

	private static int darkSurface(int accent) {
		int red = ((accent >>> 16) & 0xFF) / 11;
		int green = ((accent >>> 8) & 0xFF) / 11;
		int blue = (accent & 0xFF) / 11;
		return ColorUtil.argb(238, 7 + red, 8 + green, 10 + blue);
	}

	private record Layout(int x, int y, int width, int rows) {
		int height() {
			return rows * ROW_H;
		}
	}

	private static final class State {
		private String signature = "";
		private List<Completion> completions = List.of();
		private boolean clientInput;
		private boolean hidden;
		private int selected;
		private int offset;

		private void reset() {
			signature = "";
			completions = List.of();
			clientInput = false;
			hidden = false;
			selected = 0;
			offset = 0;
		}
	}
}
