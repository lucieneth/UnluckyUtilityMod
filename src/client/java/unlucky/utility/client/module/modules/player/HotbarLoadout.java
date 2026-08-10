package unlucky.utility.client.module.modules.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import net.minecraft.world.entity.player.Inventory;
import unlucky.utility.client.network.CarpetBridge;
import unlucky.utility.client.network.PlacePayload;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.HotbarVault;

/**
 * Restores a saved <em>creative</em> hotbar into survival, without a gamemode
 * switch.
 *
 * <p>The saved hotbars vanilla writes when you press Ctrl+1..9 in the creative
 * inventory live in {@code hotbar.nbt} in your game directory, and they keep the
 * full stack — every data component, including the ones you can only get from a
 * command. That file is ours to read. Getting the items back onto a server is the
 * rest of the job, and every route here goes through Carpet rather than through
 * vanilla's creative-slot handler, which is gated on {@code abilities.instabuild}.
 * None of them need the player to be an operator:
 *
 * <ul>
 * <li><b>Creative spoof</b> — the stack is re-serialised with the codec vanilla
 *     stored it with and written straight into the container. No command, so no
 *     32767 cap, no chunking and no throttle. Needs the bridge channel to come back
 *     from the handshake; see {@link CarpetBridge}.
 * <li><b>Script run</b> — {@code /script run inventory_set(...)}, nothing to install.
 *     Costs {@code commandScriptACE 0} in {@code carpet.conf}, which opens
 *     <em>all</em> of Scarpet to <em>every</em> player, not just this.
 * <li><b>App command</b> — {@code server/scarpet/spawnart.sc} in the world folder,
 *     which keeps the blast radius to one command at the price of a file upload.
 * </ul>
 *
 * <p>Commands go out through {@code sendCommand} rather than
 * {@link ChatUtil#say}, which fits to {@code MAX_CHAT} (256). The command packet
 * itself is a bare {@code readUtf()} — 32767 — and component-heavy stacks need
 * far more than 256 characters.
 */
public class HotbarLoadout extends Module {
	/** Vanilla saves nine of these; {@code HotbarManager.NUM_HOTBAR_GROUPS}. */
	private static final int GROUPS = 9;

