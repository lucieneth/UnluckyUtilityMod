package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/** Collision-aware flight for the boat currently controlled by the local player. */
public class BoatFly extends Module {
	public final NumberSetting horizontalSpeed = add(new NumberSetting("Horizontal speed",
			"WASD speed in blocks per tick", 0.6, 0.05, 3.0, 0.05));
	public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical speed",
			"Speed while holding jump or the sprint key", 0.3, 0.05, 2.0, 0.05));
	public final NumberSetting fallSpeed = add(new NumberSetting("Fall speed",
			"Downward drift with neither vertical key held; 0 hovers", 0.0, 0.0, 1.0, 0.01));
	public final BooleanSetting lockYaw = add(new BooleanSetting("Lock yaw",
			"Point the boat where your camera is facing", true));
	public final BooleanSetting antiKick = add(new BooleanSetting("Anti kick",
			"Periodically dip far enough to reset vanilla's floating-vehicle timer", true));
	public final NumberSetting antiKickDelay = add(new NumberSetting("Anti-kick delay",
			"Ticks between floating-timer reset dips", 20, 5, 60, 1), antiKick::get);
	public final NumberSetting ceiling = add(new NumberSetting("Ceiling",
			"Do not ascend above this world height; 0 disables the limit", 0, 0, 512, 1));
	public final BooleanSetting stopHorizontal = add(new BooleanSetting("Stop without input",
			"Set horizontal boat motion to zero after releasing WASD", true));

	private int flightTicks;

	public BoatFly() {
		super("BoatFly", "Fly the boat you are riding with WASD, jump, and the sprint key", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		flightTicks = 0;
	}

	@Override
	protected void onDisable() {
		flightTicks = 0;
	}

	/**
	 * Replaces only the vector passed into vanilla's collision-aware move call.
	 * Space ascends and the sprint key descends; sneak remains vanilla's dismount key.
	 */
	public Vec3 movement(AbstractBoat boat, Vec3 vanilla) {
		LocalPlayer player = mc().player;
		if (!isEnabled() || player == null || player.getVehicle() != boat) {
			return vanilla;
		}

		if (lockYaw.get()) {
			boat.setYRot(player.getYRot());
		}

		Vec3 direction = MoveUtil.inputDirection(player);
		Vec3 previous = boat.getDeltaMovement();
		double x = direction.x * horizontalSpeed.get();
		double z = direction.z * horizontalSpeed.get();
		if (!stopHorizontal.get() && direction.horizontalDistanceSqr() == 0.0) {
			x = previous.x;
			z = previous.z;
		}
		double y = -fallSpeed.get();
		if (mc().options.keyJump.isDown()) {
			y = verticalSpeed.get();
		} else if (mc().options.keySprint.isDown()) {
			y = -verticalSpeed.get();
		}
		if (ceiling.get() > 0.0 && boat.getY() >= ceiling.get() && y > 0.0) y = 0.0;
		// Vanilla resets its vehicle-floating counter below -0.03125 blocks/tick.
		// One unobtrusive -0.04 dip per cycle prevents a hovering boat from reaching
		// the kick threshold without touching packet or collision handling.
		if (antiKick.get() && ++flightTicks % antiKickDelay.getInt() == 0) {
			y = Math.min(y, -0.04);
		}

		Vec3 movement = new Vec3(x, y, z);
		boat.setDeltaMovement(movement);
		return movement;
	}
}
