package unlucky.utility.client.module.modules.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.HotbarVault;

/**
 * The performance: ride a chested donkey, feed it worthless blocks, and watch them
 * come back out as the contents of a saved creative hotbar when it dies.
 *
 * <p><b>What this actually does.</b> It sends {@code /script run inventory_set(...)}
 * to the server — three times per item, in fact: once to shrink the stack of filler
 * you are paying with, once to write the real stack into the donkey's chest, and the
 * kill is just you hitting it. Carpet does the work. Nothing here bypasses anything,
 * and {@code commandScriptACE} has to be reachable or every step no-ops. The illusion
 * is entirely in the presentation: command feedback is swallowed, our own lines are
 * written to look like a container desync, and the donkey does the rest by dying with
 * a full inventory.
 *
 * <p>That distinction is worth keeping straight while reading this file. Someone
 * watching sees filler vanish and impossible items hit the ground; someone reading
 * sees a queue of commands. Both are true, and only the first one is the point.
 *
 * <p><b>Why a donkey.</b> {@code AbstractChestedHorse.getInventoryColumns} is 5 when
 * it has a chest, times 3 rows, so 15 slots — more than a hotbar's 9.
 * {@code AbstractHorse.dropEquipment} loops the whole inventory on death and
 * {@code spawnAtLocation}s every non-empty stack, so the contents genuinely scatter
 * rather than being deleted. And {@code OPEN_INVENTORY} only opens the screen when
 * {@code player.getVehicle()} is the thing being opened, which is why the ritual
 * insists you are actually mounted rather than merely standing next to it.
 */
public class DonkeyRitual extends Module {
	/** Cheap, stackable, and unremarkable in a survival inventory. */
	private static final String[] FILLER_NAMES = {"Cobblestone", "Dirt", "Stone", "Oak Planks", "Gravel"};

	private enum Stage {
		IDLE, CONSUME, OPEN, LOAD, KILL, DONE
	}

