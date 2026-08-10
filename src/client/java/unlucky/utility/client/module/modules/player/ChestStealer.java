package unlucky.utility.client.module.modules.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
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

/** Reliable, delayed looting of ordinary storage menus. */
public class ChestStealer extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"All takes every stack; Filtered takes only selected item types", "All", "All", "Filtered"));
	public final ItemListSetting items = add(new ItemListSetting("Items",
			"Items taken in Filtered mode", item -> true), () -> mode.is("Filtered"));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between inventory actions", 1, 0, 20, 1));
	public final NumberSetting randomDelay = add(new NumberSetting("Random delay",
			"Adds a random 0..N ticks after each action", 2, 0, 10, 1));
	public final ModeSetting order = add(new ModeSetting("Order",
			"Nearest uses menu order; Random shuffles; Value prefers generic rarity, enchantment and count",
			"Nearest slot", "Nearest slot", "Random", "Value"));
	public final BooleanSetting quickMove = add(new BooleanSetting("Use quick-move",
			"Use vanilla shift-click routing instead of an explicit player slot", true));
	public final BooleanSetting autoClose = add(new BooleanSetting("Auto close",
			"Close the real container screen after nothing eligible remains", true));
	public final NumberSetting closeDelay = add(new NumberSetting("Close delay",
			"Ticks to wait before auto-closing an exhausted container", 2, 0, 20, 1), autoClose::get);
	public final BooleanSetting onlyChests = add(new BooleanSetting("Only chests",
			"Reject shulker menus and known barrel titles; the protocol cannot distinguish a renamed barrel from a renamed chest",
			false));
	public final BooleanSetting ignoreNamed = add(new BooleanSetting("Ignore named containers",
			"Leave custom-titled storage alone", false));
	public final BooleanSetting stopWhenFull = add(new BooleanSetting("Stop when inventory full",
			"Stop clicking when no player slot can accept the next stack", true));
	private AbstractContainerMenu lastMenu;
	private int exhaustedTicks;

	public ChestStealer() {
		super("ChestStealer", "Reliably loots storage with delayed server-synced clicks",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		lastMenu = null;
		exhaustedTicks = 0;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gameMode == null
				|| !(mc().gui.screen() instanceof AbstractContainerScreen<?> screen)) {
			standDown();
			return;
		}
		AbstractContainerMenu menu = mc().player.containerMenu;
		if (!supported(menu, screen)) {
			standDown();
			return;
		}
		if (menu != lastMenu) {
			InventoryActionCoordinator.release(this);
			lastMenu = menu;
			exhaustedTicks = 0;
		}
		// State zero is the locally-created shell. Only a server content packet advances it.
		if (menu.getStateId() == 0) {
			return;
		}

		List<Integer> candidates = candidates(menu);
		if (candidates.isEmpty()) {
			exhausted(menu);
			return;
		}
		exhaustedTicks = 0;
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_LOOT)
				|| !InventoryActionCoordinator.owns(this)
				|| !InventoryActionCoordinator.canAct(this)) {
			return;
		}

		int source = choose(menu, candidates);
		ItemStack stack = menu.getSlot(source).getItem();
		int destination = ContainerUtil.freeSlot(menu, stack);
		if (destination < 0 && stopWhenFull.get()) {
			exhausted(menu);
			return;
		}
		boolean moved = quickMove.get()
				? InventoryActionCoordinator.quickMove(this, menu, source)
				: destination >= 0 && InventoryActionCoordinator.pickupMove(this, menu, source, destination);
		if (moved) {
			InventoryActionCoordinator.delay(this, delay.getInt(), randomDelay.getInt());
		}
	}

	private boolean supported(AbstractContainerMenu menu, AbstractContainerScreen<?> screen) {
		if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
			return false;
		}
		String title = screen.getTitle().getString();
		boolean defaultChest = title.equals(Component.translatable("container.chest").getString())
				|| title.equals(Component.translatable("container.chestDouble").getString());
		boolean defaultBarrel = title.equals(Component.translatable("container.barrel").getString());
		boolean defaultShulker = title.equals(Component.translatable("container.shulkerBox").getString());
		if (ignoreNamed.get() && !defaultChest && !defaultBarrel && !defaultShulker) {
			return false;
		}
		return !onlyChests.get() || (menu instanceof ChestMenu && !defaultBarrel);
	}

	private List<Integer> candidates(AbstractContainerMenu menu) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			ItemStack stack = slot.getItem();
			if (slot.container instanceof Inventory || stack.isEmpty()
					|| (mode.is("Filtered") && !items.contains(stack.getItem()))) {
				continue;
			}
			if (!stopWhenFull.get() || ContainerUtil.freeSlot(menu, stack) >= 0) {
				result.add(i);
			}
		}
		return result;
	}

	private int choose(AbstractContainerMenu menu, List<Integer> candidates) {
		if (order.is("Random")) {
			return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		}
		if (order.is("Value")) {
			return candidates.stream().max(Comparator.comparingLong(i -> value(menu.getSlot(i).getItem())))
					.orElse(candidates.getFirst());
		}
		return candidates.getFirst();
	}

	/** Generic signal only; item-specific desirability belongs to the later Value profile. */
	private long value(ItemStack stack) {
		return (long) stack.getRarity().ordinal() * 1_000_000L
				+ (stack.hasFoil() ? 100_000L : 0L) + stack.getCount();
	}

	private void exhausted(AbstractContainerMenu menu) {
		InventoryActionCoordinator.release(this);
		if (!autoClose.get() || ++exhaustedTicks <= closeDelay.getInt()
				|| !InventoryActionCoordinator.isOpen(menu)) {
			return;
		}
		// This module uses a real visible screen, so use vanilla's ordinary close path.
		// ContainerUtil.closeMenu deliberately preserves a client GUI and is wrong here.
		mc().player.closeContainer();
		lastMenu = null;
		exhaustedTicks = 0;
	}

	private void standDown() {
		InventoryActionCoordinator.release(this);
		lastMenu = null;
		exhaustedTicks = 0;
	}
}
