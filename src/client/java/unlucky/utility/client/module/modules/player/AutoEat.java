package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InputActionCoordinator;

/**
 * Eats when you get hungry, and won't touch the food you told it not to.
 *
 * <p>Rather than driving the eat with packets, it holds the use key down and lets
 * vanilla do the rest — animation, timing, sounds and the slot sync all come free,
 * because vanilla's own {@code handleKeybinds} continues a use while the key reads
 * as held. Letting go of the key is what stops the eat.
 *
 * <p>The hold goes through {@link InputActionCoordinator} rather than
 * {@code KeyMapping.setDown} directly, at {@code PRIORITY_SURVIVAL}. The request is
 * renewed every tick of the meal, so losing it to something that outranks eating
 * ends the meal cleanly instead of leaving two owners of one key.
 *
 * <p>{@link #isEating()} is the interop hook: interact modules (ClickTP,
 * TridentFly, later Nuker) check it so they don't steal the right-click
 * mid-meal.
 */
public class AutoEat extends Module {
	public final NumberSetting threshold = add(new NumberSetting("Hunger threshold",
			"Start eating once your hunger drops to this (20 is full)", 14.0, 1.0, 19.0, 1.0));
	public final ModeSetting triggerMode = add(new ModeSetting("Trigger mode",
			"Which resource can start an automatic meal", "Any", "Hunger", "Health", "Any", "Both"));
	public final NumberSetting healthThreshold = add(new NumberSetting("Health threshold",
			"Health plus absorption at which emergency food is allowed", 8.0, 1.0, 36.0, 1.0),
			() -> !triggerMode.is("Hunger"));
	public final NumberSetting stopAtHunger = add(new NumberSetting("Stop at hunger",
			"Stop once this hunger level is reached (20 means full)", 20.0, 1.0, 20.0, 1.0));
	public final BooleanSetting skipHarmful = add(new BooleanSetting("Skip harmful",
			"Never eat food that poisons you, makes you hungrier, or teleports you", true));
	public final ItemListSetting blacklist = add(new ItemListSetting("Blacklist",
			"Extra food to never eat, on top of Skip harmful", AutoEat::isFood));
	public final ModeSetting prefer = add(new ModeSetting("Prefer",
			"Which food to reach for first", "Least waste", "Best saturation", "Best hunger",
			"Least waste", "First in hotbar"));
	public final BooleanSetting ignoreGapples = add(new BooleanSetting("Ignore gapples",
			"Legacy hard block for golden apples; Golden apple mode is the preferred control", false));
	public final ModeSetting goldenApples = add(new ModeSetting("Golden apple",
			"Normal food allows gapples; Emergency only uses them at the health threshold.",
			"Emergency only", "Never", "Normal food", "Emergency only"));
	public final BooleanSetting preferOffhand = add(new BooleanSetting("Prefer offhand",
			"Choose an equally suitable offhand food before a hotbar stack", false));
	public final NumberSetting minimumReserve = add(new NumberSetting("Minimum stack reserve",
			"Do not consume the last items in a stack", 0, 0, 32, 1));
	public final BooleanSetting doNotStealSelected = add(new BooleanSetting("Do not steal selected slot",
			"Leave the currently selected hotbar slot alone when possible", true));
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
		super("AutoEat", "Eats automatically when you get hungry", Category.PLAYER, ServerVisibility.CONDITIONAL);
	}

	/** True while we're holding the use key to eat. Interact modules should stand down. */
	public boolean isEating() {
		return eating;
	}

	/** True while eating <em>or</em> about to: the window in which nothing else may take the hand. */
	public boolean isClaimed() {
		return eating || claim > 0;
	}

	/**
	 * Hungry is not a state the server can read. Only the meal itself is observable — a held
	 * use key and a hotbar selection you did not make — so Panic Minimal cuts a meal in
	 * progress and otherwise leaves the module armed. {@link #onDisable()} is the right
	 * shutdown either way: it lets go of the key and puts your slot back.
	 */
	@Override
	public boolean isServerObservableNow() {
		return isClaimed();
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

	/**
	 * Does eating <em>this stack</em> hurt you, or move you?
	 *
	 * <p>Replaces the seven ids this module used to ship as a default blacklist. A written
	 * list is the wrong shape twice over: it silently misses whatever a new version adds,
	 * and it misses every modded food no matter what. The rule instead asks the game the
	 * question a player would — does eating this apply something the game itself files under
	 * {@link MobEffectCategory#HARMFUL}, or does it move me? Chorus fruit is not poisonous;
	 * it is just the last thing you want auto-eaten at the edge of a build.
	 *
	 * <p><b>The stack, not the item, and at eat time rather than at startup.</b> That is
	 * forced and it is also better. Forced, because 26.2 binds item components only once a
	 * world has synced its registries — {@code item.components()} throws "Components not
	 * bound yet" during client init, which is when a setting's default would have to be
	 * computed (see {@code ItemUtil}). Better, because a stack answers for itself: suspicious
	 * stew carries its effects in {@code SUSPICIOUS_STEW_EFFECTS} on the stack, so the one
	 * food that no item-level rule could ever classify is read exactly, bowl by bowl, instead
	 * of being named and assumed.
	 *
	 * <p>Probability is deliberately not weighed. Raw chicken only makes you hungry three
	 * times in ten, which is an argument for eating it in a pinch and none at all for
	 * reaching for it by default.
	 */
	public static boolean harmful(ItemStack stack) {
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable != null) {
			for (ConsumeEffect effect : consumable.onConsumeEffects()) {
				if (effect instanceof TeleportRandomlyConsumeEffect) {
					return true;
				}
				if (effect instanceof ApplyStatusEffectsConsumeEffect apply) {
					for (MobEffectInstance instance : apply.effects()) {
						if (isHarmful(instance.getEffect())) {
							return true;
						}
					}
				}
			}
		}

		SuspiciousStewEffects stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
		if (stew != null) {
			for (SuspiciousStewEffects.Entry entry : stew.effects()) {
				if (isHarmful(entry.effect())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isHarmful(Holder<MobEffect> effect) {
		return effect.value().getCategory() == MobEffectCategory.HARMFUL;
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
			if (player.getFoodData().getFoodLevel() >= stopAtHunger.getInt()
					|| !edible(player, player.getItemInHand(eatingHand))) {
				stop();
				return;
			}
			// Renew the hold. Losing it means something that outranks a meal wants the hand;
			// ending here is the only way that does not leave two owners of one key.
			if (!InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_SURVIVAL,
					InputActionCoordinator.Key.USE)) {
				stop();
				retry = RETRY_TICKS;
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
		if (!shouldEat(player) || player.isUsingItem()) {
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
		// Take the key before touching the hotbar: a refused hold with the slot already changed
		// would be a swap made for a meal that never happens.
		if (!InputActionCoordinator.hold(this, InputActionCoordinator.PRIORITY_SURVIVAL,
				InputActionCoordinator.Key.USE)) {
			claim = 0;
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
			// Nothing was held, but a request made earlier in this same tick still needs
			// dropping — onDisable and the null-player path both arrive here.
			InputActionCoordinator.release(this, InputActionCoordinator.Key.USE);
			return;
		}
		eating = false;
		started = false;
		blocked = 0;
		InputActionCoordinator.release(this, InputActionCoordinator.Key.USE);
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
			if (!edible(player, stack) || stack.getCount() <= minimumReserve.getInt()) {
				continue;
			}
			if (doNotStealSelected.get() && slot == player.getInventory().getSelectedSlot() && best != null) continue;
			if (first) {
				return new Choice(InteractionHand.MAIN_HAND, slot);
			}
			float score = score(player, stack);
			if (score > bestScore) {
				bestScore = score;
				best = new Choice(InteractionHand.MAIN_HAND, slot);
			}
		}
		if (edible(player, player.getOffhandItem()) && player.getOffhandItem().getCount() > minimumReserve.getInt()
				&& (preferOffhand.get() || first || best == null || score(player, player.getOffhandItem()) > bestScore)) {
			return new Choice(InteractionHand.OFF_HAND, -1);
		}
		return best;
	}

	/**
	 * Edible, not harmful, not on our blacklist, and not a gapple we're told to save.
	 *
	 * <p>The single funnel every candidate passes through — which is why the harmful check
	 * lives here and not in a list built at startup. It has the stack, and it only ever runs
	 * in a world.
	 */
	private boolean edible(LocalPlayer player, ItemStack stack) {
		if (stack.isEmpty() || !isFood(stack.getItem()) || blacklist.contains(stack.getItem())) {
			return false;
		}
		if (skipHarmful.get() && harmful(stack)) {
			return false;
		}
		if (!isGapple(stack.getItem())) return true;
		if (ignoreGapples.get() || goldenApples.is("Never")) return false;
		return goldenApples.is("Normal food") || player.getHealth() + player.getAbsorptionAmount() <= healthThreshold.get();
	}

	private boolean shouldEat(LocalPlayer player) {
		boolean hunger = player.getFoodData().getFoodLevel() <= threshold.getInt();
		boolean health = player.getHealth() + player.getAbsorptionAmount() <= healthThreshold.get();
		return switch (triggerMode.get()) {
			case "Hunger" -> hunger;
			case "Health" -> health;
			case "Both" -> hunger && health;
			default -> hunger || health;
		};
	}

	/** Scores saturation, nutrition, or how little surplus food would be consumed. */
	private float score(LocalPlayer player, ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null) return 0.0f;
		if (prefer.is("Best hunger")) return food.nutrition();
		if (prefer.is("Least waste")) {
			int missing = Math.max(0, stopAtHunger.getInt() - player.getFoodData().getFoodLevel());
			return -Math.max(0, food.nutrition() - missing) * 100 + food.nutrition();
		}
		return food.saturation() * 4.0f + food.nutrition();
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
