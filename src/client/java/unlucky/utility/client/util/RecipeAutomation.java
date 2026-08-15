package unlucky.utility.client.util;

import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.modules.player.AutoEat;

/**
 * The parts of "work a menu the player has open" that AutoCraft and AutoSmelt would otherwise
 * each get slightly wrong.
 *
 * <p><b>The menu the plan was made against is the menu the click must land in.</b> This is the
 * failure that actually costs items, and AutoBrew learned it the expensive way: a click aimed at
 * slot 13 of a furnace that closed a tick ago lands on slot 13 of whatever replaced it. Every
 * action here re-checks the menu, and a menu that changed is a terminal stop rather than a
 * retry — a plan built against a container that is gone is not a plan.
 *
 * <p><b>Neither of these modules may open anything.</b> Both are explicitly scoped to the menu
 * the player opened themselves, which is what keeps them ordinary automation rather than a bot
 * that walks up to furnaces. There is deliberately no "find a crafting table" here to be tempted
 * by later.
 *
 * <p>The stop reason is a first-class result rather than a log line. A machine spread across
 * containers you cannot see into fails as "nothing is happening", which looks identical whether
 * the queue is empty, the fuel ran out or the output has nowhere to go — the BrewingWidget exists
 * because of exactly that, and these modules get to show the same thing without inventing it.
 *
 * @see InventoryActionCoordinator which owns the clicks this schedules
 */
public final class RecipeAutomation {
	/** Why the machine is not doing anything. */
	public enum Stop {
		/** It is, in fact, working. */
		NONE,
		/** No supported menu is open. Not an error — the module simply waits. */
		NO_MENU,
		/** The menu changed underneath a plan. Terminal for that plan. */
		MENU_CHANGED,
		/** Somebody more important holds the inventory. */
		NO_LEASE,
		/** Nothing left that can be made or smelted. */
		NO_INGREDIENTS,
		/** No fuel, and none allowed to be taken. */
		NO_FUEL,
		/** The output has nowhere to go. */
		INVENTORY_FULL,
		/** The configured maximum was reached. */
		CAP_REACHED,
		/** Standing down for a meal or a screen. */
		PAUSED,
		/** A sequential plan fed itself. Terminal, and worth telling the player about. */
		CYCLE
	}

	/**
	 * The caller's rules for the tick.
	 *
	 * @param supported  whether this menu is one the module works with — the module knows what a
	 *                   furnace is, this class does not
	 * @param maxActions zero means "until a stop condition", matching both modules' option
	 */
	public record Options(Predicate<AbstractContainerMenu> supported, int delay, int randomDelay,
			int maxActions, boolean pauseOnEat) {
	}

	private final Object owner;
	private final int priority;

	private Options options;
	private AbstractContainerMenu menu;
	private int delayTicks;
	private int actions;
	private Stop stop = Stop.NONE;

	public RecipeAutomation(Object owner) {
		this(owner, InventoryActionCoordinator.PRIORITY_AUTOMATION);
	}

	public RecipeAutomation(Object owner, int priority) {
		this.owner = owner;
		this.priority = priority;
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- the tick ----------------------------------------------------------

	/**
	 * Validates the open menu and opens a tick's work.
	 *
	 * <p>Also the place a menu change is noticed. A module that only checked at click time
	 * would keep a plan built against the last furnace and act on it in the next one.
	 *
	 * @return whether the module may act at all this tick; {@link #stopReason} says why not
	 */
	public boolean beginTick(Options tickOptions) {
		this.options = tickOptions;
		if (delayTicks > 0) {
			delayTicks--;
		}
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			reset();
			stop = Stop.NO_MENU;
			return false;
		}
		if (tickOptions.pauseOnEat() && AutoEat.busy()) {
			release();
			stop = Stop.PAUSED;
			return false;
		}
		AbstractContainerMenu open = player.containerMenu;
		if (open == null || !tickOptions.supported().test(open)) {
			// Not a failure and not a plan to abandon — the player simply has nothing relevant
			// open. Only an open menu that then *changed* is terminal.
			release();
			menu = null;
			stop = Stop.NO_MENU;
			return false;
		}
		if (menu != null && menu != open) {
			release();
			menu = open;
			actions = 0;
			stop = Stop.MENU_CHANGED;
			return false;
		}
		if (menu == null) {
			menu = open;
			actions = 0;
		}
		if (tickOptions.maxActions() > 0 && actions >= tickOptions.maxActions()) {
			release();
			stop = Stop.CAP_REACHED;
			return false;
		}
		stop = Stop.NONE;
		return true;
	}

	/** The validated menu for this tick, or null. */
	public AbstractContainerMenu menu() {
		return menu;
	}

