package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.OffhandManager;

/**
 * Tops a hotbar stack back up from the rest of your inventory before it runs out.
 *
 * <p><b>It swaps, it does not merge.</b> Merging 3 blocks into a stack of 61 is the tidier
 * outcome and takes three clicks with a loaded cursor in between; swapping the full stack in
 * and the remnant out takes one, is atomic on the server, and cannot leave an item on the
 * cursor if the tick it happens in is also the tick a chest closes. The remnant is still in
 * your inventory and still yours. Given how often this fires mid-fight, one click that cannot
 * go wrong beats three that can.
 *
 * <p>Two safeguards on top of that. A swap only happens when the source stack is genuinely
 * <em>larger</em> than what is in hand, so the module can never make a slot worse. And item
 * identity is item-plus-components by default, so a stack of Splash Potions of Healing is
 * never topped up from the Potions of Slowness next to them.
 *
 * <p>The offhand goes through {@link OffhandManager} rather than being clicked directly,
 * which is what stops this and AutoTotem fighting over one slot. AutoTotem simply outranks it.
 */
public class AutoReplenish extends Module {
	/** Hotbar plus offhand — the slots this module watches. */
	private static final int WATCHED = Inventory.SELECTION_SIZE + 1;
	/** Index of the offhand within {@link #lastSeen}. */
	private static final int OFFHAND = Inventory.SELECTION_SIZE;

	public final ModeSetting thresholdMode = add(new ModeSetting("Threshold mode",
			"Count refills below a fixed number. Percent scales with the stack size, so a "
					+ "16-stack of eggs is not held to the same bar as 64 cobblestone.",
			"Count", "Count", "Percent"));
	public final NumberSetting minCount = add(new NumberSetting("Min count",
			"Refill once the stack falls to this", 8, 1, 63, 1), () -> thresholdMode.is("Count"));
	public final NumberSetting minPercent = add(new NumberSetting("Min percent",
			"Refill once the stack falls to this share of a full one", 25, 1, 99, 1),
			() -> thresholdMode.is("Percent"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between swaps", 1, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra ticks, up to this many, added at random", 0, 0, 20, 1));
	public final BooleanSetting offhand = add(new BooleanSetting("Offhand",
			"Watch the offhand too. AutoTotem still outranks this for that slot.", true));
	public final BooleanSetting unstackables = add(new BooleanSetting("Unstackable items",
			"Replace a one-of-a-kind item — a bucket, a potion, a shulker — once it is used up",
			true));
	public final BooleanSetting matchComponents = add(new BooleanSetting("Match components",
			"Require an exact match, so potion type and custom data survive a refill", true));
	public final BooleanSetting sameEnchantments = add(new BooleanSetting("Same enchantments",
			"When components are not matched, at least require the same enchantments", true),
			() -> !matchComponents.get());
	public final BooleanSetting searchHotbar = add(new BooleanSetting("Search hotbar",
			"Allow refills to come from another hotbar slot", false));
	public final ModeSetting sourcePriority = add(new ModeSetting("Source priority",
			"Which candidate stack to take when several match", "Largest stack",
			"Largest stack", "Nearest slot", "First match"));
	public final ItemListSetting excluded = add(new ItemListSetting("Excluded items",
			"Never refill these", item -> true));
	public final BooleanSetting pauseInContainer = add(new BooleanSetting("Pause in container",
			"Stand down while a chest or other container is open — the slot numbers this "
					+ "module clicks belong to the player's own menu", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	/**
	 * The last non-empty stack seen in each watched slot.
	 *
	 * <p>The whole reason unstackable replacement can work: once a bucket has been emptied
	 * the slot no longer says what it used to hold, so the intent has to have been recorded
	 * before it was lost.
	 */
	private final ItemStack[] lastSeen = new ItemStack[WATCHED];

	public AutoReplenish() {
		super("AutoReplenish", "Refills a hotbar stack before it runs out", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
		clearMemory();
	}

	@Override
	protected void onEnable() {
		clearMemory();
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		clearMemory();
	}

	private void clearMemory() {
		java.util.Arrays.fill(lastSeen, ItemStack.EMPTY);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || AutoEat.pauses(pauseOnEat)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		if (pauseInContainer.get() && player.containerMenu != menu) {
			InventoryActionCoordinator.release(this);
			return;
		}

		remember(player);

		// The offhand is a request, not a click: its owner is decided by OffhandManager so
		// AutoTotem can take the slot back without this module ever knowing.
		if (offhand.get()) {
			ItemStack want = wantedFor(player.getOffhandItem(), lastSeen[OFFHAND]);
			if (!want.isEmpty()) {
				OffhandManager.request(this, OffhandManager.PRIORITY_REPLENISH,
						candidate -> matches(want, candidate) && candidate.getCount() > player.getOffhandItem().getCount(),
						want.getHoverName().getString(), false);
			}
		}

		int target = -1;
		ItemStack want = ItemStack.EMPTY;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack candidate = wantedFor(player.getInventory().getItem(slot), lastSeen[slot]);
			if (!candidate.isEmpty()) {
				target = slot;
				want = candidate;
				break;
			}
		}
		if (target < 0) {
			InventoryActionCoordinator.release(this);
			return;
		}

		int source = findSource(menu, want, player.getInventory().getItem(target).getCount(), target);
		if (source < 0) {
			InventoryActionCoordinator.release(this);
			return;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_REPLENISH)
				|| !InventoryActionCoordinator.canAct(this)) {
			return;
		}
		if (InventoryActionCoordinator.swapToHotbar(this, menu, source, target)) {
			InventoryActionCoordinator.delay(this, delay.getInt(), randomDelay.getInt());
		}
	}

	/**
	 * Records what each watched slot holds, so an emptied one still knows what it was.
	 *
	 * <p>Only copies when the item actually changed. The obvious version copies ten stacks a
	 * tick for ever, which is two hundred allocations a second to answer a question whose
	 * answer almost never changes.
	 */
	private void remember(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			record(slot, player.getInventory().getItem(slot));
		}
		record(OFFHAND, player.getOffhandItem());
	}

