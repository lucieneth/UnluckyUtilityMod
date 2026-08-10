package unlucky.utility.client.module.modules.misc;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.mixin.ChatComponentAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatMessageKey;
import unlucky.utility.client.util.ItemUtil;

/**
 * Timestamps, duplicate collapsing and filtering, as one pass over each arriving line.
 *
 * <p><b>It never stringifies a message.</b> Every transformation here builds a new
 * {@link Component} that <em>appends</em> the original rather than rebuilding it from
 * {@code getString()}, so click events, hover text, colours, the chat-head indent and
 * translatable arguments all survive untouched. Flattening chat to text is the standard way
 * this feature is written and it quietly breaks every clickable link on the server.
 *
 * <p><b>The duplicate key rides on the message, not on its text.</b> Compacting needs to know
 * whether a line arriving now equals one already on screen — but by then the earlier one is
 * wearing a timestamp and possibly an {@code ×3}, both of which this module put there.
 * Recomputing the key would mean parsing our own decorations back off, and a message that
 * genuinely ends in "×3" would compare equal to one that repeated three times. So the key is
 * stashed on the {@code GuiMessage} through {@link ChatMessageKey}, the same duck-interface
 * trick Heads already uses to carry the sender across re-flows.
 *
 * <p><b>Order within the pass is fixed and matters:</b> filter, then compact, then stamp. A
 * hidden message must not consume a duplicate slot, and the timestamp goes on last because it
 * is the only part that must not participate in the comparison.
 */
public class BetterChat extends Module {
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("HH:mm:ss");

	// ---- timestamps --------------------------------------------------------
	public final BooleanSetting timestamps = add(new BooleanSetting("Timestamps",
			"Put the time in front of every line", true));
	public final ModeSetting timeFormat = add(new ModeSetting("Format",
			"How precise the clock is", "HH:mm", "HH:mm", "HH:mm:ss"), timestamps::get);
	public final StringSetting brackets = add(new StringSetting("Brackets",
			"Two characters wrapped around the time. Empty for none.", "[]"), timestamps::get);
	public final ColorSetting timeColor = add(new ColorSetting("Timestamp color",
			"Colour of the clock", 0xFF9AA0A6), timestamps::get);

	// ---- duplicates --------------------------------------------------------
	public final BooleanSetting compact = add(new BooleanSetting("Compact duplicates",
			"Collapse a repeated line into one, with a counter", true));
	public final NumberSetting searchDepth = add(new NumberSetting("Search depth",
			"How many recent lines to look back through", 20, 1, 100, 1), compact::get);
	public final BooleanSetting onlyConsecutive = add(new BooleanSetting("Only consecutive",
			"Collapse only when the repeat is the line immediately before", false), compact::get);
	public final ColorSetting counterColor = add(new ColorSetting("Counter color",
			"Colour of the ×N", 0xFFFF7043), compact::get);

	// ---- filtering ---------------------------------------------------------
	public final BooleanSetting filtering = add(new BooleanSetting("Filtering",
			"Act on lines that match one of your patterns", false));
	public final ModeSetting filterAction = add(new ModeSetting("Action",
			"Hide drops the line entirely. Highlight keeps it and tints it.", "Hide",
			"Hide", "Highlight"), filtering::get);
	public final ModeSetting matchMode = add(new ModeSetting("Match",
			"Contains is a plain substring test. Regex is a full Java regular expression.",
			"Contains", "Contains", "Regex"), filtering::get);
	public final StringSetting patterns = add(new StringSetting("Patterns",
			"Comma-separated. Use \\, for a literal comma.", ""), filtering::get);
	public final BooleanSetting caseSensitive = add(new BooleanSetting("Case sensitive",
			"Match capitals exactly", false), filtering::get);
	public final BooleanSetting filterSystem = add(new BooleanSetting("Filter system messages",
			"Apply patterns to server system messages", true), filtering::get);
	public final BooleanSetting filterPlayers = add(new BooleanSetting("Filter player messages",
			"Apply patterns to chat from players", true), filtering::get);
	public final ColorSetting highlightColor = add(new ColorSetting("Highlight color",
			"Colour applied by Highlight", 0xFFFFD54F),
			() -> filtering.get() && filterAction.is("Highlight"));

