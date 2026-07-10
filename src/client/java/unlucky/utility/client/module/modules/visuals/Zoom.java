package unlucky.utility.client.module.modules.visuals;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Optifine-style zoom: hold the zoom key while the module is enabled.
 * The FOV divisor is smoothed over time for a buttery zoom-in.
 */
public class Zoom extends Module {
	public final NumberSetting factor = add(new NumberSetting("Factor", "Zoom strength", 4.0, 1.5, 10.0, 0.5));
	public final KeybindSetting holdKey = add(new KeybindSetting("Zoom key", "Hold to zoom", GLFW.GLFW_KEY_C));

	private double currentDivisor = 1.0;
	private long lastFrame = System.nanoTime();

	public Zoom() {
		super("Zoom", "Hold a key to zoom in smoothly", Category.RENDER);
		setEnabledSilently(true);
	}

	/** Called every frame from the camera mixin. */
	public float fovDivisor() {
		double target = 1.0;
		if (isEnabled() && holdKey.isBound()
				&& mc().gui.screen() == null
				&& InputConstants.isKeyDown(mc().getWindow(), holdKey.get())) {
			target = factor.get();
		}

		long now = System.nanoTime();
		double dt = Math.min((now - lastFrame) / 1_000_000_000.0, 0.1);
		lastFrame = now;
		// exponential smoothing, frame rate independent
		double blend = 1.0 - Math.exp(-dt * 14.0);
		currentDivisor += (target - currentDivisor) * blend;
		if (Math.abs(currentDivisor - target) < 0.001) {
			currentDivisor = target;
		}
		return (float) currentDivisor;
	}
}

