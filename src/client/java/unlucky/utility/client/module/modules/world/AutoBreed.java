package unlucky.utility.client.module.modules.world;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.RotationManager;

/** Breeds animals with the food predicate supplied by the animal itself. */
public class AutoBreed extends Module {
	public final EntityListSetting animals = add(new EntityListSetting("Animals", "Animal types to breed"));
	public final NumberSetting range = add(new NumberSetting("Range", "Interaction range", 4.5, 1, 6, 0.1));
	public final BooleanSetting adultsOnly = add(new BooleanSetting("Adults only", "Skip baby animals", true));
	public final ModeSetting hand = add(new ModeSetting("Hand", "Food hand selection", "Auto", "Auto", "Main", "Offhand"));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch food", "Select valid food from the hotbar", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate", "Face the animal before interacting", true));
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between interactions", 2, 0, 20, 1));
	public final BooleanSetting continuous = add(new BooleanSetting("Continuous", "Retry animals after the configured interval", false));
	public final NumberSetting retry = add(new NumberSetting("Retry interval", "Ticks before an already-clicked animal is eligible again", 6600, 20, 12000, 20), continuous::get);
	public final BooleanSetting ignoreNamed = add(new BooleanSetting("Ignore named animals", "Skip animals with custom names", false));
	private final Map<UUID, Integer> interacted = new HashMap<>();
	private int tick;
	private int cooldown;

	public AutoBreed() {
		super("AutoBreed", "Feeds nearby breedable animals without hardcoded food lists",
				Category.WORLD, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		tick++;
		if (mc().player == null || mc().level == null || mc().gameMode == null || cooldown-- > 0) return;
		Animal best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (var entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof Animal animal) || !valid(animal)) continue;
			double d = mc().player.distanceToSqr(animal);
			if (d < bestDistance) { best = animal; bestDistance = d; }
		}
		if (best == null) { InventoryActionCoordinator.release(this); return; }
		InteractionHand useHand = chooseHeldHand(best);
		int slot = -1;
		if (useHand == null && !hand.is("Offhand") && autoSwitch.get()) slot = foodSlot(best);
		if (useHand == null && slot < 0) { InventoryActionCoordinator.release(this); return; }
		if (rotate.get() && !RotationManager.face(best.getBoundingBox().getCenter(), 45,
				RotationManager.PRIORITY_FUNCTIONAL)) return;
		if (slot >= 0) {
			if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_FARMING)
					|| !InventoryActionCoordinator.owns(this)
					|| !InventoryActionCoordinator.selectHotbar(this, slot)) return;
			useHand = InteractionHand.MAIN_HAND;
		}
		InteractionResult result = mc().gameMode.interact(mc().player, best,
				new EntityHitResult(best), useHand);
		if (result.consumesAction()) mc().player.swing(useHand);
		interacted.put(best.getUUID(), tick);
		cooldown = delay.getInt();
		InventoryActionCoordinator.release(this);
	}

	private boolean valid(Animal animal) {
		if (!animal.isAlive() || !animals.allows(animal.getType())
				|| mc().player.distanceToSqr(animal) > range.get() * range.get()
				|| (adultsOnly.get() && animal.isBaby()) || !animal.canFallInLove()
				|| (ignoreNamed.get() && animal.hasCustomName())) return false;
		Integer last = interacted.get(animal.getUUID());
		return last == null || continuous.get() && tick - last >= retry.getInt();
	}

	private InteractionHand chooseHeldHand(Animal animal) {
		if (!hand.is("Offhand") && animal.isFood(mc().player.getMainHandItem())) return InteractionHand.MAIN_HAND;
		if (!hand.is("Main") && animal.isFood(mc().player.getOffhandItem())) return InteractionHand.OFF_HAND;
		return null;
	}

	private int foodSlot(Animal animal) {
		Inventory inv = mc().player.getInventory();
		for (int i = 0; i < Inventory.SELECTION_SIZE; i++) if (animal.isFood(inv.getItem(i))) return i;
		return -1;
	}

	@Override protected void onDisable() { InventoryActionCoordinator.release(this); interacted.clear(); }
}
