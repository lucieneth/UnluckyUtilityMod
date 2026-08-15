package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.misc.Friends;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringListSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * A tab list that shows more than eighty players and says who is who.
 *
 * <p><b>Everything that already lived in the tab list stays.</b> The friend badge on the face, the
 * Unlucky star after the profile name, the two-pixel name gap — those are
 * {@code PlayerTabOverlayMixin}'s and they were there first. This module extends that mixin rather
 * than adding a second one, which is the only way the two can agree about a row: two mixins
 * rewriting the same name component would each be working from the other's output depending on
 * load order.
 *
 * <p><b>The sort is stable, deliberately.</b> Ties are everywhere in a tab list — a hundred players
 * on 30ms, half of them with six-letter names — and an unstable sort makes those rows swap places
 * every frame. A list that will not hold still is worse than an unsorted one.
 *
 * <p><b>A bad regex disables itself once, loudly, and never throws.</b> This code runs inside the
 * HUD extract; an exception here is a crash on every frame the tab key is held, and the player who
 * typed the pattern would see a client that dies whenever they open the tab list rather than a
 * message telling them their pattern is wrong.
 */
public class BetterTab extends Module {
	public final NumberSetting maximumPlayers = add(new NumberSetting("Maximum players",
			"How many rows the list may show", 1000, 1, 5000, 10));
	public final NumberSetting columnHeight = add(new NumberSetting("Column height",
			"Rows before a new column starts", 20, 1, 100, 1));
	public final ModeSetting sorting = add(new ModeSetting("Sorting",
			"Row order; Vanilla is gamemode then name, None leaves the server's order alone",
			"Vanilla", "Vanilla", "Ping", "Name length", "Display-name length",
			"Alphabetical", "Reverse alphabetical", "None"));

	public final ModeSetting headerFooter = add(new ModeSetting("Header/footer",
			"Server-provided header and footer text", "Show", "Show", "Hide"));
	public final ModeSetting names = add(new ModeSetting("Names",
			"Full keeps whatever the server decorated the name with; Name only strips it back "
					+ "to the profile name", "Full", "Full", "Name only"));
	public final ModeSetting gamemode = add(new ModeSetting("Gamemode",
			"Vanilla only greys out spectators; Icon and Text spell the mode out for every row",
			"Vanilla", "Vanilla", "Icon", "Text", "Hide"));

	public final ModeSetting latency = add(new ModeSetting("Latency",
			"How ping is shown", "Bars", "Bars", "Exact", "Both", "Hide"));
	public final StringSetting latencySuffix = add(new StringSetting("Latency suffix",
			"Appended to an exact latency", "ms"), () -> !latency.is("Bars") && !latency.is("Hide"));

	public final BooleanSetting highlightSelf = add(new BooleanSetting("Highlight self",
			"Tint your own row", true));
	public final ColorSetting selfColor = add(new ColorSetting("Self color",
			"Your row's tint", 0x40B478FF), highlightSelf::get);
	public final BooleanSetting highlightFriends = add(new BooleanSetting("Highlight friends",
			"Tint friends' rows", true));
	public final ColorSetting friendColor = add(new ColorSetting("Friend color",
			"Friends' row tint", 0x4061C9FF), highlightFriends::get);
	public final StringListSetting highlightRegex = add(new StringListSetting("Highlight regex",
			"Java regular expressions matched against the plain name"));
	public final ColorSetting regexColor = add(new ColorSetting("Regex color",
			"Tint for a regex match", 0x40FFB347));
	public final StringListSetting hideRegex = add(new StringListSetting("Hide regex",
			"Hide rows whose name matches — checked after the self and friend tests, so neither "
					+ "you nor a friend can be hidden by a pattern"));

	public final BooleanSetting showHeads = add(new BooleanSetting("Show heads",
			"Keep the player faces", true));
	public final BooleanSetting showScore = add(new BooleanSetting("Show server score",
			"Keep the server's scoreboard column", true));

