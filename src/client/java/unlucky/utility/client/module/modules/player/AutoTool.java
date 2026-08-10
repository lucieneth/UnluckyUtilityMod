package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.mixin.MultiPlayerGameModeAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.BlockGroups;
import unlucky.utility.client.util.InventoryActionCoordinator;

/**
 * Puts the right tool in your hand before the block notices.
 *
 * <p>Driven from {@code MultiPlayerGameModeMixin}'s destroy hooks rather than from a tick,
 * which is the difference between a tool that arrives for the block and one that arrives for
 * the block after it. Two consequences fall out of that choice and both are wanted: the switch
 * happens inside the same call that computes client-side destroy progress, so the client's own
 * arithmetic already sees the new tool; and it covers <em>every</em> break, including the ones
 * a module started — Nuker, the Printer's shulker, and VeinMiner when it lands — without any
 * of them having to ask.
 *
 * <p>The selection goes through {@link InventoryActionCoordinator}, and only when a switch is
 * actually needed. A module that grabs the hotbar every tick to put it back unchanged is a
 * module that spends the whole fight outranking AutoTotem for no reason.
 */
public class AutoTool extends Module {
	/** Hand speed. Anything at or below this is not a tool for the block in question. */
	private static final float HAND_SPEED = 1.0f;

	/**
	 * How much better a candidate must be before it is worth a switch at all.
	 *
	 * <p>Guards against two shovels of the same tier trading the slot back and forth on
	 * successive blocks, which costs a packet each way and gains nothing.
	 */
	private static final double SWITCH_MARGIN = 0.01;

	public final ModeSetting selection = add(new ModeSetting("Selection",
			"Fastest picks whatever breaks it soonest. Preserve durability picks the most "
					+ "worn-in tool that still does the job. Preferred enchant puts the enchant "
					+ "below ahead of raw speed.",
			"Fastest", "Fastest", "Preserve durability", "Preferred enchant"));
	public final ModeSetting prefer = add(new ModeSetting("Prefer",
			"The enchant to reach for", "None", "None", "Fortune", "Silk Touch"));
	public final BooleanSetting silkEnderChest = add(new BooleanSetting("Silk for ender chests",
			"Always use Silk Touch on an ender chest, whatever the mode says. Breaking one "
					+ "without it returns eight obsidian and no chest.", true));
	public final BooleanSetting fortuneOres = add(new BooleanSetting("Fortune for ores",
			"Prefer Fortune when the target is an ore", false));
	public final BooleanSetting antiBreak = add(new BooleanSetting("Anti-break",
			"Never pick a tool that is about to snap", true));
	public final NumberSetting minDurability = add(new NumberSetting("Min durability",
			"Percent of durability a tool must have left to be picked", 10, 0, 90, 1),
			antiBreak::get);
	public final BooleanSetting switchBack = add(new BooleanSetting("Switch back",
			"Return to the slot you were holding once the break is over", true));
	public final NumberSetting switchBackDelay = add(new NumberSetting("Switch-back delay",
			"Ticks to wait after mining stops before going back", 0, 0, 20, 1),
			switchBack::get);
	public final ModeSetting listMode = add(new ModeSetting("List mode",
			"Whether Tools names what to avoid or the only things allowed", "Blacklist",
			"Blacklist", "Whitelist"));
	public final ItemListSetting tools = add(new ItemListSetting("Tools",
			"Tools this module may never pick (Blacklist), or the only ones it may pick "
					+ "(Whitelist) — for keeping a good pickaxe out of its hands",
			AutoTool::isTool));
	public final BooleanSetting ignoreWhileUsing = add(new BooleanSetting("Ignore while using",
			"Stand down while you are eating, drawing a bow or blocking", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	/** Ticks left before the switch-back, counted only once mining has actually stopped. */
	private int restoreCountdown = -1;

	public AutoTool() {
		super("AutoTool", "Swaps to the best tool for the block you are breaking", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		restoreCountdown = -1;
	}

	/**
	 * Panic wants the tool put away now, not after the switch-back delay — and
	 * {@link #onDisable()} already does exactly that, so this exists only to cancel the
	 * countdown that would otherwise be sitting on a released lease.
	 */
	@Override
	protected void onPanic() {
		restoreCountdown = -1;
	}

	/**
	 * Called from the destroy hooks, before vanilla starts or continues the break.
	 *
	 * @param pos the block about to be hit
	 */
	public void onDestroy(BlockPos pos) {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			return;
		}
		if (AutoEat.pauses(pauseOnEat) || (ignoreWhileUsing.get() && player.isUsingItem())) {
			return;
		}
		BlockState state = mc().level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}

		restoreCountdown = -1; // still mining; the switch-back clock has not started
		int best = bestSlot(player, state);
		if (best < 0 || best == player.getInventory().getSelectedSlot()) {
			return;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_TOOL)) {
			return; // something more urgent owns the hotbar; mine with what we have
		}
		if (InventoryActionCoordinator.selectHotbar(this, best)) {
			// After the select, never before: selectHotbar is what records the slot to go back
			// to, so clearing it first would simply be undone by the very next call.
			if (!switchBack.get()) {
				InventoryActionCoordinator.keepHotbar(this);
			}
			// Get the selection onto the wire before the block action that follows it.
			((MultiPlayerGameModeAccessor) mc().gameMode).unlucky$ensureHasSentCarriedItem();
		}
	}

