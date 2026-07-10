package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Ignores levitation (shulker bullets, the potion) so you stop drifting upward.
 * Movement is client-authoritative on vanilla servers, so simply not applying
 * the effect in {@code LivingEntity.travelInAir} is enough — see
 * {@code LivingEntityMixin}. The effect icon still shows; only its motion is
 * dropped.
 */
public class AntiLevitation extends Module {
	public final BooleanSetting levitation = add(new BooleanSetting("Levitation",
			"Ignore the upward pull from levitation", true));
	public final BooleanSetting slowFalling = add(new BooleanSetting("Slow falling",
			"Also ignore slow falling, so you drop at normal speed", false));

	public AntiLevitation() {
		super("AntiLevitation", "Ignores the levitation effect", Category.MOVEMENT);
	}
}
