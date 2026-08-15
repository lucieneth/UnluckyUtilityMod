package unlucky.utility.client.module.modules.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * Keeps a furnace you opened fed, and takes the output out.
 *
 * <p><b>The furnace has to be open already.</b> No searching, no walking, no placing, no refuelling
 * a furnace across the room. Same line as AutoCraft and for the same reason: this is a module that
 * saves you clicks in a menu you are standing in front of, not one that runs a smelting operation
 * while you are elsewhere.
 *
 * <p><b>Furnace-specific recipes are respected, and that is not cosmetic.</b> A blast furnace will
 * not smelt food and a smoker will not smelt ore; shovelling the wrong input in does not fail
 * loudly, it just sits there taking up the slot while the module reports that it is working. The
 * check comes from the recipe book — the client's only honest source for what a given furnace can
 * actually do — rather than from a hard-coded list that would go stale the first time a datapack
 * touched it.
 *
 * <p><b>The fuel reserve is the setting that stops it burning your building materials.</b> Coal is
 * also a trade good and a torch ingredient, and a module that feeds every last piece into a furnace
 * has technically done its job.
 */
public class AutoSmelt extends Module {
	public final BooleanSetting furnace = add(new BooleanSetting("Furnace",
			"Work an ordinary furnace", true));
	public final BooleanSetting blastFurnace = add(new BooleanSetting("Blast furnace",
			"Work a blast furnace", true));
	public final BooleanSetting smoker = add(new BooleanSetting("Smoker",
			"Work a smoker", true));

	public final ModeSetting filter = add(new ModeSetting("Filter",
			"Which inputs to load", "Whitelist", "Whitelist", "Blacklist", "All smeltable"));
	public final ItemListSetting smeltables = add(new ItemListSetting("Smeltables",
			"Used by Whitelist and Blacklist — right-click to pick", item -> true),
			() -> !filter.is("All smeltable"));
	public final ItemListSetting fuels = add(new ItemListSetting("Fuels",
			"What may be burned, in preference order", item -> true,
			java.util.Set.of("minecraft:coal", "minecraft:charcoal")));