	/**
	 * Compiled patterns, keyed by their source text and the flags they were built with.
	 * Rebuilt only when the setting changes — compiling a regex per message per pattern is
	 * the kind of cost that only shows up on a busy server, which is exactly where chat is
	 * already the thing making noise.
	 */
	private final Map<String, Pattern> compiled = new HashMap<>();
	private String compiledFrom;
	private boolean compiledCaseSensitive;
	private boolean compiledAsRegex;

	/** Reported once per broken pattern rather than once per message. */
	private String lastBadPattern;

	/**
	 * The key for the message currently being added, handed from the transform pass to the
	 * point where the {@code GuiMessage} finally exists. Same stash-and-consume shape as
	 * {@code Heads.stashSender}.
	 */
	private String pendingKey;
	private int pendingCount = 1;

	/**
	 * The exact component {@link #transform} returned for the pending key.
	 *
	 * <p>Mixin does not order two handlers into the same method, so this module's transform can
	 * run for a message that another one — AdBlocker, DonkeyRitual, our own Hide — is about to
	 * cancel at the same injection point. Without a check, that message's key would be left in
	 * the stash and collected by the <em>next</em> line, which would then collapse against a
	 * message nobody ever saw. Identity is the check: the component handed back here is the one
	 * that ends up inside the GuiMessage, or the stash is not ours to consume.
	 */
	private Component pendingFor;

	public BetterChat() {
		super("BetterChat", "Timestamps, duplicate collapsing and chat filtering", Category.MISC,
				ServerVisibility.CLIENT_ONLY);
	}

	/**
	 * Whether this line should never be shown. Asked at the head of {@code addMessage}, where
	 * cancelling is still possible.
	 */
	public boolean shouldHide(Component contents, GuiMessageSource source) {
		return filtering.get() && filterAction.is("Hide") && matches(contents, source);
	}

	/**
	 * The one transform pass: highlight, collapse, stamp.
	 *
	 * <p>Called from {@code ChatComponentMixin}'s existing component handler rather than as a
	 * second injection, because mixin does not order two handlers into the same method — the
	 * comment on that handler already says so for AntiToS and ChatTag, and this joins the same
	 * chain.
	 */
	public Component transform(Component contents, GuiMessageSource source) {
		pendingKey = null;
		pendingCount = 1;
		pendingFor = null;

		// A line that is about to be cancelled must not collapse a duplicate or claim a key:
		// the collapse would delete a visible message on behalf of one nobody sees.
		if (shouldHide(contents, source)) {
			return contents;
		}

		Component result = contents;
		if (filtering.get() && filterAction.is("Highlight") && matches(contents, source)) {
			int color = highlightColor.get() & 0xFFFFFF;
			result = Component.empty().append(result).withStyle(style -> style.withColor(color));
		}

		if (compact.get()) {
			// The key is taken before the counter and the timestamp go on, so it describes the
			// message the server sent rather than the one we are about to draw.
			pendingKey = result.getString();
			int repeats = collapsePrevious(pendingKey);
			if (repeats > 0) {
				pendingCount = repeats + 1;
				int color = counterColor.get() & 0xFFFFFF;
				result = Component.empty().append(result)
						.append(Component.literal(" ×" + pendingCount)
								.withStyle(style -> style.withColor(color)));
			}
		}

		if (timestamps.get()) {
			result = Component.empty().append(stamp()).append(result);
		}
		pendingFor = result;
		return result;
	}

	/**
	 * Records the key on the message that was just built, so the next arrival can compare
	 * against it. Consumes the stash either way — a message that slipped past without one must
	 * not inherit the previous line's key.
	 */
	public void tagMessage(GuiMessage message) {
		// GuiMessage is a record and therefore final, so the duck interface cannot be reached
		// with instanceof — the double cast through Object is the same one Heads uses.
		if (pendingFor == message.content()) {
			ChatMessageKey keyed = (ChatMessageKey) (Object) message;
			keyed.unlucky$setChatKey(pendingKey);
			keyed.unlucky$setChatCount(pendingCount);
		}
		pendingKey = null;
		pendingCount = 1;
		pendingFor = null;
	}

