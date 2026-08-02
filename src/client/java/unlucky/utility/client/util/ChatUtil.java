package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Client-side chat output with the client prefix. */
public final class ChatUtil {
	private static final String PREFIX = "§8[§dUnlucky§8]§r ";

	private ChatUtil() {
	}

	public static void info(String message) {
		send(Component.literal(PREFIX + message));
	}

	public static void info(Component message) {
		send(Component.literal(PREFIX).append(message));
	}

	private static void send(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			mc.gui.hud.getChat().addClientSystemMessage(component);
		}
	}

	/**
	 * Says {@code text} to the server as if you had typed it, splitting commands off
	 * the way the chat screen does — a leading '/' has to go through
	 * {@code sendCommand}, since {@code sendChat} would send the slash as literal
	 * text rather than running anything.
	 *
	 * <p>Returns false when the message couldn't be sent, which is worth reporting
	 * rather than swallowing: a leading '.' followed by a letter is claimed by our
	 * own client-command hook ({@code ChatCommandMixin}) and would never reach the
	 * server, so an automated sender would look like it was working while silently
	 * running commands at itself.
	 */
	public static boolean say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.connection == null) {
			return false;
		}
		String message = ChatFont.fit(text.trim(), ChatFont.MAX_CHAT);
		if (message.isEmpty()) {
			return false;
		}
		if (message.length() > 1 && message.charAt(0) == '.' && Character.isLetter(message.charAt(1))) {
			return false; // our own command prefix would eat it
		}
		if (message.charAt(0) == '/') {
			mc.player.connection.sendCommand(message.substring(1));
		} else {
			mc.player.connection.sendChat(message);
		}
		return true;
	}
}
