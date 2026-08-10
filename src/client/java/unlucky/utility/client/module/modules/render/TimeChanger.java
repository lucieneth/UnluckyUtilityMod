package unlucky.utility.client.module.modules.render;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/** Overrides only the client-facing day time while server time continues updating underneath. */
public class TimeChanger extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Time source", "Preset", "Preset", "Custom", "Offset"));
	public final ModeSetting preset = add(new ModeSetting("Preset", "Named day time", "Noon", "Dawn", "Day", "Noon", "Dusk", "Night", "Midnight"), () -> mode.is("Preset"));
	public final NumberSetting customTime = add(new NumberSetting("Custom time", "Time of day in ticks", 6000, 0, 23999, 100), () -> mode.is("Custom"));
	public final NumberSetting offset = add(new NumberSetting("Offset", "Ticks added to server time", 0, -24000, 24000, 100), () -> mode.is("Offset"));
	public final BooleanSetting freeze = add(new BooleanSetting("Freeze", "Hold the selected time instead of advancing", true));
	private long anchorValue;
	private long anchorGameTime;
	private String signature;

	public TimeChanger() {
		super("TimeChanger", "Changes rendered time without discarding server updates", Category.RENDER, ServerVisibility.CLIENT_ONLY);
	}

	public long override(long serverTime) {
		if (!isEnabled() || mc().level == null) return serverTime;
		String current = mode.get() + ":" + preset.get() + ":" + customTime.getInt() + ":" + offset.getInt() + ":" + freeze.get();
		if (!current.equals(signature)) {
			signature = current;
			anchorValue = selected(serverTime);
			anchorGameTime = mc().level.getGameTime();
		}
		long value = freeze.get() ? anchorValue : anchorValue + mc().level.getGameTime() - anchorGameTime;
		return Math.floorMod(value, 24000L);
	}

	private long selected(long server) {
		if (mode.is("Custom")) return customTime.getInt();
		if (mode.is("Offset")) return server + offset.getInt();
		return switch (preset.get()) {
			case "Dawn" -> 0; case "Day" -> 1000; case "Dusk" -> 12000;
			case "Night" -> 13000; case "Midnight" -> 18000; default -> 6000;
		};
	}

	@Override protected void onEnable() { signature = null; }
	@Override protected void onDisable() { signature = null; }
}
