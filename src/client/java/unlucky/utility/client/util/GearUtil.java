package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/** Reads a living entity's worn gear and its enchantments for HUD readouts. */
public final class GearUtil {
	// display order: armor head->feet, then the two hands
	private static final EquipmentSlot[] ORDER = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
			EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

	private GearUtil() {
	}

	/** Non-empty gear stacks in display order. */
	public static List<ItemStack> gear(LivingEntity entity) {
		List<ItemStack> list = new ArrayList<>();
		for (EquipmentSlot slot : ORDER) {
			ItemStack stack = entity.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				list.add(stack);
			}
		}
		return list;
	}

	/** Abbreviated enchant tags for a single item, in the order stored. */
	public static List<String> enchantChips(ItemStack stack) {
		List<String> chips = new ArrayList<>();
		for (Entry<Holder<Enchantment>> e : stack.getEnchantments().entrySet()) {
			chips.add(abbrev(e.getKey().value().description().getString(), e.getIntValue()));
		}
		return chips;
	}

	/** "Protection" 4 -> "Prot 4"; level shown only when above 1. */
	private static String abbrev(String name, int level) {
		String tag = name.length() > 4 ? name.substring(0, 4) : name;
		return level > 1 ? tag + " " + level : tag;
	}
}