	private void record(int index, ItemStack stack) {
		if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(lastSeen[index], stack)) {
			lastSeen[index] = stack.copy();
		}
	}

	/**
	 * What this slot wants more of, or empty when it is fine.
	 *
	 * <p>Two shapes of "running out". A stackable is judged on what is still in it. An
	 * unstackable has no partial state at all — it is there or it is not — so the question is
	 * whether the slot has just become empty and what used to be in it.
	 */
	private ItemStack wantedFor(ItemStack current, ItemStack remembered) {
		if (current.isEmpty()) {
			if (!unstackables.get() || remembered.isEmpty() || remembered.getMaxStackSize() > 1
					|| excluded.contains(remembered.getItem())) {
				return ItemStack.EMPTY;
			}
			return remembered;
		}
		if (current.getMaxStackSize() <= 1 || excluded.contains(current.getItem())) {
			return ItemStack.EMPTY;
		}
		int floor = thresholdMode.is("Count")
				? minCount.getInt()
				: Math.max(1, current.getMaxStackSize() * minPercent.getInt() / 100);
		return current.getCount() <= floor && current.getCount() < current.getMaxStackSize()
				? current
				: ItemStack.EMPTY;
	}

	/**
	 * Menu slot of the stack to swap in, or -1.
	 *
	 * @param heldCount what the target slot has now — a candidate must beat it, or the swap
	 *                  would trade a big stack for a small one and then want to undo itself
	 * @param targetHotbar the hotbar slot being filled, excluded from its own search
	 */
	private int findSource(AbstractContainerMenu menu, ItemStack want, int heldCount, int targetHotbar) {
		int best = -1;
		int bestCount = heldCount;
		int last = Math.min(44, menu.slots.size() - 1);
		for (int slot = 9; slot <= last; slot++) {
			boolean inHotbar = slot >= 36;
			if (inHotbar && (!searchHotbar.get() || slot == 36 + targetHotbar)) {
				continue;
			}
			ItemStack candidate = menu.getSlot(slot).getItem();
			if (candidate.isEmpty() || !matches(want, candidate) || candidate.getCount() <= heldCount) {
				continue;
			}
			switch (sourcePriority.get()) {
				case "First match" -> {
					return slot;
				}
				case "Nearest slot" -> {
					// Nearest to the hotbar is the bottom of the main inventory, which is the
					// end of the range — so the last match wins rather than the first.
					best = slot;
				}
				default -> {
					if (candidate.getCount() > bestCount) {
						bestCount = candidate.getCount();
						best = slot;
					}
				}
			}
		}
		return best;
	}

	/** Whether {@code candidate} may stand in for {@code want}, at the configured strictness. */
	private boolean matches(ItemStack want, ItemStack candidate) {
		if (matchComponents.get()) {
			return ItemStack.isSameItemSameComponents(want, candidate);
		}
		if (!candidate.is(want.getItem())) {
			return false;
		}
		return !sameEnchantments.get() || candidate.getEnchantments().equals(want.getEnchantments());
	}
}
