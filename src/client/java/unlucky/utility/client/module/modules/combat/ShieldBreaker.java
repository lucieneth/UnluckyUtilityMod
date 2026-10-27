package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.HeldAttack;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Knocks a raised shield down the moment one points at you.
 *
 * <p>The disable is not a damage roll: a hit from anything carrying a weapon component with
 * {@code disableBlockingForSeconds} (every axe) puts the shield on a five-second cooldown, at
 * any charge. So this does not wait for a full swing — it spends one quick axe hit and hands the
 * fight back to whatever was doing it, which is usually Aura with a sword.
 *
 * <p>Only shields raised <em>towards</em> us count. Vanilla blocks by the blocker's head yaw, and
 * a player shielding against somebody else is already open to us; swinging an axe at them then
 * would only trade the better weapon for nothing.
 *
 * <p>With rotation on, the aim goes out a tick before the hit. The spoofed rotation rides the
 * end-of-tick movement packet, and an attack sent in the same tick would precede it.
 */
public class ShieldBreaker extends Module {
	public final NumberSetting range = add(new NumberSetting("Range",
			"Maximum distance to a blocking player", 3.0, 1, 6, 0.1));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Silently face the target the tick before hitting", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before the axe hit", true));
	public final NumberSetting cooldown = add(new NumberSetting("Cooldown",
			"Ticks to wait after a hit before trying again", 10, 0, 40, 1));
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never hit a friend's shield", true));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players",
			"Include client-side practice players, for testing", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	private int cooldownTicks;
	private int aimedAt = -1;

	public ShieldBreaker() {
		super("ShieldBreaker", "Disables a raised shield with a quick axe hit", Category.COMBAT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		cooldownTicks = 0;
		aimedAt = -1;
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		aimedAt = -1;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		// A Criticals hop may be holding our hit; the axe has to stay in hand until it is sent.
		if (InventoryActionCoordinator.owns(this)) {
			if (HeldAttack.isHeld()) {
				return;
			}
			InventoryActionCoordinator.release(this);
		}
		if (cooldownTicks > 0) {
			cooldownTicks--;
			return;
		}
		if (mc().gui.screen() != null || player.isSpectator() || player.isUsingItem()
				|| AutoEat.pauses(pauseOnEat)) {
			aimedAt = -1;
			return;
		}

		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, false, false)
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(range.get()).priority(TargetingUtil.Priority.CLOSEST)
						.extra(candidate -> CombatItemUtil.shieldRaisedAgainst(candidate, player)));
		int axe = axeSlot(player);
		if (target == null || axe < 0) {
			aimedAt = -1;
			return;
		}

		if (rotate.get()) {
			RotationManager.lookAt(target.getBoundingBox().getCenter());
			if (aimedAt != target.getId()) {
				aimedAt = target.getId(); // hit next tick, once the server has the rotation
				return;
			}
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)
				|| !InventoryActionCoordinator.selectHotbar(this, axe)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		CombatUtil.attack(target);
		if (!swapBack.get()) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		if (!HeldAttack.isHeld()) {
			InventoryActionCoordinator.release(this);
		}
		aimedAt = -1;
		cooldownTicks = cooldown.getInt();
	}

	/**
	 * Whether this module is the one that should answer {@code target} right now: a shield raised
	 * at us, in reach, and an axe in the hotbar. Aura asks so it does not spend its hits on the
	 * shield, or swap the sword back in under our axe.
	 */
	public boolean handles(Entity target) {
		LocalPlayer player = mc().player;
		return isEnabled() && player != null && target instanceof LivingEntity living
				&& player.distanceTo(living) <= range.get()
				&& CombatItemUtil.shieldRaisedAgainst(living, player) && axeSlot(player) >= 0;
	}

	private static int axeSlot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		if (CombatItemUtil.disablesShields(inventory.getItem(inventory.getSelectedSlot()))) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (CombatItemUtil.disablesShields(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}
}
