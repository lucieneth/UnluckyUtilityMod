package unlucky.utility.client.util;

import java.lang.ref.WeakReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * One owner at a time for automated inventory clicks and hotbar switches.
 *
 * <p>Every module that reaches into your inventory on its own is competing for the same three
 * things — the selected hotbar slot, the cursor stack, and whichever menu happens to be open —
 * and none of those tolerate two writers. Two modules swapping slots in the same tick do not
 * produce two swaps: they produce one swap and one module holding a stale "put this back
 * afterwards" note, which it then acts on, and the item you were holding ends up somewhere
 * nobody chose. AutoEat already had to invent a two-tick notice period ({@code CLAIM_TICKS})
 * to avoid exactly this against the modules that existed then; this is that idea generalised
 * so the next ten do not each need their own.
 *
 * <p><b>The contract is check-every-tick, not acquire-once.</b> A lease can be taken from you
 * between ticks by anything that outranks you — that is the point of the priorities — and you
 * are told by {@link #owns} answering false, not by a callback. Ask before you act, every
 * tick, and treat a false as "somebody more important is mid-action, try again later".
 *
 * <p><b>The menu is passed in, never assumed.</b> Every click takes the
 * {@link AbstractContainerMenu} the caller planned against and is dropped if that is no longer
 * the open one. This is the failure that actually costs items: a click aimed at slot 13 of a
 * chest that closed a tick ago lands on slot 13 of whatever replaced it. AutoBrew learned this
 * the expensive way and its lesson is the rule here.
 *
 * @see OffhandManager for the offhand, which needs a longer-lived claim than a click lease
 */
public final class InventoryActionCoordinator {
	/**
	 * A direct button press — QuickStash's take-all/store-all. The player just clicked
	 * something and the burst it triggers finishes within the same call, before the next
	 * tick gives anything else a chance to act; outranking even the totem priority costs
	 * nothing; it is momentarily evicted and free to reacquire the moment the burst ends.
	 */
	public static final int PRIORITY_MANUAL = 110;
	/** Keeping a totem in hand outranks everything; being alive is a precondition for the rest. */
	public static final int PRIORITY_TOTEM = 100;
	/** Emergency safety actions — AutoLog's last moves before a disconnect. */
	public static final int PRIORITY_SAFETY = 90;
	/**
	 * ElytraSwap replacing a worn elytra before it breaks. Above combat and below the totem:
	 * losing your wings mid-flight is a death, but it is a death you can still totem out of.
	 * Deliberately above {@link #PRIORITY_EQUIPMENT} so AutoArmor cannot put a chestplate on
	 * over the top of a swap that is happening because the elytra is about to fail.
	 */
	public static final int PRIORITY_ELYTRA_SAFETY = 85;
	/** Combat item switching: CrystalAura, AnchorAura. */
	public static final int PRIORITY_COMBAT = 80;
	/** Placement that is holding a structure together: Surround, Scaffold. */
	public static final int PRIORITY_PLACEMENT = 70;
	/**
	 * Ordinary equipment upkeep: AutoArmor. Below placement because armour you are about to
	 * put on is worth less than the obsidian you are already halfway through placing, and
	 * above tools because a broken helmet costs more than a slow mine.
	 */
	public static final int PRIORITY_EQUIPMENT = 65;
	/** AutoTool. Losing a switch costs a slower mine, not a life. */
	public static final int PRIORITY_TOOL = 60;
	/** AutoReplenish. */
	public static final int PRIORITY_REPLENISH = 50;
	/**
	 * Tidying: InventoryCleaner and ChestCleaner.
	 *
	 * <p>Just above {@link #PRIORITY_LOOT} so a throw already decided on finishes rather than
	 * being cut in half. That ordering is <em>not</em> what keeps a cleaner out of a chest
	 * ChestStealer is emptying — priority decides who clicks when both want to, and what is
	 * needed there is that they never want to in the same tick. ChestCleaner enforces that
	 * itself by refusing to plan while the stealer holds the lease.
	 */
	public static final int PRIORITY_CLEANER = 45;
	/** ChestStealer and other looting. */
	public static final int PRIORITY_LOOT = 40;
	/**
	 * Bulk menu automation the player set running and walked away from: AutoCraft, AutoSmelt.
	 * The lowest rank there is, because everything else is either a response to something
	 * happening or a thing the player is doing right now.
	 */
	public static final int PRIORITY_AUTOMATION = 35;
	/** Farming utilities: AutoBreed, AutoShear. */
	public static final int PRIORITY_FARMING = 30;

	private static Object owner;
	private static int priority;
	private static int cooldown;

	/** Hotbar slot to put back when the owner is done — see {@link #selectHotbar}. */
	private static int savedSlot = -1;
	/** Menu slot the cursor's stack was lifted from, for {@link #endTick}'s tidy-up. */
	private static int cursorSource = -1;

	/**
	 * Held weakly on purpose. These exist only to notice that the world or the connection was
	 * replaced; a strong reference would pin a dead {@link ClientLevel} — an entire world's
	 * worth of chunks — alive until the next one happened to load.
	 */
	private static WeakReference<Object> level = new WeakReference<>(null);
	private static WeakReference<Object> connection = new WeakReference<>(null);

	private InventoryActionCoordinator() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- the lease ---------------------------------------------------------

	/**
	 * Claims the inventory for {@code holder}, evicting a lower-priority holder if there is one.
	 *
	 * <p>Equal priority does <b>not</b> evict. Two modules at the same rank swapping the lease
	 * back and forth every tick would each get exactly one click in before losing it, which is
	 * worse for both than one of them simply going second.
	 *
	 * @return true when the caller may act this tick
	 */
	public static boolean acquire(Object holder, int newPriority) {
		if (holder == null || mc().player == null) {
			return false;
		}
		if (owner == holder) {
			priority = newPriority; // a module may raise its own stakes mid-action
			return true;
		}
		if (owner != null && newPriority <= priority) {
			return false;
		}
		// Evicting mid-action: the outgoing holder's cursor and hotbar bookkeeping is not
		// something the incoming one can finish, so it is settled here before the handover.
		if (owner != null) {
			returnCursor();
			restoreHotbar();
		}
		owner = holder;
		priority = newPriority;
		cooldown = 0;
		return true;
	}

	/** Whether {@code holder} may act right now. Ask every tick — leases are taken, not given back. */
	public static boolean owns(Object holder) {
		return holder != null && owner == holder;
	}

	/**
	 * Whether {@code holder} owns the lease <em>and</em> its self-imposed delay has run out.
	 * The convenience form of {@code owns(holder) && ready()}.
	 */
	public static boolean canAct(Object holder) {
		return owns(holder) && cooldown <= 0;
	}

	/**
	 * Waits {@code ticks} before {@link #canAct} answers true again, with up to {@code jitter}
	 * extra ticks on top. The caller supplies both — how fast a module should click is a
	 * property of the module, not of the inventory.
	 */
	public static void delay(Object holder, int ticks, int jitter) {
		if (!owns(holder)) {
			return;
		}
		cooldown = Math.max(0, ticks) + (jitter > 0 ? (int) (Math.random() * (jitter + 1)) : 0);
	}

	/**
	 * Hands the lease back, restoring the hotbar slot the owner started from and putting down
	 * anything left on the cursor. Safe to call when you do not hold it.
	 */
	public static void release(Object holder) {
		if (!owns(holder)) {
			return;
		}
		returnCursor();
		restoreHotbar();
		owner = null;
		priority = 0;
		cooldown = 0;
	}

	/** The current holder, for read-outs and for modules that want to know who beat them. */
	public static Object owner() {
		return owner;
	}

	public static boolean isBusy() {
		return owner != null;
	}

	// ---- actions -----------------------------------------------------------

	/**
	 * Selects a hotbar slot, remembering the one you were on so {@link #release} can put it
	 * back. Only the <em>first</em> selection in a lease is remembered — a module that walks
	 * through three tools should still end up back where the player left it, not on tool two.
	 */
	public static boolean selectHotbar(Object holder, int slot) {
		LocalPlayer player = mc().player;
		if (!owns(holder) || player == null || slot < 0 || slot >= Inventory.SELECTION_SIZE) {
			return false;
		}
		Inventory inventory = player.getInventory();
		if (savedSlot < 0) {
			savedSlot = inventory.getSelectedSlot();
		}
		if (inventory.getSelectedSlot() != slot) {
			inventory.setSelectedSlot(slot);
		}
		return true;
	}

	/** The hotbar slot {@link #release} will return to, or -1 if none has been taken yet. */
	public static int savedHotbarSlot() {
		return savedSlot;
	}

	/**
	 * Forgets the hotbar slot to restore, so the current selection becomes the new normal.
	 * For "swap back: off" — the module still wants the lease's other guarantees.
	 */
	public static void keepHotbar(Object holder) {
		if (owns(holder)) {
			savedSlot = -1;
		}
	}

	/** One click on a slot of {@code menu}, refused if that menu is no longer the open one. */
	public static boolean click(Object holder, AbstractContainerMenu menu, int slot, int button,
			ContainerInput input) {
		if (!owns(holder) || !isOpen(menu) || slot < 0 || slot >= menu.slots.size()) {
			return false;
		}
		ContainerUtil.click(menu, slot, button, input);
		if (input == ContainerInput.PICKUP) {
			// Remember where a lifted stack came from, so a lease that ends mid-move can put
			// it back rather than leaving it on the cursor for the next screen to inherit.
			cursorSource = menu.getCarried().isEmpty() ? -1 : slot;
		}
		return true;
	}

	/** Shift-click: moves a whole stack to the other side of {@code menu}. */
	public static boolean quickMove(Object holder, AbstractContainerMenu menu, int slot) {
		return click(holder, menu, slot, 0, ContainerInput.QUICK_MOVE);
	}

	/**
	 * Moves as much of one stack as an explicit player-side destination accepts, without
	 * QUICK_MOVE. Every constituent click stays under the same checked menu lease.
	 */
	public static boolean pickupMove(Object holder, AbstractContainerMenu menu, int source, int destination) {
		if (!owns(holder) || !isOpen(menu) || source == destination
				|| source < 0 || destination < 0
				|| source >= menu.slots.size() || destination >= menu.slots.size()) {
			return false;
		}
		if (!click(holder, menu, source, 0, ContainerInput.PICKUP)) {
			return false;
		}
		click(holder, menu, destination, 0, ContainerInput.PICKUP);
		if (!menu.getCarried().isEmpty()) {
			click(holder, menu, source, 0, ContainerInput.PICKUP);
		}
		return menu.getCarried().isEmpty();
	}

	/** Swaps a menu slot with a hotbar slot — the number-key click, no cursor involved. */
	public static boolean swapToHotbar(Object holder, AbstractContainerMenu menu, int menuSlot, int hotbarSlot) {
		if (hotbarSlot < 0 || hotbarSlot >= Inventory.SELECTION_SIZE) {
			return false;
		}
		return click(holder, menu, menuSlot, hotbarSlot, ContainerInput.SWAP);
	}

	/**
	 * Swaps a menu slot with the offhand (the F key, button 40).
	 *
	 * <p>Goes through {@link OffhandManager} for the claim, not just this lease: the offhand is
	 * held across ticks by whoever wants it there — a totem sits in it for a whole fight —
	 * which is a different lifetime from a click.
	 */
	public static boolean swapToOffhand(Object holder, AbstractContainerMenu menu, int menuSlot) {
		return click(holder, menu, menuSlot, 40, ContainerInput.SWAP);
	}

	/** The player's own inventory menu — the one that is open when no container is. */
	public static AbstractContainerMenu playerMenu() {
		LocalPlayer player = mc().player;
		return player == null ? null : player.inventoryMenu;
	}

	/**
	 * Whether {@code menu} is still the menu the player has open.
	 *
	 * <p>Identity <em>and</em> container id: the id catches a server that replaced the menu
	 * with another of the same kind, and the identity catches everything else.
	 */
	public static boolean isOpen(AbstractContainerMenu menu) {
		LocalPlayer player = mc().player;
		if (player == null || menu == null || mc().gameMode == null) {
			return false;
		}
		return player.containerMenu == menu && player.containerMenu.containerId == menu.containerId;
	}

	/**
	 * Inventory-menu slot index of a hotbar slot. The player inventory menu lays the main
	 * inventory out at 9..35 and the hotbar at 36..44, which is the one bit of slot arithmetic
	 * every caller would otherwise write for itself.
	 */
	public static int hotbarMenuSlot(int hotbarSlot) {
		return 36 + hotbarSlot;
	}

	// ---- lifecycle ---------------------------------------------------------

	/**
	 * End of client tick: notice a world or connection change, and never carry a cursor stack
	 * into the next tick.
	 *
	 * <p>A stack left on the cursor is not a cosmetic problem. It is invisible while no screen
	 * is open, it is dropped on the floor the moment one closes, and by then the module that
	 * lifted it has forgotten it existed.
	 */
	public static void onTickEnd() {
		Minecraft mc = mc();
		Object currentLevel = mc.level;
		Object currentConnection = mc.getConnection();
		if (currentLevel != level.get() || currentConnection != connection.get()) {
			// A new world or a new connection means every menu id we knew is meaningless and
			// every plan built on one is void. Drop the lot rather than resyncing it.
			level = new WeakReference<>(currentLevel);
			connection = new WeakReference<>(currentConnection);
			reset();
			return;
		}
		if (cooldown > 0) {
			cooldown--;
		}
		if (mc.player == null) {
			reset();
			return;
		}
		returnCursor();
	}

	/**
	 * Drops every claim without touching the game — for Panic and for a world change, where
	 * clicking would be at best pointless and at worst aimed at a menu that no longer exists.
	 */
	public static void reset() {
		owner = null;
		priority = 0;
		cooldown = 0;
		savedSlot = -1;
		cursorSource = -1;
	}

	/**
	 * Panic's entry point: put the cursor and the hotbar back <em>while the world is still
	 * there to accept the clicks</em>, then drop the lease.
	 */
	public static void panic() {
		returnCursor();
		restoreHotbar();
		reset();
	}

	/**
	 * Puts a carried stack back where it came from, or anywhere it fits.
	 *
	 * <p><b>Only ever a stack we lifted ourselves.</b> {@code cursorSource} is written by
	 * {@link #click} and by nothing else, so a player mid-drag in a chest — cursor full,
	 * {@code cursorSource} still -1 — is invisible to this method. Without that test the
	 * tidy-up would slam the item out of their hand into the first slot that would take it,
	 * every tick, which is a far worse bug than the one it is here to prevent.
	 */
	private static void returnCursor() {
		if (cursorSource < 0) {
			return;
		}
		LocalPlayer player = mc().player;
		if (player == null) {
			cursorSource = -1;
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu.getCarried().isEmpty()) {
			cursorSource = -1;
			return;
		}
		ItemStack carried = menu.getCarried();
		int target = cursorSource < menu.slots.size() && menu.getSlot(cursorSource).mayPlace(carried)
				? cursorSource
				: ContainerUtil.freeSlot(menu, carried);
		if (target >= 0) {
			ContainerUtil.click(menu, target, 0, ContainerInput.PICKUP);
		}
		cursorSource = -1;
	}

	private static void restoreHotbar() {
		LocalPlayer player = mc().player;
		if (player != null && savedSlot >= 0 && savedSlot < Inventory.SELECTION_SIZE) {
			player.getInventory().setSelectedSlot(savedSlot);
		}
		savedSlot = -1;
	}

	/** One line of internal state, for the debug read-out. */
	public static String debug() {
		return String.format("owner=%s priority=%d cooldown=%d saved=%d cursorFrom=%d",
				owner == null ? "none" : owner.getClass().getSimpleName(), priority, cooldown,
				savedSlot, cursorSource);
	}
}
