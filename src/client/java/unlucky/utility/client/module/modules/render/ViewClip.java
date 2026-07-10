package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Third-person camera freedom. Vanilla raycasts from your head and pulls the
 * camera in to the first block it hits, capped at 4 blocks; {@code CameraMixin}
 * feeds it our distance instead, and can skip the raycast entirely so the camera
 * passes through walls. Only affects the detached (F5) camera.
 */
public class ViewClip extends Module {
	public final NumberSetting distance = add(new NumberSetting("Distance",
			"How far behind you the camera sits (vanilla is 4)", 4.0, 1.0, 32.0, 0.5));
	public final BooleanSetting clip = add(new BooleanSetting("Clip through blocks",
			"Let the camera pass through terrain instead of being pulled in", true));

	public ViewClip() {
		super("ViewClip", "Third-person camera ignores blocks", Category.RENDER);
	}
}
