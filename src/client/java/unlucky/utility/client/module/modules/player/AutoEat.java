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

	/**
	 * Ticks of notice given before the hotbar is touched.
	 *
	 * <p>Announcing the meal a moment early is the whole difference between yielding and being
	 * interrupted. A module cut off mid-action has already selected a slot and remembered what
	 * to put back; change the selection underneath it and its bookkeeping restores the wrong
	 * thing when it finishes. Two ticks is enough for every claimant to see {@link #busy()},
	 * finish the swing it is in and hand the hotbar back tidily, and it costs a tenth of a
	 * second against a meal that takes thirty-two.
	 */
	private static final int CLAIM_TICKS = 2;

	/**
	 * Ticks a meal is given to actually begin before it is abandoned, and how long to wait
	 * before trying again. Eating takes 32 ticks, so 20 is long enough that a slow server
	 * round trip is never mistaken for a blocked one.
	 */
	private static final int START_GRACE = 20;
	private static final int RETRY_TICKS = 40;

	private int previousSlot = -1;
	/** Ticks of notice already served — see {@link #CLAIM_TICKS}. Nothing is held yet. */
	private int claim;
	private boolean eating;
	/** Whether this meal was ever seen to be under way, and how long it has not been. */
	private boolean started;
	private int blocked;
	/** Ticks before another attempt after one that never got going. */
	private int retry;
	private InteractionHand eatingHand = InteractionHand.MAIN_HAND;

	public AutoEat() {
		super("AutoEat", "Eats automatically when you get hungry", Category.PLAYER);
	}

	/** True while we're holding the use key to eat. Interact modules should stand down. */
	public boolean isEating() {
		return eating;
	}

	/** True while eating <em>or</em> about to: the window in which nothing else may take the hand. */
	public boolean isClaimed() {
		return eating || claim > 0;
	}

	/** Convenience for the modules that need to yield to it. */
	public static boolean busy() {
		AutoEat autoEat = UnluckyClient.INSTANCE.modules.get(AutoEat.class);
		return autoEat.isEnabled() && autoEat.isClaimed();
	}

	/**
	 * Whether a module with this "Pause on AutoEat" setting should stand down right now.
	 *
	 * <p>One place to ask, so the notice window and the setting can never drift apart between
	 * the dozen modules that yield.
	 */
	public static boolean pauses(BooleanSetting setting) {
		return setting.get() && busy();
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
				return;
			}
			// Something can still open one mid-meal — a paused module finishing its last
			// click, or the server pushing a screen at us. Held shut for the whole meal.
			closeContainers();
			// Did the meal actually start? Holding the use key only eats if vanilla processes
			// it, and it does not while a screen owns the mouse. Without this check a blocked
			// eat is permanent: hunger never rises, the food never leaves the hand, so neither
			// exit above ever fires — and since every module with "Pause on AutoEat" is
			// standing down on isClaimed(), the whole client stops with it. That is the state
			// a restock walked into, and it is the one bug here that takes everything with it.
			if (player.isUsingItem()) {
				started = true;
			} else if (!started && ++blocked > START_GRACE) {
				stop();
				// Back off before trying again, or a permanently blocked eat becomes a
				// permanent stutter instead of a permanent freeze.
				retry = RETRY_TICKS;
			}
			return;
		}
		if (retry > 0) {
			retry--;
			return;
		}
		if (player.getFoodData().getFoodLevel() > threshold.getInt() || player.isUsingItem()) {
			claim = 0;
			return;
		}
		Choice choice = chooseFood(player);
		if (choice == null) {
			claim = 0;
			return;
		}
		// Give notice before taking anything. busy() is already true on this tick, so whoever
		// holds the hotbar gets to put it back before we change it rather than after.
		if (claim < CLAIM_TICKS) {
			claim++;
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
		claim = 0;
		started = false;
		blocked = 0;
		closeContainers();
		mc().options.keyUse.setDown(true);
	}

	/**
	 * Shuts any container before the use key goes down, and keeps it shut for the meal.
	 *
	 * <p>An open menu swallows the eat outright — vanilla does not process the use key while
	 * a screen has the mouse — so a printer that stopped to eat with a chest open would sit
	 * there starving with food in its hand. Closing it is not tidiness, it is the difference
	 * between eating and not.
	 *
	 * <p>Covers the silent menus too — the printer opens containers with no screen at all —
	 * because it is the <em>menu</em> that swallows the key, not the window. Vanilla's own
	 * {@code closeContainer} ends in {@code gui.setScreen(null)}, so one call handles both
	 * the packet and a chest window the player can see.
	 *
	 * <p>Your own inventory is deliberately left alone: {@code containerMenu} is the
	 * inventory menu when it is open, so this cannot yank a screen you opened yourself out
	 * from under you just because you happened to get hungry looking at it.
	 */
	private void closeContainers() {
		LocalPlayer player = mc().player;
		if (player != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	private void stop() {
		claim = 0;
		if (!eating) {
			return;
		}
		eating = false;
		started = false;
		blocked = 0;
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
