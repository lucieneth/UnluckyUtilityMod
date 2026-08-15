package unlucky.utility.client.util;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Who gets to decide what is in your offhand.
 *
 * <p>The offhand is one slot with several permanent claimants, and unlike a hotbar switch the
 * claim lasts: a totem sits there for a whole fight, not for a tick. Left to themselves,
 * AutoTotem and AutoReplenish will each notice the offhand is "wrong" every tick and each
 * swap it back, which is not two modules disagreeing once — it is a swap every tick, for ever,
 * and it costs you the totem at the moment you need it. So the offhand is not something a
 * module writes; it is something a module <em>asks for</em>, and the highest bidder wins.
 *
 * <p><b>Ask every tick, for as long as you want it.</b> Requests do not persist: a module that
 * stops asking is understood to be done, and whatever it displaced goes back. That makes the
 * common case — "hold a totem while my health is low" — a single unconditional call inside an
 * {@code if}, with no release path to forget.
 *
 * <p><b>A foreign container blocks everything.</b> The swap is a click on the player's own
 * inventory menu, and while a chest is open that menu is not the one the server has us in;
 * clicking it anyway is the desync AutoXPRepair's {@code restore()} already guards against.
 * {@link #isBlocked()} says so out loud, so a caller that genuinely cannot wait — AutoTotem
 * with two hearts left — can close the container itself rather than silently doing nothing.
 */
public final class OffhandManager {
	/** AutoTotem. Nothing outbids staying alive. */
	public static final int PRIORITY_TOTEM = 100;
	/** Combat consumables: gapples, a shield going up. */
	public static final int PRIORITY_COMBAT = 80;
	/** AutoReplenish topping the slot back up. */
	public static final int PRIORITY_REPLENISH = 50;

	/** The token the manager clicks under, so the coordinator's read-out names it. */
	private static final Object LEASE = new OffhandLease();

	private static final class OffhandLease {
	}

	private record Request(Object holder, int priority, Predicate<ItemStack> wanted, String label,
			boolean restore, java.util.function.IntPredicate slotAllowed) {
	}

	/** Best request seen this tick, cleared by {@link #onTickEnd}. */
	private static Request best;

	/** Whose item is in the offhand right now, or null if we have not put anything there. */
	private static Object holder;
	private static String heldLabel = "";
	/** Menu slot the displaced item now sits in — swapping it again undoes us exactly. */
	private static int restoreSlot = -1;
	private static boolean restoreWanted;

	private OffhandManager() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/**
	 * Asks for an item matching {@code wanted} to be in the offhand, this tick and for as long
	 * as the call keeps being made.
	 *
	 * @param holder   the requesting module, for ownership and the read-out
	 * @param priority one of the constants above; ties go to whoever asked first this tick
	 * @param wanted   what counts as the right item — a predicate rather than an {@code Item}
	 *                 so a caller can insist on components too (this potion, not any potion)
	 * @param label    human-readable name of what is wanted, for the read-out
	 * @param restore  whether to put the displaced item back once the request stops
	 */
	public static void request(Object holder, int priority, Predicate<ItemStack> wanted, String label,
			boolean restore) {
		request(holder, priority, wanted, label, restore, slot -> true);
	}

	/**
	 * As above, but the caller also says which menu slots it is willing to take from.
	 *
	 * <p>For Offhand's separate "search hotbar" and "search inventory" toggles. Expressed as a
	 * slot filter here rather than as a search the caller does itself, because the search has to
	 * stay in one place: a caller that found its own source would be reading the menu a tick
	 * before the swap happens, and the slot it found can be gone by then.
	 */
	public static void request(Object holder, int priority, Predicate<ItemStack> wanted, String label,
			boolean restore, java.util.function.IntPredicate slotAllowed) {
		if (holder == null || wanted == null) {
			return;
		}
		if (best == null || priority > best.priority()) {
			best = new Request(holder, priority, wanted, label, restore,
					slotAllowed == null ? slot -> true : slotAllowed);
		}
	}

	/** Whether anything currently holds the offhand. */
	public static boolean isLocked() {
		return holder != null;
	}

	/** Who holds it, or null. */
	public static Object owner() {
		return holder;
	}

	/** What the holder asked for, for HUD and read-out use. */
	public static String heldLabel() {
		return heldLabel;
	}

	/**
	 * Whether a swap is impossible right now because a container other than the player's own
	 * inventory is open. Callers that cannot wait should close it and try again next tick.
	 */
	public static boolean isBlocked() {
		LocalPlayer player = mc().player;
		return player != null && player.containerMenu != player.inventoryMenu;
	}

	/**
	 * End of client tick: settle the winning request, or undo ourselves if nobody asked.
	 *
	 * <p>Runs after every module has ticked, which is what makes "highest priority wins"
	 * true regardless of registration order — the same reason {@link RotationManager} resolves
	 * at end of tick rather than on the first call.
	 */
	public static void onTickEnd() {
		Request request = best;
		best = null;

		LocalPlayer player = mc().player;
		if (player == null) {
			reset();
			return;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		if (player.containerMenu != menu) {
			return; // blocked; keep our bookkeeping for when the container closes
		}

		if (request == null) {
			if (holder != null) {
				if (restoreWanted && restoreSlot >= 0) {
					swap(menu, restoreSlot, InventoryActionCoordinator.PRIORITY_REPLENISH);
				}
				clearClaim();
			}
			return;
		}

		if (request.wanted().test(player.getOffhandItem())) {
			// Already right — including the case where the player put it there themselves,
			// which is worth adopting rather than shuffling around to reproduce.
			holder = request.holder();
			heldLabel = request.label();
			return;
		}

		int source = findSource(menu, request.wanted(), request.slotAllowed());
		if (source < 0) {
			return; // nothing to offer; leave the offhand alone rather than emptying it
		}
		if (!swap(menu, source, leasePriority(request.priority()))) {
			return; // something more urgent is mid-click; try again next tick
		}
		holder = request.holder();
		heldLabel = request.label();
		// The displaced stack is now in the slot we took from, so swapping the same slot
		// again is an exact undo — no second search, and no guessing where it went.
		//
		// Only the *first* displacement is remembered, and that is deliberate. Hand the
		// offhand from AutoReplenish to AutoTotem mid-fight and there are two undos on the
		// table; unwinding the later one hands you back what AutoReplenish put there, while
		// unwinding the first hands you back the shield you were carrying before any of this
		// started. The second is what "put it back" means to the person holding the mouse.
		if (restoreSlot < 0) {
			restoreSlot = source;
			restoreWanted = request.restore();
		}
	}

	/** Drops every claim without clicking — Panic, and any world where our slots are void. */
	public static void reset() {
		best = null;
		clearClaim();
	}

	private static void clearClaim() {
		holder = null;
		heldLabel = "";
		restoreSlot = -1;
		restoreWanted = false;
	}

	/**
	 * One swap, under a lease held for exactly as long as the click takes. The offhand claim
	 * outlives the tick; the right to click does not, and holding the coordinator between
	 * ticks would lock every other module out of the inventory for the length of a fight.
	 */
	private static boolean swap(AbstractContainerMenu menu, int menuSlot, int priority) {
		if (!InventoryActionCoordinator.acquire(LEASE, priority)) {
			return false;
		}
		boolean done = InventoryActionCoordinator.swapToOffhand(LEASE, menu, menuSlot);
		InventoryActionCoordinator.release(LEASE);
		return done;
	}

	/**
	 * First matching stack in the main inventory or hotbar of the player's own menu.
	 *
	 * <p>Slots 9..44 exactly: 0 is the crafting result, 1-4 the crafting grid, 5-8 the armor
	 * and 45 the offhand itself. Pulling a match out of any of those would either fail or take
	 * the very item it is meant to be replacing.
	 */
	private static int findSource(AbstractContainerMenu menu, Predicate<ItemStack> wanted,
			java.util.function.IntPredicate slotAllowed) {
		int last = Math.min(44, menu.slots.size() - 1);
		for (int slot = 9; slot <= last; slot++) {
			if (!slotAllowed.test(slot)) {
				continue;
			}
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && wanted.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	/** Whether a player-menu slot index is part of the hotbar rather than the main grid. */
	public static boolean isHotbarSlot(int menuSlot) {
		return menuSlot >= 36 && menuSlot <= 44;
	}

	/** Offhand priorities mapped onto the click coordinator's scale. */
	private static int leasePriority(int offhandPriority) {
		if (offhandPriority >= PRIORITY_TOTEM) {
			return InventoryActionCoordinator.PRIORITY_TOTEM;
		}
		if (offhandPriority >= PRIORITY_COMBAT) {
			return InventoryActionCoordinator.PRIORITY_COMBAT;
		}
		return InventoryActionCoordinator.PRIORITY_REPLENISH;
	}

	/** Inventory-menu slot index of an {@link Inventory} index, as the menu lays it out. */
	public static int menuSlot(int inventoryIndex) {
		return inventoryIndex < Inventory.SELECTION_SIZE ? 36 + inventoryIndex : inventoryIndex;
	}

	/** One line of internal state, for the debug read-out. */
	public static String debug() {
		return String.format("holder=%s want=%s restoreSlot=%d restore=%b blocked=%b",
				holder == null ? "none" : holder.getClass().getSimpleName(),
				heldLabel.isEmpty() ? "-" : heldLabel, restoreSlot, restoreWanted, isBlocked());
	}
}
