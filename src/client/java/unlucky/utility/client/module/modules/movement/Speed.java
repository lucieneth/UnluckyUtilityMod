package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/**
 * Flat horizontal speed: pushes you along your input direction at a set
 * blocks-per-second while moving.
 */
public class Speed extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Blocks per second", 8.0, 4.0, 30.0, 0.5));

	public Speed() {
		super("Speed", "Move faster than normal", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gui.screen() != null || !MoveUtil.hasInput(mc().player)) {
			return;
		}
		Vec3 direction = MoveUtil.inputDirection(mc().player);
		if (direction.lengthSqr() < 1.0e-6) {
			return;
		}
		double perTick = speed.get() / 20.0;
		Vec3 velocity = mc().player.getDeltaMovement();
		mc().player.setDeltaMovement(direction.x * perTick, velocity.y, direction.z * perTick);
	}
}
