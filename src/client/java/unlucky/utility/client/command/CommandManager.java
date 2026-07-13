package unlucky.utility.client.command;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.MojangLookup;

/**
 * The console's command set. Commands are the bare words CS-style ("bind",
 * "friend add x") — no dot prefix in the console. Output goes to the supplied
 * consumer so async results (Mojang lookups) can land after the call returns.
 */
public final class CommandManager {
	private CommandManager() {
	}

	/** Runs one input line; feedback (including errors) goes to {@code out}. */
	public static void execute(String line, Consumer<String> out) {
		String[] args = line.trim().split("\\s+");
		if (args.length == 0 || args[0].isEmpty()) {
			return;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "help" -> {
				out.accept("toggle <module> - toggle a module (alias: t)");
				out.accept("bind <module> <key|none> - set a module keybind");
				out.accept("friend add|remove <name>, friend list");
				out.accept("modules - list all modules");
				out.accept("say <text> - send a chat message");
				out.accept("clear - clear the console");
			}
			case "toggle", "t" -> {
				Module module = find(args, 1, args.length, out);
				if (module != null) {
					module.toggle();
					out.accept(module.getName() + " " + (module.isEnabled() ? "enabled" : "disabled"));
				}
			}
			case "bind" -> {
				if (args.length < 3) {
					out.accept("Usage: bind <module> <key|none>");
					return;
				}
				Module module = find(args, 1, args.length - 1, out);
				if (module == null) {
					return;
				}
				String key = args[args.length - 1].toLowerCase(Locale.ROOT);
				int code = keyCode(key);
				if (code == Integer.MIN_VALUE) {
					out.accept("Unknown key '" + key + "' (a-z, 0-9, f1-f12, or none)");
					return;
				}
				module.setKeyBind(code);
				out.accept(module.getName() + (code == GLFW.GLFW_KEY_UNKNOWN
						? " unbound" : " bound to " + key.toUpperCase(Locale.ROOT)));
			}
			case "friend" -> friend(args, out);
			case "modules" -> {
				StringBuilder sb = new StringBuilder();
				for (Module module : UnluckyClient.INSTANCE.modules.all()) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					if (sb.length() > 80) {
						out.accept(sb.toString());
						sb.setLength(0);
					}
					sb.append(module.isEnabled() ? "*" : "").append(module.getName());
				}
				out.accept(sb.toString());
				out.accept("(* = enabled)");
			}
			case "say" -> {
				var mc = net.minecraft.client.Minecraft.getInstance();
				if (mc.player == null || args.length < 2) {
					out.accept(mc.player == null ? "Not in a world" : "Usage: say <text>");
					return;
				}
				mc.player.connection.sendChat(line.trim().substring(4));
			}
			default -> out.accept("Unknown command '" + args[0] + "' - try help");
		}
	}

	private static void friend(String[] args, Consumer<String> out) {
		if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
			Map<UUID, String> all = FriendManager.all();
			out.accept(all.isEmpty() ? "No friends yet" : all.size() + " friends: " + String.join(", ", all.values()));
			return;
		}
		if (args.length < 3) {
			out.accept("Usage: friend add|remove <name>, friend list");
			return;
		}
		String name = args[2];
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "add" -> MojangLookup.resolve(name,
					(uuid, realName) -> out.accept(realName
							+ (FriendManager.add(uuid, realName) ? " added" : " already added")),
					out);
			case "remove" -> {
				for (Map.Entry<UUID, String> entry : FriendManager.all().entrySet()) {
					if (entry.getValue().equalsIgnoreCase(name)) {
						FriendManager.remove(entry.getKey());
						out.accept(entry.getValue() + " removed");
						return;
					}
				}
				out.accept(name + " is not on the friends list");
			}
			default -> out.accept("Usage: friend add|remove <name>, friend list");
		}
	}

	/** Finds a module by name (case/space-insensitive) from args[from..to). */
	private static Module find(String[] args, int from, int to, Consumer<String> out) {
		if (from >= to) {
			out.accept("Missing module name");
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = from; i < to; i++) {
			sb.append(args[i]);
		}
		String query = sb.toString().toLowerCase(Locale.ROOT);
		for (Module module : UnluckyClient.INSTANCE.modules.all()) {
			if (module.getName().replace(" ", "").toLowerCase(Locale.ROOT).equals(query)) {
				return module;
			}
		}
		out.accept("No module named '" + sb + "'");
		return null;
	}

	/** a-z / 0-9 / f1-f12 / none → GLFW code; Integer.MIN_VALUE when unknown. */
	private static int keyCode(String key) {
		if (key.equals("none") || key.equals("unbind")) {
			return GLFW.GLFW_KEY_UNKNOWN;
		}
		if (key.length() == 1) {
			char c = key.charAt(0);
			if (c >= 'a' && c <= 'z') {
				return GLFW.GLFW_KEY_A + (c - 'a');
			}
			if (c >= '0' && c <= '9') {
				return GLFW.GLFW_KEY_0 + (c - '0');
			}
		}
		if (key.startsWith("f")) {
			try {
				int n = Integer.parseInt(key.substring(1));
				if (n >= 1 && n <= 12) {
					return GLFW.GLFW_KEY_F1 + (n - 1);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return Integer.MIN_VALUE;
	}
}
