package unlucky.utility.client.module.modules.render;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.KeybindSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * The third-person camera, on your terms: how far back it sits, whether terrain
 * is allowed to pull it in, and a scroll wheel to change your mind mid-shot.
 *
 * <p>Vanilla raycasts from your head and drags the camera to the first block it
 * hits, capped at 4 blocks. {@code CameraMixin} feeds {@code getMaxZoom} our
 * distance instead, and "Clip through blocks" hands the requested distance
 * straight back so the raycast never shortens it.
 *
 * <p>This is the old ViewClip module with Meteor's CameraTweaks folded in —
 * the two were the same hook wearing different names. Freelook stays separate
 * on purpose: it steers where the camera <i>looks</i>, which is a different
 * question from where it sits, and the two compose.
 *
 * <p>Reference: Meteor's CameraTweaks.
 */
public class CameraTweaks extends Module {
	public final NumberSetting distance = add(new NumberSetting("Distance",
			"How far behind you the camera sits (vanilla is 4)", 4.0, 0.0, 32.0, 0.5));
	public final BooleanSetting clip = add(new BooleanSetting("Clip through blocks",
			"Let the camera pass through terrain instead of being pulled in", true));
	public final BooleanSetting scroll = add(new BooleanSetting("Scroll to zoom",
			"Change the distance with the mouse wheel", true));
	/**
	 * Unbound means the wheel is ours whenever the camera is detached — which is
	 * what you want if you live in third person, and not what you want if you
	 * also switch hotbar slots there. Alt is Meteor's default and stays out of
	 * the way of both.
	 */
	public final KeybindSetting scrollBind = add(new KeybindSetting("Scroll bind",
			"Hold this to take the wheel (unbound: always)", GLFW.GLFW_KEY_LEFT_ALT));
	public final NumberSetting sensitivity = add(new NumberSetting("Scroll sensitivity",
			"How much one wheel notch moves the camera", 1.0, 0.05, 5.0, 0.05));

	/** Live distance: the setting is the starting point, the wheel moves this. */
	private double current;

	public CameraTweaks() {
		super("CameraTweaks", "Third-person camera distance and clipping", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		current = distance.get();
	}

	/** The distance to hand {@code getMaxZoom}, in blocks. */
	public float distance() {
		if (current <= 0.0) {
			current = distance.get();
		}
		return (float) current;
	}

	public boolean clipsThroughBlocks() {
		return isEnabled() && clip.get();
	}

	/**
	 * A wheel notch while the camera is detached.
	 *
	 * <p>Scaled by the current distance, so it is a constant proportion rather
	 * than a constant number of blocks: the same flick feels the same whether
	 * you are at 4 blocks or 40.
	 *
	 * @return whether the scroll was consumed and must not reach the hotbar
	 */
	public boolean onScroll(double amount) {
		if (!isEnabled() || !scroll.get() || amount == 0.0
				|| mc().options.getCameraType() == CameraType.FIRST_PERSON) {
			return false;
		}
		if (scrollBind.isBound() && !InputConstants.isKeyDown(mc().getWindow(), scrollBind.get())) {
			return false;
		}
		current = Math.clamp(current - amount * 0.25 * sensitivity.get() * current,
				distance.getMin(), distance.getMax());
		return true;
	}
}
