package unlucky.utility.client.module.modules.player;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.MoveUtil;

/**
 * One key for wings, and a guard against flying on wings that are about to go.
 *
 * <p><b>The two halves have different urgencies and the priorities say so.</b> A manual swap is a
 * convenience; an automatic replacement of a nearly broken elytra is the thing standing between the
 * player and a long silent fall, and it runs at {@code ELYTRA_SAFETY} — above {@link AutoArmor}'s
 * {@code EQUIPMENT}, below AutoTotem's {@code TOTEM}. Both of those orderings are deliberate.
 * AutoArmor putting a chestplate on top of a swap that is happening <em>because the wings are
 * failing</em> would be the module politely undoing the rescue; a totem reaching your hand is still
 * more urgent than either.
 *
 * <p><b>Never a chestplate while gliding.</b> Not as a setting, not as a mode — the swap that takes
 * your wings off at altitude is indistinguishable from falling, and no configuration should be able
 * to ask for it. {@link #guardsChestSlot()} is how AutoArmor is told the same thing.
 *
 * <p><b>The rocket is fired after the swap is confirmed, never alongside it.</b> A failed swap that
 * still spends a firework leaves the player on the ground, one rocket down, wearing a chestplate
 * and wondering what happened.
 */
public class ElytraSwap extends Module {
	/** Chest armour's index in the player's own inventory menu: 0 result, 1-4 grid, 5-8 armour. */
	private static final int CHEST_MENU_SLOT = 6;

	/** Ticks between "you have no spare" warnings. */
	private static final int WARN_INTERVAL = 200;

	/** Grounded ticks before Ground restore acts, so a bounced landing does not trigger it. */
	private static final int LANDED_TICKS = 5;

	public final KeybindSetting swapKey = add(new KeybindSetting("Swap key",
			"Press to swap chest armour and elytra. Separate from the module's own bind, which "
					+ "still just turns it on and off.", GLFW.GLFW_KEY_UNKNOWN));
	public final ModeSetting manualMode = add(new ModeSetting("Manual swap mode",
			"What the swap key does", "Toggle", "Toggle", "Elytra only", "Chestplate only"));

	public final BooleanSetting autoReplace = add(new BooleanSetting("Auto replace",
			"Replace worn-out wings from the bag before they break", true));
	public final NumberSetting replaceThreshold = add(new NumberSetting("Replace threshold",
			"Remaining durability points that trigger a replacement", 5, 1, 100, 1), autoReplace::get);
	public final ModeSetting sparePreference = add(new ModeSetting("Spare preference",
			"Which spare elytra to reach for", "Highest durability",
			"Highest durability", "Mending first", "First found"), autoReplace::get);

	public final BooleanSetting groundRestore = add(new BooleanSetting("Ground restore",
			"Put chest armour back once you have landed", false));
	public final ModeSetting chestPreference = add(new ModeSetting("Chestplate preference",
			"Which chestplate to put on", "Best armor", "Best armor", "First found", "Custom"));
	public final ItemListSetting customChestplates = add(new ItemListSetting("Custom chestplates",
			"Preference order for Custom — right-click to pick",
			item -> item.components().has(DataComponents.EQUIPPABLE)),
			() -> chestPreference.is("Custom"));

	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between inventory actions", 1, 0, 20, 1));
	public final BooleanSetting pauseWhileMoving = add(new BooleanSetting("Pause while moving",
			"Do not click while movement input is held", false));
	public final BooleanSetting inventoryOnly = add(new BooleanSetting("Inventory only",
			"Only act while your inventory screen is open", false));
	public final BooleanSetting closeInventory = add(new BooleanSetting("Close inventory",
			"Close the screen after a successful manual swap", false));

	public final ModeSetting rocketAfterSwap = add(new ModeSetting("Rocket after swap",
			"Fire a firework once the wings are confirmed on", "Off", "Off", "Held only", "Hotbar"));
	public final BooleanSetting warnNoSpare = add(new BooleanSetting("Warn no spare",
			"Say so before the wings you are wearing reach zero", true));

	private int delayTicks;
	private boolean keyWasDown;
	/** A manual press waiting for a tick it is allowed to act on. */
	private boolean pendingManual;
	/** Set when the player put wings on by hand, so Ground restore knows there is work to undo. */
	private boolean restoreChestOnLanding;
	/** Consecutive grounded ticks, for Ground restore. */
	private int landedTicks;
	private int sinceWarning = WARN_INTERVAL;
	/** True on the tick a swap completed, so the rocket only ever follows a real one. */
	private boolean swappedToElytra;

