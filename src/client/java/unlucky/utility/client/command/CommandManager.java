package unlucky.utility.client.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
	/**
	 * One client-side completion. {@code replaceStart} is relative to the bare
	 * command text: chat adds its leading {@code '.'} itself, while the console
	 * can use the same source later without special casing that prefix.
	 */
	public record Completion(String value, String description, int replaceStart) {
	}

	private CommandManager() {
	}

	/**
	 * Contextual completions for the client's command language. Keeping these
	 * next to {@link #execute} means a new command has one obvious place to add
	 * both its behaviour and its discoverability; module, friend and waypoint
	 * names themselves are live data rather than another hand-maintained list.
	 *
	 * <p>{@code line} deliberately has no leading chat dot. The console already
	 * speaks this form and the chat UI removes the dot before calling us.
	 */
	public static List<Completion> completions(String line, int cursor) {
		if (line == null) {
			return List.of();
		}
		cursor = Math.clamp(cursor, 0, line.length());
		String before = line.substring(0, cursor);
		int commandStart = skipWhitespace(before, 0);
		if (commandStart >= before.length()) {
			return rootCompletions(cursor);
		}

		int commandEnd = commandStart;
		while (commandEnd < before.length() && !Character.isWhitespace(before.charAt(commandEnd))) {
			commandEnd++;
		}
		if (cursor <= commandEnd) {
			return rootCompletions(commandStart);
		}

		String command = before.substring(commandStart, commandEnd).toLowerCase(Locale.ROOT);
		int argumentStart = skipWhitespace(before, commandEnd);
		String arguments = before.substring(argumentStart);
		return switch (command) {
			case "toggle", "t" -> moduleCompletions(argumentStart, "toggle this module");
			case "bind" -> bindCompletions(arguments, argumentStart);
			case "friend" -> friendCompletions(arguments, argumentStart);
			case "waypoint", "wp" -> waypointCompletions(arguments, argumentStart);
			case "registry" -> literals(argumentStart,
					new String[][] {{"whoami", "show your Unlucky registry identity"}});
			case "stashes" -> literals(argumentStart, new String[][] {
					{"list", "list recorded stashes in this dimension"},
					{"nearest", "show the closest recorded stash"},
					{"remove", "forget the record for this chunk"}
			});
			case "stash" -> literals(argumentStart, new String[][] {
					{"list", "list marked supply containers"},
					{"check", "re-read every marked container"},
					{"clear", "forget marked containers"}
			});
			case "pbase" -> literals(argumentStart,
					new String[][] {{"clear", "forget the Printer refill base"}});
			case "sprint" -> literals(argumentStart,
					new String[][] {{"save", "write the recorded ticks to a file"}});
			default -> List.of();
		};
	}

	private static List<Completion> rootCompletions(int replaceStart) {
		return literals(replaceStart, new String[][] {
			{"help", "show every client command"},
			{"toggle", "toggle a module"},
			{"t", "toggle a module"},
			{"panic", "turn off everything the server can see"},
			{"bind", "set a module keybind"},
			{"friend", "manage your friend list"},
			{"waypoint", "manage waypoints"},
			{"wp", "manage waypoints"},
			{"registry", "Unlucky registry tools"},
			{"modules", "list all modules"},
			{"say", "send a normal chat message"},
			{"report", "save a Printer diagnostic"},
			{"pause", "pause or resume Printer"},
			{"pbase", "set or clear Printer refill base"},
			{"stash", "manage Printer supply containers"},
			{"stashes", "recorded StashFinder locations"},
			{"plan", "show the Printer plan"},
			{"hotbars", "show your saved creative hotbars"},
			{"sprint", "record the sprint flag tick by tick"},
			{"clear", "clear the console"}
		});
	}

	private static List<Completion> bindCompletions(String arguments, int argumentStart) {
		// A bound module can have spaces in its display name, so recognise the
		// longest raw name before offering the final key argument.
		for (Module module : sortedModules()) {
			String name = module.getName();
			if (!startsWithIgnoreCase(arguments, name)) {
				continue;
			}
			if (arguments.length() == name.length()) {
				return moduleCompletions(argumentStart, "bind this module");
			}
			if (arguments.length() > name.length() && Character.isWhitespace(arguments.charAt(name.length()))) {
				int keyStart = argumentStart + skipWhitespace(arguments, name.length());
				return keyCompletions(keyStart);
			}
		}
		return moduleCompletions(argumentStart, "bind this module");
	}

	private static List<Completion> friendCompletions(String arguments, int argumentStart) {
		int subEnd = firstWordEnd(arguments);
		if (subEnd == 0 || subEnd == arguments.length()) {
			return literals(argumentStart, new String[][] {
					{"add", "add a player"}, {"remove", "remove a saved friend"}, {"list", "list saved friends"}
			});
		}
		String subcommand = arguments.substring(0, subEnd).toLowerCase(Locale.ROOT);
		if (!subcommand.equals("remove")) {
			return List.of();
		}
		int nameStart = argumentStart + skipWhitespace(arguments, subEnd);
		List<Completion> result = new ArrayList<>();
		FriendManager.all().values().stream().sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(name -> result.add(new Completion(name, "remove this friend", nameStart)));
		return result;
	}

	private static List<Completion> waypointCompletions(String arguments, int argumentStart) {
		int subEnd = firstWordEnd(arguments);
		if (subEnd == 0 || subEnd == arguments.length()) {
			return literals(argumentStart, new String[][] {
					{"add", "save a waypoint at your feet"},
					{"remove", "remove a saved waypoint"},
					{"list", "list every saved waypoint"}
			});
		}
		String subcommand = arguments.substring(0, subEnd).toLowerCase(Locale.ROOT);
		if (!subcommand.equals("remove")) {
			return List.of();
		}
		int nameStart = argumentStart + skipWhitespace(arguments, subEnd);
		List<Completion> result = new ArrayList<>();
		unlucky.utility.client.util.waypoints.WaypointManager.all().stream()
				.map(waypoint -> waypoint.name).distinct().sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(name -> result.add(new Completion(name, "remove this waypoint", nameStart)));
		return result;
	}

	private static List<Completion> moduleCompletions(int replaceStart, String description) {
		List<Completion> result = new ArrayList<>();
		for (Module module : sortedModules()) {
			result.add(new Completion(module.getName(), description, replaceStart));
		}
		return result;
	}

	private static List<Module> sortedModules() {
		List<Module> modules = new ArrayList<>(UnluckyClient.INSTANCE.modules.all());
		modules.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
		return modules;
	}

	private static List<Completion> keyCompletions(int replaceStart) {
		List<Completion> result = new ArrayList<>();
		result.add(new Completion("none", "remove this keybind", replaceStart));
		for (char key = 'a'; key <= 'z'; key++) {
			result.add(new Completion(String.valueOf(key), "bind to " + Character.toUpperCase(key), replaceStart));
		}
		for (char key = '0'; key <= '9'; key++) {
			result.add(new Completion(String.valueOf(key), "bind to " + key, replaceStart));
		}
		for (int key = 1; key <= 12; key++) {
			result.add(new Completion("f" + key, "bind to F" + key, replaceStart));
		}
		return result;
	}

	private static List<Completion> literals(int replaceStart, String[][] values) {
		List<Completion> result = new ArrayList<>(values.length);
		for (String[] value : values) {
			result.add(new Completion(value[0], value[1], replaceStart));
		}
		return result;
	}

	private static int skipWhitespace(String text, int from) {
		while (from < text.length() && Character.isWhitespace(text.charAt(from))) {
			from++;
		}
		return from;
	}

	private static int firstWordEnd(String text) {
		int index = 0;
		while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
			index++;
		}
		return index;
	}

	private static boolean startsWithIgnoreCase(String value, String prefix) {
		return value.length() >= prefix.length() && value.regionMatches(true, 0, prefix, 0, prefix.length());
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
				out.accept("panic - turn off everything the server can see (same as 't panic')");
				out.accept("bind <module> <key|none> - set a module keybind");
				out.accept("friend add|remove <name>, friend list");
				out.accept("waypoint add <name>, waypoint remove <name>, waypoint list");
				out.accept("registry login|whoami - the Unlucky registry (cape auto-published)");
				out.accept("modules - list all modules");
				out.accept("say <text> - send a chat message");
				out.accept("report - save a Printer diagnostic for the block you're looking at");
			out.accept("stash [list|check|clear] - mark the container you're looking at, list "
					+ "what each is known to hold, re-read them all, or forget them");
					out.accept("pause - hold the Printer where it is, and again to carry on");
				out.accept("pbase [clear] - remember this spot as where the Printer refills");
				out.accept("stash [clear|list] - mark the chest you're looking at as Printer supply");
				out.accept("plan - what the Printer will place next, and where the bag runs out");
				out.accept("hotbars - show what's in each saved creative hotbar");
				out.accept("sprint [save] - record every tick's sprint flag, packets and "
						+ "AutoSprint decision; run again to stop");
				out.accept("clear - clear the console");
			}
			case "hotbars" -> {
				// opened next frame: we are inside the console screen's own input
				// handling, and swapping screens from under it loses the click
				net.minecraft.client.Minecraft.getInstance().schedule(
						() -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(
								new unlucky.utility.client.gui.hotbar.HotbarPreviewScreen()));
				out.accept("opening saved hotbars");
			}
			case "toggle", "t" -> {
				Module module = find(args, 1, args.length, out);
				if (module != null) {
					module.toggle();
					out.accept(module.getName() + " " + (module.isEnabled() ? "enabled" : "disabled"));
				}
			}
			// Shorthand for the one module worth reaching without spelling "toggle" first.
			// Routes through trigger() rather than toggle() so it fires even in the single tick
			// Panic is still on from a previous press, where a toggle would only turn it off.
			case "panic" -> {
				UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.misc.Panic.class).trigger();
				out.accept("panicking");
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
			case "report" -> UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.world.Printer.class).report(out);
			case "pause" -> out.accept(UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.world.Printer.class).togglePause());
			case "pbase" -> {
				var printer = UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.world.Printer.class);
				boolean clear = args.length > 1 && args[1].equalsIgnoreCase("clear");
				net.minecraft.client.player.LocalPlayer player =
						net.minecraft.client.Minecraft.getInstance().player;
				if (!clear && player == null) {
					out.accept("Stand where you want the base first");
				} else {
					out.accept(printer.setBase(clear ? null : player.blockPosition()));
				}
			}
			case "plan" -> UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.world.Printer.class).planReport(out);
			// The sprint flag is written by three parties in one tick (vanilla's aiStep, the
			// packet sync, then us), so "AutoSprint goes wild" is only answerable from a
			// recording of all three. Saving is separate from stopping: the interesting run
			// is usually the one you just did, and stopping shouldn't cost you a file.
			case "sprint" -> {
				if (args.length > 1 && args[1].equalsIgnoreCase("save")) {
					unlucky.utility.client.util.SprintProbe.save(out);
				} else {
					boolean stopping = unlucky.utility.client.util.SprintProbe.recording();
					out.accept(unlucky.utility.client.util.SprintProbe.toggle());
					if (stopping) {
						unlucky.utility.client.util.SprintProbe.save(out);
					}
				}
			}
			// Deliberately not ".stash": the Printer already owns that name for its own
			// supply containers, and one letter between two commands that both talk about
			// chests is a mistake waiting to be made.
			case "stashes" -> {
				var finder = UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.world.StashFinder.class);
				String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
				switch (what) {
					case "nearest" -> finder.nearest(out);
					case "remove" -> finder.removeHere(out);
					default -> finder.list(out);
				}
			}
			case "stash" -> {
				var printer = UnluckyClient.INSTANCE.modules
						.get(unlucky.utility.client.module.modules.world.Printer.class);
				String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
				if (what.equals("clear")) {
					out.accept(printer.clearStash());
				} else if (what.equals("list")) {
					for (String entry : printer.describeStash().split("\n")) {
						out.accept(entry);
					}
				} else if (what.equals("check")) {
					out.accept(printer.checkStash());
				} else {
					// The block under the crosshair, not the one underfoot: a stash is a wall
					// of chests you point at, and standing on the one you mean is neither
					// natural nor always possible.
					net.minecraft.world.phys.HitResult hit =
							net.minecraft.client.Minecraft.getInstance().hitResult;
					out.accept(hit instanceof net.minecraft.world.phys.BlockHitResult block
							&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
									? printer.markStash(block.getBlockPos())
									: "Look at a chest, barrel or shulker box first");
				}
			}
			case "friend" -> friend(args, out);
			case "waypoint", "wp" -> waypoint(args, out);
			case "registry" -> registry(args, out);
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

	/**
	 * Registry console helpers. There's no login — the cape you pick in the Capes
	 * module publishes itself; {@code whoami} just echoes what the registry has for
	 * you. Output lands back in the console asynchronously via the thread-safe append.
	 */
	private static void registry(String[] args, Consumer<String> out) {
		if (args.length < 2) {
			out.accept("Usage: registry whoami (your cape publishes automatically from the Capes module)");
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "whoami" -> unlucky.utility.client.util.net.UnluckyApi.whoami(
					me -> out.accept("Registry says: " + me),
					err -> out.accept("whoami failed: " + err));
			default -> out.accept("Usage: registry whoami"
					+ " (your cape is published automatically from the Capes module)");
		}
	}

	/** {@code waypoint add <name>} drops one at your feet; remove by name; list shows distances. */
	private static void waypoint(String[] args, Consumer<String> out) {
		var module = UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.render.Waypoints.class);
		var all = unlucky.utility.client.util.waypoints.WaypointManager.all();
		if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
			if (all.isEmpty()) {
				out.accept("No waypoints yet - try 'waypoint add <name>'");
				return;
			}
			for (var waypoint : all) {
				out.accept(waypoint.name + " - " + waypoint.pos.getX() + " " + waypoint.pos.getY()
						+ " " + waypoint.pos.getZ() + " (" + waypoint.dimension + ")");
			}
			return;
		}
		if (args.length < 3) {
			out.accept("Usage: waypoint add|remove <name>, waypoint list");
			return;
		}
		String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "add" -> {
				var added = module.addHere(name);
				out.accept(added == null
						? "Join a world first"
						: "Waypoint '" + name + "' saved at " + added.pos.getX() + " "
								+ added.pos.getY() + " " + added.pos.getZ());
			}
			case "remove" -> {
				var match = all.stream().filter(w -> w.name.equalsIgnoreCase(name)).findFirst();
				if (match.isPresent()) {
					unlucky.utility.client.util.waypoints.WaypointManager.remove(match.get());
					out.accept("Removed waypoint '" + name + "'");
				} else {
					out.accept("No waypoint named '" + name + "'");
				}
			}
			default -> out.accept("Usage: waypoint add|remove <name>, waypoint list");
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
