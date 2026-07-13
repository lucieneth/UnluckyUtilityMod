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

	/** Abbreviated enchant tags for a single item, in the order stored (4-letter, spaced). */
	public static List<String> enchantChips(ItemStack stack) {
		return enchantChips(stack, 4, true);
	}

	/**
	 * Abbreviated enchant tags with a custom prefix length; {@code spaceLevel} inserts
	 * a space before the level number ("Prot 4") or packs it tight ("Pro4") for width.
	 */
	public static List<String> enchantChips(ItemStack stack, int maxLen, boolean spaceLevel) {
		List<String> chips = new ArrayList<>();
		for (Entry<Holder<Enchantment>> e : stack.getEnchantments().entrySet()) {
			chips.add(abbrev(name(e.getKey()), e.getIntValue(), maxLen, spaceLevel));
		}
		return chips;
	}

	/**
	 * The enchant's default name, immune to resource packs. Texture packs
	 * override the lang entries {@code description()} resolves through (icon
	 * glyphs, decorations — stripping glyph codepoints wasn't enough), so the
	 * name is derived from the registry id instead: {@code fire_protection}
	 * → "Fire Protection". Works for modded enchants too; the display
	 * component is only a fallback for unregistered ones.
	 */
	private static String name(Holder<Enchantment> holder) {
		var key = holder.unwrapKey();
		if (key.isEmpty()) {
			return holder.value().description().getString();
		}
		StringBuilder sb = new StringBuilder();
		for (String part : key.get().identifier().getPath().split("_")) {
			if (part.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.toString();
	}

	/** "Protection" 4 -> "Prot 4" / "Pro4"; level shown only when above 1. */
	private static String abbrev(String name, int level, int maxLen, boolean spaceLevel) {
		String tag = name.length() > maxLen ? name.substring(0, maxLen) : name;
		if (level <= 1) {
			return tag;
		}
		return spaceLevel ? tag + " " + level : tag + level;
	}
}
