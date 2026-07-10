package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Interaction helpers: hotbar item selection and block/item use. */
public final class InteractUtil {
	private InteractUtil() {
	}

	/** Hotbar slot holding {@code item}, or -1. */
	public static int findHotbarItem(Item item) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return -1;
		}
		Inventory inventory = mc.player.getInventory();
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (inventory.getItem(slot).is(item)) {
				return slot;
			}
		}
		return -1;
	}

	/** Right-clicks the block at {@code pos}. Returns true when the interaction was sent. */
	public static boolean useOnBlock(BlockPos pos, Direction side) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			return false;
		}
		RotationManager.lookAt(Vec3.atCenterOf(pos));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), side, pos, false);
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
		return true;
	}

	/** Swaps the item in a hotbar slot (0-8) with the offhand via a container click. */
	public static void swapHotbarToOffhand(int hotbarSlot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			return;
		}
		int menuSlot = 36 + hotbarSlot; // inventory menu: hotbar starts at 36
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, 40,
				net.minecraft.world.inventory.ContainerInput.SWAP, mc.player);
	}

	/** Left-click (start + stop) to break the block at a position in one call. */
	public static void breakBlock(net.minecraft.core.BlockPos pos, Direction side) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null) {
			RotationManager.lookAt(Vec3.atCenterOf(pos));
			mc.gameMode.startDestroyBlock(pos, side);
			mc.gameMode.stopDestroyBlock();
		}
	}

	/** Uses the item currently in the main hand. */
	public static boolean useItem() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) {
			return false;
		}
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		return true;
	}

	/**
	 * Runs {@code action} with the given hotbar slot selected, restoring the
	 * previous selection afterwards.
	 */
	public static void withHotbarSlot(int slot, Runnable action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		Inventory inventory = mc.player.getInventory();
		int previous = inventory.getSelectedSlot();
		inventory.setSelectedSlot(slot);
		try {
			action.run();
		} finally {
			inventory.setSelectedSlot(previous);
		}
	}
}
