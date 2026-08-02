package unlucky.utility.client.module.modules.misc;

import java.util.concurrent.ThreadLocalRandom;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatFont;
import unlucky.utility.client.util.ChatUtil;

/**
 * Timed chat spam, the oldest module in anarchy client history.
 *
 * <p>The presets are written for this client rather than lifted from anyone's
 * defaults — the shape is the tradition (a client plug, a taunt, a queue joke),
 * the words are ours. Custom takes whatever you type.
 *
 * <p>Two things make spam actually land instead of vanishing:
 * <ul>
 *   <li><b>Unique tag</b> — most servers drop a message identical to your last
 *       one. A short random suffix defeats that, and it's why every old client
 *       shipped one.</li>
 *   <li><b>Rotate</b> — cycling a preset's lines reads as a person rather than a
 *       stuck macro, and sidesteps the duplicate filter on its own.</li>
 * </ul>
 *
 * <p>The floor on Delay is 10s deliberately. Faster is what gets you muted or
 * kicked on every server that has a chat cooldown, and a muted bot spams nothing.
 */
public class Spam extends Module {
	private static final String[] CLIENT = {
		"running Unlucky Client - unlucky.life",
		"Unlucky Client: free, and it flies better than yours",
		"ask me about Unlucky Client",
		"printed with Unlucky Client",
	};
	private static final String[] TAUNT = {
		"skill issue",
		"that was a close one for you",
		"no hard feelings, you were outnumbered by me",
		"i'd say good fight but i'd be lying",
	};
	private static final String[] QUEUE = {
		"two hours in queue for this",
		"position in queue: still",
		"the queue is the real endgame",
		"logged in, immediately regretted it",
	};
	private static final String[] GREETING = {
		"hello everyone",
		"o7",
		"gg",
		"who else is still here",
	};
	/** Suffix alphabet for Unique tag — no vowels, so it can't accidentally spell anything. */
	private static final String TAG_CHARS = "bcdfghjklmnpqrstvwxz0123456789";

	public final ModeSetting preset = add(new ModeSetting("Preset",
			"Which set of lines to send. Custom uses the Message row below.",
			"Custom", "Custom", "Client", "Taunt", "Queue", "Greeting"));
	public final StringSetting message = add(new StringSetting("Message",
			"The line to send. A leading / is sent as a command.",
			"running Unlucky Client - unlucky.life"), () -> preset.is("Custom"));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Walk through the preset's lines in turn instead of repeating one", true),
			() -> !preset.is("Custom"));
	public final ModeSetting font = add(new ModeSetting("Font",
			"Unicode letters to write it in. All of these render in vanilla chat.",
			"Normal", ChatFont.MODES)
			.withLabels(style -> ChatFont.apply(style, style))); // each style shown in itself
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Seconds between messages. 0.05 is one game tick — the fastest anything here can send.",
			5.0, 0.05, 30.0, 0.05));
	public final BooleanSetting unique = add(new BooleanSetting("Unique tag",
			"Append two random characters so the server doesn't drop the message as a duplicate", true));

	private int ticks;
	private int line;
	private boolean warned;

	public Spam() {
		super("Spam", "Sends a chat message on a timer", Category.MISC);
	}

	@Override
	protected void onEnable() {
		ticks = 0; // first message goes out one full delay after you switch it on
		warned = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().player.connection == null) {
			return;
		}
		// rounded to whole ticks, never below one: onTick is the only thing driving
		// this, so a tick is the floor no matter what the slider says
		if (++ticks < Math.max(1, (int) Math.round(delay.get() * 20.0))) {
			return;
		}
		ticks = 0;
		String text = next();
		if (text.isBlank()) {
			return;
		}
		if (!ChatUtil.say(compose(text)) && !warned) {
			warned = true;
			ChatUtil.info("§cSpam: that message can't be sent — a line starting with '.' "
					+ "runs as a client command instead.");
		}
	}

	/** The raw line for this round, advancing the rotation when there is one. */
	private String next() {
		String[] lines = switch (preset.get()) {
			case "Client" -> CLIENT;
			case "Taunt" -> TAUNT;
			case "Queue" -> QUEUE;
			case "Greeting" -> GREETING;
			default -> null;
		};
		if (lines == null) {
			return message.get();
		}
		if (!rotate.get()) {
			return lines[0];
		}
		String picked = lines[Math.floorMod(line, lines.length)];
		line++;
		return picked;
	}

	/**
	 * Styles the line, then fits it to the chat limit with the tag reserved, so a
	 * long message loses its own tail rather than the suffix that makes it unique.
	 * Styling first matters: everything past Fullwidth is outside the BMP and costs
	 * two chars per letter, so the limit bites at about half the letters you'd expect.
	 */
	private String compose(String text) {
		String tag = unique.get() ? " " + randomTag() : "";
		String styled = ChatFont.apply(text, font.get());
		return ChatFont.fit(styled, ChatFont.MAX_CHAT - tag.length()) + tag;
	}

	private static String randomTag() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return String.valueOf(new char[] {
			TAG_CHARS.charAt(random.nextInt(TAG_CHARS.length())),
			TAG_CHARS.charAt(random.nextInt(TAG_CHARS.length())),
		});
	}
}
