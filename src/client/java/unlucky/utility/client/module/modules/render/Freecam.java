package unlucky.utility.client.module.modules.render;

import net.minecraft.client.Options;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Detaches the camera and lets it fly free while your body stays put.
 * Movement keys steer the camera; the player is frozen while enabled.
 */
public class Freecam extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Flight speed", 1.0, 0.2, 5.0, 0.1));

	private Vec3 position = Vec3.ZERO;
	private float yaw;
	private float pitch;
	private long lastMoveNanos;

	public Freecam() {
		super("Freecam", "Fly the camera around freely", Category.RENDER, GLFW.GLFW_KEY_V);
	}

	@Override
	protected void onEnable() {
		position = mc().gameRenderer.mainCamera().position();
		if (mc().player != null) {
			yaw = mc().player.getYRot();
			pitch = mc().player.getXRot();
		}
		lastMoveNanos = System.nanoTime();
	}

	@Override
	public void onTick() {
		if (mc().level == null) {
			setEnabledSilently(false);
		}
	}

	/** Mouse rotation, same 0.15 scale as vanilla {@code Entity.turn}. */
	public void turn(double dx, double dy) {
		yaw += (float) (dx * 0.15);
		pitch = Mth.clamp(pitch + (float) (dy * 0.15), -90.0f, 90.0f);
	}

	public float getYaw() {
		return yaw;
	}

	public float getPitch() {
		return pitch;
	}

	/** Advances movement (frame-rate independent) and returns the camera position. */
	public Vec3 advance() {
		long now = System.nanoTime();
		double dt = Math.min((now - lastMoveNanos) / 1.0e9, 0.1);
		lastMoveNanos = now;

		if (mc().gui.screen() == null) {
			Options options = mc().options;
			double forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
			double strafe = (options.keyRight.isDown() ? 1 : 0) - (options.keyLeft.isDown() ? 1 : 0);
			double vertical = (options.keyJump.isDown() ? 1 : 0) - (options.keyShift.isDown() ? 1 : 0);
			if (forward != 0 || strafe != 0 || vertical != 0) {
				double yawRad = Math.toRadians(yaw);
				Vec3 forwardVec = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
				// right hand of a south-facing (yaw 0) player points west (-X)
				Vec3 rightVec = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
				double blocksPerSecond = speed.get() * 10.0;
				position = position
						.add(forwardVec.scale(forward * blocksPerSecond * dt))
						.add(rightVec.scale(strafe * blocksPerSecond * dt))
						.add(0, vertical * blocksPerSecond * dt, 0);
			}
		}
		return position;
	}
}