	public ElytraSwap() {
		super("ElytraSwap", "Swaps chest armour and elytra, and replaces worn-out wings",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		delayTicks = 0;
		keyWasDown = false;
		pendingManual = false;
		landedTicks = 0;
		swappedToElytra = false;
		sinceWarning = WARN_INTERVAL;
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		pendingManual = false;
		restoreChestOnLanding = false;
		delayTicks = 0;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	/**
	 * Whether AutoArmor must keep its hands off the chest slot.
	 *
	 * <p>Four cases, and they are the same case seen from different angles: we are gliding, we are
	 * mid-swap, a press is queued and about to be, or a replacement is due and merely waiting out a
	 * delay. In every one of them a chestplate arriving in that slot is a swap the player did not
	 * ask for, landing on top of one they did — the last case most of all, since the whole reason
	 * the wings are being replaced is that they are about to fail.
	 */
	public boolean guardsChestSlot() {
		LocalPlayer player = mc().player;
		if (!isEnabled() || player == null) {
			return false;
		}
		return player.isFallFlying() || pendingManual || InventoryActionCoordinator.owns(this)
				|| replacementDue(player);
	}

	/** Worn wings under the threshold with something in the bag to put on instead. */
	private boolean replacementDue(LocalPlayer player) {
		if (!autoReplace.get() || player.containerMenu != player.inventoryMenu) {
			return false;
		}
		ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
		if (!worn.is(Items.ELYTRA) || !worn.isDamageableItem()
				|| worn.getMaxDamage() - worn.getDamageValue() > replaceThreshold.getInt()) {
			return false;
		}
		return findSpareElytra(player) >= 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			onDisable();
			return;
		}
		if (sinceWarning < WARN_INTERVAL) {
			sinceWarning++;
		}
		trackLanding(player);
		readKey();

		if (delayTicks > 0) {
			delayTicks--;
			return;
		}
		if (!allowed(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}

		swappedToElytra = false;
		boolean acted = replaceWornElytra(player) || manualSwap(player) || restoreOnGround(player);
		InventoryActionCoordinator.release(this);
		if (!acted) {
			return;
		}
		delayTicks = delay.getInt();
		if (swappedToElytra) {
			fireRocket(player);
		}
	}

	/** Edge-triggered, and only while no screen is eating the keyboard. */
	private void readKey() {
		boolean down = swapKey.isBound() && mc().gui.screen() == null && mc().getWindow() != null
				&& InputConstants.isKeyDown(mc().getWindow(), swapKey.get());
		if (down && !keyWasDown) {
			pendingManual = true;
		}
		keyWasDown = down;
	}

	private void trackLanding(LocalPlayer player) {
		if (player.onGround() && !player.isFallFlying()) {
			landedTicks = Math.min(landedTicks + 1, LANDED_TICKS);
		} else {
			landedTicks = 0;
		}
	}

	private boolean allowed(LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			return false; // a foreign menu means these slot indices mean something else entirely
		}
		if (inventoryOnly.get() && mc().gui.screen() == null) {
			return false;
		}
		return !pauseWhileMoving.get() || !MoveUtil.hasInput(player);
	}

	/**
	 * The safety half: worn wings below the threshold, swapped for the best spare in the bag.
	 *
	 * <p>Measured in durability points rather than percent because that is what the number on the
	 * tooltip says, and because the point of the setting is "how many more blocks do I get" — which
	 * is an absolute, not a fraction of a maximum the player never sees.
	 */
	private boolean replaceWornElytra(LocalPlayer player) {
		if (!autoReplace.get()) {
			return false;
		}
		ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
		if (!worn.is(Items.ELYTRA) || !worn.isDamageableItem()) {
			return false;
		}
		if (worn.getMaxDamage() - worn.getDamageValue() > replaceThreshold.getInt()) {
			return false;
		}
		int spare = findSpareElytra(player);
		if (spare < 0) {
			warnNoSpare(worn);
			return false;
		}
		return equip(player, spare);
	}

	/** The manual half. A queued press survives a paused tick rather than being dropped. */
	private boolean manualSwap(LocalPlayer player) {
		if (!pendingManual) {
			return false;
		}
		ItemStack worn = player.getItemBySlot(EquipmentSlot.CHEST);
		boolean wearingElytra = worn.is(Items.ELYTRA);
		boolean wantElytra = switch (manualMode.get()) {
			case "Elytra only" -> true;
			case "Chestplate only" -> false;
			default -> !wearingElytra;
		};
		pendingManual = false;

		if (wantElytra == wearingElytra) {
			return false; // already in the state that was asked for
		}
		int source = wantElytra ? findSpareElytra(player) : findChestplate(player);
		if (source < 0) {
			if (wantElytra) {
				warnNoSpare(worn);
			}
			return false;
		}
		if (!wantElytra && player.isFallFlying()) {
			return false; // never, under any mode
		}
		if (!equip(player, source)) {
			return false;
		}
		restoreChestOnLanding = wantElytra && groundRestore.get() && !wearingElytra;
		if (closeInventory.get() && mc().gui.screen() != null) {
			mc().gui.setScreen(null);
		}
		return true;
	}

	/**
	 * Ground restore: chest armour back on once the flight is definitively over.
	 *
	 * <p>Gated on several consecutive grounded ticks rather than one, because the tick you touch
	 * down on is not the tick you have landed — a bounce, a slab, a boat all produce a grounded
	 * frame in the middle of a flight.
	 */
	private boolean restoreOnGround(LocalPlayer player) {
		if (!restoreChestOnLanding || !groundRestore.get() || landedTicks < LANDED_TICKS) {
			return false;
		}
		if (!player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
			restoreChestOnLanding = false;
			return false;
		}
		int chestplate = findChestplate(player);
		if (chestplate < 0) {
			return false;
		}
		if (!equip(player, chestplate)) {
			return false;
		}
		restoreChestOnLanding = false;
		return true;
	}

	/** One swap into the chest slot, under the lease, leaving whatever came off where it came from. */
	private boolean equip(LocalPlayer player, int menuSlot) {
		if (!InventoryActionCoordinator.acquire(this,
				InventoryActionCoordinator.PRIORITY_ELYTRA_SAFETY)) {
			return false;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		boolean done = InventoryActionCoordinator.pickupMove(this, menu, menuSlot, CHEST_MENU_SLOT);
		swappedToElytra = done && player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
		return done;
	}

	/**
	 * The best spare elytra in the bag, or -1.
	 *
	 * <p>Slots 9..44 only — the same window AutoArmor uses, and for the same reason: reading the
	 * armour slots back would find the wings already on your back and swap them with themselves.
	 */
	private int findSpareElytra(LocalPlayer player) {
		AbstractContainerMenu menu = player.inventoryMenu;
		int best = -1;
		float bestScore = Float.NEGATIVE_INFINITY;
		int last = Math.min(44, menu.slots.size() - 1);
		for (int index = 9; index <= last; index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (!stack.is(Items.ELYTRA)) {
				continue;
			}
			float score = spareScore(stack);
			if (sparePreference.is("First found")) {
				return index;
			}
			if (score > bestScore) {
				bestScore = score;
				best = index;
			}
		}
		return best;
	}

	/**
	 * How good a spare is under the chosen preference.
	 *
	 * <p>Mending first is a large offset rather than a tiebreak: the player who picked it means
	 * "the pair that repairs itself, even if it is the more worn of the two", which is the opposite
	 * of what a small bonus would produce.
	 */
	private float spareScore(ItemStack stack) {
		float durability = EquipmentScorer.durabilityPercent(stack);
		if (sparePreference.is("Mending first")
				&& EquipmentScorer.has(stack, net.minecraft.world.item.enchantment.Enchantments.MENDING)) {
			return durability + 1000.0f;
		}
		return durability;
	}

	/**
	 * The chestplate to wear, or -1.
	 *
	 * <p>Best armor defers to {@link EquipmentScorer} so this and AutoArmor cannot disagree about
	 * which chestplate is the good one — two modules with two answers would swap past each other
	 * for ever.
	 *
	 * <p>Custom takes the best <em>listed</em> piece rather than the first: the list is a set of
	 * ids, so it has no order of its own to honour, and quietly scoring within it beats pretending
	 * an alphabetical accident was the player's preference.
	 */
	private int findChestplate(LocalPlayer player) {
		AbstractContainerMenu menu = player.inventoryMenu;
		int last = Math.min(44, menu.slots.size() - 1);
		int best = -1;
		float bestScore = Float.NEGATIVE_INFINITY;
		for (int index = 9; index <= last; index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (!fitsChest(stack)) {
				continue;
			}
			if (chestPreference.is("Custom") && !customChestplates.contains(stack.getItem())) {
				continue;
			}
			if (chestPreference.is("First found")) {
				return index;
			}
			float score = EquipmentScorer.score(stack, EquipmentSlot.CHEST,
					EquipmentScorer.Preferences.DEFAULT);
			if (score > bestScore) {
				bestScore = score;
				best = index;
			}
		}
		return best;
	}

	/** Chest-equippable and not itself a pair of wings — asked of the item, not of its class. */
	private static boolean fitsChest(ItemStack stack) {
		if (stack.isEmpty() || stack.is(Items.ELYTRA)) {
			return false;
		}
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
	}

	/**
	 * Held only uses a rocket the player deliberately keeps in hand; Hotbar is allowed to reach for
	 * one, through the shared hotbar helper that puts the selection back.
	 */
	private void fireRocket(LocalPlayer player) {
		if (rocketAfterSwap.is("Off") || mc().gameMode == null
				|| !player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
			return;
		}
		if (player.getMainHandItem().is(Items.FIREWORK_ROCKET)) {
			InteractUtil.useItem();
			return;
		}
		if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
			mc().gameMode.useItem(player, InteractionHand.OFF_HAND);
			player.swing(InteractionHand.OFF_HAND);
			return;
		}
		if (!rocketAfterSwap.is("Hotbar")) {
			return;
		}
		int slot = InteractUtil.findHotbarItem(Items.FIREWORK_ROCKET);
		if (slot >= 0) {
			InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
		}
	}

	/** Throttled, because the state that produces it lasts for as long as the flight does. */
	private void warnNoSpare(ItemStack worn) {
		if (!warnNoSpare.get() || sinceWarning < WARN_INTERVAL) {
			return;
		}
		sinceWarning = 0;
		int left = worn.isDamageableItem() ? worn.getMaxDamage() - worn.getDamageValue() : 0;
		UnluckyClient.INSTANCE.notifications.add("ElytraSwap",
				"No spare elytra — " + left + " durability left", ItemUtil.icon(Items.ELYTRA));
	}
}
