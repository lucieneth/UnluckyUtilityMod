package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Adjusts render distance to hold an FPS target.
 * Inspired by Stardust's AutoDrawDistance.
 */
public class AutoDrawDistance extends Module {
	public final NumberSetting targetFps = add(new NumberSetting("Target FPS", "FPS to hold", 60, 30, 240, 5));
	public final NumberSetting minDistance = add(new NumberSetting("Min chunks", "Lowest render distance", 4, 2, 32, 1));
	public final NumberSetting maxDistance = add(new NumberSetting("Max chunks", "Highest render distance", 16, 2, 32, 1));

	private int ticksUntilCheck;

	public AutoDrawDistance() {
		super("AutoDrawDistance", "Holds an FPS target by adjusting render distance", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		ticksUntilCheck = 40;
	}

	@Override
	public void onTick() {
		if (mc().level == null || --ticksUntilCheck > 0) {
			return;
		}
		ticksUntilCheck = 40; // adjust at most every 2 seconds

		int fps = mc().getFps();
		int current = mc().options.renderDistance().get();
		int min = (int) Math.min(minDistance.get(), maxDistance.get());
		int max = (int) Math.max(minDistance.get(), maxDistance.get());

		if (fps < targetFps.getInt() - 5 && current > min) {
			mc().options.renderDistance().set(current - 1);
		} else if (fps > targetFps.getInt() + 15 && current < max) {
			mc().options.renderDistance().set(current + 1);
		}
	}
}
