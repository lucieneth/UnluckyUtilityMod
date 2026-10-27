package unlucky.utility.client.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.gui.clickgui.component.BindComponent;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.MacroManager;
import unlucky.utility.client.util.MojangLookup;
import unlucky.utility.client.util.Keys;

/**
 * The console's command set. Commands are the bare words CS-style ("bind",
 * "friend add x") — no dot prefix in the console. Output goes to the supplied
 * consumer so async results (Mojang lookups) can land after the call returns.
 */
public final class CommandManager {
	/** Vanilla's ordinary movement check rejects a larger one-tick position hop. */
	private static final double MAX_VCLIP_DISTANCE = 10.0;

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
			case "vclip" -> literals(argumentStart, new String[][] {
					{"up", "clip upward by a number of blocks"},
					{"down", "clip downward by a number of blocks"}
			});
			case "drop" -> literals(argumentStart, new String[][] {
					{"hand", "drop the held stack"},
					{"hotbar", "drop every hotbar stack"},
					{"all", "drop everything but armor and offhand"}
			});
			case "macro" -> macroCompletions(arguments, argumentStart);
			case "cm", "chestmanager" -> unlucky.utility.client.module.modules.world.ChestManager.completions(arguments, argumentStart);
			case "excavate" -> literals(argumentStart, new String[][] {
					{"pos1", "first corner: the block you look at, or your feet"},
					{"pos2", "opposite corner: the block you look at, or your feet"},
					{"clear", "forget the selection"}
			});
			case "give" -> firstWordEnd(arguments) == arguments.length()
					? registryCompletions(net.minecraft.core.registries.Registries.ITEM, argumentStart, "give this item")
					: List.of();
			case "enchant" -> firstWordEnd(arguments) == arguments.length()
					? registryCompletions(net.minecraft.core.registries.Registries.ENCHANTMENT, argumentStart,
							"enchant the held item")
					: List.of();
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
			{"vclip", "clip vertically"},
			{"hclip", "clip forward horizontally"},
			{"drop", "drop the held stack, the hotbar or everything"},
			{"peek", "look inside the held shulker box"},
			{"server", "server brand, version and plugins"},
			{"fov", "show or set your field of view"},
			{"macro", "bind a key to a chat line or command"},
			{"give", "give yourself an item (creative)"},
			{"enchant", "enchant the held item (creative)"},
			{"lag", "measure how far players render behind the server"},
			{"excavate", "set the corners Excavator digs out"},
			{"cm", "ChestManager: tag, search and count your chests"},
			{"chestmanager", "ChestManager: tag, search and count your chests"},
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

	private static List<Completion> macroCompletions(String arguments, int argumentStart) {
		int subEnd = firstWordEnd(arguments);
		if (subEnd == 0 || subEnd == arguments.length()) {
			return literals(argumentStart, new String[][] {
					{"add", "bind a key to a chat line"},
					{"remove", "unbind a macro key"},
					{"list", "list every macro"}
			});
		}
		String subcommand = arguments.substring(0, subEnd).toLowerCase(Locale.ROOT);
		int keyStart = skipWhitespace(arguments, subEnd);
		if (firstWordEnd(arguments.substring(keyStart)) != arguments.length() - keyStart) {
			return List.of(); // past the key: free text
		}
		if (subcommand.equals("add")) {
			return keyCompletions(argumentStart + keyStart);
		}
		if (subcommand.equals("remove")) {
			List<Completion> result = new ArrayList<>();
			for (Map.Entry<Integer, String> entry : MacroManager.all().entrySet()) {
				result.add(new Completion(keyArgument(entry.getKey()), entry.getValue(), argumentStart + keyStart));
			}
			return result;
		}
		return List.of();
	}

