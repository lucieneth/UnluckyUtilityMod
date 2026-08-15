package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InventoryPolicy;
import unlucky.utility.client.util.RecipeAutomation;

/**
 * Crafts from the recipe book, in a menu you opened.
 *
 * <p><b>It never opens anything, and it never moves you.</b> The module works the crafting grid
 * that is already in front of you and stops the moment it is not. That is the line between
 * automation the player is present for and a bot that walks to a table — and it is a line worth
 * drawing in code rather than in a README, so there is no "find a crafting table" here to be
 * tempted by later.
 *
 * <p><b>Placement goes through vanilla's own recipe-book action.</b> {@code handlePlaceRecipe} is
 * the same call the recipe book's own click makes, so the server fills the grid exactly as it does
 * for a human and this module never has to know a recipe's shape. Reimplementing grid-filling
 * would mean getting shaped recipes, tag ingredients and remainders right, and getting any of them
 * wrong destroys inventory.
 *
 * <p><b>Sequential crafting is cycle-checked.</b> A list of "planks, then sticks, then planks"
 * feeds itself forever and looks like it is working the whole time. The output of every craft in a
 * sequential run is tracked, and a recipe that consumes something an earlier one produced while
 * producing something it consumed is a stop with a message, not a silent loop.
 */
public class AutoCraft extends Module {
	public final ItemListSetting itemsToCraft = add(new ItemListSetting("Items to craft",
			"Outputs to make, in preference order — right-click to pick", item -> true));
	public final ModeSetting recipePriority = add(new ModeSetting("Recipe priority",
			"List order takes the first thing it can make; Most craftable takes whichever it "
					+ "has the most ingredients for", "List order", "List order", "Most craftable"));
	public final ModeSetting craftMode = add(new ModeSetting("Craft mode",
			"One at a time, or as many as fit in a stack per action", "Single", "Single", "Stack"));
	public final BooleanSetting sequential = add(new BooleanSetting("Sequential crafting",
			"Let one recipe's output feed another on the list", false));