	/** Whether an action may be taken right now — lease held and the delay expired. */
	public boolean canAct() {
		if (menu == null || delayTicks > 0) {
			return false;
		}
		if (!InventoryActionCoordinator.acquire(owner, priority)) {
			stop = Stop.NO_LEASE;
			return false;
		}
		return InventoryActionCoordinator.isOpen(menu);
	}

	/**
	 * One click in the validated menu, charging the budget and starting the delay.
	 *
	 * @return whether the click was sent
	 */
	public boolean click(int slot, int button, ContainerInput input) {
		if (!canAct() || !InventoryActionCoordinator.click(owner, menu, slot, button, input)) {
			return false;
		}
		spend();
		return true;
	}

	/** Shift-click, which is how both a crafted output and a smelted one are collected. */
	public boolean quickMove(int slot) {
		return click(slot, 0, ContainerInput.QUICK_MOVE);
	}

	/** Charges the budget and starts the delay for an action taken some other way. */
	public void spend() {
		actions++;
		delayTicks = Math.max(0, options.delay())
				+ (options.randomDelay() > 0 ? (int) (Math.random() * (options.randomDelay() + 1)) : 0);
	}

	/** How many actions this menu session has taken. */
	public int actions() {
		return actions;
	}

	// ---- what the module needs to know -------------------------------------

	/**
	 * Whether {@code stack} has anywhere to go in the player's own inventory.
	 *
	 * <p>Checked before producing rather than after: a craft whose output cannot be collected
	 * leaves the result sitting in the menu, and the next craft then silently does nothing while
	 * the module reports success. Partial stacks count, which is why this is not simply "is
	 * there an empty slot".
	 */
	public static boolean hasRoomFor(ItemStack stack) {
		LocalPlayer player = mc().player;
		if (player == null || stack.isEmpty()) {
			return false;
		}
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack existing = inventory.getItem(slot);
			if (existing.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(existing, stack)
					&& existing.getCount() < existing.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * How many of {@code stack}'s item the player has available, ignoring anything reserved.
	 *
	 * <p>The reservation is the point: a module that consumes its way through the armour it is
	 * wearing or the blocks another module is placing has technically done what it was asked
	 * and has still ruined the run.
	 */
	public static int available(Predicate<ItemStack> matches, Predicate<Integer> reserved) {
		LocalPlayer player = mc().player;
		if (player == null) {
			return 0;
		}
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (reserved != null && reserved.test(slot)) {
				continue;
			}
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** The stack in a menu slot, or empty if the slot is not part of the validated menu. */
	public ItemStack slot(int index) {
		if (menu == null || index < 0 || index >= menu.slots.size()) {
			return ItemStack.EMPTY;
		}
		Slot slot = menu.getSlot(index);
		return slot.getItem();
	}

	// ---- stop reasons ------------------------------------------------------

	/** Records why the module has stopped, for the read-out and for self-disable modes. */
	public void stop(Stop reason) {
		stop = reason == null ? Stop.NONE : reason;
		if (stop != Stop.NONE) {
			release();
		}
	}

	public Stop stopReason() {
		return stop;
	}

	/** Whether the module has stopped for a reason that will not resolve itself. */
	public boolean terminal() {
		return switch (stop) {
			case MENU_CHANGED, CAP_REACHED, CYCLE -> true;
			default -> false;
		};
	}

	/**
	 * A short line for the queue status read-out.
	 *
	 * <p>{@code NONE} means "no reason to have stopped", which is <em>not</em> the same as
	 * working: a module that has never validated a menu — because nothing is configured, or
	 * because it returned before its first tick got that far — has that same reason and is doing
	 * nothing at all. Reporting "working" there is a machine that claims to be running while it is
	 * switched off, which is precisely the confusion the stop reasons exist to prevent.
	 */
	public String status() {
		if (stop == Stop.NONE && menu == null) {
			return "idle";
		}
		return switch (stop) {
			case NONE -> "working (" + actions + ")";
			case NO_MENU -> "no supported menu open";
			case MENU_CHANGED -> "menu changed";
			case NO_LEASE -> "waiting for the inventory";
			case NO_INGREDIENTS -> "out of ingredients";
			case NO_FUEL -> "out of fuel";
			case INVENTORY_FULL -> "inventory full";
			case CAP_REACHED -> "reached the configured maximum";
			case PAUSED -> "paused";
			case CYCLE -> "recipe feeds itself — stopped";
		};
	}

	// ---- lifecycle ---------------------------------------------------------

	/** Hands the inventory back without forgetting the session. */
	public void release() {
		InventoryActionCoordinator.release(owner);
	}

	/** Full stop: disable, panic, world change. */
	public void reset() {
		release();
		options = null;
		menu = null;
		delayTicks = 0;
		actions = 0;
		stop = Stop.NONE;
	}
}