	public final NumberSetting hotbar = add(new NumberSetting("Hotbar",
			"Which saved creative hotbar to pull from (.hotbars shows what's in each)", 1, 1, HotbarVault.GROUPS, 1));
	public final ModeSetting filler = add(new ModeSetting("Filler",
			"The block you appear to be converting", "Cobblestone", FILLER_NAMES));
	public final NumberSetting fillerCost = add(new NumberSetting("Cost",
			"Filler consumed per item produced", 1, 1, 64, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between steps — the pacing of the whole performance", 6, 1, 60, 1));
	public final BooleanSetting autoKill = add(new BooleanSetting("Auto kill",
			"Swing at the donkey once it's loaded. Off means you land the last hit yourself.", true));
	public final BooleanSetting silent = add(new BooleanSetting("Silent",
			"Swallow the server's command replies so only our own lines show", true));
	public final BooleanSetting narrate = add(new BooleanSetting("Narrate",
			"Print the in-character progress lines", true));
	public final BooleanSetting dryRun = add(new BooleanSetting("Dry run",
			"Print the commands instead of sending them", false));

	/**
	 * Open while the ritual is sending commands — {@code ChatComponentMixin} drops
	 * {@code SYSTEM_SERVER} lines during this window. Static because the mixin has no
	 * instance to ask, and deliberately narrow: player chat is a different
	 * {@code GuiMessageSource} and is never touched.
	 */
	private static boolean swallowing;

	private final Deque<String> queue = new ArrayDeque<>();
	private Stage stage = Stage.IDLE;
	private int cooldown;
	private int produced;

	public DonkeyRitual() {
		super("DonkeyRitual", "Turns filler blocks into a saved creative hotbar, theatrically", Category.PLAYER);
	}

	/** Whether chat should be dropping server system lines right now. */
	public static boolean swallowingServerChat() {
		return swallowing;
	}

	@Override
	protected void onEnable() {
		queue.clear();
		cooldown = 0;
		produced = 0;
		stage = Stage.IDLE;

		if (mc().player == null || !HotbarVault.ready()) {
			fail("not in a world");
			return;
		}

		AbstractChestedHorse donkey = mount();
		if (donkey == null) {
			fail("you need to be riding a donkey or mule with a chest on it");
			return;
		}

		List<HotbarVault.Entry> entries = HotbarVault.read(hotbar.getInt());
		if (entries.isEmpty()) {
			fail("saved hotbar " + hotbar.getInt() + " is empty — press Ctrl+" + hotbar.getInt()
					+ " in a creative inventory first");
			return;
		}
		if (entries.size() > donkey.getInventorySize()) {
			fail("hotbar " + hotbar.getInt() + " has " + entries.size() + " items and the chest holds "
					+ donkey.getInventorySize());
			return;
		}

		int needed = entries.size() * fillerCost.getInt();
		Item fillerItem = fillerItem();
		int have = countInInventory(fillerItem);
		if (have < needed) {
			fail("need " + needed + " " + filler.get().toLowerCase() + " and you have " + have);
			return;
		}

		queue.addAll(consumeCommands(fillerItem, needed));
		queue.addAll(loadCommands(donkey, entries));
		produced = entries.size();

		if (dryRun.get()) {
			ChatUtil.info("§7DonkeyRitual dry run — " + queue.size() + " command(s):");
			for (String line : queue) {
				ChatUtil.info("§8" + (line.length() <= 160 ? line : line.substring(0, 160) + "… (" + line.length() + ")"));
			}
			queue.clear();
			setEnabled(false);
			return;
		}

		swallowing = silent.get();
		stage = Stage.CONSUME;
		say("§8[§dUnlucky§8] §7Reading §fhotbar " + hotbar.getInt() + "§7 …");
	}

	@Override
	protected void onDisable() {
		queue.clear();
		swallowing = false;
		stage = Stage.IDLE;
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
		cooldown = delay.getInt();

		switch (stage) {
			case CONSUME -> {
				// the filler shrinking is the visible lie: to anyone watching, the
				// blocks are being spent on whatever comes out of the donkey
				if (drain()) {
					say("§8[§dUnlucky§8] §7" + filler.get() + " §8→ §7staging buffer");
					stage = Stage.OPEN;
				}
			}
			case OPEN -> {
				AbstractChestedHorse donkey = mount();
				if (donkey == null) {
					fail("you got off the donkey");
					return;
				}
				openMountInventory();
				say("§8[§dUnlucky§8] §7Container open §8· §7writing §f" + produced + "§7 slots");
				stage = Stage.LOAD;
			}
			case LOAD -> {
				if (drain()) {
					stage = Stage.KILL;
				}
			}
			case KILL -> {
				AbstractChestedHorse donkey = mount();
				if (donkey == null) {
					finish();
					return;
				}
				if (!autoKill.get()) {
					say("§8[§dUnlucky§8] §7Loaded. §8Kill it when you're ready.");
					finish();
					return;
				}
				mc().gameMode.attack(mc().player, donkey);
				mc().player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				if (!donkey.isAlive()) {
					finish();
				}
			}
			default -> setEnabled(false);
		}
	}

	/** Sends the next queued command. True once the queue is empty. */
	private boolean drain() {
		String next = queue.poll();
		if (next == null) {
			return true;
		}
		mc().player.connection.sendCommand(next);
		return queue.isEmpty();
	}

	private void finish() {
		say("§8[§dUnlucky§8] §a" + produced + " item(s) recovered from hotbar " + hotbar.getInt());
		setEnabled(false);
	}

	private void fail(String reason) {
		ChatUtil.info("§cDonkeyRitual: " + reason);
		setEnabled(false);
	}

	private void say(String line) {
		if (narrate.get()) {
			ChatUtil.info(line);
		}
	}

	/** The chested horse you are sitting on, or null. */
	private AbstractChestedHorse mount() {
		Entity vehicle = mc().player.getVehicle();
		return vehicle instanceof AbstractChestedHorse horse && horse.hasChest() ? horse : null;
	}

	/**
	 * Vanilla's own "open the thing I'm riding" action. The server checks
	 * {@code player.getVehicle() instanceof HasCustomInventoryScreen}, so this is the
	 * same packet the inventory key sends while mounted — no special casing.
	 */
	private void openMountInventory() {
		mc().player.connection.send(new ServerboundPlayerCommandPacket(
				mc().player, ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY));
	}

	private Item fillerItem() {
		return switch (filler.get()) {
			case "Dirt" -> Items.DIRT;
			case "Stone" -> Items.STONE;
			case "Oak Planks" -> Items.OAK_PLANKS;
			case "Gravel" -> Items.GRAVEL;
			default -> Items.COBBLESTONE;
		};
	}

	private int countInInventory(Item item) {
		Inventory inventory = mc().player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Shrinks filler stacks until {@code needed} items are gone.
	 *
	 * <p>Uses {@code inventory_set}'s three-argument form, which copies the stack
	 * already in the slot and only changes the count — so enchanted or renamed filler
	 * would keep whatever it carries. A count of 0 is the documented removal case and
	 * takes the slot out entirely.
	 */
	private List<String> consumeCommands(Item item, int needed) {
		List<String> out = new ArrayList<>();
		Inventory inventory = mc().player.getInventory();
		int left = needed;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(item)) {
				continue;
			}
			int take = Math.min(left, stack.getCount());
			out.add("script run inventory_set(player()," + slot + "," + (stack.getCount() - take) + ")");
			left -= take;
		}
		return out;
	}

	/**
	 * Writes the saved stacks into the donkey's chest.
	 *
	 * <p>The donkey is addressed by UUID: vanilla's selector parser takes a bare UUID
	 * as a target, so this cannot pick up a second donkey standing next to the one you
	 * are on. Passing null for the count keeps the count stored inside the payload, and
	 * the item name is ignored whenever NBT is supplied — {@code ItemStack.CODEC}
	 * rebuilds the whole stack from it.
	 */
	private List<String> loadCommands(AbstractChestedHorse donkey, List<HotbarVault.Entry> entries) {
		List<String> out = new ArrayList<>();
		String target = "entity_selector('" + donkey.getUUID() + "'):0";
		int chestSlot = 0;
		for (HotbarVault.Entry entry : entries) {
			String snbt = HotbarVault.encode(entry.stack());
			if (snbt == null) {
				ChatUtil.info("§cDonkeyRitual: hotbar slot " + entry.slot() + " wouldn't serialise, skipped");
				continue;
			}
			out.add("script run inventory_set(" + target + "," + chestSlot++ + ",null,'stone','" + escape(snbt) + "')");
		}
		return out;
	}

	/**
	 * Escapes a payload for a Scarpet single-quoted string, which takes {@code \\} and
	 * {@code \'}. Load-bearing for exactly the components worth moving: SNBT switches
	 * to single quotes by itself as soon as a string value contains a double quote.
	 */
	private static String escape(String snbt) {
		return snbt.replace("\\", "\\\\").replace("'", "\\'");
	}
}
