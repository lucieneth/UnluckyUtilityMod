package unlucky.utility.client.util;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;

/** Small shared weapon predicates; targeting modules must not grow subtly different lists. */
public final class CombatItemUtil {
	private CombatItemUtil() {
	}

	/** The melee set used by Reach and Hitboxes: swords, axes and maces. */
	public static boolean isMeleeWeapon(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& (isSword(stack) || isAxe(stack) || isMace(stack));
	}

	public static boolean isSword(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.is(ItemTags.SWORDS);
	}

	public static boolean isAxe(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.is(ItemTags.AXES);
	}

	public static boolean isMace(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof MaceItem;
	}

	/** Item-list picker form of {@link #isMeleeWeapon(ItemStack)}. */
	public static boolean isMeleeWeapon(Item item) {
		return item != null && (item.builtInRegistryHolder().is(ItemTags.SWORDS)
				|| item.builtInRegistryHolder().is(ItemTags.AXES) || item instanceof MaceItem);
	}
}
