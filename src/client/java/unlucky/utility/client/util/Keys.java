package unlucky.utility.client.util;

import com.mojang.blaze3d.platform.InputConstants;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/**
 * The one place that knows what a key code is.
 *
 * <p>26.3 replaced GLFW with SDL, and with it every numeric key code: {@code A} went
 * from 65 to 4, {@code ESCAPE} from 256 to 41. Vanilla no longer exposes an
 * {@code int} sentinel for "no key" either — only {@link InputConstants#UNKNOWN}, a
 * {@code Key} object — so the value is read off that rather than written down.
 *
 * <p>{@link #fromGlfw(int)} exists because the change is silent: a config written
 * before 26.3 holds GLFW numbers, and every one of them is a <em>valid</em> SDL code
 * for some other key. Left alone, a returning user's binds do not break loudly, they
 * quietly move — 65 stops being {@code A} and becomes {@code F9}. ConfigManager runs
 * the table once, the first time it loads a config with no {@code keyCodes} marker.
 */
public final class Keys {
	/** The code a {@link unlucky.utility.client.settings.KeybindSetting} holds when unbound. */
	public static final int NONE = InputConstants.UNKNOWN.getValue();

	/**
	 * GLFW key code to the 26.3 equivalent.
	 *
	 * <p>The GLFW side is written out as literals because the library is gone from the
	 * classpath — these are its published constants, and they never moved. The SDL side
	 * is never a literal: it is read off {@link InputConstants}, so if Mojang renumbers
	 * again this table follows without being edited.
	 */
	private static final Int2IntMap FROM_GLFW = new Int2IntOpenHashMap();

	static {
		FROM_GLFW.defaultReturnValue(NONE);
		put(32, InputConstants.KEY_SPACE);
		put(39, InputConstants.KEY_APOSTROPHE);
		put(44, InputConstants.KEY_COMMA);
		put(45, InputConstants.KEY_MINUS);
		put(46, InputConstants.KEY_PERIOD);
		put(47, InputConstants.KEY_SLASH);
		// 0-9 are contiguous in both schemes, but only as a block: SDL puts 0 after 9.
		put(48, InputConstants.KEY_0);
		put(49, InputConstants.KEY_1);
		put(50, InputConstants.KEY_2);
		put(51, InputConstants.KEY_3);
		put(52, InputConstants.KEY_4);
		put(53, InputConstants.KEY_5);
		put(54, InputConstants.KEY_6);
		put(55, InputConstants.KEY_7);
		put(56, InputConstants.KEY_8);
		put(57, InputConstants.KEY_9);
		put(59, InputConstants.KEY_SEMICOLON);
		put(61, InputConstants.KEY_EQUALS);
		putRange(65, InputConstants.KEY_A, 'Z' - 'A' + 1);
		put(91, InputConstants.KEY_LBRACKET);
		put(92, InputConstants.KEY_BACKSLASH);
		put(93, InputConstants.KEY_RBRACKET);
		put(96, InputConstants.KEY_GRAVE);
		put(256, InputConstants.KEY_ESCAPE);
		put(257, InputConstants.KEY_RETURN);
		put(258, InputConstants.KEY_TAB);
		put(259, InputConstants.KEY_BACKSPACE);
		put(260, InputConstants.KEY_INSERT);
		put(261, InputConstants.KEY_DELETE);
		put(262, InputConstants.KEY_RIGHT);
		put(263, InputConstants.KEY_LEFT);
		put(264, InputConstants.KEY_DOWN);
		put(265, InputConstants.KEY_UP);
		put(266, InputConstants.KEY_PAGEUP);
		put(267, InputConstants.KEY_PAGEDOWN);
		put(268, InputConstants.KEY_HOME);
		put(269, InputConstants.KEY_END);
		put(280, InputConstants.KEY_CAPSLOCK);
		put(281, InputConstants.KEY_SCROLLLOCK);
		put(282, InputConstants.KEY_NUMLOCK);
		put(283, InputConstants.KEY_PRINTSCREEN);
		put(284, InputConstants.KEY_PAUSE);
		putRange(290, InputConstants.KEY_F1, 12);
		putRange(302, InputConstants.KEY_F13, 12);
		put(320, InputConstants.KEY_NUMPAD0);
		put(321, InputConstants.KEY_NUMPAD1);
		put(322, InputConstants.KEY_NUMPAD2);
		put(323, InputConstants.KEY_NUMPAD3);
		put(324, InputConstants.KEY_NUMPAD4);
		put(325, InputConstants.KEY_NUMPAD5);
		put(326, InputConstants.KEY_NUMPAD6);
		put(327, InputConstants.KEY_NUMPAD7);
		put(328, InputConstants.KEY_NUMPAD8);
		put(329, InputConstants.KEY_NUMPAD9);
		put(330, InputConstants.KEY_NUMPADCOMMA);
		// GLFW's KP_DIVIDE (331) and KP_SUBTRACT (333) have no InputConstants name in
		// 26.3, so a bind on one of those falls through to NONE rather than landing on
		// an unrelated key. Rebinding is the honest outcome; silently moving is not.
		put(332, InputConstants.KEY_MULTIPLY);
		put(334, InputConstants.KEY_ADD);
		put(335, InputConstants.KEY_NUMPADENTER);
		put(336, InputConstants.KEY_NUMPADEQUALS);
		put(340, InputConstants.KEY_LSHIFT);
		put(341, InputConstants.KEY_LCONTROL);
		put(342, InputConstants.KEY_LALT);
		put(343, InputConstants.KEY_LGUI);
		put(344, InputConstants.KEY_RSHIFT);
		put(345, InputConstants.KEY_RCONTROL);
		put(346, InputConstants.KEY_RALT);
		put(347, InputConstants.KEY_RGUI);
	}

	private static void put(int glfw, int sdl) {
		FROM_GLFW.put(glfw, sdl);
	}

	/** Both schemes keep these runs contiguous, so the block maps as a block. */
	private static void putRange(int firstGlfw, int firstSdl, int count) {
		for (int i = 0; i < count; i++) {
			FROM_GLFW.put(firstGlfw + i, firstSdl + i);
		}
	}

	/**
	 * Translates a pre-26.3 (GLFW) key code to its 26.3 (SDL) equivalent.
	 *
	 * <p>GLFW's own "unknown" was -1, which is not {@link #NONE}, so it is mapped
	 * explicitly. Anything else with no counterpart also comes back {@link #NONE}.
	 */
	public static int fromGlfw(int glfwCode) {
		if (glfwCode == -1) {
			return NONE;
		}
		return FROM_GLFW.get(glfwCode);
	}

	private Keys() {
	}
}
