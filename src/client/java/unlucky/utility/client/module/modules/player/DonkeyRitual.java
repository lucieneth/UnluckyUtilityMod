package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.network.CarpetBridge;
import unlucky.utility.client.network.PlacePayload;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.HotbarVault;

/**
 * Ride a chested donkey, feed it worthless blocks, and watch them come back out as
 * the contents of a saved creative hotbar when it dies.
 *
 * <p>Filler goes into the donkey's chest one block per slot, the real stacks replace
 * it in a spoofed creative window opened at the last moment, and the donkey's own
 * death drops whatever the chest holds. Nothing goes through the command system, so
 * there is nothing in the command log.
 *
 * <p><b>The spoof is timed to the kill.</b> The filler stays in the chest while the
 * donkey is beaten down, and the window opens only once a full-charge hit is about to
 * be lethal. A container write lands a tick late — it hops to the main thread — so the
 * killing swing is deliberately held a couple of ticks after the window opens; the real
 * items are in the chest before the blow lands and the filler is never what drops. The
 * bias is always to open early rather than late: losing the timing costs a slightly
 * longer flash of the real items in a chest only you can see, not a donkey that dies
 * holding cobblestone.
 *
 * <p><b>Why a donkey.</b> {@code getInventoryColumns} is 5 with a chest, times 3 rows,
 * so 15 slots for a hotbar's 9. {@code AbstractHorse.dropEquipment} loops the whole
 * inventory on death and drops every stack, so the contents genuinely scatter. The
 * ritual insists you are mounted because that is the cover story, not a requirement.
 *
 * <p>The window has to be instant and atomic, so this needs {@link CarpetBridge} and
 * has no command fallback — {@link HotbarLoadout} keeps that.
 */
public class DonkeyRitual extends Module {
	public final BooleanSetting abortPlayers = add(new BooleanSetting("Abort on nearby player", "Stop before the swap if another player comes close", true));
	public final NumberSetting playerRange = add(new NumberSetting("Player range", "Nearby-player abort distance", 16, 2, 128, 1), abortPlayers::get);
	/** Cheap, stackable, and unremarkable in a survival inventory. */
	private static final String[] FILLER_NAMES = {"Cobblestone", "Dirt", "Stone", "Oak Planks", "Gravel"};

	/**
	 * How close to death the donkey must be before the real stacks swap in, as a
	 * multiple of the estimated full hit. Above 1 on purpose: {@code ATTACK_DAMAGE}
	 * knows the weapon but not its enchantments, so the estimate can run low, and the
	 * margin makes the swap fire a hit early rather than a hit late. Early wastes a
	 * moment; late drops cobblestone.
	 */
	private static final float LETHAL_MARGIN = 1.5f;

	/** Full-charge threshold: only land hits at (near) full attack strength. */
	private static final float FULL_CHARGE = 0.9f;

	/** Ticks to wait after sending the swap before the killing swing, so it applies. */
	private static final int SWAP_SETTLE = 2;

	private enum Stage {
		IDLE, KILL
	}

	public final NumberSetting hotbar = add(new NumberSetting("Hotbar",
			"Which saved creative hotbar to pull from (.hotbars shows what's in each)", 1, 1, HotbarVault.GROUPS, 1));
	public final ActionSetting preloadHotbar = add(new ActionSetting("Preload hotbar.nbt",
			"Load saved creative hotbars now, so the ritual's first run does not stutter", this::preloadHotbar));
	public final NumberSetting slots = add(new NumberSetting("Slots",
			"How many of that hotbar's filled slots to recover — each becomes one temp block in its own chest slot",
			9, 1, 9, 1));
	public final ModeSetting filler = add(new ModeSetting("Filler",
			"The block you appear to be feeding the donkey", "Cobblestone", FILLER_NAMES));
	public final BooleanSetting autoKill = add(new BooleanSetting("Auto kill",
			"Beat the donkey down and land the lethal hit for you. Off: the temp blocks are staged and swapped once "
					+ "it's one hit from death, and you throw that hit yourself.", true));
	public final BooleanSetting narrate = add(new BooleanSetting("Narrate",
			"Print the in-character progress lines", true));
	public final BooleanSetting dryRun = add(new BooleanSetting("Dry run",
			"Describe what would happen instead of doing it", false));

