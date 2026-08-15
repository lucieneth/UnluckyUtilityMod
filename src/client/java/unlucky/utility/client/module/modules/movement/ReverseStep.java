package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.DamageForecast;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.MoveUtil;

/**
 * Step down as briskly as {@link Step} steps up.
 *
 * <p>Walking off a one-block ledge in vanilla is a small parabola: you leave the ground, drift,
 * and land a moment later a little further out than you meant to. This shortens that by asking for
 * a faster descent, so a drop reads like a stair rather than a hop.
 *
 * <p><b>It asks; it does not write velocity.</b> The request goes to
 * {@link MovementActionCoordinator} at {@code TRAVEL} priority, which is below AntiVoid's — a
 * module whose whole purpose is to accelerate you downward must never be the one that wins against
 * the module whose whole purpose is to stop you falling.
 *
 * <p><b>The drop bounds are the safety, and both ends matter.</b> Below the minimum there is
 * nothing worth accelerating and the request would just make ordinary walking feel wrong; above
 * the maximum the fall is one you should be arriving at slowly, or not at all. A drop with no
 * bottom — the void — never qualifies, whatever the settings say.
 */
public class ReverseStep extends Module {
	public final NumberSetting fallSpeed = add(new NumberSetting("Fall speed",
			"Blocks per tick of requested descent", 1.0, 0.1, 5.0, 0.1));
	public final NumberSetting minimumDrop = add(new NumberSetting("Minimum drop",
			"Ignore ledges shorter than this", 1.0, 0.5, 3.0, 0.1));
	public final NumberSetting maximumDrop = add(new NumberSetting("Maximum drop",
			"Never accelerate a drop taller than this", 3.0, 1.0, 10.0, 0.5));

	public final BooleanSetting safeLandingOnly = add(new BooleanSetting("Safe landing only",
			"Require a real surface underneath that will not hurt to land on", true));
	public final BooleanSetting pauseWhileSneaking = add(new BooleanSetting("Pause while sneaking",
			"Sneak is deliberate edge control; leave it alone", true));
	public final BooleanSetting pauseInLiquids = add(new BooleanSetting("Pause in liquids",
			"No downward request while swimming", true));
	public final BooleanSetting pauseWhileGliding = add(new BooleanSetting("Pause while gliding",
			"No downward request with an elytra out", true));
	public final BooleanSetting vehicles = add(new BooleanSetting("Vehicles",
			"Apply while riding", false));

	/** Whether the downward transform is live — the answer {@link #isServerObservableNow} needs. */
	private boolean active;

	public ReverseStep() {
		super("ReverseStep", "Drops off ledges at a chosen speed", Category.MOVEMENT,
				ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only while the transform is actually being applied.
	 *
	 * <p>The module spends nearly all of its time doing nothing at all — you are on the ground, or
	 * the drop is too small, or there is a floor two blocks down and the request never gets made.
	 * A descent that vanilla would have produced anyway is not something the server can see.
	 */
	@Override
	public boolean isServerObservableNow() {
		return active;
	}

	@Override
	protected void onEnable() {
		active = false;
	}

	@Override
	protected void onDisable() {
		MovementActionCoordinator.release(this);
		active = false;
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null) {
			onDisable();
			return;
		}
		Entity subject = subject(player);
		if (subject == null || !falling(player, subject) || !dropQualifies(subject)) {
			MovementActionCoordinator.release(this);
			active = false;
			return;
		}
		double speed = fallSpeed.get();
		active = MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_TRAVEL,
				velocity -> new Vec3(velocity.x, Math.min(velocity.y, -speed), velocity.z));
	}

	/** Whose fall this is: ours, or the vehicle's when the player asked for that. */
	private Entity subject(LocalPlayer player) {
		Entity vehicle = player.getVehicle();
		if (vehicle == null) {
			return player;
		}
		return vehicles.get() ? vehicle : null;
	}

	/**
	 * Whether this is a fall off a ledge rather than a jump, a swim or a glide.
	 *
	 * <p>The downward-velocity test is what separates the two: a jump is airborne and rising, and
	 * pulling it back down would turn every jump into a stumble.
	 */
	private boolean falling(LocalPlayer player, Entity subject) {
		if (subject.onGround() || subject.getDeltaMovement().y >= 0.0) {
			return false;
		}
		if (pauseWhileSneaking.get() && player.isShiftKeyDown()) {
			return false;
		}
		if (pauseInLiquids.get() && (subject.isInWater() || subject.isInLava())) {
			return false;
		}
		if (pauseWhileGliding.get() && player.isFallFlying()) {
			return false;
		}
		return !player.onClimbable();
	}

	/**
	 * Whether there is a bottom to this drop, it is within the configured range, and — when asked
	 * — it is somewhere worth arriving at.
	 *
	 * <p>The footprint overload of {@link DamageForecast#distanceToGround} rather than the column
	 * one: this is precisely the moving-sideways-over-a-ledge case the cheap version documents
	 * itself as wrong for.
	 */
	private boolean dropQualifies(Entity subject) {
		double drop = DamageForecast.distanceToGround(subject, subject.getBoundingBox());
		if (drop < minimumDrop.get() || drop > maximumDrop.get()) {
			// Includes the void, which reports -1 and must never be accelerated into.
			return false;
		}
		if (!safeLandingOnly.get()) {
			return true;
		}
		BlockPos landing = BlockPos.containing(subject.getX(),
				subject.getBoundingBox().minY - drop - 0.5, subject.getZ());
		return MoveUtil.solidSupport(landing) && !MoveUtil.hazardous(landing)
				&& !MoveUtil.hazardous(landing.above());
	}
}
