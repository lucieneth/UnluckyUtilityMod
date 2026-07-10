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
}
