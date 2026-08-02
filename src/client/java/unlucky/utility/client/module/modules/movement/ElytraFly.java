package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Two ways to fly an elytra.
 *
 * <p><b>Boost</b> is the original: hold jump while gliding and you accelerate
 * along your look vector, capped at a top speed. Vanilla's glide physics still
 * run underneath, so you keep the dive-and-climb feel — pitch changes speed and
 * you sink whenever you stop pushing.
 *
 * <p><b>Static</b> replaces the glide physics outright (see
 * {@code LivingEntityMixin#unlucky$elytraFlyStatic}). Horizontal movement comes
 * only from WASD relative to your <i>yaw</i>, so looking up or down doesn't
 * change where you go; vertical comes only from jump and sneak. Nothing
 * accumulates — release the keys and you stop exactly there, hovering, which is
 * what makes it precise enough to fly a build with. Sink controls how much of
 * the natural fall is kept, and at 0 you don't drop at all.
 */
public class ElytraFly extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Boost accelerates along your look while you hold jump, on top of vanilla gliding. "
					+ "Static ignores vanilla gliding entirely: WASD moves you flat, jump and sneak "
					+ "move you up and down, and letting go stops you dead in the air.",
			"Boost", "Boost", "Static"));

	public final NumberSetting maxSpeed = add(new NumberSetting("Max speed",
			"Top speed in blocks per tick", 1.7, 0.5, 3.0, 0.1), () -> mode.is("Boost"));
	public final NumberSetting acceleration = add(new NumberSetting("Acceleration",
			"Strength added per tick", 0.08, 0.02, 0.3, 0.02), () -> mode.is("Boost"));

	public final NumberSetting horizontalSpeed = add(new NumberSetting("Horizontal speed",
			"Blocks per tick under WASD", 1.0, 0.1, 3.0, 0.05), () -> mode.is("Static"));
	public final NumberSetting verticalSpeed = add(new NumberSetting("Vertical speed",
			"Blocks per tick under jump and sneak", 0.5, 0.05, 2.0, 0.05), () -> mode.is("Static"));
	public final NumberSetting sink = add(new NumberSetting("Sink",
			"How much of the natural fall is kept. 0 hovers in place with no keys held.",
			0.0, 0.0, 1.0, 0.01), () -> mode.is("Static"));

	public ElytraFly() {
		super("ElytraFly", "Boost or fly flat while gliding", Category.MOVEMENT);
	}

	/** True while Static owns the glide, i.e. the mixin should replace vanilla's movement. */
	public boolean staticFlight() {
		return isEnabled() && mode.is("Static");
	}

	@Override
	public void onTick() {
		if (!mode.is("Boost") || mc().player == null || !mc().player.isFallFlying()
				|| !mc().options.keyJump.isDown()) {
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

	/**
	 * The whole of Static's movement for one tick, replacing whatever vanilla's
	 * glide physics worked out.
	 *
	 * @param vanilla what vanilla would have moved us by — only its downward Y is
	 *                reused, scaled by Sink
	 */
	public Vec3 staticMovement(Vec3 vanilla) {
		LocalPlayer player = mc().player;
		if (player == null) {
			return vanilla;
		}
		// only a fall is carried over: an upward kick (a rocket, a bounce) would
		// otherwise fight the hover for several ticks
		double y = vanilla.y < 0.0 ? vanilla.y * sink.get() : 0.0;
		double x = 0.0;
		double z = 0.0;

		// a screen eats the keys anyway; the hover still applies, so opening your
		// inventory mid-flight parks you in the air instead of dropping you
		if (mc().gui.screen() == null) {
			double forwardInput = axis(mc().options.keyUp.isDown(), mc().options.keyDown.isDown());
			double strafeInput = axis(mc().options.keyRight.isDown(), mc().options.keyLeft.isDown());
			if (forwardInput != 0.0 || strafeInput != 0.0) {
				// yaw only — pitch deliberately does nothing, that's what makes it precise.
				// Normalising the sum keeps diagonals the same speed as the cardinals.
				Vec3 forward = Vec3.directionFromRotation(0.0f, player.getYRot());
				Vec3 right = Vec3.directionFromRotation(0.0f, player.getYRot() + 90.0f);
				Vec3 heading = forward.scale(forwardInput).add(right.scale(strafeInput)).normalize();
				x = heading.x * horizontalSpeed.get();
				z = heading.z * horizontalSpeed.get();
			}
			if (mc().options.keyJump.isDown()) {
				y += verticalSpeed.get();
			}
			if (mc().options.keyShift.isDown()) {
				y -= verticalSpeed.get();
			}
		}
		return new Vec3(x, y, z);
	}

	private static double axis(boolean positive, boolean negative) {
		return (positive ? 1.0 : 0.0) - (negative ? 1.0 : 0.0);
	}
}
