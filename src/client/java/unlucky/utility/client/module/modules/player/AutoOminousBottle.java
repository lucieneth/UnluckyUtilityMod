package unlucky.utility.client.module.modules.player;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.combat.Aura;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;

/**
 * Drinks an ominous bottle from the hotbar whenever Bad Omen runs out, for raid and trial
 * farms that should never sit idle.
 *
 * <p>It drinks the way AutoEat eats: {@code useItem} on the item itself, never the use key, so
 * a chest or villager under the crosshair is not opened instead. Vanilla would end that use on
 * the next tick because the key is not down, and {@code MinecraftMixin} keeps that one release
 * out while a drink is under way.
 *
 * <p>The hotbar is held under the inventory lease at automation priority for the whole drink.
 * Anything more urgent can take it; the drink is then cut short and tried again later.
 */
public class AutoOminousBottle extends Module {
	public final BooleanSetting waitRaidOmen = add(new BooleanSetting("Wait for raid",
			"Hold off while a Raid Omen is counting down to a raid", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before drinking", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	/** Ticks to wait after a drink before checking again, so the effect has time to arrive. */
	private static final int SETTLE_TICKS = 20;

	private boolean drinking;
	private int waitTicks;

	public AutoOminousBottle() {
		super("AutoOminousBottle", "Drinks an ominous bottle when Bad Omen runs out", Category.PLAYER,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Whether a drink of ours is in progress; MinecraftMixin keeps vanilla from ending it. */
	public static boolean drinking() {
		AutoOminousBottle module = UnluckyClient.INSTANCE.modules.get(AutoOminousBottle.class);
		return module.isEnabled() && module.drinking;
	}

	@Override
	protected void onEnable() {
		drinking = false;
		waitTicks = 0;
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Override
	protected void onPanic() {
		stop();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().gameMode == null) {
			drinking = false;
			InventoryActionCoordinator.release(this);
			return;
		}
		if (drinking) {
			// lease taken by something more urgent, or the bottle is gone: the drink is over
			if (!player.isUsingItem() || !InventoryActionCoordinator.owns(this)) {
				stop();
				waitTicks = SETTLE_TICKS;
			}
			return;
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		if (mc().gui.screen() != null || player.isUsingItem() || player.isCreative()
				|| AutoEat.pauses(pauseOnEat) || Aura.currentTarget != null) {
			return;
		}
		if (player.hasEffect(MobEffects.BAD_OMEN)
				|| (waitRaidOmen.get() && player.hasEffect(MobEffects.RAID_OMEN))) {
			return;
		}
		int slot = bottleSlot(player.getInventory());
		if (slot < 0 || !InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_AUTOMATION)) {
			return;
		}
		if (!InventoryActionCoordinator.selectHotbar(this, slot)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		mc().gameMode.useItem(player, InteractionHand.MAIN_HAND);
		if (player.isUsingItem()) {
			drinking = true;
		} else {
			stop(); // refused; do not hammer the server with retries
			waitTicks = SETTLE_TICKS;
		}
	}

	private void stop() {
		LocalPlayer player = mc().player;
		if (drinking && player != null && player.isUsingItem() && mc().gameMode != null
				&& player.getUseItem().is(Items.OMINOUS_BOTTLE)) {
			mc().gameMode.releaseUsingItem(player);
		}
		drinking = false;
		if (!swapBack.get()) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		InventoryActionCoordinator.release(this);
	}

	private static int bottleSlot(Inventory inventory) {
		if (inventory.getItem(inventory.getSelectedSlot()).is(Items.OMINOUS_BOTTLE)) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (inventory.getItem(slot).is(Items.OMINOUS_BOTTLE)) {
				return slot;
			}
		}
		return -1;
	}
}