	/** Paths without the {@code minecraft:} namespace, since that is what people type. */
	private static <T> List<Completion> registryCompletions(
			net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> registry,
			int replaceStart, String description) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) {
			return List.of();
		}
		List<Completion> result = new ArrayList<>();
		level.registryAccess().lookupOrThrow(registry).listElementIds()
				.map(key -> key.identifier().getNamespace().equals("minecraft")
						? key.identifier().getPath() : key.identifier().toString())
				.sorted()
				.forEach(id -> result.add(new Completion(id, description, replaceStart)));
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
				out.accept("vclip up|down <blocks> - clip vertically (or use a signed distance)");
				out.accept("hclip <blocks> - clip forward the way you face (negative goes back)");
				out.accept("drop [hand|hotbar|all] - drop the held stack, the hotbar, or everything");
				out.accept("peek - look inside the shulker box you're holding");
				out.accept("server - brand, version and plugins of the server you're on");
				out.accept("fov [degrees] - show or set your field of view (30-110)");
				out.accept("macro add <key> <line>, macro remove <key>, macro list - "
						+ "a key that sends chat, /commands or .commands");
				out.accept("give <item> [count], enchant <enchantment> [level] - creative only");
				out.accept("lag [seconds] - measure how far nearby players render behind the server");
				out.accept("excavate [pos1|pos2|clear] - set or show the box Excavator digs out");
				out.accept("cm add|click|remove|search <item>|total <item>|list|rescan|clear - ChestManager");
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
				out.accept(module.getName() + (code == Keys.NONE
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
			case "vclip" -> verticalClip(args, out);
			case "hclip" -> horizontalClip(args, out);
			case "drop" -> drop(args, out);
			case "peek" -> peek(out);
			case "server" -> server(out);
			case "fov" -> fov(args, out);
			case "macro" -> macro(args, line, out);
			case "give" -> give(args, out);
			case "enchant" -> enchant(args, out);
			case "lag" -> unlucky.utility.client.util.InterpolationProbe.command(args, out);
			case "excavate" -> unlucky.utility.client.module.modules.world.Excavator.command(args, out);
			case "cm", "chestmanager" -> UnluckyClient.INSTANCE.modules.get(unlucky.utility.client.module.modules.world.ChestManager.class).command(args, out);
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
	 * Moves the local player vertically; the next ordinary movement update carries
	 * the new position to the server, just like ClickTP. Directional syntax keeps
	 * chat use readable, while a signed single argument supports the usual vclip
	 * shorthand ({@code vclip -3}).
	 */
	private static void verticalClip(String[] args, Consumer<String> out) {
		if (args.length < 2 || args.length > 3) {
			out.accept("Usage: vclip up|down <blocks> (or vclip <signed blocks>)");
			return;
		}

		double delta;
		try {
			if (args.length == 2) {
				if (args[1].equalsIgnoreCase("up") || args[1].equalsIgnoreCase("down")) {
					out.accept("Usage: vclip up|down <blocks> (or vclip <signed blocks>)");
					return;
				}
				delta = Double.parseDouble(args[1]);
				if (!Double.isFinite(delta) || delta == 0.0) {
					out.accept("Distance must be a finite non-zero number");
					return;
				}
			} else {
				double distance = Double.parseDouble(args[2]);
				if (!Double.isFinite(distance) || distance <= 0.0) {
					out.accept("Distance must be a positive finite number");
					return;
				}
				delta = switch (args[1].toLowerCase(Locale.ROOT)) {
					case "up" -> distance;
					case "down" -> -distance;
					default -> Double.NaN;
				};
				if (Double.isNaN(delta)) {
					out.accept("Usage: vclip up|down <blocks> (or vclip <signed blocks>)");
					return;
				}
			}
		} catch (NumberFormatException ignored) {
			out.accept("Distance must be a number");
			return;
		}
		if (Math.abs(delta) > MAX_VCLIP_DISTANCE) {
			out.accept("Distance cannot exceed " + Double.toString(MAX_VCLIP_DISTANCE) + " blocks");
			return;
		}

		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			out.accept("Join a world first");
			return;
		}
		// A riding LocalPlayer sends only rotation plus its vehicle position; moving
		// the passenger here would look successful for one frame but never reach the server.
		if (player.isPassenger()) {
			out.accept("Dismount before using vclip");
			return;
		}
		double targetY = player.getY() + delta;
		if (!Double.isFinite(targetY)) {
			out.accept("Distance is too large");
			return;
		}
		if (targetY == player.getY()) {
			out.accept("Distance is too small to change your position");
			return;
		}
		player.setPos(player.getX(), targetY, player.getZ());
		player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		player.fallDistance = 0.0;
		out.accept("Clipped " + (delta > 0.0 ? "up " : "down ")
				+ Double.toString(Math.abs(delta)) + " blocks");
	}

	/**
	 * Forward along the way you face, flat — pitch is ignored, so looking at the floor still
	 * clips through the wall ahead. Same rules and limit as {@link #verticalClip}.
	 */
	private static void horizontalClip(String[] args, Consumer<String> out) {
		if (args.length != 2) {
			out.accept("Usage: hclip <blocks> (negative clips backward)");
			return;
		}
		double distance;
		try {
			distance = Double.parseDouble(args[1]);
		} catch (NumberFormatException ignored) {
			out.accept("Distance must be a number");
			return;
		}
		if (!Double.isFinite(distance) || distance == 0.0) {
			out.accept("Distance must be a finite non-zero number");
			return;
		}
		if (Math.abs(distance) > MAX_VCLIP_DISTANCE) {
			out.accept("Distance cannot exceed " + Double.toString(MAX_VCLIP_DISTANCE) + " blocks");
			return;
		}
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player == null) {
			out.accept("Join a world first");
			return;
		}
		if (player.isPassenger()) {
			out.accept("Dismount before using hclip");
			return;
		}
		double yaw = Math.toRadians(player.getYRot());
		player.setPos(player.getX() - Math.sin(yaw) * distance, player.getY(),
				player.getZ() + Math.cos(yaw) * distance);
		player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		out.accept("Clipped " + (distance > 0.0 ? "forward " : "back ")
				+ Double.toString(Math.abs(distance)) + " blocks");
	}

	/**
	 * {@code drop} alone is the drop key with Ctrl held. The bulk forms throw through the
	 * player's own inventory menu, the same click as Ctrl+Q over a slot; armour and the
	 * offhand are left alone, since emptying those is rarely what "drop everything" means.
	 */
	private static void drop(String[] args, Consumer<String> out) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var player = mc.player;
		if (player == null || mc.gameMode == null) {
			out.accept("Join a world first");
			return;
		}
		if (player.isSpectator()) {
			out.accept("Spectators can't drop items");
			return;
		}
		String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "hand";
		int first;
		switch (what) {
			case "hand" -> {
				if (player.getMainHandItem().isEmpty()) {
					out.accept("Nothing in your hand");
					return;
				}
				mc.gameMode.dropItem(player, true);
				out.accept("Dropped the held stack");
				return;
			}
			case "hotbar" -> first = net.minecraft.world.inventory.InventoryMenu.USE_ROW_SLOT_START;
			case "all" -> first = net.minecraft.world.inventory.InventoryMenu.INV_SLOT_START;
			default -> {
				out.accept("Usage: drop [hand|hotbar|all]");
				return;
			}
		}
		var menu = player.inventoryMenu;
		if (player.containerMenu != menu) {
			out.accept("Close the open container first");
			return;
		}
		int dropped = 0;
		for (int slot = first; slot < net.minecraft.world.inventory.InventoryMenu.USE_ROW_SLOT_END; slot++) {
			if (!menu.getSlot(slot).getItem().isEmpty()) {
				mc.gameMode.handleContainerInput(menu.containerId, slot, 1,
						net.minecraft.world.inventory.ContainerInput.THROW, player);
				dropped++;
			}
		}
		out.accept(dropped == 0 ? "Nothing to drop" : "Dropped " + dropped + " stacks");
	}

	/** Opened next frame, for the same reason as {@code hotbars}. */
	private static void peek(Consumer<String> out) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var player = mc.player;
		if (player == null) {
			out.accept("Join a world first");
			return;
		}
		var stack = player.getMainHandItem();
		var screen = unlucky.utility.client.gui.hotbar.PeekScreen.of(stack, player.getInventory());
		if (screen == null) {
			stack = player.getOffhandItem();
			screen = unlucky.utility.client.gui.hotbar.PeekScreen.of(stack, player.getInventory());
		}
		if (screen == null) {
			out.accept("Hold a shulker box first");
			return;
		}
		var opened = screen;
		mc.schedule(() -> mc.gui.setScreen(opened));
		out.accept("peeking into " + stack.getHoverName().getString());
	}

	/**
	 * Plugins are read off the command tree the server sent us: Bukkit-style servers register
	 * every plugin command a second time under {@code plugin:command}, so the namespaces are the
	 * plugin list — at least the part of it that has commands this player may use.
	 */
	private static void server(Consumer<String> out) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		var connection = mc.getConnection();
		if (connection == null) {
			out.accept("Join a server first");
			return;
		}
		var data = mc.getCurrentServer();
		out.accept("Address: " + (data == null ? "singleplayer" : data.ip));
		out.accept("Brand: " + (connection.serverBrand() == null ? "unknown" : connection.serverBrand()));
		if (data != null) {
			out.accept("Version: " + data.version.getString() + " (protocol " + data.protocol + ")");
		}
		java.util.TreeSet<String> plugins = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (var node : connection.getCommands().getRoot().getChildren()) {
			int colon = node.getName().indexOf(':');
			if (colon > 0) {
				String namespace = node.getName().substring(0, colon);
				if (!namespace.equals("minecraft") && !namespace.equals("bukkit")) {
					plugins.add(namespace);
				}
			}
		}
		out.accept(plugins.isEmpty()
				? "Plugins: none visible in the command tree"
				: "Plugins (" + plugins.size() + "): " + String.join(", ", plugins));
	}

	private static void fov(String[] args, Consumer<String> out) {
		var options = net.minecraft.client.Minecraft.getInstance().options;
		if (args.length < 2) {
			out.accept("FOV is " + options.fov().get());
			return;
		}
		int value;
		try {
			value = Integer.parseInt(args[1]);
		} catch (NumberFormatException ignored) {
			out.accept("FOV must be a whole number");
			return;
		}
		if (value < 30 || value > 110) {
			out.accept("FOV must be between 30 and 110");
			return;
		}
		options.fov().set(value);
		options.save();
		out.accept("FOV set to " + value);
	}

	/**
	 * {@code macro add <key> <line>} keeps the rest of the raw line as typed, so the chat
	 * line can have its own spacing and case.
	 */
	private static void macro(String[] args, String line, Consumer<String> out) {
		String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
		if (sub.equals("list")) {
			var all = MacroManager.all();
			if (all.isEmpty()) {
				out.accept("No macros yet - try 'macro add <key> <line>'");
			}
			for (Map.Entry<Integer, String> entry : all.entrySet()) {
				out.accept(BindComponent.keyName(entry.getKey()) + " - " + entry.getValue());
			}
			return;
		}
		if (args.length < 3 || (sub.equals("add") && args.length < 4)) {
			out.accept("Usage: macro add <key> <line>, macro remove <key>, macro list");
			return;
		}
		int key = keyCode(args[2].toLowerCase(Locale.ROOT));
		if (key == Integer.MIN_VALUE || key == Keys.NONE) {
			out.accept("Unknown key '" + args[2] + "' (a-z, 0-9 or f1-f12)");
			return;
		}
		switch (sub) {
			case "add" -> {
				String text = line.trim();
				for (int word = 0; word < 3; word++) {
					text = text.substring(firstWordEnd(text));
					text = text.substring(skipWhitespace(text, 0));
				}
				String replaced = MacroManager.set(key, text);
				out.accept(BindComponent.keyName(key) + " now sends '" + text + "'"
						+ (replaced == null ? "" : " (was '" + replaced + "')"));
			}
			case "remove" -> {
				String removed = MacroManager.remove(key);
				out.accept(removed == null
						? "No macro on " + BindComponent.keyName(key)
						: "Removed the macro on " + BindComponent.keyName(key));
			}
			default -> out.accept("Usage: macro add <key> <line>, macro remove <key>, macro list");
		}
	}

	/**
	 * Creative only: vanilla accepts a creative slot packet from a creative player and refuses
	 * it otherwise, so outside creative this would only desync the client. Lands in the held
	 * slot when it is empty, else the first empty hotbar slot, else replaces the held item.
	 */
	private static void give(String[] args, Consumer<String> out) {
		var player = creativePlayer(out);
		if (player == null) {
			return;
		}
		if (args.length < 2 || args.length > 3) {
			out.accept("Usage: give <item> [count]");
			return;
		}
		var id = net.minecraft.resources.Identifier.tryParse(args[1].toLowerCase(Locale.ROOT));
		var item = id == null ? null
				: net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		if (item == null || item == net.minecraft.world.item.Items.AIR) {
			out.accept("No item called '" + args[1] + "'");
			return;
		}
		var stack = new net.minecraft.world.item.ItemStack(item);
		int count = 1;
		if (args.length == 3) {
			try {
				count = Integer.parseInt(args[2]);
			} catch (NumberFormatException ignored) {
				out.accept("Count must be a whole number");
				return;
			}
		}
		stack.setCount(Math.clamp(count, 1, stack.getMaxStackSize()));
		var inventory = player.getInventory();
		int slot = inventory.getSelectedSlot();
		if (!inventory.getItem(slot).isEmpty()) {
			for (int candidate = 0; candidate < net.minecraft.world.entity.player.Inventory.SELECTION_SIZE; candidate++) {
				if (inventory.getItem(candidate).isEmpty()) {
					slot = candidate;
					break;
				}
			}
		}
		setCreativeSlot(player, slot, stack);
		out.accept("Gave " + stack.getCount() + " " + stack.getHoverName().getString()
				+ " (hotbar " + (slot + 1) + ")");
	}

	/** Any level 1-255, past the table's limits; a book gets it as a stored enchantment. */
	private static void enchant(String[] args, Consumer<String> out) {
		var player = creativePlayer(out);
		if (player == null) {
			return;
		}
		if (args.length < 2 || args.length > 3) {
			out.accept("Usage: enchant <enchantment> [level]");
			return;
		}
		var held = player.getMainHandItem();
		if (held.isEmpty()) {
			out.accept("Hold the item to enchant first");
			return;
		}
		var id = net.minecraft.resources.Identifier.tryParse(args[1].toLowerCase(Locale.ROOT));
		var registries = net.minecraft.core.registries.Registries.ENCHANTMENT;
		var holder = id == null ? java.util.Optional.<net.minecraft.core.Holder.Reference<
				net.minecraft.world.item.enchantment.Enchantment>>empty()
				: player.registryAccess().lookupOrThrow(registries)
						.get(net.minecraft.resources.ResourceKey.create(registries, id));
		if (holder.isEmpty()) {
			out.accept("No enchantment called '" + args[1] + "'");
			return;
		}
		int level = 1;
		if (args.length == 3) {
			try {
				level = Integer.parseInt(args[2]);
			} catch (NumberFormatException ignored) {
				out.accept("Level must be a whole number");
				return;
			}
		}
		int finalLevel = Math.clamp(level, 1, 255);
		var stack = held.copy();
		net.minecraft.world.item.enchantment.EnchantmentHelper.updateEnchantments(stack,
				enchantments -> enchantments.set(holder.get(), finalLevel));
		setCreativeSlot(player, player.getInventory().getSelectedSlot(), stack);
		out.accept("Enchanted " + stack.getHoverName().getString() + " with "
				+ holder.get().value().description().getString() + " " + finalLevel);
	}

	private static net.minecraft.client.player.LocalPlayer creativePlayer(Consumer<String> out) {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			out.accept("Join a world first");
			return null;
		}
		if (!mc.player.isCreative()) {
			out.accept("Creative mode only");
			return null;
		}
		return mc.player;
	}

	/** Sets the hotbar slot here and tells the server, as the creative inventory screen does. */
	private static void setCreativeSlot(net.minecraft.client.player.LocalPlayer player, int hotbarSlot,
			net.minecraft.world.item.ItemStack stack) {
		player.getInventory().setItem(hotbarSlot, stack);
		net.minecraft.client.Minecraft.getInstance().gameMode.handleCreativeModeItemAdd(stack,
				net.minecraft.world.inventory.InventoryMenu.USE_ROW_SLOT_START + hotbarSlot);
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

	/** The inverse of {@link #keyCode}: the word a key is typed as, for completions. */
	private static String keyArgument(int code) {
		if (code >= InputConstants.KEY_A && code <= InputConstants.KEY_Z) {
			return String.valueOf((char) ('a' + code - InputConstants.KEY_A));
		}
		if (code == InputConstants.KEY_0) {
			return "0";
		}
		if (code >= InputConstants.KEY_1 && code <= InputConstants.KEY_9) {
			return String.valueOf((char) ('1' + code - InputConstants.KEY_1));
		}
		if (code >= InputConstants.KEY_F1 && code <= InputConstants.KEY_F12) {
			return "f" + (code - InputConstants.KEY_F1 + 1);
		}
		return BindComponent.keyName(code).toLowerCase(Locale.ROOT);
	}

	/** a-z / 0-9 / f1-f12 / none → key code; Integer.MIN_VALUE when unknown. */
	private static int keyCode(String key) {
		if (key.equals("none") || key.equals("unbind")) {
			return Keys.NONE;
		}
		if (key.length() == 1) {
			char c = key.charAt(0);
			if (c >= 'a' && c <= 'z') {
				return InputConstants.KEY_A + (c - 'a');
			}
			// SDL runs 1-9 and puts 0 after 9, so the digits are not a block from KEY_0.
			if (c == '0') {
				return InputConstants.KEY_0;
			}
			if (c >= '1' && c <= '9') {
				return InputConstants.KEY_1 + (c - '1');
			}
		}
		if (key.startsWith("f")) {
			try {
				int n = Integer.parseInt(key.substring(1));
				if (n >= 1 && n <= 12) {
					return InputConstants.KEY_F1 + (n - 1);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return Integer.MIN_VALUE;
	}
}
