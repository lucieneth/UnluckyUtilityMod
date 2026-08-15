package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ItemListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ContainerUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;

/**
 * Throws selected contents out of the container you already have open.
 *
 * <p><b>It does not look for containers and it does not open them.</b> The player opened this
 * chest; the module works on that one and stops when it closes. That is the whole scope, and it
 * is what keeps this a convenience rather than a bot that walks a base emptying storage.
 *
 * <p><b>Player slots can never be thrown.</b> Every action is checked against
 * {@link ContainerUtil#isStorageSlot}, which is the container's own half of the menu — not a
 * slot-index range, because the index at which the player's inventory begins is different in a
 * chest, a double chest and a shulker box, and getting that arithmetic wrong once means
 * throwing your armour into the void instead of the cobblestone.
 *
 * <p><b>It never runs in the same tick as ChestStealer, and priority is not what guarantees
 * that.</b> {@code CLEANER} sits just above {@code LOOT}, which decides who clicks when both
 * want to — not whether both want to. The guarantee is that this module refuses to plan at all
 * while the stealer holds the lease, and that the plan is rebuilt from the live menu every
 * tick: a slot the stealer emptied is simply not in the next plan, rather than a stale index
 * that gets thrown anyway.
 */
public class ChestCleaner extends Module {
	public final ModeSetting filter = add(new ModeSetting("Filter",
			"Blacklist throws what matches; whitelist throws what does not", "Blacklist",
			"Blacklist", "Whitelist"));
	public final ItemListSetting items = add(new ItemListSetting("Items",
			"The list the filter applies to — right-click to pick", item -> true));

	public final BooleanSetting chests = add(new BooleanSetting("Chests",
			"Work in chests", true));
	public final BooleanSetting trappedChests = add(new BooleanSetting("Trapped chests",
			"Work in trapped chests", true));
	public final BooleanSetting barrels = add(new BooleanSetting("Barrels",
			"Work in barrels", true));
	public final BooleanSetting shulkers = add(new BooleanSetting("Shulker boxes",
			"Work in shulker boxes", true));

