package unlucky.utility.client.module.modules.visuals;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Optifine-style zoom: hold the zoom key while the module is enabled.
 * The FOV divisor is smoothed over time for a buttery zoom-in.
 */
public class Zoom extends Module {
	public final NumberSetting factor = add(new NumberSetting("Factor", "Zoom strength", 4.0, 1.5, 10.0, 0.5));
	public final KeybindSetting holdKey = add(new KeybindSetting("Zoom key", "Hold to zoom", GLFW.GLFW_KEY_C));
	public final BooleanSetting scroll = add(new BooleanSetting("Scroll zoom", "Mouse wheel adjusts the factor while zooming", true));
	public final NumberSetting scrollStep = add(new NumberSetting("Scroll step", "Factor change per wheel notch", 0.5, 0.1, 2.0, 0.1));

	private double currentDivisor = 1.0;
	private long lastFrame = System.nanoTime();

	public Zoom() {
		super("Zoom", "Hold a key to zoom in smoothly", Category.RENDER);
	}

	/** True while the zoom key is held with no screen in the way. */
	public boolean keyHeld() {
		return isEnabled() && holdKey.isBound()
				&& mc().gui.screen() == null
				&& InputConstants.isKeyDown(mc().getWindow(), holdKey.get());
	}

	/**
	 * Mouse wheel while zooming: steps the factor (wheel up = closer). Returns
	 * true when the scroll was consumed, so the hotbar doesn't move too.
	 */
	public boolean onScroll(double amount) {
		if (amount == 0.0 || !scroll.get() || !keyHeld()) {
			return false;
		}
		factor.set(factor.get() + Math.signum(amount) * scrollStep.get());
		return true;
	}

	/** Called every frame from the camera mixin. */
	public float fovDivisor() {
		double target = keyHeld() ? factor.get() : 1.0;

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

