package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.EquipmentScorer;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.InventoryPolicy;
import unlucky.utility.client.util.MoveUtil;

/**
 * Sorts your bag, and throws away only what you explicitly allowed it to.
 *
 * <p><b>The default configuration performs zero throw actions, and that is a design commitment
 * rather than a cautious default.</b> A cleaner is one classification bug away from throwing
 * something you cannot replace, so nothing is discarded unless the excess action is set to Drop
 * <em>and</em> a cap is exceeded, or the item is on the drop list by name. Everything else is
 * moving stacks around inside an inventory you already own.
 *
 * <p><b>The plan is built before anything happens, and it is the same plan Preview shows.</b>
 * One pure function produces an ordered list of actions; execution walks it one action per tick
 * and Preview prints it. That is the only way "it showed me one thing and did another" can be
 * ruled out rather than merely not observed.
 *
 * <p>What counts as protected, useful, capped or excess is {@link InventoryPolicy}'s answer, so
 * this module and ChestCleaner cannot disagree about whether your named pickaxe is disposable.
 */
public class InventoryCleaner extends Module {
	/** The nine hotbar slots, each with a job the layout pass tries to fill. */
	private static final String[] LAYOUT_CHOICES = {
			"Preserve", "Sword", "Axe", "Pickaxe", "Bow", "Blocks", "Food", "Pearls", "Crystals",
			"Totems", "Gapples", "Any weapon", "Any tool", "Empty"
	};

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Sort only never throws anything", "Sort only",
			"Sort only", "Cleanup only", "Sort and cleanup"));
	public final ModeSetting runCondition = add(new ModeSetting("Run condition",
			"Which open menu lets it work", "Inventory open",
			"Inventory open", "Any supported menu"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between clicks", 2, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks on top", 1, 0, 10, 1));
	public final BooleanSetting pauseWhileMoving = add(new BooleanSetting("Pause while moving",
			"Yield while movement input is held", true));

	public final ModeSetting slot1 = add(layout("Hotbar slot 1", "Preserve"));
	public final ModeSetting slot2 = add(layout("Hotbar slot 2", "Preserve"));
	public final ModeSetting slot3 = add(layout("Hotbar slot 3", "Preserve"));
	public final ModeSetting slot4 = add(layout("Hotbar slot 4", "Preserve"));
	public final ModeSetting slot5 = add(layout("Hotbar slot 5", "Preserve"));
	public final ModeSetting slot6 = add(layout("Hotbar slot 6", "Preserve"));
	public final ModeSetting slot7 = add(layout("Hotbar slot 7", "Preserve"));
	public final ModeSetting slot8 = add(layout("Hotbar slot 8", "Preserve"));
	public final ModeSetting slot9 = add(layout("Hotbar slot 9", "Preserve"));
	public final BooleanSetting greedyLayout = add(new BooleanSetting("Greedy layout",
			"Fill a missing category with the next-best valid item", false));

	public final BooleanSetting mergeStacks = add(new BooleanSetting("Merge stacks",
			"Consolidate partial stacks before anything else", true));
	public final BooleanSetting protectNamed = add(new BooleanSetting("Protect named items",
			"Never discard custom-named stacks", true));
	public final BooleanSetting protectEnchanted = add(new BooleanSetting("Protect enchanted items",
			"Never discard enchanted stacks unless they are explicitly on the drop list", true));
	public final BooleanSetting protectEquipped = add(new BooleanSetting("Protect equipped/hotbar",
			"Keep the held slot and every slot the layout has assigned a job", true));
	public final ItemListSetting keepList = add(new ItemListSetting("Keep list",
			"Always keep — beats caps and every automatic judgement", item -> true));
	public final ItemListSetting dropList = add(new ItemListSetting("Drop list",
			"Explicitly disposable, but never beats the keep list", item -> true));

	public final ModeSetting excessAction = add(new ModeSetting("Excess action",
			"What to do with items over a cap", "Keep", "Keep", "Drop"));
	public final NumberSetting maxBlocks = add(new NumberSetting("Maximum blocks",
			"Block cap", 512, 0, 2304, 8), () -> excessAction.is("Drop"));
	public final NumberSetting maxArrows = add(new NumberSetting("Maximum arrows",
			"Normal arrows only", 128, 0, 2304, 8), () -> excessAction.is("Drop"));
	public final NumberSetting maxThrowables = add(new NumberSetting("Maximum throwables",
			"Snowballs and eggs", 64, 0, 2304, 8), () -> excessAction.is("Drop"));
	public final NumberSetting maxFood = add(new NumberSetting("Maximum food points",
			"Sum of nutrition, not stack count", 512, 0, 10000, 16), () -> excessAction.is("Drop"));
	public final NumberSetting maxPearls = add(new NumberSetting("Maximum pearls",
			"Ender pearl cap", 64, 0, 2304, 8), () -> excessAction.is("Drop"));
	public final NumberSetting maxCrystals = add(new NumberSetting("Maximum crystals",
			"End crystal cap", 128, 0, 2304, 8), () -> excessAction.is("Drop"));
	public final NumberSetting maxTotems = add(new NumberSetting("Maximum totems",
			"Totem cap", 8, 0, 64, 1), () -> excessAction.is("Drop"));
	public final NumberSetting maxGapples = add(new NumberSetting("Maximum gapples",
			"Golden and enchanted golden apples", 64, 0, 2304, 8), () -> excessAction.is("Drop"));

	public final ModeSetting lowDurabilityTools = add(new ModeSetting("Low-durability tools",
			"What to do with a nearly broken tool", "Keep", "Keep", "Move out of hotbar", "Drop"));

	public final ActionSetting preview = add(new ActionSetting("Preview",
			"Report the planned moves and drops without doing any of them", this::printPreview));

	/** One step of the plan. */
	private record Step(Kind kind, int from, int to, int count, String what) {
	}

	private enum Kind {
		MERGE,
		MOVE,
		DROP
	}

	private int delayTicks;

	public InventoryCleaner() {
		super("InventoryCleaner", "Sorts your inventory and discards only what you allowed",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	private static ModeSetting layout(String name, String initial) {
		return new ModeSetting(name, "What this hotbar slot is for", initial, LAYOUT_CHOICES);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		delayTicks = 0;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			onDisable();
			return;
		}
		if (delayTicks > 0) {
			delayTicks--;
			return;
		}
		if (!allowed(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		List<Step> plan = plan(player);
		if (plan.isEmpty()) {
			InventoryActionCoordinator.release(this);
			return;
		}
		// The plan is rebuilt every tick and only its first step is executed. That is slower
		// than caching it and far more honest: after one click the inventory is a different
		// inventory, and a cached plan is a set of instructions about a state that no longer
		// exists — which is exactly how a cleaner throws the wrong stack.
		if (execute(player, plan.get(0))) {
			delayTicks = delay.getInt()
					+ (randomDelay.getInt() > 0 ? (int) (Math.random() * (randomDelay.getInt() + 1)) : 0);
		}
	}

	private boolean allowed(LocalPlayer player) {
		if (runCondition.is("Inventory open")
				? player.containerMenu != player.inventoryMenu
				: player.containerMenu == null) {
			return false;
		}
		return !pauseWhileMoving.get() || !MoveUtil.hasInput(player);
	}

	// ---- the plan ----------------------------------------------------------

	/**
	 * The ordered action plan for the inventory as it stands right now.
	 *
	 * <p>Pure: it reads the inventory and returns steps, and nothing in here clicks. Preview and
	 * execution both call it, which is what makes them provably the same plan rather than two
	 * implementations that agree today.
	 *
	 * <p>Ordered merge → layout → cleanup on purpose. Merging first means the layout pass sees
	 * whole stacks rather than three partial ones, and the cleanup pass counts against the caps
	 * once rather than per fragment.
	 *
	 * <p>The two sorting phases contribute at most one step each, because every click leaves a
	 * different inventory and the step after it would be planned against a state that no longer
	 * exists. Cleanup contributes all of them: which stacks are over a cap is one classification
	 * of the whole inventory, and dropping one does not change the verdict on another. That is
	 * also what makes Preview useful — the throws are the part worth checking before it happens.
	 */
	private List<Step> plan(LocalPlayer player) {
		List<Step> steps = new ArrayList<>();
		Inventory inventory = player.getInventory();
		InventoryPolicy.Rules rules = rules();
		Map<Integer, InventoryPolicy.Entry> verdicts = InventoryPolicy.classify(inventory, rules);

		if (mergeStacks.get() && !mode.is("Cleanup only")) {
			planMerges(inventory, steps);
		}
		if (!mode.is("Cleanup only")) {
			planLayout(inventory, verdicts, steps);
		}
		if (!mode.is("Sort only")) {
			planCleanup(inventory, verdicts, steps);
		}
		return steps;
	}

	/** Pours a partial stack into another partial stack of the same thing. */
	private void planMerges(Inventory inventory, List<Step> steps) {
		for (int source = 0; source < Inventory.INVENTORY_SIZE; source++) {
			ItemStack from = inventory.getItem(source);
			if (from.isEmpty() || from.getCount() >= from.getMaxStackSize()) {
				continue;
			}
			for (int target = 0; target < Inventory.INVENTORY_SIZE; target++) {
				ItemStack to = inventory.getItem(target);
				if (target == source || to.isEmpty() || to.getCount() >= to.getMaxStackSize()
						|| !ItemStack.isSameItemSameComponents(from, to)) {
					continue;
				}
				steps.add(new Step(Kind.MERGE, source, target, from.getCount(), name(from)));
				return; // one at a time: the next tick re-reads a changed inventory
			}
		}
	}

	/**
	 * Moves the right item into each hotbar slot that has been given a job.
	 *
	 * <p>A slot set to Preserve is left entirely alone, which is the default for all nine — the
	 * player who has not configured a layout has not asked for their hotbar to be rearranged.
	 */
	private void planLayout(Inventory inventory, Map<Integer, InventoryPolicy.Entry> verdicts,
			List<Step> steps) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			String want = layoutFor(slot);
			if (want.equals("Preserve")) {
				continue;
			}
			ItemStack current = inventory.getItem(slot);
			if (want.equals("Empty")) {
				if (!current.isEmpty() && !isProtected(verdicts, slot)) {
					int free = firstFreeMainSlot(inventory);
					if (free >= 0) {
						steps.add(new Step(Kind.MOVE, slot, free, current.getCount(), name(current)));
						return;
					}
				}
				continue;
			}
			if (matchesCategory(current, want)) {
				continue;
			}
			int source = findCategory(inventory, want, slot);
			if (source >= 0) {
				steps.add(new Step(Kind.MOVE, source, slot, inventory.getItem(source).getCount(),
						name(inventory.getItem(source))));
				return;
			}
		}
		if (lowDurabilityTools.is("Move out of hotbar")) {
			planToolEviction(inventory, verdicts, steps);
		}
	}

	/** Gets a nearly-broken tool out of the hotbar, where it is one click from being used. */
	private void planToolEviction(Inventory inventory, Map<Integer, InventoryPolicy.Entry> verdicts,
			List<Step> steps) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || isProtected(verdicts, slot)
					|| !EquipmentScorer.nearlyBroken(stack, 10)) {
				continue;
			}
			int free = firstFreeMainSlot(inventory);
			if (free >= 0) {
				steps.add(new Step(Kind.MOVE, slot, free, stack.getCount(), name(stack)));
				return;
			}
		}
	}

	/**
	 * The throws, and only the throws the rules explicitly permit.
	 *
	 * <p>Excess is dropped partially — the count the policy says is over the cap, not the whole
	 * stack. Dropping all 64 when 12 were over is how a cleaner costs you half a shulker.
	 */
	private void planCleanup(Inventory inventory, Map<Integer, InventoryPolicy.Entry> verdicts,
			List<Step> steps) {
		for (Map.Entry<Integer, InventoryPolicy.Entry> entry : verdicts.entrySet()) {
			InventoryPolicy.Entry verdict = entry.getValue();
			if (!verdict.droppable()) {
				continue;
			}
			if (verdict.verdict() == InventoryPolicy.Verdict.EXCESS && !excessAction.is("Drop")) {
				continue;
			}
			ItemStack stack = inventory.getItem(entry.getKey());
			int count = Math.min(stack.getCount(), Math.max(1, verdict.excess()));
			steps.add(new Step(Kind.DROP, entry.getKey(), -1, count, name(stack)));
		}
		if (lowDurabilityTools.is("Drop")) {
			for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!stack.isEmpty() && !isProtected(verdicts, slot)
						&& EquipmentScorer.nearlyBroken(stack, 10)) {
					steps.add(new Step(Kind.DROP, slot, -1, stack.getCount(), name(stack)));
				}
			}
		}
	}

	private InventoryPolicy.Rules rules() {
		Set<Integer> reserved = new HashSet<>();
		if (protectEquipped.get()) {
			for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
				if (!layoutFor(slot).equals("Preserve") && !layoutFor(slot).equals("Empty")) {
					reserved.add(slot);
				}
			}
		}
		return new InventoryPolicy.Rules(items(keepList), items(dropList),
				new InventoryPolicy.Caps(maxBlocks.getInt(), maxArrows.getInt(),
						maxThrowables.getInt(), maxFood.getInt(), maxPearls.getInt(),
						maxCrystals.getInt(), maxTotems.getInt(), maxGapples.getInt()),
				reserved, protectNamed.get(), protectEnchanted.get(), protectEquipped.get(),
				excessAction.is("Drop"));
	}

	private static Set<Item> items(ItemListSetting setting) {
		Set<Item> out = new HashSet<>();
		for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (setting.contains(item)) {
				out.add(item);
			}
		}
		return out;
	}

	private static boolean isProtected(Map<Integer, InventoryPolicy.Entry> verdicts, int slot) {
		InventoryPolicy.Entry entry = verdicts.get(slot);
		return entry != null && entry.verdict() == InventoryPolicy.Verdict.PROTECTED;
	}

	// ---- categories --------------------------------------------------------

	private String layoutFor(int slot) {
		return switch (slot) {
			case 0 -> slot1.get();
			case 1 -> slot2.get();
			case 2 -> slot3.get();
			case 3 -> slot4.get();
			case 4 -> slot5.get();
			case 5 -> slot6.get();
			case 6 -> slot7.get();
			case 7 -> slot8.get();
			default -> slot9.get();
		};
	}

	private int findCategory(Inventory inventory, String category, int exclude) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (slot == exclude) {
				continue;
			}
			if (matchesCategory(inventory.getItem(slot), category)) {
				return slot;
			}
		}
		if (!greedyLayout.get()) {
			return -1;
		}
		// Greedy: the next-best valid item for a broad category, so a slot asking for "any
		// weapon" is filled by an axe when there is no sword rather than left empty.
		return switch (category) {
			case "Sword" -> findCategory(inventory, "Any weapon", exclude);
			case "Pickaxe", "Axe" -> findCategory(inventory, "Any tool", exclude);
			default -> -1;
		};
	}

	private static boolean matchesCategory(ItemStack stack, String category) {
		if (stack.isEmpty()) {
			return category.equals("Empty");
		}
		Item item = stack.getItem();
		return switch (category) {
			case "Sword" -> CombatItemUtil.isSword(stack);
			case "Axe" -> CombatItemUtil.isAxe(stack);
			case "Pickaxe" -> stack.is(ItemTags.PICKAXES);
			case "Bow" -> item == Items.BOW || item == Items.CROSSBOW;
			case "Blocks" -> item instanceof BlockItem;
			case "Food" -> stack.has(net.minecraft.core.component.DataComponents.FOOD)
					&& item != Items.GOLDEN_APPLE && item != Items.ENCHANTED_GOLDEN_APPLE;
			case "Pearls" -> item == Items.ENDER_PEARL;
			case "Crystals" -> item == Items.END_CRYSTAL;
			case "Totems" -> item == Items.TOTEM_OF_UNDYING;
			case "Gapples" -> item == Items.GOLDEN_APPLE || item == Items.ENCHANTED_GOLDEN_APPLE;
			case "Any weapon" -> CombatItemUtil.isMeleeWeapon(stack);
			case "Any tool" -> stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES)
					|| stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
			default -> false;
		};
	}

	private static int firstFreeMainSlot(Inventory inventory) {
		for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	private static String name(ItemStack stack) {
		return stack.isEmpty() ? "nothing" : stack.getHoverName().getString();
	}

	// ---- execution ---------------------------------------------------------

	private boolean execute(LocalPlayer player, Step step) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_CLEANER)) {
			return false;
		}
		AbstractContainerMenu menu = player.inventoryMenu;
		boolean done = switch (step.kind()) {
			case MERGE, MOVE -> InventoryActionCoordinator.pickupMove(this, menu,
					menuSlot(step.from()), menuSlot(step.to()));
			// Button 1 throws the whole stack, button 0 throws one. Anything short of the whole
			// stack is spelled out click by click rather than guessed at.
			case DROP -> throwCount(menu, step);
		};
		InventoryActionCoordinator.release(this);
		return done;
	}

	private boolean throwCount(AbstractContainerMenu menu, Step step) {
		int slot = menuSlot(step.from());
		ItemStack stack = menu.getSlot(slot).getItem();
		if (stack.getCount() <= step.count()) {
			return InventoryActionCoordinator.click(this, menu, slot, 1, ContainerInput.THROW);
		}
		return InventoryActionCoordinator.click(this, menu, slot, 0, ContainerInput.THROW);
	}

	/** Inventory index to player-menu slot: hotbar 0-8 sits at 36-44, the rest is 1:1. */
	private static int menuSlot(int inventorySlot) {
		return inventorySlot < Inventory.SELECTION_SIZE ? 36 + inventorySlot : inventorySlot;
	}

	// ---- preview -----------------------------------------------------------

	/**
	 * Prints the plan that execution would follow, in order, without doing any of it.
	 *
	 * <p>Built by the same function execution walks, so this is not a description of the plan —
	 * it is the plan. The sorting steps shown are the next one of each kind; every throw is
	 * listed, which is the part worth reading before it happens.
	 */
	private void printPreview() {
		LocalPlayer player = mc().player;
		if (player == null) {
			ChatUtil.info("Join a world first.");
			return;
		}
		List<Step> plan = plan(player);
		if (plan.isEmpty()) {
			ChatUtil.info("InventoryCleaner: nothing to do.");
			return;
		}
		ChatUtil.info("InventoryCleaner would:");
		for (Step step : plan) {
			ChatUtil.info(switch (step.kind()) {
				case MERGE -> "  merge " + step.what() + " from slot " + step.from()
						+ " into slot " + step.to();
				case MOVE -> "  move " + step.what() + " from slot " + step.from()
						+ " to slot " + step.to();
				case DROP -> "  §cdrop§r " + step.count() + "x " + step.what()
						+ " from slot " + step.from();
			});
		}
	}
}
