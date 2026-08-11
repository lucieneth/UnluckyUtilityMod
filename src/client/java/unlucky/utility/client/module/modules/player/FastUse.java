package unlucky.utility.client.module.modules.player;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Removes the four-tick cooldown vanilla puts between right-click uses
 * (handled by {@code MinecraftMixin}). Speeds up block placing, bucket work,
 * eating, and anything else gated on {@code rightClickDelay}.
 */
public class FastUse extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"What to speed up", "Everything", "Everything", "Food only", "Custom"));
	public final ModeSetting listMode = add(new ModeSetting("List mode", "Custom items are either allowed or excluded", "Allowlist", "Allowlist", "Blacklist"), () -> mode.is("Custom"));
	public final BooleanSetting requireUse = add(new BooleanSetting("Require use key", "Only shorten delay while the player is actively using an item", false));
	public final ItemListSetting items = add(new ItemListSetting("Items",
			"Only these items use the shortened delay", item -> true), () -> mode.is("Custom"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between uses (vanilla is 4, 0 is instant)", 0.0, 0.0, 4.0, 1.0));

	public FastUse() {
		super("FastUse", "Removes the delay between right-click uses", Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Whether the delay should be shortened for what the player is currently holding. */
	public boolean appliesTo(ItemStack mainHand, ItemStack offHand) {
		if (mode.is("Everything")) {
			return true;
		}
		if (mode.is("Custom")) {
			boolean listed = listed(mainHand) || listed(offHand);
			return listMode.is("Allowlist") ? listed : !listed;
		}
		return isFood(mainHand) || isFood(offHand);
	}

	public boolean requiresUse() {
		return !requireUse.get() || mc().options.keyUse.isDown();
	}

	private boolean listed(ItemStack stack) {
		return stack != null && !stack.isEmpty() && items.contains(stack.getItem());
	}

	private static boolean isFood(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.has(DataComponents.FOOD);
	}
}
