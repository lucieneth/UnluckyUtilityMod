package unlucky.utility.client.module.modules.movement;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.MovementActionCoordinator;

/** One generic horizontal launch with optional glide; no server-branded packet patterns. */
public class LongJump extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode", "Launch profile", "Boost", "Boost", "Burst", "Glide"));
	public final NumberSetting horizontal = add(new NumberSetting("Horizontal boost", "Launch horizontal speed", 1.8, 0.2, 5, 0.1));
	public final NumberSetting vertical = add(new NumberSetting("Vertical boost", "Launch vertical speed", 0.42, 0, 1.5, 0.01));
	public final BooleanSetting onlyGround = add(new BooleanSetting("Only on ground", "Start only from ground", true));
	public final BooleanSetting activateJump = add(new BooleanSetting("Activate on jump", "Launch on jump-key edge", true));
	public final BooleanSetting autoJump = add(new BooleanSetting("Auto jump", "Launch automatically when moving", false));
	public final NumberSetting glideMultiplier = add(new NumberSetting("Glide fall multiplier", "Scale downward velocity in Glide mode", 0.6, 0, 1, 0.05), () -> mode.is("Glide"));
	public final BooleanSetting autoDisable = add(new BooleanSetting("Auto disable on landing", "Turn off after the launch lands", true));
	public final BooleanSetting correctionDisable = add(new BooleanSetting("Disable on server correction", "Stop after a server position correction", true));
	public final BooleanSetting requireInput = add(new BooleanSetting("Require movement input", "Do not launch while stationary", true));
	private boolean active;
	private boolean leftGround;
	private boolean jumpWasDown;

	public LongJump() {
		super("LongJump", "Launches a generic boosted jump through normal movement", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override public void onTick() {
		if (mc().player == null) return;
		boolean jump = mc().options.keyJump.isDown();
		if (!active && (autoJump.get() || activateJump.get() && jump && !jumpWasDown)
				&& (!onlyGround.get() || mc().player.onGround())
				&& (!requireInput.get() || MoveUtil.hasInput(mc().player))) launch();
		if (active) {
			if (!mc().player.onGround()) leftGround = true;
			if (mode.is("Glide") && mc().player.getDeltaMovement().y < 0) {
				MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
						v -> new Vec3(v.x, v.y * glideMultiplier.get(), v.z));
			}
			if (leftGround && mc().player.onGround()) {
				active = false;
				if (autoDisable.get()) setEnabled(false);
			}
		}
		jumpWasDown = jump;
	}

	private void launch() {
		Vec3 direction = MoveUtil.inputDirection(mc().player);
		if (direction == Vec3.ZERO) direction = mc().player.getLookAngle().multiply(1, 0, 1).normalize();
		double boost = horizontal.get() * (mode.is("Burst") ? 1.25 : 1.0);
		Vec3 launch = direction.scale(boost);
		mc().player.setDeltaMovement(launch.x, vertical.get(), launch.z);
		active = true;
		leftGround = false;
	}

	public void onCorrection() {
		if (isEnabled() && correctionDisable.get()) setEnabled(false);
	}

	@Override protected void onDisable() { active = false; leftGround = false; }
}
