package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.item.Items;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.InteractUtil;

/**
 * Enhances your jumps with wind charges: jump and a charge is fired straight
 * down beneath you for an extra boost. Inspired by Stardust's Updraft.
 */
public class Updraft extends Module {
	public final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between boosts", 15, 10, 100, 1));

	private int cooldown;

	public Updraft() {
		super("Updraft", "Wind charge jump boosts", Category.MOVEMENT);
	}

	@Override
	protected void onEnable() {
		cooldown = 0;
	}

	@Override
	public void onTick() {
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		if (mc().player == null || !mc().player.onGround() || !mc().player.input.keyPresses.jump()) {
			return;
		}
		int slot = InteractUtil.findHotbarItem(Items.WIND_CHARGE);
		if (slot < 0) {
			return;
		}

		// aim straight down just for the use packet, then restore the real pitch
		float oldPitch = mc().player.getXRot();
		mc().player.setXRot(89.0f);
		try {
			InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
		} finally {
			mc().player.setXRot(oldPitch);
		}
		cooldown = (int) Math.round(delay.get());
	}
}
