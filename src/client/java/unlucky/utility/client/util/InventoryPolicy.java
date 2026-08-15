package unlucky.utility.client.util;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What each stack in the bag is for, and which part of it is surplus.
 *
 * <p>The whole reason a cleaner is frightening is that it is one classification bug away from
 * throwing something you cannot replace. So the classification is here, on its own, where it can
 * be reasoned about and tested without an inventory screen open — and where InventoryCleaner and
 * ChestCleaner cannot quietly disagree about whether your named pickaxe is disposable.
 *
 * <p><b>Two rules carry the safety, and they are ordered.</b> The keep list is absolute: nothing
 * outranks it, not a cap, not the drop list, not any automatic judgement. The drop list beats
 * <em>automatic usefulness</em> only — it does not beat protection, and it does not beat being
 * equipped. That ordering is the difference between "throw the cobblestone I said to throw" and
 * "throw the sword I am holding because I once listed swords".
 *
 * <p><b>Caps are totals, not per-stack.</b> "At most 512 blocks" is a fact about the inventory,
 * so classification is a single pass over all of it rather than a question you can ask of one
 * stack. That is also why {@link #classify} hands back a per-slot map instead of a predicate.
 *
 * @see EquipmentScorer for which of the armour pieces is worth keeping on
 */
public final class InventoryPolicy {
	/** What may be done with a stack. */
	public enum Verdict {
		/** Never touched, by any mode. Keep list, named, enchanted, equipped, hotbar-assigned. */
		PROTECTED,
		/** Worth having and subject to no cap. */
		USEFUL,
		/** Counts toward a cap and is within it — still kept. */
		CAPPED,
		/** Beyond a cap. Droppable, but only when the caller's mode says to drop. */
		EXCESS,
		/** Explicitly listed for disposal. */
		DISPOSABLE
	}

	/** The kinds of thing that have their own cap. */
	public enum Category {
		TOTEM,
		CRYSTAL,
		PEARL,
		GAPPLE,
		THROWABLE,
		ARROW,
		FOOD,
		BLOCK,
		/** Everything else — no cap applies, so it is never excess. */
		NONE
	}

	/**
	 * The caps, in the unit the category counts in.
	 *
	 * <p>Food is the odd one: it is counted in nutrition points rather than items, because
	 * "64 food" means something completely different for bread than for golden carrots and the
	 * player is really asking about how many meals they are carrying.
	 */
	public record Caps(int blocks, int arrows, int throwables, int foodPoints, int pearls,
			int crystals, int totems, int gapples) {
		public static final Caps DEFAULT = new Caps(512, 128, 64, 512, 64, 128, 8, 64);

		int of(Category category) {
			return switch (category) {
				case BLOCK -> blocks;
				case ARROW -> arrows;
				case THROWABLE -> throwables;
				case FOOD -> foodPoints;
				case PEARL -> pearls;
				case CRYSTAL -> crystals;
				case TOTEM -> totems;
				case GAPPLE -> gapples;
				case NONE -> Integer.MAX_VALUE;
			};
		}
	}

	/**
	 * The caller's rules.
	 *
	 * @param reservedSlots hotbar slots the caller has assigned a job to, on top of the selected
	 *                      one. The policy has no opinion about what a hotbar layout is; it only
	 *                      needs to know which slots are not up for grabs
	 * @param capsActive    whether caps produce {@link Verdict#EXCESS} at all — off, everything
	 *                      within a category is simply {@link Verdict#CAPPED} and kept, which is
	 *                      the default and the reason the default config throws nothing
	 */
	public record Rules(Set<Item> keep, Set<Item> drop, Caps caps, Set<Integer> reservedSlots,
			boolean protectNamed, boolean protectEnchanted, boolean protectEquipped,
			boolean capsActive) {
		public static final Rules SAFE_DEFAULT = new Rules(Set.of(), Set.of(), Caps.DEFAULT,
				Set.of(), true, true, true, false);
	}

	/**
	 * One slot's answer.
	 *
	 * @param excess how many <em>items</em> of this stack are over the cap; 0 unless the
	 *               verdict is {@link Verdict#EXCESS}. Partial, because the stack that straddles
	 *               a cap is normally the interesting one — dropping all 64 when 12 were over is
	 *               how a cleaner loses you half a shulker of blocks
	 */
	public record Entry(int slot, Verdict verdict, Category category, int excess) {
		public boolean keepable() {
			return verdict == Verdict.PROTECTED || verdict == Verdict.USEFUL || verdict == Verdict.CAPPED;
		}

		public boolean droppable() {
			return verdict == Verdict.EXCESS || verdict == Verdict.DISPOSABLE;
		}
	}

	private InventoryPolicy() {
	}

	/**
	 * Classifies every slot of {@code inventory} in one pass.
	 *
	 * <p>Ordered deliberately: protection first over the whole inventory, then the drop list,
	 * then the cap tallies over what is left. Running the caps last is what makes protected and
	 * dropped stacks not count toward them — a keep-listed stack of 64 obsidian should not push
	 * the rest of your blocks over the block cap, and a stack you are about to throw should not
	 * reserve room under one.
	 */
	public static Map<Integer, Entry> classify(Inventory inventory, Rules rules) {
		Map<Integer, Entry> result = new HashMap<>();
		if (inventory == null) {
			return result;
		}
		Map<Category, Integer> tally = new EnumMap<>(Category.class);

		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			Category category = categoryOf(stack);
			Verdict verdict = protectedVerdict(stack, slot, inventory, rules);
			if (verdict != null) {
				result.put(slot, new Entry(slot, verdict, category, 0));
				continue;
			}
			if (rules.drop().contains(stack.getItem())) {
				result.put(slot, new Entry(slot, Verdict.DISPOSABLE, category, stack.getCount()));
				continue;
			}
			if (category == Category.NONE) {
				result.put(slot, new Entry(slot, Verdict.USEFUL, category, 0));
				continue;
			}

			int units = units(stack, category);
			int before = tally.getOrDefault(category, 0);
			int cap = rules.caps().of(category);
			tally.put(category, before + units);
			if (!rules.capsActive() || before + units <= cap) {
				result.put(slot, new Entry(slot, Verdict.CAPPED, category, 0));
				continue;
			}
			// Converting "units over the cap" back into items: for food, units are nutrition
			// points and one item is worth several of them, so the two are not interchangeable.
			int overUnits = Math.min(units, before + units - cap);
			int perItem = Math.max(1, units / Math.max(1, stack.getCount()));
			int overItems = Math.min(stack.getCount(), Math.max(1, (overUnits + perItem - 1) / perItem));
			result.put(slot, new Entry(slot, Verdict.EXCESS, category, overItems));
		}
		return result;
	}

	/**
	 * Whether {@code stack} is protected, and null if it is not.
	 *
	 * <p>The keep list is checked before everything else so that no later rule can reach it,
	 * which is what "absolute" means in practice.
	 */
	private static Verdict protectedVerdict(ItemStack stack, int slot, Inventory inventory, Rules rules) {
		if (rules.keep().contains(stack.getItem())) {
			return Verdict.PROTECTED;
		}
		if (rules.protectEquipped() && equipped(slot, inventory, rules)) {
			return Verdict.PROTECTED;
		}
		if (rules.protectNamed() && stack.has(DataComponents.CUSTOM_NAME)) {
			return Verdict.PROTECTED;
		}
		if (rules.protectEnchanted() && !stack.getEnchantments().isEmpty()
				&& !rules.drop().contains(stack.getItem())) {
			// Enchanted protection yields to an explicit drop-list entry, and only to that: a
			// player who names an item in the drop list has said something specific about it.
			return Verdict.PROTECTED;
		}
		return null;
	}

	/**
	 * Whether this slot is spoken for.
	 *
	 * <p>Only the selected slot and whatever the caller reserved. In 26.2 the worn armour and
	 * the offhand are not {@code Inventory} slots at all — they live on the entity's
	 * {@code EntityEquipment} — so they are unreachable here by construction, which is the
	 * safest possible answer: a cleaner walking slots 0..35 can never touch them.
	 */
	public static boolean equipped(int slot, Inventory inventory, Rules rules) {
		return slot == inventory.getSelectedSlot() || rules.reservedSlots().contains(slot);
	}

	/**
	 * Which cap this stack counts against.
	 *
	 * <p>Order matters and the specific beats the general: a golden apple is food, but a player
	 * who set a gapple cap of 8 and a food cap of 512 did not mean their gapples to be counted
	 * as 32 points of lunch.
	 */
	public static Category categoryOf(ItemStack stack) {
		Item item = stack.getItem();
		if (item == Items.TOTEM_OF_UNDYING) {
			return Category.TOTEM;
		}
		if (item == Items.END_CRYSTAL) {
			return Category.CRYSTAL;
		}
		if (item == Items.ENDER_PEARL) {
			return Category.PEARL;
		}
		if (item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE) {
			return Category.GAPPLE;
		}
		if (item == Items.SNOWBALL || item == Items.EGG) {
			return Category.THROWABLE;
		}
		// Normal arrows only for now: tipped and spectral arrows are chosen deliberately and
		// counting them against the same cap would quietly throw the ones you crafted on purpose.
		if (item == Items.ARROW) {
			return Category.ARROW;
		}
		if (stack.has(DataComponents.FOOD)) {
			return Category.FOOD;
		}
		if (item instanceof BlockItem) {
			return Category.BLOCK;
		}
		return Category.NONE;
	}

	/** How much of its category's budget this stack uses — nutrition for food, count otherwise. */
	public static int units(ItemStack stack, Category category) {
		if (category != Category.FOOD) {
			return stack.getCount();
		}
		FoodProperties food = stack.get(DataComponents.FOOD);
		return food == null ? stack.getCount() : food.nutrition() * stack.getCount();
	}
}
