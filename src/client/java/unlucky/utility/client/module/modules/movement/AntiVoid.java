package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.MovementActionCoordinator;

/**
 * Stops a fall before the world has nothing left to catch it.
 *
 * <p>The detector moves the player's box through a short approximation of vanilla falling;
 * it never places a block and never rewrites position. Rescue is one controlled velocity
 * decision at the end of the module tick, after lower-priority strafes and travel modules
 * have run. A remembered safe point is therefore a destination, not a teleport packet.
 */
public class AntiVoid extends Module {
	private static final double GRAVITY = 0.08;
	private static final double VERTICAL_DRAG = 0.98;
	private static final double HORIZONTAL_DRAG = 0.91;
	private static final double RETURN_SPEED = 0.32;
	private static final double RETURN_LIFT = 0.35;
	private static final double FLIGHT_SPEED = 0.30;
	private static final double FLIGHT_LIFT = 0.35;

	public final ModeSetting detection = add(new ModeSetting("Detection",
			"Predictive follows the current velocity; Simple Y waits for the configured world-floor margin",
			"Predictive", "Predictive", "Simple Y"));
	public final NumberSetting voidMargin = add(new NumberSetting("Void margin",
			"Simple-Y rescue line above the dimension's minimum build height", 4, 0, 32, 1));
	public final NumberSetting lookAhead = add(new NumberSetting("Look ahead",
			"Ticks of the current fall Predictive simulates", 10, 1, 40, 1),
			() -> detection.is("Predictive"));
	public final BooleanSetting onlyTrueVoid = add(new BooleanSetting("Only true void",
			"Require the projected footprint to have no solid block below it; leave survivable cliffs alone",
			true));
	public final NumberSetting minimumFall = add(new NumberSetting("Minimum fall distance",
			"Do not rescue ordinary steps or the first instant after leaving an edge", 3, 0, 20, 0.5));
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Freeze stops the fall; Return steers toward recent ground; Flight gives controlled lift",
			"Freeze", "Freeze", "Return to safe position", "Flight"));
	public final BooleanSetting rememberSafe = add(new BooleanSetting("Remember safe position",
			"Remember the last supported grounded position for Return mode", true));
	public final NumberSetting safeAge = add(new NumberSetting("Safe position age",
			"Forget remembered ground after this many ticks", 100, 20, 600, 10),
			() -> rememberSafe.get() && mode.is("Return to safe position"));
	public final BooleanSetting autoDisable = add(new BooleanSetting("Auto disable after rescue",
			"Turn AntiVoid off once rescue reaches supported ground", false));
	public final BooleanSetting warn = add(new BooleanSetting("Warn",
			"Print one client-side warning when a rescue starts", true));

	private ClientLevel lastLevel;
	private Vec3 safePosition;
	private long safeTick = Long.MIN_VALUE;
	private long clientTick;
	private boolean rescuing;

	public AntiVoid() {
		super("AntiVoid", "Predicts and rescues falls into the void", Category.MOVEMENT,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		reset(null);
	}

	@Override
	protected void onDisable() {
		MovementActionCoordinator.release(this);
		rescuing = false;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null) {
			reset(level);
			return;
		}
		if (level != lastLevel) {
			reset(level);
		}
		clientTick++;

		boolean supported = player.onGround() && supportedNow(player);
		if (supported) {
			if (rescuing) {
				rescuing = false;
				MovementActionCoordinator.release(this);
				if (autoDisable.get()) {
					setEnabled(false);
					return;
				}
			}
			if (rememberSafe.get()) {
				safePosition = player.position();
				safeTick = clientTick;
			}
		}

		if (player.isSpectator() || player.getAbilities().flying || player.isFallFlying()
				|| player.isInWater() || player.isInLava() || player.onClimbable()) {
			rescuing = false;
			MovementActionCoordinator.release(this);
			return;
		}

		boolean danger = rescuing || detectsDanger(player);
		if (!danger) {
			return;
		}
		if (!rescuing) {
			rescuing = true;
			if (warn.get()) {
				ChatUtil.info("AntiVoid: rescue engaged (" + mode.get() + ")");
			}
		}

		MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_ANTI_VOID,
				current -> rescueVelocity(player, current));
	}

	private boolean detectsDanger(LocalPlayer player) {
		if (player.getDeltaMovement().y >= 0.0 || player.fallDistance < minimumFall.getFloat()) {
			return false;
		}
		double rescueY = mc().level.getMinY() + voidMargin.get();
		if (detection.is("Simple Y")) {
			if (player.getBoundingBox().minY > rescueY) {
				return false;
			}
			return !onlyTrueVoid.get()
					|| DamageForecast.distanceToGround(player, player.getBoundingBox()) < 0.0;
		}

		AABB projected = player.getBoundingBox();
		Vec3 motion = player.getDeltaMovement();
		for (int tick = 0; tick < lookAhead.getInt(); tick++) {
			AABB horizontal = projected.move(motion.x, 0.0, motion.z);
			if (mc().level.noCollision(player, horizontal)) {
				projected = horizontal;
			} else {
				motion = new Vec3(0.0, motion.y, 0.0);
			}

			AABB vertical = projected.move(0.0, motion.y, 0.0);
			if (!mc().level.noCollision(player, vertical)) {
				return false; // projected landing before the look-ahead window ends
			}
			projected = vertical;
			motion = new Vec3(motion.x * HORIZONTAL_DRAG,
					(motion.y - GRAVITY) * VERTICAL_DRAG,
					motion.z * HORIZONTAL_DRAG);
		}

		double support = DamageForecast.distanceToGround(player, projected);
		if (onlyTrueVoid.get() && support >= 0.0) {
			return false;
		}
		// With Only-true-void off, a long unsupported cliff fall is enough. With it on,
		// the shared support query above is the gate, even high above the build floor.
		return projected.minY <= rescueY || !onlyTrueVoid.get()
				|| support < 0.0;
	}

	private Vec3 rescueVelocity(LocalPlayer player, Vec3 current) {
		if (mode.is("Return to safe position") && safePositionUsable()) {
			Vec3 delta = safePosition.subtract(player.position());
			Vec3 horizontal = new Vec3(delta.x, 0.0, delta.z);
			double length = horizontal.length();
			double x = length <= RETURN_SPEED ? horizontal.x : horizontal.x / length * RETURN_SPEED;
			double z = length <= RETURN_SPEED ? horizontal.z : horizontal.z / length * RETURN_SPEED;
			double y = Math.clamp(delta.y * 0.20, 0.08, RETURN_LIFT);
			return new Vec3(x, y, z);
		}
		if (mode.is("Flight")) {
			Vec3 direction = MoveUtil.inputDirection(player);
			return new Vec3(direction.x * FLIGHT_SPEED, FLIGHT_LIFT, direction.z * FLIGHT_SPEED);
		}
		return new Vec3(0.0, Math.max(0.0, current.y), 0.0);
	}

	private boolean safePositionUsable() {
		return rememberSafe.get() && safePosition != null
				&& clientTick - safeTick <= safeAge.getInt();
	}

	private boolean supportedNow(LocalPlayer player) {
		return !mc().level.noCollision(player, player.getBoundingBox().move(0.0, -0.05, 0.0));
	}

	private void reset(ClientLevel level) {
		lastLevel = level;
		safePosition = null;
		safeTick = Long.MIN_VALUE;
		clientTick = 0;
		rescuing = false;
		MovementActionCoordinator.release(this);
	}
}
