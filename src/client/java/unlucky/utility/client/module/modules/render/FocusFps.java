package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Caps the frame rate while the game window is not focused.
 *
 * <p>Vanilla only throttles a minimised window (or one gone AFK); a window sitting behind your
 * browser still renders flat out. The cap is applied on top of vanilla's own answer in
 * {@code FramerateLimitTrackerMixin}, so it can only ever lower the limit.
 */
public class FocusFps extends Module {
	public final NumberSetting fps = add(new NumberSetting("FPS",
			"Frame cap while the window is unfocused", 15, 1, 120, 1));

	public FocusFps() {
		super("FocusFps", "Caps FPS while the window is unfocused", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	/** {@code limit}, capped when the window has lost focus. */
	public int apply(int limit) {
		if (!isEnabled() || mc().getWindow() == null || mc().getWindow().isFocused()) {
			return limit;
		}
		return Math.min(limit, fps.getInt());
	}
}
