package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.MovementActionCoordinator;

/** Generic wall climb that preserves vanilla horizontal collision resolution. */
public class Spider extends Module {
	public final NumberSetting speed = add(new NumberSetting("Climb speed", "Upward velocity at a wall", 0.20, 0.05, 0.6, 0.01));
	public final BooleanSetting forwardInput = add(new BooleanSetting("Require forward input", "Climb only while moving", true));
	public final BooleanSetting horizontalCollision = add(new BooleanSetting("Require horizontal collision", "Require contact with a wall", true));
	public final BooleanSetting stopAtTop = add(new BooleanSetting("Stop at top edge", "Do not keep rising after wall contact ends", true));
	public final BooleanSetting pauseSneaking = add(new BooleanSetting("Pause while sneaking", "Let sneak hold position", true));

	public Spider() {
		super("Spider", "Climbs walls with controlled vertical velocity", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		if (mc().player == null || pauseSneaking.get() && mc().player.isShiftKeyDown()
				|| forwardInput.get() && !MoveUtil.hasInput(mc().player)
				|| horizontalCollision.get() && !mc().player.horizontalCollision
				|| stopAtTop.get() && !mc().player.horizontalCollision) return;
		MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
				v -> new net.minecraft.world.phys.Vec3(v.x, Math.max(v.y, speed.get()), v.z));
	}
}
