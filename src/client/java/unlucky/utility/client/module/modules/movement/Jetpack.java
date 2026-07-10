package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Hold jump to thrust upward (and forward toward your look, optionally). */
public class Jetpack extends Module {
	public final NumberSetting thrust = add(new NumberSetting("Thrust", "Upward push per tick", 0.4, 0.1, 1.0, 0.05));
	public final BooleanSetting forward = add(new BooleanSetting("Forward", "Also push toward where you look", true));
	public final NumberSetting forwardPush = add(new NumberSetting("Forward push", "Forward strength", 0.3, 0.1, 1.0, 0.05));

	public Jetpack() {
		super("Jetpack", "Rocket upward on the jump key", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gui.screen() != null || !mc().options.keyJump.isDown()) {
			return;
		}
		Vec3 velocity = mc().player.getDeltaMovement();
		velocity = new Vec3(velocity.x, thrust.get(), velocity.z);
		if (forward.get() && mc().options.keyUp.isDown()) {
			Vec3 look = mc().player.getLookAngle();
			velocity = velocity.add(look.x * forwardPush.get(), 0, look.z * forwardPush.get());
		}
		mc().player.setDeltaMovement(velocity);
		mc().player.fallDistance = 0;
	}
}
