package unlucky.utility.client.util;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * How good a piece of armour is, as one number, so the modules that choose between two of them
 * agree about which one won.
 *
 * <p><b>The item is asked, not recognised.</b> Ranking by material name — netherite beats
 * diamond beats iron — is the obvious implementation and it is wrong for every trimmed,
 * enchanted, damaged or modded piece in the game. A Protection IV diamond chestplate beats a
 * bare netherite one and no table of material names will ever say so. Everything here comes from
 * the stack's own attribute modifiers and enchantments, which is also why it works on armour
 * this client has never heard of.
 *
 * <p>The profiles are not cosmetic. Protection reduces everything a little; the specific ones
 * reduce their own damage type far more, and which of those matters is a fact about where the
 * player is standing rather than about the armour. Blast is what you want in a crystal fight and
 * actively worse in a mob grinder, so the choice has to be the player's.
 *
 * @see InventoryPolicy for what to do with the pieces that lose
 */
public final class EquipmentScorer {
	/**
	 * What the player is armouring against.
	 *
	 * <p>Balanced weights plain Protection highest and still counts the specialists, which is
	 * the right default for someone who does not know what is about to happen to them.
	 */
	public enum Profile {
		BALANCED,
		PROTECTION,
		BLAST,
		FIRE,
		PROJECTILE
	}

	/** What to do about a piece carrying Curse of Binding. */
	public enum BindingPolicy {
		/** Never take it off once it is on, but do not put another one on either. */
		KEEP_EQUIPPED,
		/** Never equip it in the first place. */
		IGNORE,
		/** Treat it as ordinary armour. */
		ALLOW
	}

	/**
	 * The player's stated preferences.
	 *
	 * @param minimumDurabilityPercent pieces at or below this are not candidates at all
	 * @param preferMending            a small tiebreak, deliberately far too small to beat a
	 *                                 materially stronger piece
	 * @param blastLeggings            nudges blast protection on leggings, where the difference
	 *                                 between surviving a crystal and not usually lands
	 */
	public record Preferences(Profile profile, boolean preferMending, boolean blastLeggings,
			int minimumDurabilityPercent, BindingPolicy binding) {
		public static final Preferences DEFAULT =
				new Preferences(Profile.BALANCED, true, true, 10, BindingPolicy.KEEP_EQUIPPED);
	}

	/**
	 * Weight on one armour point. Armour points are the dominant term by design — an extra
	 * point of armour is worth more than a level of any single protection enchantment, which is
	 * what keeps the enchantment bonuses from turning into the ranking.
	 */
	private static final float ARMOUR_WEIGHT = 2.0f;
	private static final float TOUGHNESS_WEIGHT = 1.5f;
	private static final float KNOCKBACK_WEIGHT = 4.0f;

	/** Mending's tiebreak, in armour-point-equivalents. A quarter of one point, on purpose. */
	private static final float MENDING_BONUS = 0.5f;
	/** Unbreaking's, smaller still: it changes how long the piece lasts, not how good it is. */
	private static final float UNBREAKING_BONUS = 0.1f;
	/** Blast protection's nudge on leggings under {@code blastLeggings}. */
	private static final float BLAST_LEGGINGS_BONUS = 0.3f;

	private EquipmentScorer() {
	}

	/**
	 * How good {@code stack} is in {@code slot}, higher being better. Zero for anything that
	 * cannot go there at all.
	 *
	 * <p>Scores are only comparable within one slot. A helmet's number means nothing beside a
	 * chestplate's, and nothing here tries to make it.
	 */
	public static float score(ItemStack stack, EquipmentSlot slot, Preferences preferences) {
		if (stack.isEmpty() || slot == null) {
			return 0.0f;
		}
		ItemAttributeModifiers modifiers =
				stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		float score = (float) (modifiers.compute(Attributes.ARMOR, 0.0, slot) * ARMOUR_WEIGHT
				+ modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot) * TOUGHNESS_WEIGHT
				+ modifiers.compute(Attributes.KNOCKBACK_RESISTANCE, 0.0, slot) * KNOCKBACK_WEIGHT);

