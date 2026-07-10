package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Locks your facing, for dead-straight highway travel. This is a <em>real</em>
 * rotation, not a spoofed one (unlike {@code RotationManager}) — movement has to
 * follow it, so the camera turns with you. Vanilla yaw: 0 = south (+Z), 90 = west,
 * 180 = north, 270 = east.
 */
public class Yaw extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Exact: hold one angle. Snap: round your facing to the nearest step",
			"Exact", "Exact", "Snap 45", "Snap 90"));
	public final NumberSetting angle = add(new NumberSetting("Angle",
			"Yaw to hold in Exact mode (0 south, 90 west, 180 north, 270 east)", 0.0, 0.0, 359.0, 1.0));

	public Yaw() {
		super("Yaw", "Locks your yaw to a fixed direction", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null) {
			return;
		}
		float target;
		if (mode.is("Exact")) {
			target = Mth.wrapDegrees(angle.getFloat());
		} else {
			float step = mode.is("Snap 45") ? 45.0f : 90.0f;
			target = Math.round(player.getYRot() / step) * step;
		}
		player.setYRot(target);
		player.setYHeadRot(target);
		player.yBodyRot = target; // keep the model from twisting away from the camera
	}
}
