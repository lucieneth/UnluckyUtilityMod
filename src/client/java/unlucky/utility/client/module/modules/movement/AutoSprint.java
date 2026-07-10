package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Keeps you sprinting whenever you move. Omni-directional mode also sprints
 * sideways and backwards.
 */
public class AutoSprint extends Module {
	public final BooleanSetting omniDirectional = add(new BooleanSetting("Omni-directional", "Sprint in any direction", true));
	public final BooleanSetting keepSprinting = add(new BooleanSetting("Keep sprinting",
			"Re-enable sprint the instant an action like attacking cancels it", true));

	public AutoSprint() {
		super("AutoSprint", "Sprints for you", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		// the server rejects sprinting when too hungry, don't fight it
		if (mc().player.getFoodData().getFoodLevel() <= 6 && !mc().player.isCreative()) {
			return;
		}
		if (mc().player.isShiftKeyDown() || mc().player.isUsingItem()) {
			return;
		}

		boolean moving = omniDirectional.get()
				? mc().player.input.getMoveVector().lengthSquared() > 0.0f
				: mc().player.input.keyPresses.forward();
		if (!moving) {
			return;
		}
		// Keep sprinting re-asserts every tick, so an attack (or anything that
		// drops the sprint flag) gets it straight back. Otherwise only kick it
		// off when we're not already sprinting.
		if (keepSprinting.get() || !mc().player.isSprinting()) {
			mc().player.setSprinting(true);
		}
	}
}
