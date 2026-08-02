package unlucky.utility.client.util;

/**
 * Unicode letter substitution for chat — the "fancy font" every anarchy client
 * grew at some point. The server only ever sees plain text, so these are just
 * different characters, not formatting: nothing here needs section signs, and
 * nothing here can be stripped by a chat filter that only knows about §.
 *
 * <p>Every glyph used below was checked against 26.2's bundled
 * {@code unifont_all_no_pua} before being listed, so all of it renders in vanilla
 * chat rather than falling back to missing-glyph boxes. That check is the whole
 * reason the tables are written out by hand:
 * <ul>
 *   <li>Script and Fraktur use the <b>bold</b> math ranges (U+1D4D0 / U+1D56C).
 *       The non-bold ones are famously full of holes — ℬ ℯ ℭ ℌ ℑ ℜ ℨ live over in
 *       Letterlike Symbols — so a naive {@code base + (c - 'a')} silently emits
 *       reserved codepoints for seven letters. The bold ranges are contiguous.</li>
 *   <li>Small caps aren't a range at all, they're scattered across three blocks,
 *       and there is no small-capital X in Unicode — plain x stands in.</li>
 * </ul>
 *
 * <p><b>Length:</b> everything past Fullwidth is outside the BMP, so one letter
 * costs two Java chars. A 256-character chat limit is therefore ~128 letters in
 * Script or Fraktur. Callers must trim <i>after</i> styling — see
 * {@link #fit(String, int)}.
 */
public final class ChatFont {
	public static final String[] MODES = {
		"Normal", "Small caps", "Fullwidth", "Bold", "Script", "Fraktur", "Circled", "Upside down"
	};

	/** Vanilla's chat message cap, in Java chars. */
	public static final int MAX_CHAT = 256;

	private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘꞯʀꜱᴛᴜᴠᴡxʏᴢ";
	private static final String UPSIDE_DOWN_LOWER = "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz";
	private static final String UPSIDE_DOWN_UPPER = "∀ᗺƆᗡƎℲ⅁HIſʞ⅂WNOԀΌᴚS⊥∩ΛMX⅄Z";
	private static final String UPSIDE_DOWN_DIGIT = "0ІᄅƐㄣϛ9ㄥ86";

	private ChatFont() {
	}

	/** {@code text} rewritten in the named style; unknown styles pass through unchanged. */
	public static String apply(String text, String style) {
		if (text == null || text.isEmpty() || style == null || "Normal".equals(style)) {
			return text;
		}
		return switch (style) {
			case "Small caps" -> mapLetters(text, SMALL_CAPS);
			case "Fullwidth" -> fullwidth(text);
			case "Bold" -> shift(text, 0x1D400, 0x1D41A, 0x1D7CE);
			case "Script" -> shift(text, 0x1D4D0, 0x1D4EA, 0x1D7CE); // bold script; no bold-script digits exist
			case "Fraktur" -> shift(text, 0x1D56C, 0x1D586, 0x1D7CE); // bold fraktur, same
			case "Circled" -> circled(text);
			case "Upside down" -> upsideDown(text);
			default -> text;
		};
	}

	/**
	 * Trims to at most {@code limit} Java chars without splitting a surrogate pair —
	 * cutting one in half produces a lone surrogate, which is not valid text and gets
	 * the whole message rejected rather than shortened.
	 */
	public static String fit(String text, int limit) {
		if (limit <= 0) {
			return "";
		}
		if (text.length() <= limit) {
			return text;
		}
		int end = limit;
		if (Character.isHighSurrogate(text.charAt(end - 1))) {
			end--;
		}
		return text.substring(0, end);
	}

	// --- styles ---------------------------------------------------------------

	/** Both cases fold onto one 26-entry table (small caps have no case of their own). */
	private static String mapLetters(String text, String table) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 'a' && c <= 'z') {
				out.append(table.charAt(c - 'a'));
			} else if (c >= 'A' && c <= 'Z') {
				out.append(table.charAt(c - 'A'));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** U+FF01..FF5E mirrors ASCII !..~ one-for-one; space gets the ideographic one. */
	private static String fullwidth(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == ' ') {
				out.append('　');
			} else if (c >= '!' && c <= '~') {
				out.append((char) (c - '!' + 0xFF01));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** Contiguous math-alphanumeric ranges: one base per case, one for digits. */
	private static String shift(String text, int upperBase, int lowerBase, int digitBase) {
		StringBuilder out = new StringBuilder(text.length() * 2);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				out.appendCodePoint(upperBase + (c - 'A'));
			} else if (c >= 'a' && c <= 'z') {
				out.appendCodePoint(lowerBase + (c - 'a'));
			} else if (c >= '0' && c <= '9') {
				out.appendCodePoint(digitBase + (c - '0'));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** Circled letters are contiguous; the digits are not — 0 sits apart from 1-9. */
	private static String circled(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				out.append((char) (0x24B6 + (c - 'A')));
			} else if (c >= 'a' && c <= 'z') {
				out.append((char) (0x24D0 + (c - 'a')));
			} else if (c >= '1' && c <= '9') {
				out.append((char) (0x2460 + (c - '1')));
			} else if (c == '0') {
				out.append('⓪');
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** Flipped glyphs, then reversed — upside-down text reads right-to-left. */
	private static String upsideDown(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = text.length() - 1; i >= 0; i--) {
			char c = text.charAt(i);
			if (c >= 'a' && c <= 'z') {
				out.append(UPSIDE_DOWN_LOWER.charAt(c - 'a'));
			} else if (c >= 'A' && c <= 'Z') {
				out.append(UPSIDE_DOWN_UPPER.charAt(c - 'A'));
			} else if (c >= '0' && c <= '9') {
				out.append(UPSIDE_DOWN_DIGIT.charAt(c - '0'));
			} else {
				out.append(switch (c) {
					case '?' -> '¿';
					case '!' -> '¡';
					case '.' -> '˙';
					case ',' -> '\'';
					case '\'' -> ',';
					case '(' -> ')';
					case ')' -> '(';
					case '[' -> ']';
					case ']' -> '[';
					case '_' -> '‾';
					case '&' -> '⅋';
					default -> c;
				});
			}
		}
		return out.toString();
	}
}
