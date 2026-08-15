package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;

/**
 * Jumps for you at the edge of a block.
 *
 * <p><b>It presses jump; it does not invent velocity.</b> {@code jumpFromGround()} is the same
 * call the space bar makes, so sprint bonuses, jump boost, slime blocks and honey all behave
 * exactly as they would have. A module that wrote its own upward vector would get every one of
 * those wrong and would be doing something the player cannot reproduce by hand.
 *
 * <p><b>One jump per ledge.</b> The re-arm needs a landing, not merely a tick without an edge
 * underneath: an edge probe that flickers as you shuffle would otherwise fire again mid-air, and
 * a second jump in a gap is how you end up short of the far side rather than on it.
 *
 * <p><b>Safe landing is a look across the gap, not a leap of faith.</b> Somewhere to stand within
 * the configured reach, in a chunk the client actually has, with nothing on it that bites. With it
 * off this will cheerfully launch you into a canyon, which is the setting doing what it says.
 */
public class Parkour extends Module {
	/** How far below the feet the support probe looks. Deep enough to see a step down as ground. */
	private static final double SUPPORT_DEPTH = 0.55;

	/** Step between landing probes across the gap, in blocks. */
	private static final double LANDING_STEP = 0.25;

	public final NumberSetting edgeDistance = add(new NumberSetting("Edge distance",
			"How far past your leading edge the ground has to be missing", 0.15, 0.01, 0.50, 0.01));
	public final BooleanSetting requireForward = add(new BooleanSetting("Require forward",
			"Only jump while you are actually walking somewhere", true));
	public final BooleanSetting requireSprint = add(new BooleanSetting("Require sprint",
			"Only jump while sprinting", false));

	public final BooleanSetting safeLandingOnly = add(new BooleanSetting("Safe landing only",
			"Require somewhere to land within reach before jumping", true));
	public final NumberSetting maximumGap = add(new NumberSetting("Maximum gap",
			"Widest gap treated as jumpable", 1.0, 1.0, 3.0, 0.1), safeLandingOnly::get);
	public final NumberSetting minimumHealth = add(new NumberSetting("Minimum health",
			"Health, absorption included, below which it stops jumping for you", 6, 1, 36, 1));

	public final BooleanSetting pauseWhileSneaking = add(new BooleanSetting("Pause while sneaking",
			"Sneak always wins — it is how you stop at an edge on purpose", true));
	public final BooleanSetting pauseInLiquids = add(new BooleanSetting("Pause in liquids",
			"No auto-jump while swimming", true));
	public final BooleanSetting pauseWhileGliding = add(new BooleanSetting("Pause while gliding",
			"No auto-jump with an elytra out", true));

	/** Set when we jump, cleared only after a landing — the one-jump-per-ledge rule. */
	private boolean jumped;
	/** Whether the jump actually got us off the ground, so a landing means something. */
	private boolean airborne;
	/** Ticks left of "the server just saw a jump it did not ask for". */
	private int observable;

