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
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Restores a saved <em>creative</em> hotbar into survival, without a gamemode
 * switch.
 *
 * <p>The saved hotbars vanilla writes when you press Ctrl+1..9 in the creative
 * inventory live in {@code hotbar.nbt} in your game directory, and they keep the
 * full stack — every data component, including the ones you can only get from a
 * command. That file is ours to read. Getting the items back onto a server is the
 * part vanilla will not do: {@code handleSetCreativeModeSlot} is the only handler
 * that accepts a client-authored {@code ItemStack} and it is gated on
 * {@code abilities.instabuild}, which no packet can write. There is no bypass —
 * the protocol has no other field that carries an item.
 *
 * <p>So this does not try to bypass it. It re-serialises each stack with the same
 * codec vanilla stored it with and hands it to a Carpet Scarpet app
 * ({@code server/scarpet/spawnart.sc}) that does the write server-side. The app
 * decides who is allowed to ask; with {@code command_permission} set to
 * {@code 'all'} that is everyone, no operator status involved.
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
	public final ModeSetting placement = add(new ModeSetting("Placement",
			"Same slot it was saved in, or the first free slot", "Same slot", "Same slot", "First free"));
	public final BooleanSetting skipEmpty = add(new BooleanSetting("Skip empty",
			"Don't send anything for slots that were empty when you saved", true));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between commands — one per tick can trip server rate limits", 4, 0, 40, 1));
	public final StringSetting command = add(new StringSetting("Command",
			"Name of the Scarpet app command (see server/scarpet/spawnart.sc)", "spawnart"));
	public final BooleanSetting dryRun = add(new BooleanSetting("Dry run",
			"Print what would be sent to chat instead of sending it", false));

	/** Pending commands, drained one per {@link #delay} ticks. */
	private final Deque<String> queue = new ArrayDeque<>();
	private int cooldown;

	public HotbarLoadout() {
		super("HotbarLoadout", "Restores a saved creative hotbar in survival", Category.PLAYER);
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
		ChatUtil.info("§7HotbarLoadout: restoring §f" + commands.size() + "§7 item(s) from hotbar §f" + group.getInt());
	}

	@Override
	protected void onDisable() {
		queue.clear();
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
		mc().player.connection.sendCommand(next);
		cooldown = delay.getInt();
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
			out.add(placement.is("Same slot")
					? command.get() + " slot " + slot + " " + snbt
					: command.get() + " give " + snbt);
		}
		return out;
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