		int protection = level(stack, Enchantments.PROTECTION);
		int blast = level(stack, Enchantments.BLAST_PROTECTION);
		int fire = level(stack, Enchantments.FIRE_PROTECTION);
		int projectile = level(stack, Enchantments.PROJECTILE_PROTECTION);
		score += weighted(preferences.profile(), protection, blast, fire, projectile);

		if (preferences.blastLeggings() && slot == EquipmentSlot.LEGS) {
			score += blast * BLAST_LEGGINGS_BONUS;
		}
		if (preferences.preferMending() && has(stack, Enchantments.MENDING)) {
			score += MENDING_BONUS;
		}
		score += level(stack, Enchantments.UNBREAKING) * UNBREAKING_BONUS;
		return score;
	}

	/**
	 * The protection enchantments, weighted by profile.
	 *
	 * <p>Vanilla's own arithmetic is deliberately not reproduced here. Its EPF caps and per-type
	 * multipliers describe damage against one specific source, and this is a ranking made
	 * before anyone knows what the source will be — a number that pretends to be a damage
	 * calculation would be a worse lie than a number that admits it is a preference.
	 */
	private static float weighted(Profile profile, int protection, int blast, int fire, int projectile) {
		return switch (profile) {
			case PROTECTION -> protection * 1.5f + (blast + fire + projectile) * 0.2f;
			case BLAST -> blast * 1.5f + protection * 0.8f + (fire + projectile) * 0.1f;
			case FIRE -> fire * 1.5f + protection * 0.8f + (blast + projectile) * 0.1f;
			case PROJECTILE -> projectile * 1.5f + protection * 0.8f + (blast + fire) * 0.1f;
			case BALANCED -> protection * 1.0f + (blast + fire + projectile) * 0.4f;
		};
	}

	/**
	 * Whether this piece may be equipped at all — durability, curses, and nothing else.
	 *
	 * <p>Separate from {@link #score} because "worse than what I am wearing" and "must not be
	 * worn" are different answers, and a caller that conflates them ends up refusing to equip
	 * anything when its only candidate is merely poor.
	 */
	public static boolean eligible(ItemStack stack, Preferences preferences) {
		if (stack.isEmpty()) {
			return false;
		}
		if (durabilityPercent(stack) <= preferences.minimumDurabilityPercent()) {
			return false;
		}
		return switch (preferences.binding()) {
			case ALLOW -> true;
			// Keeping a bound piece on is the wearer's problem; putting one on is a choice, and
			// both of the non-ALLOW policies decline to make it.
			case IGNORE, KEEP_EQUIPPED -> !bound(stack);
		};
	}

	/** Whether an already-worn piece may be taken off. */
	public static boolean removable(ItemStack stack, Preferences preferences) {
		return preferences.binding() != BindingPolicy.KEEP_EQUIPPED || !bound(stack);
	}

	/** Remaining durability as a percentage; 100 for anything that cannot be damaged. */
	public static float durabilityPercent(ItemStack stack) {
		if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) {
			return 100.0f;
		}
		return (stack.getMaxDamage() - stack.getDamageValue()) * 100.0f / stack.getMaxDamage();
	}

	/** Whether the piece is about to break — the anti-break trigger. */
	public static boolean nearlyBroken(ItemStack stack, int thresholdPercent) {
		return stack.isDamageableItem() && durabilityPercent(stack) <= thresholdPercent;
	}

	public static boolean bound(ItemStack stack) {
		return has(stack, Enchantments.BINDING_CURSE);
	}

	public static boolean vanishing(ItemStack stack) {
		return has(stack, Enchantments.VANISHING_CURSE);
	}

	/**
	 * An enchantment's level on this stack, or 0.
	 *
	 * <p>Matched through {@code Holder.is(ResourceKey)} rather than a registry lookup, so this
	 * works before a world has bound its registries — the trap {@code AutoEat} documents for
	 * item components applies here too.
	 */
	public static int level(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().is(enchantment)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}

	public static boolean has(ItemStack stack, ResourceKey<Enchantment> enchantment) {
		return level(stack, enchantment) > 0;
	}
}