	public Parkour() {
		super("Parkour", "Jumps automatically at the edge of a block", Category.MOVEMENT,
				ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only on the tick the jump is sent.
	 *
	 * <p>Standing at an edge is not observable; jumping off one is, and only because the jump was
	 * not the player's. Everything after that tick is ordinary vanilla flight through the air.
	 */
	@Override
	public boolean isServerObservableNow() {
		return observable > 0;
	}

	@Override
	protected void onEnable() {
		jumped = false;
		airborne = false;
		observable = 0;
	}

	@Override
	protected void onDisable() {
		onEnable();
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
		if (observable > 0) {
			observable--;
		}
		trackReArm(player);

		if (jumped || !allowed(player)) {
			return;
		}
		Vec3 direction = heading(player);
		if (direction.lengthSqr() < 1.0e-6 || !atEdge(player, direction)) {
			return;
		}
		if (safeLandingOnly.get() && !landingExists(player, direction)) {
			return;
		}
		player.jumpFromGround();
		jumped = true;
		airborne = false;
		observable = 1;
	}

	/**
	 * The grounded re-arm. A jump that never left the ground — blocked by a ceiling, say — still
	 * clears, or the module would lock itself off for the rest of the session.
	 */
	private void trackReArm(LocalPlayer player) {
		if (!jumped) {
			return;
		}
		if (!player.onGround()) {
			airborne = true;
			return;
		}
		if (airborne || player.getDeltaMovement().y <= 0.0) {
			jumped = false;
			airborne = false;
		}
	}

	private boolean allowed(LocalPlayer player) {
		if (!player.onGround() || player.isPassenger() || player.isSpectator()
				|| mc().gui.screen() != null) {
			return false;
		}
		if (pauseWhileSneaking.get() && player.isShiftKeyDown()) {
			return false;
		}
		if (pauseInLiquids.get() && (player.isInWater() || player.isInLava())) {
			return false;
		}
		if (pauseWhileGliding.get() && player.isFallFlying()) {
			return false;
		}
		if (player.getHealth() + player.getAbsorptionAmount() < minimumHealth.get()) {
			return false;
		}
		if (requireSprint.get() && !player.isSprinting()) {
			return false;
		}
		return !requireForward.get() || MoveUtil.hasInput(player);
	}

	/**
	 * Where the player is going: their movement input if they have any, otherwise their actual
	 * horizontal velocity.
	 *
	 * <p>Input first because it is the intent — momentum carries you sideways off a ledge you were
	 * already turning away from, and jumping along the old direction there is worse than not
	 * jumping at all.
	 */
	private Vec3 heading(LocalPlayer player) {
		Vec3 input = MoveUtil.inputDirection(player);
		if (input.lengthSqr() > 1.0e-6) {
			return input;
		}
		Vec3 velocity = player.getDeltaMovement();
		Vec3 flat = new Vec3(velocity.x, 0.0, velocity.z);
		return flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize();
	}

	/**
	 * Whether the ground stops just ahead.
	 *
	 * <p>Two probes rather than one: the slab under the player must be occupied — otherwise this
	 * is not standing at an edge, it is standing on nothing and a jump is not the answer — and the
	 * same slab, pushed past the leading face by {@code Edge distance}, must be empty.
	 */
	private boolean atEdge(LocalPlayer player, Vec3 direction) {
		AABB box = player.getBoundingBox();
		if (mc().level.noCollision(player, supportSlab(box))) {
			return false;
		}
		double reach = box.getXsize() * 0.5 + edgeDistance.get();
		AABB ahead = box.move(direction.x * reach, 0.0, direction.z * reach);
		return mc().level.noCollision(player, supportSlab(ahead));
	}

	/** The thin box immediately beneath a footprint — "is there floor here", nothing more. */
	private static AABB supportSlab(AABB box) {
		return new AABB(box.minX, box.minY - SUPPORT_DEPTH, box.minZ,
				box.maxX, box.minY - 0.01, box.maxZ);
	}

	/**
	 * Walks outward along the heading looking for a landing.
	 *
	 * <p>Both halves have to hold at the same distance: room for the player to be there, and floor
	 * under them that will not hurt. Checking them separately would happily approve a ledge with a
	 * wall on it, or the top of a cactus.
	 *
	 * <p><b>The search runs a block past the gap, not up to it.</b> {@code Maximum gap} is measured
	 * in blocks of missing floor, and the landing is by definition on the far side of them — stopping
	 * at the gap width itself only ever probes the hole, so every jump would be refused.
	 */
	private boolean landingExists(LocalPlayer player, Vec3 direction) {
		AABB box = player.getBoundingBox();
		double start = box.getXsize() * 0.5 + edgeDistance.get();
		double limit = maximumGap.get() + 1.0;
		for (double distance = start; distance <= limit + 1.0e-6; distance += LANDING_STEP) {
			AABB candidate = box.move(direction.x * distance, 0.0, direction.z * distance);
			if (!mc().level.noCollision(player, candidate)) {
				continue; // a wall at this distance is not a landing, but something past it may be
			}
			BlockPos floor = BlockPos.containing(candidate.getCenter().x,
					candidate.minY - 0.5, candidate.getCenter().z);
			if (MoveUtil.solidSupport(floor) && !MoveUtil.hazardous(floor)
					&& !MoveUtil.hazardous(floor.above())) {
				return true;
			}
		}
		return false;
	}
}
