package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.MovementActionCoordinator;

/** Faster vanilla climbable movement; timer mode is omitted because no shared timer owner exists. */
public class FastClimb extends Module {
	public final NumberSetting speed = add(new NumberSetting("Speed", "Upward velocity on climbables", 0.287, 0.05, 0.8, 0.001));
	public final BooleanSetting requireInput = add(new BooleanSetting("Require climb input", "Require movement or jump", true));
	public final BooleanSetting requireCollision = add(new BooleanSetting("Require horizontal collision", "Require wall contact too", false));
	public final BooleanSetting pauseSneaking = add(new BooleanSetting("Pause while sneaking", "Leave downward climb control to sneak", true));
	public final NumberSetting descendSpeed = add(new NumberSetting("Descend speed", "Downward velocity while sneaking when not paused", 0.20, 0.05, 0.8, 0.01), () -> !pauseSneaking.get());

	public FastClimb() {
		super("FastClimb", "Accelerates movement on vanilla climbables", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		if (mc().player == null || !mc().player.onClimbable()
				|| pauseSneaking.get() && mc().player.isShiftKeyDown()
				|| requireCollision.get() && !mc().player.horizontalCollision
				|| requireInput.get() && !MoveUtil.hasInput(mc().player) && !mc().options.keyJump.isDown()) return;
		double vertical = !pauseSneaking.get() && mc().player.isShiftKeyDown() ? -descendSpeed.get() : speed.get();
		MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
				v -> new net.minecraft.world.phys.Vec3(v.x, vertical < 0 ? Math.min(v.y, vertical) : Math.max(v.y, vertical), v.z));
	}
}
