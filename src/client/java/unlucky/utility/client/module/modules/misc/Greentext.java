package unlucky.utility.client.module.modules.misc;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatFont;

/**
 * Types everything in green.
 *
 * <p>The colour is the <b>server's</b> doing, not ours: 2b2t (and every plugin
 * that copies it — ChatCo, BukkitGreentext) renders any message beginning with
 * {@code >} in green, 4chan style. So this module has exactly one job — put the
 * marker on the front of what you send. There's nothing to render client-side and
 * nothing that works offline; on a server without the feature you'll just be
 * sending literal {@code >} characters.
 *
 * <p>Four things are deliberately left alone:
 * <ul>
 *   <li><b>A leading space.</b> That's the server's own opt-out — prepending one
 *       is how you say a line that starts with {@code >} without going green — so
 *       treating it as "don't touch this" matches what people already expect.</li>
 *   <li><b>Server commands.</b> {@code /} goes out through {@code sendCommand}
 *       rather than {@code sendChat} anyway, but the guard is free.</li>
 *   <li><b>Client commands.</b> {@code .report} has to stay recognisable to
 *       {@code ChatCommandMixin}, which claims it before it ever reaches the
 *       server. See the mixin for why this matters more than it looks.</li>
 *   <li><b>Already-green lines.</b> No {@code >>} from typing the marker yourself.</li>
 * </ul>
 *
 * <p>This sits on the send path, so it greens everything that goes out — including
 * Spam and BibleBot.
 */
public class Greentext extends Module {
	public final StringSetting prefix = add(new StringSetting("Prefix",
			"The marker put on the front. > is what 2b2t and its clones look for.", ">"));
	public final BooleanSetting space = add(new BooleanSetting("Space after",
			"Write \"> text\" rather than \">text\"", false));

	public Greentext() {
		super("Greentext", "Prefixes your chat with > so the server renders it green", Category.MISC, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected boolean hiddenByDefault() {
		return true;
	}

	/** {@code message} with the marker applied, or unchanged when it shouldn't be. */
	public String apply(String message) {
		if (!isEnabled() || message.isEmpty()) {
			return message;
		}
		String marker = prefix.get();
		if (marker.isEmpty() || message.startsWith(marker)) {
			return message;
		}
		char first = message.charAt(0);
		if (first == ' ' || first == '/') {
			return message;
		}
		if (message.length() > 1 && first == '.' && Character.isLetter(message.charAt(1))) {
			return message;
		}
		// prefixing can push a full-length message over the cap, so re-fit
		return ChatFont.fit(marker + (space.get() ? " " : "") + message, ChatFont.MAX_CHAT);
	}
}
