package unlucky.utility.client.module.modules.player;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Keeps the four 2x2 crafting slots usable as carried storage.
 *
 * <p>The mechanism is one dropped packet and nothing else. The player inventory menu is
 * never <em>opened</em> over the wire — the server treats container 0 as permanently
 * available — so the only thing that empties the crafting grid is the
 * {@link ServerboundContainerClosePacket} the client sends when the inventory screen goes
 * away, which the server answers with {@code doCloseContainer()} and its return-to-inventory
 * sweep. Withhold that one packet and the grid keeps its contents; the local screen still
 * closes normally because the client half of the close never depended on it.
 *
 * <p>Only container 0 is ever withheld. A chest, shulker or merchant menu carries a
 * different id and closes exactly as vanilla does — suppressing those would strand a real
 * server-side container and desync every subsequent click into it.
 *
 * <p>The dangerous state is not "grid full", it is "client believes a menu is open that the
 * server has already torn down". Respawn, dimension change and disconnect all invalidate the
 * assumption from the server side without telling us, so each of them drops the module's
 * belief that it is holding anything ({@link #resetBelief}) rather than trying to reconcile it.
 *
 * @see #safeClose for why disabling sends the close it spent the whole time suppressing
 */
public class XCarry extends Module {
	public final BooleanSetting safeClose = add(new BooleanSetting("Safe close on disable",
			"Send the close packet that was being withheld, so the server returns the grid "
					+ "contents to your inventory instead of holding them until the next real close",
			true));
	public final BooleanSetting warnOccupied = add(new BooleanSetting("Show warning when occupied",
			"Say something in chat when the module is switched off while the extra slots still "
					+ "hold items", true));

	/**
	 * Set only for the duration of {@link #safeClose}. The suppression test runs on whatever
	 * thread {@code send} was called from, so this is deliberately the narrowest possible
	 * window: one packet, built and handed to the connection inside the same try/finally.
	 */
	private static volatile boolean bypass;

	private ClientLevel level;

	public XCarry() {
		super("XCarry", "Keeps the 2x2 crafting slots usable as four extra carried slots",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * Whether the outbound close for {@code containerId} should be dropped.
	 *
	 * <p>Called from the one existing outbound-packet hook rather than a second mixin on the
	 * same method. The player-menu id is read from the live menu instead of assuming the
	 * vanilla constant, so a future id change cannot silently turn this into a filter that
	 * suppresses somebody's chest.
	 */
	public boolean suppressesClose(int containerId) {
		if (!isEnabled() || bypass) {
			return false;
		}
		LocalPlayer player = mc().player;
		return player != null && containerId == player.inventoryMenu.containerId;
	}

	@Override
	protected void onEnable() {
		level = mc().level;
	}

	@Override
	protected void onDisable() {
		if (warnOccupied.get() && occupiedSlots() > 0) {
			ChatUtil.info("XCarry: " + occupiedSlots() + " item stack(s) still in the crafting grid"
					+ (safeClose.get() ? " — closing so the server hands them back."
							: " — they stay there until the next real inventory close."));
		}
		if (safeClose.get()) {
			safeClose();
		}
		resetBelief();
	}

	@Override
	protected void onPanic() {
		// A panic wants the server-visible state resolved, not deferred: always close.
		safeClose();
		resetBelief();
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			resetBelief();
			return;
		}
		// A new level means a new server-side player entity, so whatever container 0 held
		// belongs to a session that no longer exists.
		if (mc().level != level) {
			resetBelief();
			level = mc().level;
		}
	}

	/**
	 * Sends the close that the module has been withholding.
	 *
	 * <p>{@link #bypass} rather than a plain "module already disabled" check because the
	 * ordinary disable path calls this <em>before</em> {@code enabled} flips, and because
	 * Panic calls it while the module is still on. Guarding the flag itself is the only
	 * version that is correct from both callers.
	 */
	private void safeClose() {
		LocalPlayer player = mc().player;
		ClientPacketListener connection = mc().getConnection();
		if (player == null || connection == null) {
			return;
		}
		bypass = true;
		try {
			connection.send(new ServerboundContainerClosePacket(player.inventoryMenu.containerId));
		} finally {
			bypass = false;
		}
	}

	/** How many of the four crafting slots currently hold something. */
	private int occupiedSlots() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return 0;
		}
		int occupied = 0;
		for (int i = 0; i < InventoryMenu.CRAFT_SLOT_COUNT; i++) {
			ItemStack stack = player.inventoryMenu.getSlot(InventoryMenu.CRAFT_SLOT_START + i).getItem();
			if (!stack.isEmpty()) {
				occupied++;
			}
		}
		return occupied;
	}

	private void resetBelief() {
		level = mc().level;
	}
}
