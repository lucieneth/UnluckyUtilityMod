package unlucky.utility.client.module.modules.player;

import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Eats when you get hungry, and won't touch the food you told it not to.
 *
 * <p>Rather than driving the eat with packets, it holds the use key down
 * ({@code KeyMapping.setDown}) and lets vanilla do the rest — animation, timing,
 * sounds and the slot sync all come free, because vanilla's own
 * {@code handleKeybinds} continues a use while the key reads as held. Letting go
 * of the key is what stops the eat.
 *
 * <p>{@link #isEating()} is the interop hook: interact modules (ClickTP,
 * TridentFly, later Nuker) check it so they don't steal the right-click
 * mid-meal.
 */
public class AutoEat extends Module {
	/** Food that is never worth eating unless you say so. */
	private static final Set<String> DEFAULT_BLACKLIST = Set.of(
			"minecraft:rotten_flesh",
			"minecraft:spider_eye",
			"minecraft:poisonous_potato",
			"minecraft:pufferfish",
			"minecraft:chorus_fruit",
			"minecraft:chicken",
			"minecraft:suspicious_stew");

	public final NumberSetting threshold = add(new NumberSetting("Hunger threshold",
			"Start eating once your hunger drops to this (20 is full)", 16.0, 1.0, 19.0, 1.0));
	public final ItemListSetting blacklist = add(new ItemListSetting("Blacklist",
			"Food to never eat", AutoEat::isFood, DEFAULT_BLACKLIST));
	public final ModeSetting prefer = add(new ModeSetting("Prefer",
			"Which food to reach for first", "Best saturation", "Best saturation", "First in hotbar"));
	public final BooleanSetting ignoreGapples = add(new BooleanSetting("Ignore gapples",
			"Never auto-eat golden or enchanted golden apples — save them for combat", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot you were holding once you're done", true));

	private int previousSlot = -1;
	private boolean eating;

	public AutoEat() {
		super("AutoEat", "Eats automatically when you get hungry", Category.PLAYER);
	}

	/** True while we're holding the use key to eat. Interact modules should stand down. */
	public boolean isEating() {
		return eating;
	}

	/** Convenience for the modules that need to yield to it. */
	public static boolean busy() {
		AutoEat autoEat = UnluckyClient.INSTANCE.modules.get(AutoEat.class);
		return autoEat.isEnabled() && autoEat.isEating();
	}

	static boolean isFood(Item item) {
		return item.components().has(DataComponents.FOOD);
	}

	private static boolean isGapple(Item item) {
		return item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE;
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || player.isSpectator()) {
			stop();
			return;
		}
		if (eating) {
			// keep going until we're full, or the food ran out from under us
			if (player.getFoodData().getFoodLevel() >= 20 || !isFood(player.getMainHandItem().getItem())) {
				stop();
			}
			return;
		}
		if (player.getFoodData().getFoodLevel() > threshold.getInt() || player.isUsingItem()) {
			return;
		}
		int slot = bestFoodSlot(player);
		if (slot < 0) {
			return;
		}
		previousSlot = player.getInventory().getSelectedSlot();
		player.getInventory().setSelectedSlot(slot);
		eating = true;
		mc().options.keyUse.setDown(true);
	}

	private void stop() {
		if (!eating) {
			return;
		}
		eating = false;
		mc().options.keyUse.setDown(false);
		LocalPlayer player = mc().player;
		if (player != null && swapBack.get() && previousSlot >= 0) {
			player.getInventory().setSelectedSlot(previousSlot);
		}
		previousSlot = -1;
	}

	/** Best hotbar slot holding edible, non-blacklisted food, or -1. */
	private int bestFoodSlot(LocalPlayer player) {
		int best = -1;
		float bestScore = -1.0f;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || !isFood(stack.getItem()) || blacklist.contains(stack.getItem())) {
				continue;
			}
			if (ignoreGapples.get() && isGapple(stack.getItem())) {
				continue;
			}
			if (prefer.is("First in hotbar")) {
				return slot;
			}
			FoodProperties food = stack.get(DataComponents.FOOD);
			// saturation is the component that actually keeps hunger away
			float score = food == null ? 0.0f : food.saturation() * 4.0f + food.nutrition();
			if (score > bestScore) {
				bestScore = score;
				best = slot;
			}
		}
		return best;
	}
}