	public final BooleanSetting ignoreNamed = add(new BooleanSetting("Ignore named containers",
			"Skip containers with a custom title", true));
	public final NumberSetting initialDelay = add(new NumberSetting("Initial delay",
			"Ticks to wait after the menu opens", 5, 0, 40, 1));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Base ticks between throws", 2, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Extra random ticks on top", 1, 0, 10, 1));
	public final BooleanSetting autoClose = add(new BooleanSetting("Auto close",
			"Close the container once there is nothing left to throw", false));
	public final NumberSetting closeDelay = add(new NumberSetting("Close delay",
			"Ticks before the automatic close", 5, 0, 40, 1), autoClose::get);
	public final BooleanSetting stopWhenFull = add(new BooleanSetting("Stop when inventory full",
			"Stop while your own inventory is full. Throwing needs no space, but stopping keeps "
					+ "this predictable next to ChestStealer.", true));
	public final BooleanSetting preview = add(new BooleanSetting("Preview",
			"Highlight the planned removals and wait for the module key before doing any of them",
			true));

	/** The menu this session belongs to, so a replaced container is noticed rather than acted on. */
	private AbstractContainerMenu menu;
	/**
	 * The menu a plan was previewed against. Survives a disable on purpose: that is what lets
	 * the second press of the module key mean "yes, do it" rather than "start again".
	 */
	private AbstractContainerMenu previewed;
	private int openTicks;
	private int delayTicks;
	private int closeTicks = -1;
	/** Whether the player has confirmed a previewed plan for the current menu. */
	private boolean confirmed;

	public ChestCleaner() {
		super("ChestCleaner", "Throws selected items out of the container you have open",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		// The module key is the confirmation. Enabling with the same container still open as the
		// one a plan was previewed against means the player has seen the list and pressed again.
		LocalPlayer player = mc().player;
		confirmed = !preview.get()
				|| (player != null && previewed != null && player.containerMenu == previewed);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		reset();
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	private void reset() {
		menu = null;
		openTicks = 0;
		delayTicks = 0;
		closeTicks = -1;
		confirmed = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			onDisable();
			return;
		}
		AbstractContainerMenu open = player.containerMenu;
		if (open == null || open == player.inventoryMenu || !supported(open)) {
			// Closing or changing the menu cancels the plan outright. A plan is a list of slot
			// indices, and slot 13 of the chest that just closed is slot 13 of whatever is open
			// now — the failure AutoBrew learned the expensive way.
			if (menu != null) {
				InventoryActionCoordinator.release(this);
				reset();
			}
			return;
		}
		if (open != menu) {
			reset();
			menu = open;
			confirmed = !preview.get();
		}

		if (openTicks < initialDelay.getInt()) {
			openTicks++;
			return;
		}
		if (closeTicks >= 0) {
			if (++closeTicks >= closeDelay.getInt()) {
				ContainerUtil.closeMenu();
				reset();
			}
			return;
		}
		if (delayTicks > 0) {
			delayTicks--;
			return;
		}

		List<Integer> plan = plan(player);
		if (plan.isEmpty()) {
			InventoryActionCoordinator.release(this);
			if (autoClose.get()) {
				closeTicks = 0;
			}
			return;
		}
		if (!confirmed) {
			// Nothing is thrown until the player says so. The plan is listed once per container
			// and rebuilt every tick, so what they are reading is current.
			if (previewed != menu) {
				previewed = menu;
				announcePreview(plan);
			}
			return;
		}
		// One slot per granted window, and the plan is rebuilt next tick against a menu that has
		// changed — including any slot ChestStealer took in the meantime.
		if (throwSlot(plan.get(0))) {
			delayTicks = delay.getInt()
					+ (randomDelay.getInt() > 0 ? (int) (Math.random() * (randomDelay.getInt() + 1)) : 0);
		}
	}

	/**
	 * Container slots this tick would throw, in menu order.
	 *
	 * <p>Rebuilt every tick from the live menu rather than cached, which is what makes the
	 * ChestStealer interaction safe: a slot it emptied is not in the next plan, so there is no
	 * stale index to act on.
	 */
	public List<Integer> plan(LocalPlayer player) {
		List<Integer> out = new ArrayList<>();
		if (menu == null || !InventoryActionCoordinator.isOpen(menu)) {
			return out;
		}
		if (stopWhenFull.get() && inventoryFull(player)) {
			return out;
		}
		// Never in the same tick as a steal. Asked of the lease rather than of ChestStealer,
		// because the lease is the thing that is actually true right now — the stealer holding
		// it means a click of its own is in flight, whatever its own bookkeeping says.
		if (InventoryActionCoordinator.owner() instanceof ChestStealer) {
			return out;
		}
		for (int index = 0; index < menu.slots.size(); index++) {
			Slot slot = menu.getSlot(index);
			if (!ContainerUtil.isStorageSlot(slot)) {
				continue; // the player's own half of the menu, which this module never touches
			}
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			boolean listed = items.contains(stack.getItem());
			if (filter.is("Blacklist") ? listed : !listed) {
				out.add(index);
			}
		}
		return out;
	}

	/** Whether this is a container type the player asked the module to work in. */
	private boolean supported(AbstractContainerMenu open) {
		if (ignoreNamed.get() && namedTitle()) {
			return false;
		}
		// The menu carries no block identity, so the type is taken from the screen's title key
		// the same way the player reads it. Deliberately permissive: an unrecognised container
		// with the right shape is still a container, and refusing it would silently do nothing.
		String title = titleKey();
		if (title.contains("shulker")) {
			return shulkers.get();
		}
		if (title.contains("barrel")) {
			return barrels.get();
		}
		if (title.contains("trapped")) {
			return trappedChests.get();
		}
		return title.contains("chest") && chests.get();
	}

	private String titleKey() {
		var screen = mc().gui.screen();
		return screen == null ? "" : screen.getTitle().getString().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Whether the open container has a custom title.
	 *
	 * <p>A named chest is one somebody labelled on purpose, and "Ignore named containers" exists
	 * because a labelled chest is far more likely to be a sorted one than a dump.
	 */
	private boolean namedTitle() {
		var screen = mc().gui.screen();
		if (screen == null) {
			return false;
		}
		// A vanilla container title is a translatable component; a renamed one carries a literal
		// or a custom name component, which is the difference being tested here.
		return screen.getTitle().getContents() instanceof net.minecraft.network.chat.contents.PlainTextContents;
	}

	private static boolean inventoryFull(LocalPlayer player) {
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private boolean throwSlot(int index) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_CLEANER)) {
			return false;
		}
		// Button 1 throws the whole stack. There is no partial case here: the filter answers per
		// item, so a stack that matches matches entirely.
		boolean done = InventoryActionCoordinator.click(this, menu, index, 1, ContainerInput.THROW);
		InventoryActionCoordinator.release(this);
		return done;
	}

	/**
	 * Lists what would be thrown, once per container, and asks for the key again.
	 *
	 * <p>Reported rather than drawn over the slots: the highlight would need its own container
	 * screen hook, and a list you can read is the part that actually answers "am I about to
	 * throw something I wanted".
	 */
	private void announcePreview(List<Integer> plan) {
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (int index : plan) {
			ItemStack stack = menu.getSlot(index).getItem();
			if (shown++ > 0) {
				text.append(", ");
			}
			if (shown > 8) {
				text.append("and ").append(plan.size() - 8).append(" more");
				break;
			}
			text.append(stack.getCount()).append("x ").append(stack.getHoverName().getString());
		}
		unlucky.utility.client.util.ChatUtil.info("§eChestCleaner would throw:§r " + text);
		unlucky.utility.client.util.ChatUtil.info("Press the ChestCleaner key again to confirm.");
	}

	/** Whether a previewed plan is waiting for the player, for the read-out. */
	public boolean awaitingConfirmation() {
		return menu != null && !confirmed;
	}
}
