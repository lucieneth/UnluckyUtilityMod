package unlucky.utility.client.module.modules.render;

import java.util.ArrayDeque;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Adjusts render distance to hold an FPS target.
 * Inspired by Stardust's AutoDrawDistance.
 */
public class AutoDrawDistance extends Module {
	public final NumberSetting targetFps = add(new NumberSetting("Target FPS", "FPS to hold", 60, 30, 240, 5));
	public final NumberSetting minDistance = add(new NumberSetting("Min chunks", "Lowest render distance", 4, 2, 32, 1));
	public final NumberSetting maxDistance = add(new NumberSetting("Max chunks", "Highest render distance", 16, 2, 32, 1));
	public final NumberSetting sampleWindow = add(new NumberSetting("Sample window", "Seconds of FPS samples to average", 5, 3, 10, 1));
	public final NumberSetting checkInterval = add(new NumberSetting("Check interval", "Seconds between adjustment decisions", 2, 1, 10, 1));
	public final NumberSetting downThreshold = add(new NumberSetting("Down threshold", "FPS below target before reducing chunks", 5, 0, 60, 1));
	public final NumberSetting upThreshold = add(new NumberSetting("Up threshold", "FPS above target before increasing chunks", 15, 0, 120, 1));
	public final NumberSetting cooldown = add(new NumberSetting("Cooldown after change", "Seconds to wait after changing distance", 4, 0, 30, 1));
	public final NumberSetting stepUp = add(new NumberSetting("Step up", "Chunks added when stable", 1, 1, 4, 1));
	public final NumberSetting stepDown = add(new NumberSetting("Step down", "Chunks removed when under target", 1, 1, 4, 1));
	public final BooleanSetting emergencyDrop = add(new BooleanSetting("Fast emergency drop",
			"Remove several chunks after a severe FPS dip", true));
	public final BooleanSetting ignoreMenus = add(new BooleanSetting("Ignore menus/loading",
			"Do not sample or adjust while a screen is open", true));
	public final NumberSetting joinGrace = add(new NumberSetting("World join grace", "Seconds to wait after joining a world",
			5, 0, 30, 1));

	private int ticksUntilCheck;
	private int cooldownTicks;
	private int joinTicks;
	private final ArrayDeque<Integer> samples = new ArrayDeque<>();

	public AutoDrawDistance() {
		super("AutoDrawDistance", "Holds an FPS target by adjusting render distance", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		ticksUntilCheck = checkInterval.getInt() * 20;
		cooldownTicks = 0;
		joinTicks = 0;
		samples.clear();
	}

	@Override
	public void onTick() {
		if (mc().level == null) {
			joinTicks = 0;
			samples.clear();
			return;
		}
		joinTicks++;
		if (ignoreMenus.get() && mc().gui.screen() != null) return;
		int maxSamples = sampleWindow.getInt() * 20;
		samples.addLast(mc().getFps());
		while (samples.size() > maxSamples) samples.removeFirst();
		if (cooldownTicks > 0) cooldownTicks--;
		if (--ticksUntilCheck > 0 || joinTicks < joinGrace.getInt() * 20 || samples.isEmpty()
				|| cooldownTicks > 0) return;
		ticksUntilCheck = checkInterval.getInt() * 20;

		int fps = (int) Math.round(samples.stream().mapToInt(Integer::intValue).average().orElse(0));
		int current = mc().options.renderDistance().get();
		int min = (int) Math.min(minDistance.get(), maxDistance.get());
		int max = (int) Math.max(minDistance.get(), maxDistance.get());

		if (fps < targetFps.getInt() - downThreshold.getInt() && current > min) {
			int step = emergencyDrop.get() && fps < targetFps.getInt() / 2 ? stepDown.getInt() * 2 : stepDown.getInt();
			mc().options.renderDistance().set(Math.max(min, current - step));
			cooldownTicks = cooldown.getInt() * 20;
		} else if (fps > targetFps.getInt() + upThreshold.getInt() && current < max) {
			mc().options.renderDistance().set(Math.min(max, current + stepUp.getInt()));
			cooldownTicks = cooldown.getInt() * 20;
		}
	}
}
