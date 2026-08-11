package unlucky.utility.client.module.modules.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;

/** Shears the nearest server-confirmed ready adult, one coordinated interaction at a time. */
public class AutoShear extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Interaction range", 5, 1, 6, 0.1));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch shears", "Select safe shears from the hotbar", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back", "Restore the previous hotbar slot", true));
	public final BooleanSetting antiBreak = add(new BooleanSetting("Anti-break", "Reject shears below minimum durability", true));
	public final NumberSetting minimumDurability = add(new NumberSetting("Minimum durability", "Remaining durability percentage", 10, 1, 100, 1), antiBreak::get);
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate", "Face the sheep before interacting", true));
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between interactions", 2, 0, 20, 1));
	public final BooleanSetting adultsOnly = add(new BooleanSetting("Adults only", "Skip lambs", true));
	public final NumberSetting maxInteractions = add(new NumberSetting("Max interactions per tick",
			"Maximum sheep sheared in one tick", 1, 1, 8, 1));
	private final Map<UUID, Integer> recent = new HashMap<>();
	private int tick;
	private int cooldown;

	public AutoShear() {
		super("AutoShear", "Shears nearby sheep with durability-safe coordinated switching",
				Category.WORLD, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		tick++;
		if (mc().player == null || mc().level == null || mc().gameMode == null || cooldown-- > 0) return;
		int completed = 0;
		while (completed < maxInteractions.getInt()) {
			Sheep best = nearestValid();
			if (best == null || !shear(best)) break;
			completed++;
		}
		if (completed > 0) cooldown = delay.getInt();
		InventoryActionCoordinator.release(this);
	}

	private Sheep nearestValid() {
		Sheep best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (var entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof Sheep sheep) || !valid(sheep)) continue;
			double d = mc().player.distanceToSqr(sheep);
			if (d < bestDistance) { best = sheep; bestDistance = d; }
		}
		return best;
	}

	private boolean shear(Sheep best) {
		InteractionHand hand = safeShears(mc().player.getMainHandItem()) ? InteractionHand.MAIN_HAND
				: safeShears(mc().player.getOffhandItem()) ? InteractionHand.OFF_HAND : null;
		int slot = hand == null && autoSwitch.get() ? shearsSlot() : -1;
		if (hand == null && slot < 0) return false;
		if (rotate.get() && !RotationManager.face(best.getBoundingBox().getCenter(), 45,
				RotationManager.PRIORITY_FUNCTIONAL)) return false;
		if (slot >= 0) {
			if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_FARMING)
					|| !InventoryActionCoordinator.owns(this)
					|| !InventoryActionCoordinator.selectHotbar(this, slot)) return false;
			hand = InteractionHand.MAIN_HAND;
		}
		InteractionResult result = mc().gameMode.interact(mc().player, best, new EntityHitResult(best), hand);
		if (result.consumesAction()) mc().player.swing(hand);
		recent.put(best.getUUID(), tick);
		if (!swapBack.get()) InventoryActionCoordinator.keepHotbar(this);
		return true;
	}

	private boolean valid(Sheep sheep) {
		return sheep.isAlive() && sheep.readyForShearing() && (!adultsOnly.get() || !sheep.isBaby())
				&& mc().player.distanceToSqr(sheep) <= range.get() * range.get()
				&& tick - recent.getOrDefault(sheep.getUUID(), Integer.MIN_VALUE / 2) > 20;
	}

	private int shearsSlot() {
		Inventory inv = mc().player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) if (safeShears(inv.getItem(i))) return i;
		return -1;
	}

	private boolean safeShears(ItemStack stack) {
		if (!stack.is(Items.SHEARS)) return false;
		if (!antiBreak.get() || !stack.isDamageableItem()) return true;
		return (stack.getMaxDamage() - stack.getDamageValue()) * 100.0 / stack.getMaxDamage()
				>= minimumDurability.get();
	}

	@Override protected void onDisable() { InventoryActionCoordinator.release(this); recent.clear(); }
}
