package unlucky.utility.client.module.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.CombatItemUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;

/**
 * Puts the right weapon in hand at the moment of a hit, whoever made the hit.
 *
 * <p>It runs from the attack hook rather than from a tick, which is the point: vanilla's
 * {@code attack} syncs the carried item before it sends the attack packet, so a slot chosen
 * here reaches the server <em>with</em> the hit, not a tick after it. Your own clicks, Aura,
 * TriggerBot and a Criticals replay all go through the same door and all get the same choice.
 *
 * <p>One honest limit: the server refreshes attribute modifiers on its own tick, so the base
 * attack damage of a just-swapped weapon applies from the next hit on. Everything that reads the
 * stack itself — the axe's shield disable, the mace's fall bonus, Sharpness and friends — is
 * already in effect on this one, and those are the reasons a swap is worth making mid-fight.
 *
 * <p>Swapping keeps the charged cooldown for the same reason (the server resets it on its tick,
 * after the hit), which is why the swap-back waits for a quiet spell instead of being instant.
 */
public class AutoWeapon extends Module {
	public final ModeSetting prefer = add(new ModeSetting("Prefer",
			"What to hold for an ordinary hit", "Damage", "Damage", "Sword", "Axe"));
	public final BooleanSetting axeShields = add(new BooleanSetting("Axe vs shields",
			"Hit a shield raised towards you with something that disables it", true));
	public final BooleanSetting maceFalling = add(new BooleanSetting("Mace when falling",
			"Use a mace when the fall is worth its bonus", true));
	public final NumberSetting minFall = add(new NumberSetting("Min fall",
			"Fall distance at which the mace takes over", 1.5, 0.5, 10, 0.5), maceFalling::get);
	public final BooleanSetting onlyLiving = add(new BooleanSetting("Only living targets",
			"Leave crystals, boats and other non-living hits alone", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before once the fight goes quiet", true));
	public final NumberSetting swapBackDelay = add(new NumberSetting("Swap back delay",
			"Ticks without a hit before returning", 20, 0, 100, 1), swapBack::get);

	private int returnSlot = -1;
	private int switchedTo = -1;
	private int quietTicks;

	public AutoWeapon() {
		super("AutoWeapon", "Switches to the best weapon for each hit", Category.COMBAT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		returnSlot = -1;
		switchedTo = -1;
	}

	@Override
	protected void onDisable() {
		restore();
	}

	@Override
	protected void onPanic() {
		returnSlot = -1;
		switchedTo = -1;
	}

	/** Called from the attack hook, before the attack packet is built. */
	public void onAttack(Entity target) {
		LocalPlayer player = mc().player;
		if (player == null || (onlyLiving.get() && !(target instanceof LivingEntity))) {
			return;
		}
		quietTicks = 0;
		int slot = choose(player, target);
		Inventory inventory = player.getInventory();
		int selected = inventory.getSelectedSlot();
		if (slot < 0 || slot == selected) {
			return;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)) {
			return;
		}
		if (InventoryActionCoordinator.selectHotbar(this, slot)) {
			if (returnSlot < 0) {
				returnSlot = selected;
			}
			switchedTo = slot;
		}
		// The swap is the point, so the coordinator must not undo it on release.
		InventoryActionCoordinator.keepHotbar(this);
		InventoryActionCoordinator.release(this);
	}

	@Override
	public void onTick() {
		if (returnSlot < 0 || mc().player == null) {
			return;
		}
		if (mc().player.getInventory().getSelectedSlot() != switchedTo) {
			// The player (or another module) picked a slot since; that choice stands.
			returnSlot = -1;
			switchedTo = -1;
			return;
		}
		if (swapBack.get() && ++quietTicks >= swapBackDelay.getInt()
				&& !InventoryActionCoordinator.isBusy()) {
			restore();
		}
	}

	private void restore() {
		if (returnSlot >= 0 && swapBack.get() && mc().player != null
				&& mc().player.getInventory().getSelectedSlot() == switchedTo) {
			mc().player.getInventory().setSelectedSlot(returnSlot);
		}
		returnSlot = -1;
		switchedTo = -1;
	}

	/** The hotbar slot to hit with, or -1 to leave the hand as it is. */
	private int choose(LocalPlayer player, Entity target) {
		if (axeShields.get() && target instanceof LivingEntity living
				&& CombatItemUtil.shieldRaisedAgainst(living, player)) {
			int axe = best(player, Kind.SHIELD_BREAKER);
			if (axe >= 0) {
				return axe;
			}
		}
		if (maceFalling.get() && !player.onGround() && player.fallDistance >= minFall.get()) {
			int mace = best(player, Kind.MACE);
			if (mace >= 0) {
				return mace;
			}
		}
		int preferred = prefer.is("Sword") ? best(player, Kind.SWORD)
				: prefer.is("Axe") ? best(player, Kind.AXE) : -1;
		return preferred >= 0 ? preferred : best(player, Kind.ANY);
	}

	private enum Kind {
		ANY,
		SWORD,
		AXE,
		MACE,
		SHIELD_BREAKER
	}

	/**
	 * Highest-damage hotbar weapon of a kind. The held slot wins ties, so an equal weapon
	 * already in hand never causes a swap.
	 */
	private static int best(LocalPlayer player, Kind kind) {
		Inventory inventory = player.getInventory();
		int selected = inventory.getSelectedSlot();
		ItemStack held = inventory.getItem(selected);
		int best = matches(held, kind) ? selected : -1;
		double bestDamage = best >= 0 ? CombatItemUtil.meleeDamage(held) : Double.NEGATIVE_INFINITY;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (slot == selected || !matches(stack, kind)) {
				continue;
			}
			double damage = CombatItemUtil.meleeDamage(stack);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = slot;
			}
		}
		return best;
	}

	private static boolean matches(ItemStack stack, Kind kind) {
		return switch (kind) {
			case ANY -> CombatItemUtil.isMeleeWeapon(stack);
			case SWORD -> CombatItemUtil.isSword(stack);
			case AXE -> CombatItemUtil.isAxe(stack);
			case MACE -> CombatItemUtil.isMace(stack);
			case SHIELD_BREAKER -> CombatItemUtil.disablesShields(stack);
		};
	}
}