	/**
	 * Always on and not a setting: {@code ChatComponentMixin} drops
	 * {@code SYSTEM_SERVER} lines while the ritual runs. Static because the mixin has no
	 * instance to ask, and narrow by design — player chat is a different source and is
	 * never touched.
	 */
	private static boolean swallowing;

	private Stage stage = Stage.IDLE;

	/** The real stacks to swap in, index = the donkey chest slot they belong to. */
	private final List<ItemStack> recovered = new ArrayList<>();
	private boolean swapped;
	private int swapWait;

	public DonkeyRitual() {
		super("DonkeyRitual", "Turns filler blocks into a saved creative hotbar, theatrically", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Whether chat should be dropping server system lines right now. */
	public static boolean swallowingServerChat() {
		return swallowing;
	}

	private void preloadHotbar() {
		HotbarVault.preload();
	}

	@Override
	protected void onEnable() {
		reset();

		if (mc().player == null || !HotbarVault.ready()) {
			fail("not in a world");
			return;
		}
		if (!CarpetBridge.available()) {
			fail("no Carpet bridge on this server — DonkeyRitual needs it");
			return;
		}

		AbstractChestedHorse donkey = mount();
		if (donkey == null) {
			fail("you need to be riding a donkey or mule with a chest on it");
			return;
		}

		// first N filled slots of the chosen hotbar, capped by how many you asked for
		// and by the chest; each recovered stack takes its own chest slot, 0-based
		List<HotbarVault.Entry> saved = HotbarVault.read(hotbar.getInt());
		if (saved.isEmpty()) {
			fail("saved hotbar " + hotbar.getInt() + " is empty — press Ctrl+" + hotbar.getInt()
					+ " in a creative inventory first");
			return;
		}
		int want = Math.min(Math.min(slots.getInt(), saved.size()), donkey.getInventorySize());
		for (int i = 0; i < want; i++) {
			recovered.add(saved.get(i).stack().copy());
		}

		Item temp = fillerItem();
		int have = countInInventory(temp);
		if (have < recovered.size()) {
			fail("need " + recovered.size() + " " + filler.get().toLowerCase() + " (one per slot) and you have " + have);
			return;
		}

		if (dryRun.get()) {
			ChatUtil.info("§7DonkeyRitual dry run — would feed §f" + recovered.size() + "§7 " + filler.get().toLowerCase()
					+ "§7 and swap in:");
			for (int i = 0; i < recovered.size(); i++) {
				ItemStack s = recovered.get(i);
				ChatUtil.info("§8  slot " + i + " → " + s.getCount() + "× " + s.getItem());
			}
			setEnabled(false);
			return;
		}

		swallowing = true;
		sendCover(temp);
		stage = Stage.KILL;
		say("§8[§dUnlucky§8] §7Feeding §f" + recovered.size() + "§7 " + filler.get().toLowerCase() + "§7 to the donkey …");
	}

	@Override
	protected void onDisable() {
		reset();
		swallowing = false;
	}

	private void reset() {
		recovered.clear();
		swapped = false;
		swapWait = 0;
		stage = Stage.IDLE;
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			setEnabled(false);
			return;
		}
		if (stage != Stage.KILL) {
			return;
		}
		if (abortPlayers.get() && mc().level.players().stream().anyMatch(player -> player != mc().player
				&& player.distanceTo(mc().player) <= playerRange.get())) {
			fail("another player is nearby");
			return;
		}

		AbstractChestedHorse donkey = mount();
		if (donkey == null) {
			fail("you left the donkey before it died");
			return;
		}
		if (!donkey.isAlive()) {
			finish();
			return;
		}

		float charge = mc().player.getAttackStrengthScale(0.5f);
		boolean full = charge >= FULL_CHARGE;
		boolean lethalSoon = donkey.getHealth() <= estimatedHit() * LETHAL_MARGIN;

		if (!swapped) {
			if (lethalSoon && (full || !autoKill.get())) {
				// one hit from death: open the window, then let the swing land after a
				// couple of ticks so the writes have been applied
				say("§8[§dUnlucky§8] §7Spoofing creative …");
				sendSwap();
				swapped = true;
				swapWait = SWAP_SETTLE;
				say("§8[§dUnlucky§8] §7The donkey shudders.");
			} else if (autoKill.get() && full) {
				// still healthy: bring it down with the temp blocks still in the chest
				swing(donkey);
			}
			return;
		}

		if (swapWait > 0) {
			swapWait--;
			return;
		}
		if (!autoKill.get()) {
			// staged for a hit you throw yourself
			say("§8[§dUnlucky§8] §7Loaded. §8Land the last hit.");
			finish();
			return;
		}
		if (full) {
			swing(donkey);
			if (!donkey.isAlive()) {
				finish();
			}
		}
	}

	/** The chested horse you are sitting on, or null. */
	private AbstractChestedHorse mount() {
		Entity vehicle = mc().player.getVehicle();
		return vehicle instanceof AbstractChestedHorse horse && horse.hasChest() ? horse : null;
	}

	private void swing(AbstractChestedHorse donkey) {
		mc().gameMode.attack(mc().player, donkey);
		mc().player.swing(InteractionHand.MAIN_HAND);
	}

	/**
	 * A full-charge hit's damage, from {@code ATTACK_DAMAGE}. The weapon is in it; its
	 * enchantments are not, which is why {@link #LETHAL_MARGIN} pads the swap decision.
	 * No crit term: you are sitting on the donkey, and a mounted attacker never crits.
	 */
	private double estimatedHit() {
		return mc().player.getAttributeValue(Attributes.ATTACK_DAMAGE);
	}

	/**
	 * The cover: one temp block into each of the donkey's used chest slots, and the same
	 * count taken out of your own inventory, so it reads as the blocks moving into the
	 * donkey rather than appearing from nowhere. One write — temp blocks are tiny.
	 */
	private void sendCover(Item temp) {
		List<PlacePayload.Entry> entries = new ArrayList<>();
		for (int slot = 0; slot < recovered.size(); slot++) {
			entries.add(PlacePayload.Entry.vehicle(slot, new ItemStack(temp, 1)));
		}
		entries.addAll(consumeTemp(temp, recovered.size()));
		CarpetBridge.place(entries);
	}

	/**
	 * The window: each real stack into its chest slot, one write per stack. Separate
	 * writes because a single saved container can be megabytes and the per-packet cap
	 * is the wire's frame limit — nine of them in one could overrun it, nine in one
	 * tick cannot.
	 */
	private void sendSwap() {
		for (int slot = 0; slot < recovered.size(); slot++) {
			CarpetBridge.place(PlacePayload.Entry.vehicle(slot, recovered.get(slot)));
		}
	}

	/** Player-inventory entries that remove {@code n} temp blocks, for the cover packet. */
	private List<PlacePayload.Entry> consumeTemp(Item item, int n) {
		List<PlacePayload.Entry> out = new ArrayList<>();
		Inventory inventory = mc().player.getInventory();
		int left = n;
		for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(item)) {
				continue;
			}
			int take = Math.min(left, stack.getCount());
			ItemStack reduced = stack.copy();
			reduced.shrink(take);
			out.add(PlacePayload.Entry.player(slot, reduced));
			left -= take;
		}
		return out;
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

	private void finish() {
		say("§8[§dUnlucky§8] §a" + recovered.size() + " item(s) recovered from hotbar " + hotbar.getInt());
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
}
