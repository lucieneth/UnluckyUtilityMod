package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Hold jump to thrust upward (and forward toward your look, optionally). */
public class Jetpack extends Module {
	public final NumberSetting maxHorizontalSpeed = add(new NumberSetting("Max horizontal speed", "Cap horizontal momentum; 0 disables the cap", 1.5, 0.0, 5.0, 0.1));
	public final BooleanSetting inLiquids = add(new BooleanSetting("In liquids", "Allow thrust in water and lava", false));
	public final BooleanSetting whileUsing = add(new BooleanSetting("While using item", "Allow thrust while using an item", false));
	public final NumberSetting thrust = add(new NumberSetting("Thrust", "Upward push per tick", 0.4, 0.1, 1.0, 0.05));
	public final BooleanSetting forward = add(new BooleanSetting("Forward", "Also push toward where you look", true));
	public final NumberSetting forwardPush = add(new NumberSetting("Forward push", "Forward strength", 0.3, 0.1, 1.0, 0.05));

	public Jetpack() {
		super("Jetpack", "Rocket upward on the jump key", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gui.screen() != null || !mc().options.keyJump.isDown()
				|| (!inLiquids.get() && (mc().player.isInWater() || mc().player.isInLava()))
				|| (!whileUsing.get() && mc().player.isUsingItem())) {
			return;
		}
		Vec3 velocity = mc().player.getDeltaMovement();
		velocity = new Vec3(velocity.x, thrust.get(), velocity.z);
		if (forward.get() && mc().options.keyUp.isDown()) {
			Vec3 look = mc().player.getLookAngle();
			velocity = velocity.add(look.x * forwardPush.get(), 0, look.z * forwardPush.get());
		}
		if (maxHorizontalSpeed.get() > 0.0 && velocity.horizontalDistance() > maxHorizontalSpeed.get()) {
			double scale = maxHorizontalSpeed.get() / velocity.horizontalDistance();
			velocity = new Vec3(velocity.x * scale, velocity.y, velocity.z * scale);
		}
		mc().player.setDeltaMovement(velocity);
		mc().player.fallDistance = 0;
	}
}
