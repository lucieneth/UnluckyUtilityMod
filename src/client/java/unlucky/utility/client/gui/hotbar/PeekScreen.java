package unlucky.utility.client.gui.hotbar;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * A held shulker box's contents in the ordinary shulker GUI, without placing it.
 *
 * <p>Read-only by construction: the menu is never handed to the player as their open
 * container, so the server knows nothing of it, and every click is dropped here before it
 * becomes a packet. Closing it sends the same close vanilla sends for the plain inventory.
 */
public class PeekScreen extends ShulkerBoxScreen {
	private static final int SIZE = 27;

	private PeekScreen(ShulkerBoxMenu menu, Inventory inventory, ItemStack box) {
		super(menu, inventory, box.getHoverName());
	}

	/** Null when the stack carries no container contents (not a shulker box). */
	public static PeekScreen of(ItemStack box, Inventory inventory) {
		ItemContainerContents contents = box.get(DataComponents.CONTAINER);
		if (contents == null) {
			return null;
		}
		NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
		contents.copyInto(items);
		SimpleContainer container = new SimpleContainer(SIZE);
		for (int slot = 0; slot < SIZE; slot++) {
			container.setItem(slot, items.get(slot));
		}
		// id -1 matches no menu the server could have open, should anything slip through
		return new PeekScreen(new ShulkerBoxMenu(-1, inventory, container), inventory, box);
	}

	@Override
	protected void slotClicked(Slot slot, int slotId, int buttonNum, ContainerInput containerInput) {
		// look, don't touch
	}
}
