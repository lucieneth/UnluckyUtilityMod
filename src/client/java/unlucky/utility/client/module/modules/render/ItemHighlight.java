package unlucky.utility.client.module.modules.render;

import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.util.ColorUtil;

/**
 * Paints a flat colour behind chosen items wherever a container is open, so a
 * shulker full of junk still shows the two stacks you came for at a glance.
 *
 * <p>Drawn from {@code AbstractContainerScreen.extractSlot}, which every slot in
 * every menu goes through — the player inventory row included, and the creative
 * tabs with it. That is one hook for the whole feature; matching the item and
 * picking the colour is all this class does.
 *
 * <p>Reference: Meteor's ItemHighlight.
 */
public class ItemHighlight extends Module {
	/** No filter: any item is a legitimate thing to want picked out of a wall of slots. */
	public final ItemListSetting items = add(new ItemListSetting("Items",
			"Items to highlight in containers", item -> true));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Fill drawn behind a matching slot", ColorUtil.argb(50, 225, 25, 255)));

	public ItemHighlight() {
		super("ItemHighlight", "Highlights chosen items in containers", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	/**
	 * The fill for one slot, or 0 to leave it alone. Zero doubles as "fully
	 * transparent", so a colour the player has dragged to zero alpha costs the
	 * mixin nothing either.
	 */
	public int highlight(ItemStack stack) {
		if (!isEnabled() || stack == null || stack.isEmpty() || !items.contains(stack.getItem())) {
			return 0;
		}
		return color.get();
	}
}
