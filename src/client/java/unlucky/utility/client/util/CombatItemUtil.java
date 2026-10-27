package unlucky.utility.client.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.phys.Vec3;

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

	/** Base main-hand attack damage of a stack, summed over its own modifiers. */
	public static double meleeDamage(ItemStack stack) {
		double[] total = { 0.0 };
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value()) {
				total[0] += modifier.amount();
			}
		});
		return total[0];
	}

	/** Whether a hit with this stack puts a raised shield on cooldown — axes, and anything data-driven like them. */
	public static boolean disablesShields(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		Weapon weapon = stack.get(DataComponents.WEAPON);
		return weapon != null && weapon.disableBlockingForSeconds() > 0.0f;
	}

	/**
	 * Whether {@code target} is holding a shield up <em>towards</em> {@code attacker}.
	 *
	 * <p>Vanilla only blocks a hit whose source lies in front of the blocker's head yaw (pitch
	 * plays no part), so a raised shield facing away is no reason to change weapons.
	 */
	public static boolean shieldRaisedAgainst(LivingEntity target, Entity attacker) {
		if (target == null || attacker == null || !target.isBlocking()) {
			return false;
		}
		float yaw = target.getYHeadRot() * Mth.DEG_TO_RAD;
		Vec3 facing = new Vec3(-Mth.sin(yaw), 0.0, Mth.cos(yaw));
		Vec3 toAttacker = attacker.position().subtract(target.position());
		toAttacker = new Vec3(toAttacker.x, 0.0, toAttacker.z);
		return toAttacker.lengthSqr() < 1.0e-6 || toAttacker.normalize().dot(facing) > 0.0;
	}
}
