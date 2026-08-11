package unlucky.utility.client.module.modules.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InteractUtil;
import unlucky.utility.client.util.RotationManager;

/**
 * Repairs mending gear with XP bottles, hands-free:
 * <ul>
 *   <li>Bottles go to the <b>offhand</b> and are thrown from there, with a
 *       server-side-only look-down (RotationManager spoof) so every orb lands
 *       at your feet.</li>
 *   <li>The damaged item being repaired is held in the <b>main hand</b> —
 *       hotbar items are simply selected, items deeper in the inventory are
 *       parked in hotbar slot 0 while they mend. Worn armor repairs in place
 *       (orbs pick a random damaged equipped mending item) and is never
 *       touched.</li>
 *   <li>When everything is full — or the bottles run out — every swap is
 *       undone: parked item back to its slot, bottles/original offhand back,
 *       previous hotbar selection restored. Disabling mid-run restores too.</li>
 * </ul>
 * One inventory action per tick so the server sees a sane click sequence;
 * pauses while another container is open (clicks would target the wrong menu).
 */
public class AutoXPRepair extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Bottles per second", 4, 1, 20, 1));
	public final NumberSetting startBelow = add(new NumberSetting("Start below durability %",
			"Only begin repairing items below this remaining durability", 85, 1, 100, 1));
	public final NumberSetting stopAt = add(new NumberSetting("Stop at durability %",
			"Consider an item repaired at this remaining durability", 98, 1, 100, 1));
	public final ModeSetting priority = add(new ModeSetting("Priority", "Which damaged item to hold first",
			"Lowest durability", "Lowest durability", "Armor first", "Held first"));
	public final NumberSetting bottleReserve = add(new NumberSetting("Minimum bottle reserve",
			"XP bottles left untouched in inventory", 0, 0, 256, 1));
	public final BooleanSetting disableWhenDone = add(new BooleanSetting("Disable when done", "Turn the module off once all mending gear is repaired", false));
	public final BooleanSetting disableWhenNoTarget = add(new BooleanSetting("Disable when no repair target",
			"Turn off when no eligible damaged Mending item remains", false));

	private int ticks;
	/** Inventory-menu slot the offhand bottles came from; restore target. -1 = untouched. */
	private int bottleSource = -1;
	/** Inventory-menu slot of the item currently parked in hotbar slot 0. -1 = none. */
	private int parkedFrom = -1;
	/** Hotbar selection to restore when done. -1 = untouched. */
	private int prevSelected = -1;

	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public AutoXPRepair() {
		super("AutoXPRepair", "Repairs mending gear with XP bottles", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		ticks = 0;
	}

	@Override
	protected void onDisable() {
		restore();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gameMode == null) {
			return;
		}
		// clicks below go to the inventory menu — another open container would desync
		if (mc().player.containerMenu != mc().player.inventoryMenu) {
			return;
		}
		if (AutoEat.pauses(pauseOnEat)) {
			return;
		}
		Inventory inv = mc().player.getInventory();

		// parked item finished mending (or vanished)? put it back first
		if (parkedFrom >= 0 && !needsRepair(inv.getItem(0))) {
			InteractUtil.swapWithHotbar(parkedFrom, 0);
			parkedFrom = -1;
			return; // settle
		}

		// all repaired? undo the arrangement and idle (or switch off if asked)
		if (!anythingDamaged(inv)) {
			restore();
			if (disableWhenDone.get() || disableWhenNoTarget.get()) {
				setEnabled(false); // onDisable's restore() is now a no-op
				UnluckyClient.INSTANCE.notifications.add("AutoXPRepair", "All gear repaired",
						new ItemStack(Items.EXPERIENCE_BOTTLE));
			}
			return;
		}

		// bottles live in the offhand so the main hand can hold the repair target
		ItemStack offhand = mc().player.getItemBySlot(EquipmentSlot.OFFHAND);
		if (!offhand.is(Items.EXPERIENCE_BOTTLE)) {
			int source = findBottles(inv);
			if (source < 0) {
				restore(); // out of bottles — leave things as we found them
				return;
			}
			InteractUtil.swapWithOffhand(menuSlot(source));
			bottleSource = menuSlot(source);
			return; // settle
		}

		// keep a damaged item in the main hand while any exists outside armor
		if (!needsRepair(inv.getItem(inv.getSelectedSlot()))) {
			int target = findTarget(inv);
			if (target >= 0) {
				if (prevSelected < 0) {
					prevSelected = inv.getSelectedSlot();
				}
				if (target < 9) {
					inv.setSelectedSlot(target); // already in the hotbar — no moving
				} else {
					InteractUtil.swapWithHotbar(menuSlot(target), 0);
					parkedFrom = menuSlot(target);
					inv.setSelectedSlot(0);
				}
				return; // settle
			}
			// only worn armor is damaged — it mends in place, keep throwing
		}

		if (--ticks > 0) {
			return;
		}
		ticks = Math.max(1, (int) Math.round(20.0 / speed.get()));
		// look straight down — server-side only (RotationManager spoof, like
		// Aura): the bottle smashes at your feet so every orb lands on you.
		// First person sees nothing; observers see the Aura-style head dip.
		RotationManager.rotate(mc().player.getYRot(), 90.0f);
		InteractUtil.useOffhandItem();
	}

	/** Undo every swap we made: parked item, bottles/offhand, hotbar selection. */
	private void restore() {
		if (mc().player == null || mc().player.containerMenu != mc().player.inventoryMenu) {
			bottleSource = parkedFrom = prevSelected = -1;
			return;
		}
		if (parkedFrom >= 0) {
			InteractUtil.swapWithHotbar(parkedFrom, 0);
			parkedFrom = -1;
		}
		if (bottleSource >= 0) {
			InteractUtil.swapWithOffhand(bottleSource);
			bottleSource = -1;
		}
		if (prevSelected >= 0) {
			mc().player.getInventory().setSelectedSlot(prevSelected);
			prevSelected = -1;
		}
	}

	/** Any damaged mending item we can still fix: worn armor or inventory. */
	private boolean anythingDamaged(Inventory inv) {
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND }) {
			if (needsRepair(mc().player.getItemBySlot(slot))) {
				return true;
			}
		}
		return findTarget(inv) >= 0;
	}

	/** First damaged mending item in the hotbar, then main inventory. Armor slots excluded. */
	private int findTarget(Inventory inv) {
		int best = -1;
		double bestRemaining = Double.MAX_VALUE;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inv.getItem(i);
			if (!needsRepair(stack)) continue;
			if (priority.is("Held first") && i == inv.getSelectedSlot()) return i;
			double remaining = durabilityPercent(stack);
			if (best < 0 || remaining < bestRemaining) { best = i; bestRemaining = remaining; }
		}
		return best;
	}

	private int findBottles(Inventory inv) {
		int total = 0;
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(Items.EXPERIENCE_BOTTLE)) total += inv.getItem(i).getCount();
		if (total <= bottleReserve.getInt()) return -1;
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i).is(Items.EXPERIENCE_BOTTLE) && inv.getItem(i).getCount() > bottleReserve.getInt()) {
				return i;
			}
		}
		return -1;
	}

	/** Inventory index (0-8 hotbar, 9-35 main) -> player inventory-menu slot. */
	private static int menuSlot(int invIndex) {
		return invIndex < 9 ? 36 + invIndex : invIndex;
	}

	private boolean needsRepair(ItemStack stack) {
		if (stack.isEmpty() || !stack.isDamaged()) {
			return false;
		}
		ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		double remaining = durabilityPercent(stack);
		return remaining <= startBelow.get() && remaining < stopAt.get()
				&& enchantments.keySet().stream().anyMatch(holder -> holder.is(Enchantments.MENDING));
	}

	private static double durabilityPercent(ItemStack stack) {
		return !stack.isDamageableItem() || stack.getMaxDamage() <= 0 ? 100.0
				: (stack.getMaxDamage() - stack.getDamageValue()) * 100.0 / stack.getMaxDamage();
	}
}