	public final BooleanSetting playerInventory = add(new BooleanSetting("Player inventory",
			"Work the 2x2 grid in your own inventory", true));
	public final BooleanSetting craftingTable = add(new BooleanSetting("Crafting table",
			"Work an open crafting table", true));

	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between crafts", 2, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks", 1, 0, 10, 1));
	public final NumberSetting maximumCrafts = add(new NumberSetting("Maximum crafts",
			"Stop after this many; 0 means until something else stops it", 0, 0, 10000, 1));

	public final ModeSetting onFullInventory = add(new ModeSetting("On full inventory",
			"What to do when the output has nowhere to go", "Wait",
			"Wait", "Close", "Disable", "Drop output"));
	public final BooleanSetting stopOutOfIngredients = add(new BooleanSetting("Stop out of ingredients",
			"Stop when nothing on the list can be made", true));
	public final BooleanSetting autoClose = add(new BooleanSetting("Auto close",
			"Close the menu once it is finished", false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();
	public final BooleanSetting protectReserved = add(new BooleanSetting("Protect reserved items",
			"Never consume armour, the offhand, or anything InventoryPolicy is keeping", true));
	public final BooleanSetting queueStatus = add(new BooleanSetting("Queue status",
			"Report the current output and why it stopped", true));

	private final RecipeAutomation automation = new RecipeAutomation(this);
	private final StackedItemContents contents = new StackedItemContents();

	/** Outputs produced during a sequential run, for the cycle check. */
	private final Set<net.minecraft.world.item.Item> produced = new HashSet<>();
	/** What is currently being made, for the status line. */
	private String current = "";
	private RecipeAutomation.Stop lastReported = RecipeAutomation.Stop.NONE;

	public AutoCraft() {
		super("AutoCraft", "Crafts recipe-book items in an open crafting menu", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		automation.reset();
		produced.clear();
		current = "";
		lastReported = RecipeAutomation.Stop.NONE;
	}

	@Override
	protected void onDisable() {
		automation.reset();
		produced.clear();
		current = "";
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || itemsToCraft.get().isEmpty()) {
			// Nothing configured is a full stop, not a pause: resetting drops the menu the status
			// line reads, so it reports idle rather than carrying the last session's "working".
			automation.reset();
			return;
		}
		RecipeAutomation.Options options = new RecipeAutomation.Options(this::supported,
				delay.getInt(), randomDelay.getInt(), maximumCrafts.getInt(), pauseOnEat.get());
		if (!automation.beginTick(options)) {
			report();
			if (automation.terminal() && autoClose.get()) {
				close(player);
			}
			return;
		}
		if (!(automation.menu() instanceof AbstractCraftingMenu menu)) {
			automation.stop(RecipeAutomation.Stop.NO_MENU);
			return;
		}

		// Collect the previous craft's result before making another. Leaving it in the result
		// slot is what makes the next craft silently do nothing.
		ItemStack result = menu.getResultSlot().getItem();
		if (!result.isEmpty()) {
			if (!RecipeAutomation.hasRoomFor(result) && !handleFull(player)) {
				report();
				return;
			}
			automation.quickMove(menu.getResultSlot().index);
			return;
		}

		RecipeDisplayEntry choice = choose(player);
		if (choice == null) {
			automation.stop(stopOutOfIngredients.get()
					? RecipeAutomation.Stop.NO_INGREDIENTS
					: RecipeAutomation.Stop.NONE);
			report();
			if (stopOutOfIngredients.get() && autoClose.get()) {
				close(player);
			}
			return;
		}
		place(player, menu, choice);
		report();
	}

	/** Whether this menu is one the player enabled and one we know how to work. */
	private boolean supported(AbstractContainerMenu menu) {
		if (menu instanceof CraftingMenu) {
			return craftingTable.get();
		}
		return menu instanceof InventoryMenu && playerInventory.get();
	}

	/**
	 * The recipe to make next, or null.
	 *
	 * <p>Craftability is asked of vanilla's own {@link StackedItemContents}, filled from the
	 * player's inventory — the same accounting the recipe book's own "can I make this" highlight
	 * uses. Counting ingredients by hand would disagree with the server about tags and remainders,
	 * and a craft the server refuses is a click that does nothing forever.
	 */
	private RecipeDisplayEntry choose(LocalPlayer player) {
		contents.clear();
		fillContents(player);
		ContextMap context = SlotDisplayContext.fromLevel(mc().level);

		RecipeDisplayEntry best = null;
		int bestCount = -1;
		for (var collection : player.getRecipeBook().getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				List<ItemStack> results = entry.resultItems(context);
				if (results.isEmpty()) {
					continue;
				}
				ItemStack output = results.getFirst();
				if (!wanted(output) || !entry.canCraft(contents)) {
					continue;
				}
				if (sequential.get() && feedsItself(entry, output, context)) {
					automation.stop(RecipeAutomation.Stop.CYCLE);
					ChatUtil.info("§eAutoCraft stopped: "
							+ output.getHoverName().getString() + " consumes what it produces.");
					setEnabled(false);
					return null;
				}
				if (recipePriority.is("List order")) {
					current = output.getHoverName().getString();
					return entry;
				}
				int count = output.getCount();
				if (count > bestCount) {
					bestCount = count;
					best = entry;
					current = output.getHoverName().getString();
				}
			}
		}
		return best;
	}

	/**
	 * Fills the ingredient accounting, minus anything reserved.
	 *
	 * <p>Reserved is the interesting half: without it a list containing "sticks" happily eats the
	 * planks another module is placing and the tool in your hand, which is technically what was
	 * asked for and never what was meant.
	 *
	 * <p>Worn armour and the offhand need no test — in 26.2 they are not {@code Inventory} slots
	 * at all, so a walk over 0..35 cannot reach them. That is {@link InventoryPolicy}'s note and
	 * the reason this loop is as short as it is.
	 */
	private void fillContents(LocalPlayer player) {
		if (!protectReserved.get()) {
			player.getInventory().fillStackedContents(contents);
			return;
		}
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (InventoryPolicy.equipped(slot, inventory, InventoryPolicy.Rules.SAFE_DEFAULT)) {
				continue;
			}
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				contents.accountStack(stack);
			}
		}
	}

	/**
	 * Whether a recipe consumes something an earlier craft in this run produced.
	 *
	 * <p>Only meaningful in sequential mode, which is the only mode where one craft's output is
	 * allowed to become another's input — and therefore the only mode where a cycle can form.
	 */
	private boolean feedsItself(RecipeDisplayEntry entry, ItemStack output, ContextMap context) {
		if (produced.isEmpty()) {
			return false;
		}
		var requirements = entry.craftingRequirements();
		if (requirements.isEmpty()) {
			return false;
		}
		for (var ingredient : requirements.get()) {
			for (var held : produced) {
				if (ingredient.acceptsItem(held.builtInRegistryHolder())
						&& output.is(held)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Sends vanilla's place-recipe action, then charges the budget. */
	private void place(LocalPlayer player, AbstractCraftingMenu menu, RecipeDisplayEntry entry) {
		if (!automation.canAct() || mc().gameMode == null) {
			return;
		}
		mc().gameMode.handlePlaceRecipe(menu.containerId, entry.id(), craftMode.is("Stack"));
		automation.spend();
		if (sequential.get()) {
			ContextMap context = SlotDisplayContext.fromLevel(mc().level);
			List<ItemStack> results = entry.resultItems(context);
			if (!results.isEmpty()) {
				produced.add(results.getFirst().getItem());
			}
		}
	}

	/**
	 * The inventory-full policy.
	 *
	 * @return whether crafting may continue this tick
	 */
	private boolean handleFull(LocalPlayer player) {
		automation.stop(RecipeAutomation.Stop.INVENTORY_FULL);
		switch (onFullInventory.get()) {
			case "Close" -> close(player);
			case "Disable" -> setEnabled(false);
			case "Drop output" -> {
				// Throwing the result is the one action here that loses items on purpose, so it
				// is never a default and never a fallback — only this explicit mode.
				if (automation.menu() instanceof AbstractCraftingMenu menu) {
					automation.click(menu.getResultSlot().index, 1,
							net.minecraft.world.inventory.ContainerInput.THROW);
				}
			}
			default -> {
			}
		}
		return false;
	}

	private void close(LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	private boolean wanted(ItemStack output) {
		return !output.isEmpty() && itemsToCraft.contains(output.getItem());
	}

	/** One message per change of reason, so a stalled machine says so exactly once. */
	private void report() {
		RecipeAutomation.Stop stop = automation.stopReason();
		if (!queueStatus.get() || stop == lastReported) {
			return;
		}
		lastReported = stop;
		if (stop == RecipeAutomation.Stop.NONE || stop == RecipeAutomation.Stop.NO_MENU) {
			return;
		}
		ChatUtil.info("§eAutoCraft: " + automation.status()
				+ (current.isEmpty() ? "" : " (" + current + ")"));
	}

	/** The status line, for the read-out. */
	public String status() {
		List<String> parts = new ArrayList<>(2);
		if (!current.isEmpty()) {
			parts.add(current);
		}
		parts.add(automation.status());
		return String.join(" — ", parts);
	}
}
