package unlucky.utility.client.module.modules.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Removes the four-tick cooldown vanilla puts between right-click uses
 * (handled by {@code MinecraftMixin}). Speeds up block placing, bucket work,
 * eating, and anything else gated on {@code rightClickDelay}.
 */
public class FastUse extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"What to speed up (a custom item list lands with the item picker)",
			"Everything", "Everything", "Food only"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between uses (vanilla is 4, 0 is instant)", 0.0, 0.0, 4.0, 1.0));

	public FastUse() {
		super("FastUse", "Removes the delay between right-click uses", Category.PLAYER);
	}

	/** Whether the delay should be shortened for what the player is currently holding. */
	public boolean appliesTo(ItemStack mainHand, ItemStack offHand) {
		if (mode.is("Everything")) {
			return true;
		}
		return isFood(mainHand) || isFood(offHand);
	}

	private static boolean isFood(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}
}