	/** Compiled forms of the two pattern lists, rebuilt only when the list text changes. */
	private final List<Pattern> highlightPatterns = new ArrayList<>();
	private final List<Pattern> hidePatterns = new ArrayList<>();
	private int highlightHash;
	private int hideHash;

	public BetterTab() {
		super("BetterTab", "A tab list that scales and says who is who", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	// ---- the list itself ---------------------------------------------------

	/**
	 * The whole row list: filtered, sorted, capped.
	 *
	 * <p>One method because the three are one decision — hiding after sorting would leave gaps
	 * in the cap, and capping before sorting would show an arbitrary thousand rather than the
	 * first thousand of the order the player asked for.
	 */
	public List<PlayerInfo> arrange(List<PlayerInfo> vanilla) {
		if (!isEnabled()) {
			return vanilla;
		}
		List<PlayerInfo> rows = new ArrayList<>(vanilla.size());
		for (PlayerInfo info : vanilla) {
			if (!hidden(info)) {
				rows.add(info);
			}
		}
		Comparator<PlayerInfo> order = comparator();
		if (order != null) {
			// List.sort is a stable merge sort, which is the property that stops tied rows
			// trading places every frame.
			rows.sort(order);
		}
		int cap = maximumPlayers.getInt();
		return rows.size() <= cap ? rows : rows.subList(0, cap);
	}

	/**
	 * The chosen ordering, or null to leave the incoming order alone.
	 *
	 * <p>Vanilla returns null too: the list handed in has already been through vanilla's own
	 * comparator, so re-sorting it by the same keys would only cost time.
	 */
	private Comparator<PlayerInfo> comparator() {
		return switch (sorting.get()) {
			case "Ping" -> Comparator.comparingInt(PlayerInfo::getLatency);
			case "Name length" -> Comparator.comparingInt(info -> plainName(info).length());
			case "Display-name length" -> Comparator.comparingInt(info -> displayLength(info));
			case "Alphabetical" -> Comparator.comparing(info -> plainName(info).toLowerCase());
			case "Reverse alphabetical" ->
					Comparator.comparing((PlayerInfo info) -> plainName(info).toLowerCase()).reversed();
			// Vanilla and None both mean "do not touch it"; they differ only in what the caller
			// handed us, which is vanilla's own sorted list either way.
			default -> null;
		};
	}

	private static int displayLength(PlayerInfo info) {
		Component name = info.getTabListDisplayName();
		return name == null ? plainName(info).length() : name.getString().length();
	}

	private static String plainName(PlayerInfo info) {
		String name = info.getProfile().name();
		return name == null ? "" : name;
	}

	// ---- per-row decisions -------------------------------------------------

	/** Whether a row is hidden. Self and friends are checked first and are never hideable. */
	private boolean hidden(PlayerInfo info) {
		if (hideRegex.get().isEmpty() || isSelf(info) || isFriend(info)) {
			return false;
		}
		return matches(hidePatterns(), plainName(info));
	}

	/**
	 * Row background tint, or 0 for none.
	 *
	 * <p>Self beats friend beats regex, which is the order of how specific each claim is: there
	 * is exactly one of you, a handful of friends, and a pattern that could match anybody.
	 */
	public int rowTint(PlayerInfo info) {
		if (!isEnabled() || info == null) {
			return 0;
		}
		if (highlightSelf.get() && isSelf(info)) {
			return selfColor.get();
		}
		if (highlightFriends.get() && isFriend(info)) {
			return friendColor.get();
		}
		if (!highlightRegex.get().isEmpty() && matches(highlightPatterns(), plainName(info))) {
			return regexColor.get();
		}
		return 0;
	}

	/**
	 * The name to display, given whatever the existing friend/Unlucky decoration produced.
	 *
	 * <p>Name only deliberately throws that decoration away as well: the setting is for servers
	 * whose display names carry ranks, health bars and colour codes that make the list unreadable,
	 * and keeping our own marks while stripping the server's would be an odd half-measure. The
	 * face badge survives either way, because it is not part of the name.
	 */
	public Component decorate(PlayerInfo info, Component decorated) {
		if (!isEnabled()) {
			return decorated;
		}
		Component base = names.is("Name only")
				? Component.literal(plainName(info))
				: decorated;
		String suffix = gamemodeSuffix(info);
		return suffix.isEmpty() ? base : Component.empty().append(base)
				.append(Component.literal(suffix).withColor(0xAAAAAA));
	}

	private String gamemodeSuffix(PlayerInfo info) {
		GameType mode = info.getGameMode();
		if (mode == null) {
			return "";
		}
		return switch (gamemode.get()) {
			case "Icon" -> " [" + switch (mode) {
				case SURVIVAL -> "S";
				case CREATIVE -> "C";
				case ADVENTURE -> "A";
				case SPECTATOR -> "Sp";
			} + "]";
			case "Text" -> " (" + mode.getName() + ")";
			default -> "";
		};
	}

	/**
	 * Whether the spectator grey-out still applies.
	 *
	 * <p>Only {@code Hide} turns it off. Icon and Text <em>add</em> a way to read the gamemode;
	 * they do not ask for the one vanilla already had to be taken away.
	 */
	public boolean keepsVanillaGamemodeColor() {
		return !isEnabled() || !gamemode.is("Hide");
	}

	public boolean showsHeads() {
		return !isEnabled() || showHeads.get();
	}

	public boolean showsScore() {
		return !isEnabled() || showScore.get();
	}

	public boolean showsHeaderFooter() {
		return !isEnabled() || headerFooter.is("Show");
	}

	public boolean showsPingBars() {
		return !isEnabled() || latency.is("Bars") || latency.is("Both");
	}

	public boolean showsExactLatency() {
		return isEnabled() && (latency.is("Exact") || latency.is("Both"));
	}

	public String latencyText(PlayerInfo info) {
		return info.getLatency() + latencySuffix.get();
	}

	/** Rows per column, for the one constant the layout maths uses. */
	public int rowsPerColumn(int vanilla) {
		return isEnabled() ? columnHeight.getInt() : vanilla;
	}

	// ---- regex -------------------------------------------------------------

	/**
	 * Compiled patterns, recompiled only when the list text actually changed.
	 *
	 * <p>Keyed on the list's hash rather than on a dirty flag: {@link StringListSetting} is
	 * edited in place by the GUI and by config loading, neither of which has anywhere to raise
	 * a flag, and compiling a dozen patterns on every extracted frame is not free.
	 */
	private List<Pattern> highlightPatterns() {
		int hash = highlightRegex.get().hashCode();
		if (hash != highlightHash) {
			highlightHash = hash;
			compile(highlightRegex.get(), highlightPatterns, "Highlight regex");
		}
		return highlightPatterns;
	}

	private List<Pattern> hidePatterns() {
		int hash = hideRegex.get().hashCode();
		if (hash != hideHash) {
			hideHash = hash;
			compile(hideRegex.get(), hidePatterns, "Hide regex");
		}
		return hidePatterns;
	}

	/** A pattern that will not compile is dropped with one message, not retried every frame. */
	private void compile(List<String> source, List<Pattern> into, String label) {
		into.clear();
		for (String text : source) {
			if (text == null || text.isEmpty()) {
				continue;
			}
			try {
				into.add(Pattern.compile(text));
			} catch (PatternSyntaxException e) {
				ChatUtil.info("§eBetterTab: " + label + " entry \"" + text + "\" is not a valid "
						+ "regular expression and has been ignored.");
			}
		}
	}

	private static boolean matches(List<Pattern> patterns, String name) {
		for (Pattern pattern : patterns) {
			if (pattern.matcher(name).find()) {
				return true;
			}
		}
		return false;
	}

	private boolean isSelf(PlayerInfo info) {
		return mc().player != null && info.getProfile().id().equals(mc().player.getUUID());
	}

	private boolean isFriend(PlayerInfo info) {
		UUID uuid = info.getProfile().id();
		return UnluckyClient.INSTANCE.modules.get(Friends.class).tablistBadgeColor(uuid) != 0;
	}
}
