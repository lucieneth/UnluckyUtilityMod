package unlucky.utility.client.util;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * Item stacks for GUIs that can be open with no world behind them.
 *
 * <p>26.2 made item components data-driven: they live on the registry
 * {@code Holder} and are only bound once a world has synced its registries, so
 * {@code new ItemStack(...)} on the title screen throws "Components not bound
 * yet" — which is what killed the HUD editor on a preview diamond. Anything
 * reachable from the main menu builds its icons through {@link #icon} and draws
 * nothing when the stack comes back empty; anything that needs the components
 * themselves (item names, {@code components().has(...)} filters) checks
 * {@link #componentsBound()} first.
 */
public final class ItemUtil {
	private ItemUtil() {
	}

	/** The item's stack, or {@link ItemStack#EMPTY} while its components are unbound. */
	public static ItemStack icon(ItemLike item) {
		if (item == null) {
			return ItemStack.EMPTY;
		}
		Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item.asItem());
		return holder.areComponentsBound() ? new ItemStack(holder) : ItemStack.EMPTY;
	}

	/**
	 * True once item components exist. One registry sync binds every item at
	 * once, so any registered item answers for all of them.
	 */
	public static boolean componentsBound() {
		return BuiltInRegistries.ITEM.wrapAsHolder(Items.STONE).areComponentsBound();
	}
}