	/**
	 * Watches for the break ending, so the lease is given back rather than held for the rest
	 * of the session. Mining is the only thing that renews it; one tick of not mining starts
	 * the clock.
	 */
	@Override
	public void onTick() {
		if (!InventoryActionCoordinator.owns(this)) {
			restoreCountdown = -1;
			return;
		}
		if (mc().gameMode != null && mc().gameMode.isDestroying()) {
			restoreCountdown = -1;
			return;
		}
		if (restoreCountdown < 0) {
			restoreCountdown = switchBack.get() ? switchBackDelay.getInt() : 0;
		}
		if (restoreCountdown-- <= 0) {
			InventoryActionCoordinator.release(this); // restores the slot we started from
			restoreCountdown = -1;
		}
	}

	/** Best hotbar slot for {@code state}, or -1 when nothing beats the hand we are holding. */
	private int bestSlot(LocalPlayer player, BlockState state) {
		Inventory inventory = player.getInventory();
		int selected = inventory.getSelectedSlot();
		double currentScore = usable(inventory.getItem(selected), state)
				? score(inventory.getItem(selected), state)
				: Double.NEGATIVE_INFINITY;

		int best = -1;
		double bestScore = currentScore + SWITCH_MARGIN;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (slot == selected) {
				continue;
			}
			ItemStack stack = inventory.getItem(slot);
			if (!usable(stack, state)) {
				continue;
			}
			double candidate = score(stack, state);
			if (candidate > bestScore) {
				bestScore = candidate;
				best = slot;
			}
		}
		return best;
	}

	/** Whether a stack is allowed to be picked at all, before any question of how good it is. */
	private boolean usable(ItemStack stack, BlockState state) {
		if (stack.isEmpty() || stack.getDestroySpeed(state) <= HAND_SPEED) {
			return false;
		}
		if (listMode.is("Whitelist") ? !tools.contains(stack.getItem()) : tools.contains(stack.getItem())) {
			return false;
		}
		return !antiBreak.get() || durabilityPercent(stack) >= minDurability.get();
	}

	/**
	 * How good a tool is for this block, on one scale so the modes can be compared rather
	 * than branched around.
	 *
	 * <p>Speed is the base in every mode — a tool that cannot break the block is not a
	 * durability saving — and the mode decides what is added on top. The forced rules
	 * (ender chest, ores) come first and are worth more than any speed difference could be,
	 * because they change <em>what you get</em> and not how long you wait for it.
	 */
	private double score(ItemStack stack, BlockState state) {
		double speed = stack.getDestroySpeed(state);
		int efficiency = enchantLevel(stack, "efficiency");
		if (efficiency > 0) {
			speed += efficiency * efficiency + 1; // vanilla's own formula, see Player.getDestroySpeed
		}
		if (!stack.isCorrectToolForDrops(state)) {
			speed *= 0.5; // fast but dropless is rarely what was meant
		}

		double bonus = 0.0;
		if (silkEnderChest.get() && state.is(Blocks.ENDER_CHEST) && enchantLevel(stack, "silk_touch") > 0) {
			bonus += 10_000.0;
		}
		// Enchant first, block second: isOre builds a registry-id string, and there is no point
		// paying for it nine slots a tick to ask about tools that have no Fortune anyway.
		if (fortuneOres.get() && enchantLevel(stack, "fortune") > 0 && isOre(state)) {
			bonus += 5_000.0;
		}

		if (selection.is("Preserve durability")) {
			// Spend the tool that has least left to lose, as long as it still qualifies.
			bonus += 100.0 - durabilityPercent(stack);
		} else if (selection.is("Preferred enchant") && !prefer.is("None")) {
			int level = enchantLevel(stack, prefer.is("Fortune") ? "fortune" : "silk_touch");
			bonus += level * 1_000.0;
		}
		return speed + bonus;
	}

	/** Percent of durability remaining; unbreakable and undamageable items answer 100. */
	private static double durabilityPercent(ItemStack stack) {
		int max = stack.getMaxDamage();
		if (max <= 0) {
			return 100.0;
		}
		return 100.0 * (max - stack.getDamageValue()) / max;
	}

	/**
	 * Level of a vanilla enchantment by registry path.
	 *
	 * <p>Read off the stack rather than looked up through {@code EnchantmentHelper}, which
	 * wants a {@link Holder} and therefore a registry the client only has once a world has
	 * synced. The path comparison is the same trick {@code GearUtil} uses for enchant names,
	 * with the namespace checked too so a mod's own "efficiency" cannot be mistaken for the
	 * one whose formula is hardcoded above.
	 */
	private static int enchantLevel(ItemStack stack, String path) {
		for (var entry : stack.getEnchantments().entrySet()) {
			java.util.Optional<ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
			if (key.isPresent() && key.get().identifier().getNamespace().equals("minecraft")
					&& key.get().identifier().getPath().equals(path)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}

	private static boolean isOre(BlockState state) {
		return BlockGroups.ores().contains(
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
	}

	/**
	 * What the tool picker offers: anything that can be damaged, which is every tool and
	 * weapon and nothing that stacks. Asked of the item rather than listed, so modded tools
	 * appear without anybody adding them.
	 */
	static boolean isTool(Item item) {
		return item != Items.AIR && item.components().has(net.minecraft.core.component.DataComponents.MAX_DAMAGE);
	}
}
