package unlucky.utility.client.util;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;

/** Small shared weapon predicates; targeting modules must not grow subtly different lists. */
public final class CombatItemUtil {
	private CombatItemUtil() {
	}

	/** The melee set used by Reach and Hitboxes: swords, axes and maces. */
	public static boolean isMeleeWeapon(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)
						|| stack.getItem() instanceof MaceItem);
	}
}