	/**
	 * Removes an earlier copy of {@code key} from the chat history and returns how many times
	 * it had already been seen, or 0 if there was none.
	 *
	 * <p>Removing from {@code allMessages} and asking for a refresh is the whole operation:
	 * {@code trimmedMessages} is derived, and vanilla rebuilds it — chat-head indent and all —
	 * from the list we just edited.
	 */
	private int collapsePrevious(String key) {
		Minecraft mc = mc();
		if (mc.gui == null || key == null || key.isEmpty()) {
			return 0;
		}
		ChatComponent chat = mc.gui.hud.getChat();
		List<GuiMessage> all = ((ChatComponentAccessor) chat).unlucky$allMessages();
		int depth = onlyConsecutive.get() ? 1 : searchDepth.getInt();
		int limit = Math.min(depth, all.size());
		for (int i = 0; i < limit; i++) {
			// allMessages is newest-first, so index 0 is the line immediately above.
			ChatMessageKey keyed = (ChatMessageKey) (Object) all.get(i);
			if (!key.equals(keyed.unlucky$chatKey())) {
				continue;
			}
			int count = Math.max(1, keyed.unlucky$chatCount());
			all.remove(i);
			((ChatComponentAccessor) chat).unlucky$refreshTrimmedMessages();
			return count;
		}
		return 0;
	}

	private Component stamp() {
		String time = LocalTime.now().format(timeFormat.is("HH:mm:ss") ? LONG : SHORT);
		String raw = brackets.get();
		String open = raw.length() >= 1 ? raw.substring(0, 1) : "";
		String close = raw.length() >= 2 ? raw.substring(1, 2) : "";
		int color = timeColor.get() & 0xFFFFFF;
		return Component.literal(open + time + close + " ").withStyle(style -> style.withColor(color));
	}

	/** Whether the line matches any configured pattern, at the configured strictness. */
	private boolean matches(Component contents, GuiMessageSource source) {
		// Never our own output. The client writes command replies and module notices through
		// the same pipe, and a pattern meant for a server's spam should not be able to
		// silence the thing telling you what the client just did.
		if (source == GuiMessageSource.SYSTEM_CLIENT) {
			return false;
		}
		boolean player = source != GuiMessageSource.SYSTEM_SERVER;
		if (player ? !filterPlayers.get() : !filterSystem.get()) {
			return false;
		}
		rebuildPatterns();
		if (compiled.isEmpty()) {
			return false;
		}
		String text = contents.getString();
		for (Pattern pattern : compiled.values()) {
			if (pattern.matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	/** Recompiles only when the setting text or the flags actually changed. */
	private void rebuildPatterns() {
		boolean regex = matchMode.is("Regex");
		if (patterns.get().equals(compiledFrom) && caseSensitive.get() == compiledCaseSensitive
				&& regex == compiledAsRegex) {
			return;
		}
		compiledFrom = patterns.get();
		compiledCaseSensitive = caseSensitive.get();
		compiledAsRegex = regex;
		compiled.clear();

		int flags = caseSensitive.get() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
		for (String entry : split(patterns.get())) {
			if (entry.isEmpty()) {
				continue;
			}
			try {
				compiled.put(entry, Pattern.compile(regex ? entry : Pattern.quote(entry), flags));
			} catch (PatternSyntaxException e) {
				// Never let a typo take chat down with it: the entry is dropped, the rest still
				// work, and the user is told which one — once, not once per message.
				if (!entry.equals(lastBadPattern)) {
					lastBadPattern = entry;
					UnluckyClient.INSTANCE.notifications.add("BetterChat",
							"Bad pattern: " + entry, ItemUtil.icon(Items.REDSTONE));
				}
			}
		}
	}

	/**
	 * Splits the pattern list on commas, honouring {@code \,} as a literal one.
	 *
	 * <p>A single text field rather than a list setting is a deliberate MVP choice — the
	 * client has no string-list setting type yet, and adding one is five separate wiring
	 * points (setting, GroupBox, component, ClickGUI dispatch, ConfigManager). The escape is
	 * what keeps that choice from being lossy for regexes that need a comma.
	 */
	private static List<String> split(String raw) {
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == ',') {
				current.append(',');
				i++;
			} else if (c == ',') {
				out.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		out.add(current.toString().trim());
		return out;
	}
}
