package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Hold the jump key while gliding to accelerate in your look direction,
 * no rockets needed.
 */
public class ElytraFly extends Module {
	public final NumberSetting maxSpeed = add(new NumberSetting("Max speed", "Top speed in blocks per tick", 1.7, 0.5, 3.0, 0.1));
	public final NumberSetting acceleration = add(new NumberSetting("Acceleration", "Boost strength per tick", 0.08, 0.02, 0.3, 0.02));

	public ElytraFly() {
		super("ElytraFly", "Hold jump while gliding for a speed boost", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (mc().player == null || !mc().player.isFallFlying() || !mc().options.keyJump.isDown()) {
			return;
		}
		Vec3 velocity = mc().player.getDeltaMovement()
				.add(mc().player.getLookAngle().scale(acceleration.get()));
		double speed = velocity.length();
		if (speed > maxSpeed.get()) {
			velocity = velocity.scale(maxSpeed.get() / speed);
		}
		mc().player.setDeltaMovement(velocity);
	}
}