	public final NumberSetting group = add(new NumberSetting("Hotbar",
			"Which saved creative hotbar to restore (Ctrl+1..9 in the creative inventory)", 1, 1, GROUPS, 1));
	public final ModeSetting method = add(new ModeSetting("Method",
			"Creative spoof: needs the Carpet bridge channel on the server — one write per item, no size limit, "
					+ "nothing in the command log. Script run: needs carpet.conf commandScriptACE 0, nothing to install. "
					+ "App command: needs spawnart.sc in the world folder.",
			"Creative spoof", "Creative spoof", "Script run", "App command")
			.withLabels(value -> value.equals("Script run") || value.equals("App command") ? value : "Creative spoof"));
	public final ModeSetting placement = add(new ModeSetting("Placement",
			"Same slot it was saved in, or the first free slot", "Same slot", "Same slot", "First free"));
	public final BooleanSetting skipEmpty = add(new BooleanSetting("Skip empty",
			"Don't send anything for slots that were empty when you saved", true));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between commands — one per tick can trip server rate limits", 4, 0, 40, 1),
			() -> !isSpoofMethod());
	public final StringSetting command = add(new StringSetting("Command",
			"Name of the Scarpet app command (see server/scarpet/spawnart.sc)", "spawnart"),
			() -> method.is("App command"));
	public final BooleanSetting spamGuard = add(new BooleanSetting("Spam guard",
			"Slow down when a transfer is long enough to trip the server's command spam kick. "
					+ "Ops and the singleplayer owner bypass that kick — turn this off if you are one",
			true), () -> !isSpoofMethod());
	public final BooleanSetting dryRun = add(new BooleanSetting("Dry run",
			"Print what would be sent to chat instead of sending it", false));

	/** Pending commands, drained one per {@link #pace} ticks. */
	private final Deque<String> queue = new ArrayDeque<>();
	private int cooldown;

	/** {@link #delay}, or slower when the queue would outrun the server's spam kick. */
	private int pace;

	public HotbarLoadout() {
		super("HotbarLoadout", "Restores a saved creative hotbar in survival", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * Enabling is the trigger, so a keybind fires one restore rather than leaving
	 * something running: the queue is built here and the module turns itself off
	 * once it has drained.
	 */
	@Override
	protected void onEnable() {
		queue.clear();
		cooldown = 0;

		if (mc().player == null || mc().getConnection() == null) {
			ChatUtil.info("§cHotbarLoadout: not in a world");
			setEnabled(false);
			return;
		}

		if (isSpoofMethod()) {
			// Also migrates the former name when an older config is first used.
			method.set("Creative spoof");
			restoreSpoof();
			setEnabled(false);
			return;
		}

		List<String> commands = build();
		if (commands.isEmpty()) {
			ChatUtil.info("§cHotbarLoadout: saved hotbar " + group.getInt() + " is empty");
			setEnabled(false);
			return;
		}

		if (dryRun.get()) {
			ChatUtil.info("§7HotbarLoadout dry run — " + commands.size() + " command(s):");
			for (String line : commands) {
				ChatUtil.info("§8" + trim(line));
			}
			setEnabled(false);
			return;
		}

		queue.addAll(commands);
		pace = HotbarVault.safeDelay(commands.size(), delay.getInt(), !spamGuard.get());
		if (pace != delay.getInt()) {
			ChatUtil.info("§eHotbarLoadout: pacing to §f" + pace + "§e ticks — " + commands.size()
					+ " commands at " + delay.getInt() + " would trip the server's spam kick");
		}
		ChatUtil.info("§7HotbarLoadout: sending §f" + commands.size() + "§7 command(s) from hotbar §f"
				+ group.getInt() + "§7 — about §f" + (commands.size() * pace / 20) + "§7s");
	}

	@Override
	protected void onDisable() {
		queue.clear();
	}

	/**
	 * The spoof path: no commands at all. Each saved stack is written into the
	 * container over the bridge. No 32767 cap, no chunking, no spam throttle, no
	 * command log — the size gymnastics the Scarpet methods need do not exist here.
	 */
	private void restoreSpoof() {
		if (!CarpetBridge.available()) {
			ChatUtil.info("§cHotbarLoadout: no Carpet bridge on this server");
			return;
		}

		List<HotbarVault.Entry> saved = HotbarVault.read(group.getInt());
		if (saved.isEmpty()) {
			ChatUtil.info("§cHotbarLoadout: saved hotbar " + group.getInt() + " is empty");
			return;
		}

		Inventory inventory = mc().player.getInventory();
		List<PlacePayload.Entry> entries = new ArrayList<>();
		int nextFree = 0;
		for (HotbarVault.Entry saved0 : saved) {
			int slot;
			if (placement.is("Same slot")) {
				slot = saved0.slot();
			} else {
				slot = firstFreeFrom(inventory, nextFree);
				if (slot < 0) {
					ChatUtil.info("§cHotbarLoadout: no free slot left, stopped early");
					break;
				}
				nextFree = slot + 1;
			}
			entries.add(PlacePayload.Entry.player(slot, saved0.stack()));
		}

		if (dryRun.get()) {
			ChatUtil.info("§7HotbarLoadout dry run — would place §f" + entries.size() + "§7 item(s):");
			for (PlacePayload.Entry e : entries) {
				ChatUtil.info("§8  slot " + e.slot() + " → " + e.stack().getCount() + "× " + e.stack().getItem());
			}
			return;
		}

		// one packet per item: a single saved container can be megabytes, and the wire
		// frame caps a packet, so nine-in-one could overrun where nine packets won't
		for (PlacePayload.Entry e : entries) {
			CarpetBridge.place(e);
		}
		ChatUtil.info("§aHotbarLoadout: placed §f" + entries.size() + "§a item(s) from hotbar §f" + group.getInt());
	}

	/** Unknown legacy values are the spoof method and are normalized on use. */
	private boolean isSpoofMethod() {
		return !method.is("Script run") && !method.is("App command");
	}

	/** First empty inventory slot at or after {@code from}, or -1 if the inventory is full. */
	private static int firstFreeFrom(Inventory inventory, int from) {
		for (int slot = from; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			setEnabled(false);
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		String next = queue.poll();
		if (next == null) {
			ChatUtil.info("§aHotbarLoadout: done");
			setEnabled(false);
			return;
		}
		// belt and braces: build() already filters, but an oversized command is a
		// disconnect rather than an error, so nothing reaches the wire unmeasured
		if (!HotbarVault.fits(next)) {
			ChatUtil.info("§cHotbarLoadout: dropped a " + next.length() + "-char command");
			cooldown = pace;
			return;
		}
		mc().player.connection.sendCommand(next);
		cooldown = pace;
	}

	/**
	 * Reads the saved hotbar and turns each stack into a command.
	 *
	 * <p>The payload is {@code ItemStack.CODEC} output as SNBT, which is what the
	 * app feeds back through the same codec — so this is a round trip rather than a
	 * reconstruction, and a stack that vanilla could save is a stack the app can
	 * rebuild. {@code hotbar.nbt} stores with {@code OPTIONAL_CODEC}; empty slots
	 * decode to {@link ItemStack#EMPTY} and are dropped here.
	 */
	private List<String> build() {
		HolderLookup.Provider registries = mc().getConnection().registryAccess();
		List<ItemStack> stacks = mc().getHotbarManager().get(group.getInt() - 1).load(registries);
		List<String> out = new ArrayList<>();

		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (stack.isEmpty()) {
				if (!skipEmpty.get()) {
					ChatUtil.info("§8HotbarLoadout: slot " + slot + " was empty");
				}
				continue;
			}
			String snbt = encode(stack, registries);
			if (snbt == null) {
				ChatUtil.info("§cHotbarLoadout: slot " + slot + " could not be serialised, skipped");
				continue;
			}
			List<String> lines = method.is("Script run") ? scriptRun(slot, snbt) : appCommand(slot, snbt);
			if (lines.isEmpty()) {
				ChatUtil.info("§cHotbarLoadout: slot " + slot + " could not be split, skipped");
				continue;
			}
			if (lines.size() > 1) {
				ChatUtil.info("§7HotbarLoadout: slot §f" + slot + "§7 is §f" + snbt.length()
						+ "§7 chars — sending in §f" + (lines.size() - 2) + "§7 chunks");
			}
			out.addAll(lines);
		}
		return out;
	}

	/**
	 * The no-install path: {@code /script run} straight into {@code inventory_set}.
	 *
	 * <p>Costs one line in {@code carpet.conf} — {@code commandScriptACE 0}, which is
	 * {@code Commands.LEVEL_ALL}, every player — and then there is nothing to upload,
	 * because the whole payload rides in the command. Note what that setting really
	 * grants: {@code /script run} is arbitrary Scarpet for anyone who joins, not just
	 * this one function.
	 *
	 * <p>A null count is what makes the stored count survive: {@code inventory_set}
	 * only overrides it when the argument isn't null, and the count is already inside
	 * the serialised stack. The item name is ignored whenever NBT is supplied — the
	 * stack is rebuilt from the payload by {@code ItemStack.CODEC}.
	 */
	private List<String> scriptRun(int slot, String snbt) {
		String where = placement.is("Same slot") ? String.valueOf(slot) : "inventory_find(player(),null)";
		String payload = escape(snbt);
		String single = "script run inventory_set(player()," + where + ",null,'stone','" + payload + "')";
		if (HotbarVault.fits(single)) {
			return List.of(single);
		}

		String open = "script run put(" + HotbarVault.BUFFER + ",null,'";
		String close = "')";
		List<String> parts = HotbarVault.split(payload, HotbarVault.MAX_COMMAND - open.length() - close.length());
		if (parts.isEmpty()) {
			return List.of();
		}

		List<String> out = new ArrayList<>(parts.size() + 2);
		out.add("script run " + HotbarVault.BUFFER + "=[]");
		for (String part : parts) {
			out.add(open + part + close);
		}
		// join once, place, then drop the buffer so a 5 MB payload is not left
		// sitting in the app host until the next transfer overwrites it
		out.add("script run inventory_set(player()," + where + ",null,'stone',join(''," + HotbarVault.BUFFER + "));"
				+ HotbarVault.BUFFER + "=[]");
		return out;
	}

	private List<String> appCommand(int slot, String snbt) {
		String single = placement.is("Same slot")
				? command.get() + " slot " + slot + " " + snbt
				: command.get() + " give " + snbt;
		if (HotbarVault.fits(single)) {
			return List.of(single);
		}

		// the app takes 'text' arguments greedily, so chunks ride raw — no Scarpet
		// string escaping, and correspondingly more payload per command
		String open = command.get() + " chunk ";
		List<String> parts = HotbarVault.split(snbt, HotbarVault.MAX_COMMAND - open.length());
		if (parts.isEmpty()) {
			return List.of();
		}

		List<String> out = new ArrayList<>(parts.size() + 2);
		out.add(command.get() + " begin");
		for (String part : parts) {
			out.add(open + part);
		}
		out.add(placement.is("Same slot")
				? command.get() + " commit slot " + slot
				: command.get() + " commit give");
		return out;
	}

	/**
	 * Escapes a payload for a Scarpet single-quoted string.
	 *
	 * <p>Scarpet's tokenizer takes {@code \\} and {@code \'} inside {@code '...'}, and
	 * SNBT reaches for single quotes on its own whenever a string value contains a
	 * double quote — so this is load-bearing for exactly the components most worth
	 * moving, the ones with text in them.
	 */
	private static String escape(String snbt) {
		return snbt.replace("\\", "\\\\").replace("'", "\\'");
	}

	/** {@code ItemStack} to SNBT, or null if the codec refuses it. */
	private static String encode(ItemStack stack, HolderLookup.Provider registries) {
		try {
			Tag tag = ItemStack.CODEC
					.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
					.getOrThrow();
			return tag.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Dry-run lines are for eyeballing, and a 3KB component blob is not. */
	private static String trim(String line) {
		return line.length() <= 180 ? line : line.substring(0, 180) + "§8… (" + line.length() + " chars)";
	}
}
