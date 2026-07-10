package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/**
 * CS-style bunny hopping: auto-jumps the instant you land and carries speed
 * through the hops, accelerating slightly each landing.
 */
public class BunnyHop extends Module {
	public final NumberSetting baseSpeed = add(new NumberSetting("Base speed", "Blocks per second on the ground", 9.0, 5.0, 25.0, 0.5));
	public final NumberSetting accel = add(new NumberSetting("Acceleration", "Bonus per hop", 0.5, 0.0, 3.0, 0.1));
	public final NumberSetting maxSpeed = add(new NumberSetting("Max speed", "Speed cap", 16.0, 6.0, 40.0, 1.0));
	public final BooleanSetting autoJump = add(new BooleanSetting("Auto jump", "Hop automatically while moving", true));
	public final NumberSetting jumpHeight = add(new NumberSetting("Jump height", "Hop height multiplier — below 1 = low skimming hops", 1.0, 0.25, 2.5, 0.05));

	private double currentSpeed;

	public BunnyHop() {
		super("BunnyHop", "CS-style bunny hopping", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		currentSpeed = baseSpeed.get() / 20.0;
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

		boolean onGround = mc().player.onGround();
		if (onGround) {
			currentSpeed = baseSpeed.get() / 20.0;
			if (autoJump.get()) {
				mc().player.jumpFromGround();
				Vec3 jump = mc().player.getDeltaMovement();
				mc().player.setDeltaMovement(jump.x, jump.y * jumpHeight.get(), jump.z);
			}
		} else {
			// accelerate a touch mid-air, capped
			currentSpeed = Math.min(currentSpeed + accel.get() / 400.0, maxSpeed.get() / 20.0);
		}

		Vec3 velocity = mc().player.getDeltaMovement();
		mc().player.setDeltaMovement(direction.x * currentSpeed, velocity.y, direction.z * currentSpeed);
	}
}
