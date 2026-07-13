package unlucky.utility.client.module.modules.player;

import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
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
	private InteractionHand eatingHand = InteractionHand.MAIN_HAND;

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
			// keep going until we're full, or the food ran out from under the hand we chose
			if (player.getFoodData().getFoodLevel() >= 20 || !edible(player.getItemInHand(eatingHand))) {
				stop();
			}
			return;
		}
		if (player.getFoodData().getFoodLevel() > threshold.getInt() || player.isUsingItem()) {
			return;
		}
		Choice choice = chooseFood(player);
		if (choice == null) {
			return;
		}
		previousSlot = player.getInventory().getSelectedSlot();
		if (choice.hand() == InteractionHand.MAIN_HAND) {
			player.getInventory().setSelectedSlot(choice.slot());
		} else if (mainHandIntercepts(player.getMainHandItem())) {
			// eating the offhand, but the main hand would eat its own food (blacklisted
			// or a gapple we're saving) or place a block under the held right-click —
			// swap to an empty slot so vanilla's use falls through to the offhand.
			int empty = firstEmptyHotbarSlot(player);
			if (empty >= 0) {
				player.getInventory().setSelectedSlot(empty);
			}
		}
		eatingHand = choice.hand();
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

	/** A chosen food source: a hotbar {@code slot} for the main hand, or the offhand. */
	private record Choice(InteractionHand hand, int slot) {
	}

	/**
	 * Picks the food to eat across the hotbar <em>and</em> the offhand. Main-hand food
	 * wins ties (cleanest to hold), but in "Best saturation" mode an offhand item with
	 * a higher score is preferred; in "First in hotbar" mode the offhand is the
	 * last resort when the hotbar has nothing edible.
	 */
	private Choice chooseFood(LocalPlayer player) {
		boolean first = prefer.is("First in hotbar");
		Choice best = null;
		float bestScore = -1.0f;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!edible(stack)) {
				continue;
			}
			if (first) {
				return new Choice(InteractionHand.MAIN_HAND, slot);
			}
			float score = score(stack);
			if (score > bestScore) {
				bestScore = score;
				best = new Choice(InteractionHand.MAIN_HAND, slot);
			}
		}
		if (edible(player.getOffhandItem()) && (first || best == null || score(player.getOffhandItem()) > bestScore)) {
			return new Choice(InteractionHand.OFF_HAND, -1);
		}
		return best;
	}

	/** Edible, not on our blacklist, and not a gapple we're told to save. */
	private boolean edible(ItemStack stack) {
		if (stack.isEmpty() || !isFood(stack.getItem()) || blacklist.contains(stack.getItem())) {
			return false;
		}
		return !(ignoreGapples.get() && isGapple(stack.getItem()));
	}

	/** Saturation-weighted food score; saturation is what actually keeps hunger away. */
	private static float score(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		return food == null ? 0.0f : food.saturation() * 4.0f + food.nutrition();
	}

	/** Would the main-hand item consume the held right-click before it reaches the offhand? */
	private static boolean mainHandIntercepts(ItemStack mainHand) {
		return isFood(mainHand.getItem()) || mainHand.getItem() instanceof net.minecraft.world.item.BlockItem;
	}

	private int firstEmptyHotbarSlot(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}
}
