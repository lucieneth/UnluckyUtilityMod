package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ArmorSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Driving a container menu from code: click slots, move exact counts, close without
 * taking the player's screen with it.
 *
 * <p>Grown out of AutoBrew, which solved all of this against a real server first. It
 * lives here because the Printer's shulker restock needs the same three things, and a
 * second implementation of "move exactly n items" is the kind of duplicate that drifts
 * until only one of them is right.
 */
public final class ContainerUtil {
	/** True for the instant a module is closing a container — see {@link #closeMenu}. */
	private static boolean closing;

	private ContainerUtil() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** One click on a menu slot, as the server sees it. */
	public static void click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
		mc().gameMode.handleContainerInput(menu.containerId, slot, button, input, mc().player);
	}

	/**
	 * Closes the open container without touching the player's screen.
	 *
	 * <p>There is no vanilla call for "close the menu but leave my GUI alone" —
	 * {@code LocalPlayer.closeContainer()} always ends in {@code gui.setScreen(null)},
	 * and the plain {@code Player.closeContainer()} underneath it is protected. So the
	 * call is flagged and {@code GuiMixin} drops that one screen clear; without it, a
	 * module that closes a container every few ticks slams your chat or pause menu shut
	 * a tick after you open it.
	 */
	public static void closeMenu() {
		closing = true;
		try {
			mc().player.closeContainer();
		} finally {
			closing = false;
		}
	}

	/** Whether a {@code setScreen(null)} happening right now belongs to {@link #closeMenu}. */
	public static boolean isClosing() {
		return closing;
	}

	/**
	 * Moves exactly {@code n} items out of {@code sourceSlot} into the player's side.
	 *
	 * <p>QUICK_MOVE can only move a whole stack, so anything short of one is the
	 * pick-up-and-right-click dance: take the stack onto the cursor, right-click the
	 * destination {@code n} times (one item each), put the remainder back. Exact counts
	 * are what let a caller take the 37 blocks it still needs instead of a full stack it
	 * has no room for.
	 *
	 * @return false when there is nowhere to put them
	 */
	public static boolean takeExactly(AbstractContainerMenu menu, int sourceSlot, int n) {
		if (n <= 0) {
			return false;
		}
		ItemStack source = menu.getSlot(sourceSlot).getItem();
		if (source.getCount() <= n) {
			click(menu, sourceSlot, 0, ContainerInput.QUICK_MOVE); // the whole stack is what we wanted anyway
			return true;
		}
		int target = freeSlot(menu, source);
		if (target < 0) {
			return false;
		}
		click(menu, sourceSlot, 0, ContainerInput.PICKUP); // cursor takes the stack
		for (int i = 0; i < n; i++) {
			click(menu, target, 1, ContainerInput.PICKUP); // right-click drops exactly one
		}
		click(menu, sourceSlot, 0, ContainerInput.PICKUP); // remainder goes back
		return true;
	}

	/**
	 * A player-side slot that can accept {@code like}: a partial stack of the same item
	 * first, an empty one only if there is none. Menu-relative, so it is the index to click.
	 *
	 * <p>The order matters more than it looks. Preferring an empty slot means every
	 * partial take starts a fresh stack, so repeated small takes shred the inventory into
	 * dozens of near-empty stacks — 27 slots holding 4 items each, in the run that found
	 * this — until there is no room left to take anything and the caller loops for ever.
	 */
	public static int freeSlot(AbstractContainerMenu menu, ItemStack like) {
		int empty = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (!(slot.container instanceof Inventory)) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (ItemStack.isSameItemSameComponents(stack, like)
					&& stack.getCount() < stack.getMaxStackSize()) {
				return i; // top up what is already there
			}
			if (empty < 0 && stack.isEmpty()) {
				empty = i;
			}
		}
		return empty;
	}

	/**
	 * Whether {@code slot} belongs to the opened container itself, as opposed to the player's
	 * own inventory or a mount's fixed saddle/armor slot.
	 *
	 * <p>A horse's saddle and armor slots back onto a separate {@link net.minecraft.world.Container}
	 * too (see {@code AbstractHorse.createEquipmentSlotContainer}), so the {@code Inventory}
	 * check alone would not exclude them the way it does for the player's own slots. Both use
	 * vanilla's {@link ArmorSlot}, which ordinary chest/shulker/dispenser/hopper/cargo slots
	 * never do, so excluding it here is what keeps a "take everything" sweep from also
	 * unequipping the mount it is riding.
	 */
	public static boolean isStorageSlot(Slot slot) {
		return !(slot.container instanceof Inventory) && !(slot instanceof ArmorSlot);
	}
}
