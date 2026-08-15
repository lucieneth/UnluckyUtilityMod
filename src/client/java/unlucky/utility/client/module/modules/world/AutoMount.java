package unlucky.utility.client.module.modules.world;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.EntityHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.EntityListSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.RotationManager;

/**
 * Climbs onto the nearest rideable entity you have listed.
 *
 * <p>Three guards are worth their lines. Sneaking is the universal "I am doing
 * something else with this animal" signal, so it stops the module dead. A spawn
 * egg in hand means the click would spawn rather than mount. And anything that
 * needs a saddle to steer is skipped until it has one, because mounting an
 * unsaddled pig is a ride you cannot end quickly.
 *
 * <p>Reference: Meteor's AutoMount.
 */
public class AutoMount extends Module {
	public final EntityListSetting entities = add(new EntityListSetting("Entities",
			"Rideable entity types to mount"));
	public final NumberSetting range = add(new NumberSetting("Range",
			"Interaction range", 4.0, 1, 6, 0.1));
	public final BooleanSetting requireSaddle = add(new BooleanSetting("Require saddle",
			"Only mount entities that are already saddled", true));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Face the entity before mounting", true));

	public AutoMount() {
		super("AutoMount", "Mounts nearby rideable entities", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null || mc().gameMode == null) {
			return;
		}
		if (mc().player.isPassenger() || mc().player.isShiftKeyDown()
				|| mc().player.getMainHandItem().getItem() instanceof SpawnEggItem) {
			return;
		}

		Entity best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!valid(entity)) {
				continue;
			}
			double distance = mc().player.distanceToSqr(entity);
			if (distance < bestDistance) {
				best = entity;
				bestDistance = distance;
			}
		}
		if (best == null) {
			return;
		}
		if (rotate.get() && !RotationManager.face(best.getBoundingBox().getCenter(), 45,
				RotationManager.PRIORITY_FUNCTIONAL)) {
			return;
		}
		InteractionResult result = mc().gameMode.interact(mc().player, best,
				new EntityHitResult(best), InteractionHand.MAIN_HAND);
		if (result.consumesAction()) {
			mc().player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private boolean valid(Entity entity) {
		if (!entity.isAlive() || !entities.allows(entity.getType())
				|| mc().player.distanceToSqr(entity) > range.get() * range.get()) {
			return false;
		}
		if (entity.isVehicle()) {
			return false; // somebody is already on it
		}
		// Llamas carry chests rather than saddles, and boats and rafts have no
		// tack at all — the saddle rule only makes sense for things that take one.
		return !requireSaddle.get() || !(entity instanceof Mob mob) || mob.isSaddled();
	}
}