	public final NumberSetting inputBatch = add(new NumberSetting("Input batch",
			"Most input to keep in the slot", 64, 1, 64, 1));
	public final NumberSetting fuelRefill = add(new NumberSetting("Fuel refill count",
			"Target count in the fuel slot", 16, 1, 64, 1));
	public final NumberSetting fuelReserve = add(new NumberSetting("Keep fuel reserve",
			"Never burn below this many in your inventory", 0, 0, 2304, 1));
	public final BooleanSetting collectOutput = add(new BooleanSetting("Collect output",
			"Take the finished items out when there is room", true));

	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between menu actions", 2, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks", 1, 0, 10, 1));

	public final ModeSetting onMissingInput = add(new ModeSetting("On missing input",
			"When there is nothing left to smelt", "Wait", "Wait", "Close", "Disable"));
	public final ModeSetting onMissingFuel = add(new ModeSetting("On missing fuel",
			"When there is no fuel you are allowed to burn", "Wait", "Wait", "Close", "Disable"));
	public final ModeSetting onFullInventory = add(new ModeSetting("On full inventory",
			"When the output has nowhere to go", "Wait", "Wait", "Close", "Disable"));
	public final BooleanSetting autoCloseComplete = add(new BooleanSetting("Auto close complete",
			"Close once there is no input, no output and nothing to do", false));
	public final BooleanSetting pauseOnEat = addPauseOnEat();
	public final BooleanSetting queueStatus = add(new BooleanSetting("Queue status",
			"Report input, fuel, output and why it stopped", true));

	private final RecipeAutomation automation = new RecipeAutomation(this);
	private RecipeAutomation.Stop lastReported = RecipeAutomation.Stop.NONE;

	public AutoSmelt() {
		super("AutoSmelt", "Feeds and empties an open furnace", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		automation.reset();
		lastReported = RecipeAutomation.Stop.NONE;
	}

	@Override
	protected void onDisable() {
		automation.reset();
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			automation.reset();
			return;
		}
		RecipeAutomation.Options options = new RecipeAutomation.Options(this::supported,
				delay.getInt(), randomDelay.getInt(), 0, pauseOnEat.get());
		if (!automation.beginTick(options)) {
			report();
			return;
		}
		if (!(automation.menu() instanceof AbstractFurnaceMenu menu)) {
			automation.stop(RecipeAutomation.Stop.NO_MENU);
			return;
		}

		// Output first, always. A full result slot stalls the furnace itself, so emptying it is
		// worth more than either of the two loading actions and cannot be allowed to queue behind
		// them.
		if (takeOutput(player, menu)) {
			report();
			return;
		}
		if (loadFuel(player, menu)) {
			report();
			return;
		}
		if (loadInput(player, menu)) {
			report();
			return;
		}
		idle(player, menu);
		report();
	}

	private boolean supported(AbstractContainerMenu menu) {
		if (menu instanceof BlastFurnaceMenu) {
			return blastFurnace.get();
		}
		if (menu instanceof SmokerMenu) {
			return smoker.get();
		}
		return menu instanceof FurnaceMenu && furnace.get();
	}

	/** Shift-clicks the result slot out when there is somewhere for it to go. */
	private boolean takeOutput(LocalPlayer player, AbstractFurnaceMenu menu) {
		if (!collectOutput.get()) {
			return false;
		}
		ItemStack output = automation.slot(AbstractFurnaceMenu.RESULT_SLOT);
		if (output.isEmpty()) {
			return false;
		}
		if (!RecipeAutomation.hasRoomFor(output)) {
			automation.stop(RecipeAutomation.Stop.INVENTORY_FULL);
			applyPolicy(player, onFullInventory.get());
			return true;
		}
		return automation.quickMove(AbstractFurnaceMenu.RESULT_SLOT);
	}

	/**
	 * Tops the fuel slot up from the preference list.
	 *
	 * <p>The reserve is counted across the whole inventory rather than per stack: a player who
	 * said "keep 64 coal" means 64 coal, not 64 in every slot that happens to hold some.
	 */
	private boolean loadFuel(LocalPlayer player, AbstractFurnaceMenu menu) {
		ItemStack inSlot = automation.slot(AbstractFurnaceMenu.FUEL_SLOT);
		if (!inSlot.isEmpty() && inSlot.getCount() >= fuelRefill.getInt()) {
			return false;
		}
		int source = findFuel(player, inSlot);
		if (source < 0) {
			if (inSlot.isEmpty() && !menu.isLit()) {
				automation.stop(RecipeAutomation.Stop.NO_FUEL);
				applyPolicy(player, onMissingFuel.get());
				return true;
			}
			return false;
		}
		return automation.quickMove(source);
	}

	/** Loads an approved input, up to the batch size. */
	private boolean loadInput(LocalPlayer player, AbstractFurnaceMenu menu) {
		ItemStack inSlot = automation.slot(AbstractFurnaceMenu.INGREDIENT_SLOT);
		if (!inSlot.isEmpty() && inSlot.getCount() >= inputBatch.getInt()) {
			return false;
		}
		int source = findInput(player, menu, inSlot);
		if (source < 0) {
			if (inSlot.isEmpty()) {
				automation.stop(RecipeAutomation.Stop.NO_INGREDIENTS);
				applyPolicy(player, onMissingInput.get());
				return true;
			}
			return false;
		}
		return automation.quickMove(source);
	}

	/** Nothing to do: either wait, or close if the player asked to be told it is finished. */
	private void idle(LocalPlayer player, AbstractFurnaceMenu menu) {
		automation.stop(RecipeAutomation.Stop.NONE);
		boolean empty = automation.slot(AbstractFurnaceMenu.INGREDIENT_SLOT).isEmpty()
				&& automation.slot(AbstractFurnaceMenu.RESULT_SLOT).isEmpty();
		if (autoCloseComplete.get() && empty && !menu.isLit()) {
			close(player);
		}
	}

	/**
	 * The first menu slot holding fuel we are allowed to burn, or -1.
	 *
	 * <p>Searched in the player half of the furnace menu, because that is where a shift-click can
	 * move from. The list is walked in its own order first so a preference means something, then
	 * the slots — otherwise "coal, then charcoal" would come out as whichever sat in a lower slot.
	 */
	private int findFuel(LocalPlayer player, ItemStack existing) {
		AbstractContainerMenu menu = automation.menu();
		if (menu == null) {
			return -1;
		}
		for (int index = AbstractFurnaceMenu.SLOT_COUNT; index < menu.slots.size(); index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (stack.isEmpty() || !fuels.contains(stack.getItem())) {
				continue;
			}
			if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) {
				continue; // a different fuel would not stack into the slot anyway
			}
			if (reservedFuel(player, stack)) {
				continue;
			}
			return index;
		}
		return -1;
	}

	/** Whether burning this stack would break the reserve. */
	private boolean reservedFuel(LocalPlayer player, ItemStack stack) {
		if (fuelReserve.getInt() <= 0) {
			return false;
		}
		int held = RecipeAutomation.available(other -> other.is(stack.getItem()), null);
		return held - stack.getCount() < fuelReserve.getInt();
	}

	/** The first menu slot holding an approved, smeltable input, or -1. */
	private int findInput(LocalPlayer player, AbstractFurnaceMenu menu, ItemStack existing) {
		AbstractContainerMenu container = automation.menu();
		if (container == null) {
			return -1;
		}
		Inventory inventory = player.getInventory();
		for (int index = AbstractFurnaceMenu.SLOT_COUNT; index < container.slots.size(); index++) {
			ItemStack stack = container.getSlot(index).getItem();
			if (stack.isEmpty() || !allowedInput(stack)) {
				continue;
			}
			if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) {
				continue;
			}
			// Fuel is not input, even when the filter would accept it. Feeding your coal into the
			// ingredient slot of a furnace is a way to lose it, not a way to smelt it.
			if (fuels.contains(stack.getItem()) || stack.is(Items.LAVA_BUCKET)) {
				continue;
			}
			if (!smeltableHere(menu, stack)) {
				continue;
			}
			int slot = container.getSlot(index).getContainerSlot();
			if (slot >= 0 && slot < Inventory.INVENTORY_SIZE
					&& InventoryPolicy.equipped(slot, inventory, InventoryPolicy.Rules.SAFE_DEFAULT)) {
				continue;
			}
			return index;
		}
		return -1;
	}

	private boolean allowedInput(ItemStack stack) {
		return switch (filter.get()) {
			case "Blacklist" -> !smeltables.contains(stack.getItem());
			case "All smeltable" -> true;
			default -> smeltables.contains(stack.getItem());
		};
	}

	/**
	 * Whether <em>this</em> furnace can smelt {@code stack}.
	 *
	 * <p>Asked of the recipe book, whose entries are the recipes the server has actually told this
	 * client about, filtered to the categories the open menu's own {@link RecipeBookType} covers.
	 * A blast furnace and a smoker share the ingredient slot and share nothing else, and a
	 * hard-coded "ores go in blast furnaces" table would be wrong the first time a datapack said
	 * otherwise.
	 *
	 * <p>Only consulted for whitelisted or blacklisted items too, not just All smeltable: a
	 * whitelist is a statement about what the player wants smelted, not a promise that the furnace
	 * in front of them can do it.
	 */
	private boolean smeltableHere(AbstractFurnaceMenu menu, ItemStack stack) {
		LocalPlayer player = mc().player;
		if (player == null) {
			return false;
		}
		RecipeBookType type = menu.getRecipeBookType();
		for (var collection : player.getRecipeBook().getCollections()) {
			for (var entry : collection.getRecipes()) {
				if (!matchesFurnace(type, entry.category())) {
					continue;
				}
				var requirements = entry.craftingRequirements();
				if (requirements.isEmpty() || requirements.get().size() != 1) {
					continue; // a smelting recipe has exactly one ingredient
				}
				if (requirements.get().getFirst().test(stack)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Which recipe-book categories belong to which furnace. */
	private static boolean matchesFurnace(RecipeBookType type,
			RecipeBookCategory category) {
		return switch (type) {
			case FURNACE -> category == RecipeBookCategories.FURNACE_FOOD
					|| category == RecipeBookCategories.FURNACE_BLOCKS
					|| category == RecipeBookCategories.FURNACE_MISC;
			case BLAST_FURNACE ->
					category == RecipeBookCategories.BLAST_FURNACE_BLOCKS
					|| category == RecipeBookCategories.BLAST_FURNACE_MISC;
			case SMOKER -> category == RecipeBookCategories.SMOKER_FOOD;
			default -> false;
		};
	}

	/** Wait / Close / Disable, shared by the three stop policies. */
	private void applyPolicy(LocalPlayer player, String policy) {
		switch (policy) {
			case "Close" -> close(player);
			case "Disable" -> setEnabled(false);
			default -> {
			}
		}
	}

	private void close(LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	/** One message per change of reason, so a stalled furnace says so exactly once. */
	private void report() {
		RecipeAutomation.Stop stop = automation.stopReason();
		if (!queueStatus.get() || stop == lastReported) {
			return;
		}
		lastReported = stop;
		if (stop == RecipeAutomation.Stop.NONE || stop == RecipeAutomation.Stop.NO_MENU) {
			return;
		}
		ChatUtil.info("§eAutoSmelt: " + automation.status());
	}

	/** The status line, for the read-out. */
	public String status() {
		return automation.status();
	}
}
