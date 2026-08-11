package unlucky.utility.client.module.modules.player;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.util.ContainerUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;

/** Take-all / store-all buttons on ordinary storage screens. */
public class QuickStash extends Module {
	public QuickStash() {
		super("QuickStash", "Adds take-all and store-all buttons to chests, barrels, shulkers, "
				+ "hoppers, dispensers/droppers and chested mounts",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
	}

	/**
	 * Whether {@code menu} is a kind {@code AbstractContainerScreenMixin} adds buttons to, and
	 * actually has a container-side slot right now — a horse without a chest opens the same
	 * {@link HorseInventoryMenu} class as one with a chest, just with none of the cargo slots.
	 */
	public static boolean supported(AbstractContainerMenu menu) {
		if (!(menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu || menu instanceof DispenserMenu
				|| menu instanceof HopperMenu || menu instanceof HorseInventoryMenu)) {
			return false;
		}
		for (int i = 0; i < menu.slots.size(); i++) {
			if (ContainerUtil.isStorageSlot(menu.getSlot(i))) {
				return true;
			}
		}
		return false;
	}

	/** Moves every item out of the container and into the player's inventory. */
	public void stealAll(AbstractContainerMenu menu) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_MANUAL)) {
			return;
		}
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (ContainerUtil.isStorageSlot(slot) && !slot.getItem().isEmpty()) {
				InventoryActionCoordinator.quickMove(this, menu, i);
			}
		}
		InventoryActionCoordinator.release(this);
	}

	/**
	 * Moves every item out of the player's inventory and into the container.
	 *
	 * <p>No explicit backpack-then-hotbar ordering is needed: every menu this module supports
	 * appends the player's slots via vanilla's {@code addStandardInventorySlots}, which always
	 * lays the 27-slot main inventory out before the 9-slot hotbar, so an ascending sweep of the
	 * menu already visits backpack slots first and the hotbar last.
	 */
	public void fillAll(AbstractContainerMenu menu) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_MANUAL)) {
			return;
		}
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container instanceof Inventory && !slot.getItem().isEmpty()) {
				InventoryActionCoordinator.quickMove(this, menu, i);
			}
		}
		InventoryActionCoordinator.release(this);
	}
}
