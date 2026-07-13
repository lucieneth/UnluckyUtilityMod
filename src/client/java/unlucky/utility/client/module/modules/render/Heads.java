package unlucky.utility.client.module.modules.render;

import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import net.minecraft.util.Util;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.GuiMessageSender;

/**
 * 2D player heads in front of names — chat first (tablist heads are vanilla),
 * the compass bar picks these up too.
 *
 * <p><b>How chat heads work</b> (26.2 pipeline): the sender UUID is only in
 * scope inside {@code ChatListener.showMessageToPlayer} for signed player chat
 * — {@code ChatListenerMixin} stashes it here right before vanilla calls
 * {@code addPlayerMessage}. {@code ChatComponentMixin} moves the stash to the
 * concrete {@link GuiMessage} (a duck field via {@code GuiMessageMixin}); for
 * unsigned/plugin-formatted messages {@link #guessSender} falls back to the
 * {@code <name>} convention and a token scan against names discovered this
 * session. Layout: {@code GuiMessageMixin} narrows {@code splitLines} wrapping
 * by {@link #INDENT}px and prepends a three-space spacer to every produced
 * line, so hover/click x-mapping stays native; the first line of each message
 * is registered here and the two {@code ChatComponent} graphics-access inner
 * classes call {@link #drawChatHead} with exact line positions.
 */
public class Heads extends Module {
	/** Reserved pixels at the line start: 8px face + padding (3 spaces * 4px). */
	public static final int INDENT = 12;
	private static final FormattedCharSequence SPACER = FormattedCharSequence.forward("   ", Style.EMPTY);

	public final BooleanSetting chat = add(new BooleanSetting("Chat", "Sender heads in front of chat messages", true));
	public final BooleanSetting guess = add(new BooleanSetting("Guess sender", "Match plugin-formatted messages to online names", true));

	/** Signed sender stashed by ChatListenerMixin, moved at addMessage HEAD. */
	private static UUID pendingSender;
	/**
	 * Sender for the message currently inside addMessage. The two-step handoff
	 * is cancel-safety: {@link #beginMessage} always consumes the stash even
	 * when AdBlocker cancels or the visibility filter drops the message, so a
	 * blocked line can never donate its head to the next unrelated one.
	 */
	private static UUID currentSender;
	/** First visual line of a message -> sender; weak keys follow chat trimming. */
	private static final WeakHashMap<FormattedCharSequence, UUID> LINE_HEADS = new WeakHashMap<>();

	private boolean wasActive;

	public Heads() {
		super("Heads", "2D player heads in front of chat names", Category.RENDER);
	}

	private boolean chatActive() {
		return isEnabled() && chat.get();
	}

	/** Re-flow existing chat lines when the toggle state changes. */
	@Override
	public void onTick() {
		boolean active = chatActive();
		if (active != wasActive) {
			wasActive = active;
			mc().gui.hud.getChat().rescaleChat();
		}
	}

	// --- called from mixins (all main-thread) ---

	/** ChatListenerMixin: signed player chat, sender known exactly. */
	public static void stashSender(UUID sender) {
		pendingSender = sender;
	}

	/** ChatComponentMixin, at every addMessage HEAD (even ones later cancelled). */
	public static void beginMessage() {
		currentSender = pendingSender;
		pendingSender = null;
	}

	/**
	 * ChatComponentMixin, once per message actually displayed: attach the
	 * stashed (or guessed) sender to the GuiMessage before it splits into lines.
	 */
	public void tagMessage(GuiMessage message) {
		UUID sender = currentSender;
		currentSender = null;
		if (!chatActive() || message.source() == GuiMessageSource.SYSTEM_CLIENT) {
			return;
		}
		if (sender == null && guess.get()) {
			sender = guessSender(message.content());
		}
		if (sender != null) {
			((GuiMessageSender) (Object) message).unlucky$setSender(sender);
		}
	}

	/** GuiMessageMixin (splitLines): should this message reserve head space? */
	public boolean indents(UUID sender) {
		return sender != null && chatActive();
	}

	/** GuiMessageMixin: wrap split lines with the spacer, register the head line. */
	public List<FormattedCharSequence> indentLines(List<FormattedCharSequence> lines, UUID sender) {
		List<FormattedCharSequence> out = new java.util.ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) {
			FormattedCharSequence line = FormattedCharSequence.composite(SPACER, lines.get(i));
			if (i == 0) {
				LINE_HEADS.put(line, sender);
			}
			out.add(line);
		}
		return out;
	}

	/** Chat graphics-access mixins: draw the face if this line owns one. */
	public static void drawChatHead(GuiGraphicsExtractor g, FormattedCharSequence line, int textTop, float opacity) {
		UUID sender = LINE_HEADS.get(line);
		if (sender == null) {
			return;
		}
		int color = ((int) (opacity * 255.0f) << 24) | 0xFFFFFF;
		HeadRenderer.draw(g, sender, 1, textTop, 8, color);
	}

	/**
	 * Unsigned messages: vanilla's own {@code <name>} convention first (same
	 * trick as {@code ChatListener.guessChatUUID}), then a scan of the first
	 * few name-shaped tokens against every player discovered this session.
	 */
	private UUID guessSender(Component content) {
		String plain = StringDecomposer.getPlainText(content);
		var social = mc().getPlayerSocialManager();
		int open = plain.indexOf('<');
		int close = open >= 0 ? plain.indexOf('>', open + 1) : -1;
		if (close > open + 1) {
			UUID id = social.getDiscoveredUUID(plain.substring(open + 1, close));
			if (!Util.NIL_UUID.equals(id)) {
				return id;
			}
		}
		int scanned = 0;
		for (String token : plain.split("[^A-Za-z0-9_]+")) {
			if (token.isEmpty()) {
				continue;
			}
			if (++scanned > 4) {
				break;
			}
			if (token.length() < 3 || token.length() > 16) {
				continue;
			}
			UUID id = social.getDiscoveredUUID(token);
			if (!Util.NIL_UUID.equals(id)) {
				return id;
			}
		}
		return null;
	}
}
