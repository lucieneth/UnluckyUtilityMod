package unlucky.utility.client.module.modules.render;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Perspektive-style freelook: the camera swings a full 360° around you while
 * your body keeps facing (and walking) wherever it already was — so you can
 * check your back mid-sprint, or watch someone without ever turning to them.
 *
 * <p>The recipe is the one Perspektive uses: force third person, swallow the
 * mouse deltas into our own yaw/pitch instead of the player's ({@code
 * MouseHandlerMixin}), and override the camera rotation in {@code
 * Camera.alignWithEntity} <i>just before</i> vanilla's {@code move()} pushes the
 * camera backwards — so it orbits along our look direction, not the player's.
 *
 * <p>On top of that the rotation is eased toward the mouse target every frame,
 * which is what makes the orbit feel smooth rather than snappy.
 */
public class Freelook extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Hold the key or toggle it", "Hold", "Hold", "Toggle"));
	public final KeybindSetting key = add(new KeybindSetting("Freelook key", "Hold / toggle freelook", GLFW.GLFW_KEY_LEFT_ALT));
	public final NumberSetting smoothing = add(new NumberSetting("Smoothing", "Camera easing (0 = instant snap)", 8.0, 0.0, 20.0, 0.5));
	public final NumberSetting sensitivity = add(new NumberSetting("Sensitivity", "Freelook mouse speed multiplier", 1.0, 0.2, 3.0, 0.1));
	public final BooleanSetting restore = add(new BooleanSetting("Restore view", "Return to the perspective you were in", true));

	private boolean active;
	private boolean toggled;
	private boolean keyWasDown;
	private CameraType previous = CameraType.FIRST_PERSON;

	/** Mouse target. */
	private float yaw;
	private float pitch;
	/** Eased values actually handed to the camera. */
	private float renderYaw;
	private float renderPitch;
	private long lastFrame = System.nanoTime();

	public Freelook() {
		super("Freelook", "Look around freely without turning your body", Category.RENDER);
	}

	@Override
	protected void onDisable() {
		stop();
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			stop();
		}
	}

	public boolean isActive() {
		return active;
	}

	/** Mouse deltas while active, same 0.15 scale as vanilla {@code Entity.turn}. */
	public void turn(double dx, double dy) {
		double s = 0.15 * sensitivity.get();
		yaw += (float) (dx * s);
		pitch = Mth.clamp(pitch + (float) (dy * s), -90.0f, 90.0f);
	}

	public float renderYaw() {
		return renderYaw;
	}

	public float renderPitch() {
		return renderPitch;
	}

	/**
	 * Per-frame state pump, driven from the camera mixin: reads the key, starts
	 * and stops freelook, and eases the camera toward the mouse target.
	 */
	public void updateFrame() {
		boolean down = isEnabled() && key.isBound()
				&& mc().gui.screen() == null
				&& InputConstants.isKeyDown(mc().getWindow(), key.get());
		boolean want;
		if (mode.is("Toggle")) {
			if (down && !keyWasDown) {
				toggled = !toggled;
			}
			want = toggled;
		} else {
			want = down;
		}
		keyWasDown = down;

		if (!isEnabled() || mc().player == null || mc().level == null) {
			want = false;
		}
		if (want && !active) {
			start();
		} else if (!want && active) {
			stop();
		}
		if (!active) {
			return;
		}

		long now = System.nanoTime();
		double dt = Math.min((now - lastFrame) / 1.0e9, 0.1);
		lastFrame = now;
		double strength = smoothing.get();
		if (strength <= 0.0) {
			renderYaw = yaw;
			renderPitch = pitch;
			return;
		}
		// exponential easing, frame rate independent (higher setting = snappier)
		float blend = (float) (1.0 - Math.exp(-dt * (strength + 4.0)));
		renderYaw += (yaw - renderYaw) * blend;
		renderPitch += (pitch - renderPitch) * blend;
	}

	private void start() {
		active = true;
		previous = mc().options.getCameraType();
		yaw = mc().player.getYRot();
		pitch = mc().player.getXRot();
		renderYaw = yaw;
		renderPitch = pitch;
		lastFrame = System.nanoTime();
		if (previous.isFirstPerson()) {
			// the camera only orbits in vanilla's detached branch
			mc().options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
	}

	private void stop() {
		if (!active) {
			toggled = false;
			return;
		}
		active = false;
		toggled = false;
		if (restore.get()) {
			mc().options.setCameraType(previous);
		}
	}
}
