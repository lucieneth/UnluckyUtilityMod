package unlucky.utility.client.module.modules.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.InteractUtil;

/**
 * Hands-free elytra cruising: gently oscillates your pitch to hold altitude and
 * fires rockets to keep moving. Point roughly where you want to go and idle.
 * Inspired by Stardust's AFKVanillaFly.
 */
public class AFKVanillaFly extends Module {
	public final NumberSetting targetY = add(new NumberSetting("Target Y", "Altitude to hold", 200, 0, 320, 5));
	public final NumberSetting rocketDelay = add(new NumberSetting("Rocket delay", "Ticks between rockets", 30, 10, 100, 5));
	public final NumberSetting pitchResponse = add(new NumberSetting("Pitch response", "How quickly aim adjusts to altitude error", 0.10, 0.01, 1.0, 0.01));
	public final BooleanSetting onlyDescending = add(new BooleanSetting("Only while descending", "Fire rockets only while losing altitude", true));

	private int cooldown;

	public AFKVanillaFly() {
		super("AFKVanillaFly", "AFK altitude-holding elytra flight", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || !mc().player.isFallFlying()) {
			return;
		}

		// steer pitch toward level flight, nudged by altitude error
		double error = targetY.get() - mc().player.getY();
		float desiredPitch = (float) Mth.clamp(-error * 2.0, -35.0, 35.0);
		float pitch = mc().player.getXRot();
		mc().player.setXRot(pitch + (desiredPitch - pitch) * pitchResponse.getFloat());

		if (--cooldown <= 0 && (!onlyDescending.get() || mc().player.getDeltaMovement().y < 0.0)) {
			int slot = InteractUtil.findHotbarItem(Items.FIREWORK_ROCKET);
			if (slot >= 0) {
				InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
				cooldown = (int) Math.round(rocketDelay.get());
			}
		}
	}
}
