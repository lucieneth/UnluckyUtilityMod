package unlucky.utility.client.module.modules.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;

/**
 * Moves a mending tool (never armor) into your offhand and throws XP bottles
 * to repair it. Armor is left equipped.
 */
public class AutoXPRepair extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Bottles per second", 4, 1, 20, 1));

	private int ticks;
	private boolean warned;

	public AutoXPRepair() {
		super("AutoXPRepair", "Repairs mending gear with XP bottles", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		warned = false;
	}

	@Override
	public void onTick() {
		if (mc().player == null) {
			return;
		}

		// nothing to do without XP bottles on hand
		int xpSlot = InteractUtil.findHotbarItem(Items.EXPERIENCE_BOTTLE);
		if (xpSlot < 0) {
			return;
		}

		// ensure a *damaged* mending tool sits in the offhand
		ItemStack offhand = mc().player.getItemBySlot(EquipmentSlot.OFFHAND);
		boolean offhandRepairable = hasMending(offhand) && offhand.isDamaged();
		if (!offhandRepairable) {
			for (int slot = 0; slot < 9; slot++) {
				ItemStack stack = mc().player.getInventory().getItem(slot);
				if (hasMending(stack) && stack.isDamaged() && !stack.has(DataComponents.EQUIPPABLE)) {
					InteractUtil.swapHotbarToOffhand(slot);
					return; // let the swap settle before throwing
				}
			}
			// offhand is full-durability or holds nothing worth repairing — stop throwing
			return;
		}
		int interval = Math.max(1, (int) Math.round(20.0 / speed.get()));
		if (--ticks > 0) {
			return;
		}
		ticks = interval;
		// aim up so the bottle arcs away from you
		float oldPitch = mc().player.getXRot();
		mc().player.setXRot(-45.0f);
		try {
			InteractUtil.withHotbarSlot(xpSlot, InteractUtil::useItem);
		} finally {
			mc().player.setXRot(oldPitch);
		}
	}

	private static boolean hasMending(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
		return enchantments.keySet().stream().anyMatch(holder -> holder.is(Enchantments.MENDING));
	}
}
