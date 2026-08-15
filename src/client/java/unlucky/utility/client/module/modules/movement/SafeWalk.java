package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Sneak's edge lock without the sneak.
 *
 * <p><b>This is Scaffold's clamp, exposed on its own, and it deliberately shares Scaffold's
 * hook.</b> Vanilla asks {@code isStayingOnGroundSurface()} once per move and then runs its own
 * collision-aware retreat; two mixins racing to answer that one question would produce a policy
 * nobody wrote, so {@code PlayerMixin} asks this module to reconcile both opinions and returns a
 * single answer. Nothing here zeroes input or invents collision — vanilla's {@code
 * maybeBackOffFromEdge} does the actual work, exactly as it does when you crouch.
 *
 * <p><b>Precedence exists because Scaffold sometimes wants the opposite.</b> Descend's whole
 * purpose is to walk off the edge it just built under itself, and a clamp that outranked it would
 * leave the player pinned on top of their own staircase. With {@code Scaffold precedence} — the
 * default — Scaffold's answer wins whenever it has one, which keeps deliberate downward building
 * working; the other way round is there for players who would rather never step off anything while
 * this is on.
 *
 * <p><b>Minimum fall distance is what separates a kerb from a cliff.</b> Clamping every
 * one-block step down makes ordinary terrain feel like a cage, and that is the setting most
 * people actually want to raise rather than the module they want to turn off.
 */
public class SafeWalk extends Module {
	/** Vanilla's answer: stay on the surface. */
	private static final int CLAMP = 1;
	/** Vanilla's answer: walking off here is fine. */
	private static final int ALLOW = 0;
	/** No opinion — leave vanilla's own return value alone. */
	private static final int VANILLA = -1;

	/** How far past the leading face the drop is measured. */
	private static final double PROBE_REACH = 0.35;

	/** Ticks a clamp keeps rendering and counting as observable after the last one. */
	private static final int HOLD_TICKS = 2;

	public final ModeSetting activeWhen = add(new ModeSetting("Active when",
			"Restrict the clamp to a sneak state", "Always", "Always", "Sneaking", "Not sneaking"));
	public final NumberSetting minimumFall = add(new NumberSetting("Minimum fall distance",
			"Blocks of drop before the edge is worth clamping", 2, 1, 10, 1));
	public final BooleanSetting requireSolidSupport = add(new BooleanSetting("Require solid support",
			"Treat fluid and replaceable landings as no landing at all", true));
	public final BooleanSetting pauseWhileJumping = add(new BooleanSetting("Pause while jumping",
			"Do not clamp a jump you asked for", true));
	public final ModeSetting precedence = add(new ModeSetting("Scaffold precedence",
			"Who wins when Scaffold and SafeWalk both have an opinion about the same edge",
			"Scaffold", "Scaffold", "SafeWalk"));

	public final BooleanSetting renderEdge = add(new BooleanSetting("Render edge",
			"Highlight the block the clamp is holding you on", false));
	public final BooleanSetting renderPlayerBox = add(new BooleanSetting("Render player box",
			"Show the collision box the decision was made from", false));

	/** Ticks left of the last live clamp — drives both the render and the observability answer. */
	private int clamping;
	/** Where the last clamp decision looked, for {@code Render edge}. */
	private BlockPos lastEdge;
	/** The box the last decision was made from, for {@code Render player box}. */
	private AABB lastBox;

	public SafeWalk() {
		super("SafeWalk", "Refuses to walk off ledges without sneaking", Category.MOVEMENT,
				ServerVisibility.CONDITIONAL);
	}

	/**
	 * Only while the clamp is actually changing what vanilla would have done.
	 *
	 * <p>Answering "yes" to an edge the player was never going to walk off changes nothing the
	 * server can see, and neither does agreeing with a crouch that is already held. The counter is
	 * only ever set from a decision that overrode vanilla's own return value.
	 */
	@Override
	public boolean isServerObservableNow() {
		return clamping > 0;
	}

	@Override
	protected void onEnable() {
		clamping = 0;
		lastEdge = null;
		lastBox = null;
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
		if (mc().player == null || mc().level == null) {
			onDisable();
			return;
		}
		if (clamping > 0) {
			clamping--;
			render();
		}
	}

	/**
	 * The one edge answer, reconciling this module with Scaffold's.
	 *
	 * <p>Called from {@code PlayerMixin} with whatever vanilla decided and whatever Scaffold
	 * decided. Keeping the reconciliation here rather than in the mixin means the precedence rule
	 * lives next to the setting that describes it.
	 *
	 * @param vanilla        what vanilla's own {@code isStayingOnGroundSurface} returned
	 * @param scaffoldAnswer Scaffold's opinion: -1 none, 0 walk off, 1 clamp
	 * @return -1 to leave vanilla alone, 0 to allow walking off, 1 to clamp
	 */
	public int edgePolicy(LocalPlayer player, boolean vanilla, int scaffoldAnswer) {
		int own = ownPolicy(player);
		int resolved = reconcile(own, scaffoldAnswer);
		// Observable only when *we* are the reason the answer changed. Scaffold accounts for its
		// own visibility, and agreeing with a crouch the player is already holding is not a change.
		if (resolved == own && own == CLAMP && !vanilla) {
			clamping = HOLD_TICKS;
		}
		return resolved;
	}

	/**
	 * The precedence rule with both opinions already formed.
	 *
	 * <p>Separated from {@link #edgePolicy} so the whole table — three of Scaffold's answers
	 * against two of ours, in both precedence modes — can be checked without arranging a real
	 * ledge under a real player. The interesting row is the one Scaffold Descend produces: an
	 * opinion of "walk off" that has to survive a SafeWalk that wants to clamp, or deliberate
	 * downward building stops working the moment both modules are on.
	 *
	 * @param own            this module's opinion: -1 none, 1 clamp
	 * @param scaffoldAnswer Scaffold's: -1 none, 0 walk off, 1 clamp
	 */
	public int reconcile(int own, int scaffoldAnswer) {
		if (own == VANILLA) {
			return scaffoldAnswer;
		}
		if (scaffoldAnswer == VANILLA) {
			return own;
		}
		return precedence.is("SafeWalk") ? own : scaffoldAnswer;
	}

	/** This module's opinion alone. It never says "walk off" — that is only ever Scaffold. */
	private int ownPolicy(LocalPlayer player) {
		if (!isEnabled() || player != mc().player || mc().level == null || player.isSpectator()) {
			return VANILLA;
		}
		if (!sneakStateAllows(player)) {
			return VANILLA;
		}
		if (pauseWhileJumping.get() && (mc().options.keyJump.isDown() || !player.onGround())) {
			return VANILLA;
		}
		Vec3 direction = heading(player);
		if (direction.lengthSqr() < 1.0e-6) {
			return VANILLA;
		}
		AABB box = player.getBoundingBox();
		double reach = box.getXsize() * 0.5 + PROBE_REACH;
		// Floor level, not feet level: what matters is the block holding the player up and the
		// one that should be holding them up a step further on.
		double floorY = box.minY - 0.1;
		BlockPos ahead = BlockPos.containing(
				player.getX() + direction.x * reach, floorY, player.getZ() + direction.z * reach);
		lastBox = box;
		lastEdge = BlockPos.containing(player.getX(), floorY, player.getZ());
		return dropIsWorthClamping(ahead) ? CLAMP : VANILLA;
	}

	/**
	 * Whether the column just ahead drops far enough to be worth stopping for.
	 *
	 * <p>Scanned downward one block at a time rather than by a single ray, because the answer
	 * wanted is "how many blocks of nothing", which is what {@code Minimum fall distance} is
	 * counted in. A support found on the way down ends the scan: that is a step, not a fall.
	 */
	private boolean dropIsWorthClamping(BlockPos ahead) {
		if (supportive(ahead)) {
			return false; // there is floor right there; this is not an edge
		}
		// A support found at depth d means a d-block fall, so the search stops one short of the
		// threshold: at Minimum fall distance 1 there is nothing shallow enough to allow.
		int limit = minimumFall.getInt();
		for (int drop = 1; drop < limit; drop++) {
			BlockPos probe = ahead.below(drop);
			if (!mc().level.isLoaded(probe)) {
				return true; // terrain we have not been told about is not a landing
			}
			if (supportive(probe)) {
				// Shallow enough to step down — unless the thing we would step onto bites.
				return MoveUtil.hazardous(probe) || MoveUtil.hazardous(probe.above());
			}
		}
		return true;
	}

	/** What counts as floor. {@code Require solid support} decides whether fluids qualify. */
	private boolean supportive(BlockPos pos) {
		if (MoveUtil.solidSupport(pos)) {
			return true;
		}
		if (requireSolidSupport.get() || !mc().level.isLoaded(pos)) {
			return false;
		}
		return !mc().level.getBlockState(pos).getCollisionShape(mc().level, pos).isEmpty();
	}

	private boolean sneakStateAllows(LocalPlayer player) {
		return switch (activeWhen.get()) {
			case "Sneaking" -> player.isShiftKeyDown();
			case "Not sneaking" -> !player.isShiftKeyDown();
			default -> true;
		};
	}

	/** Movement input if there is any, otherwise the direction momentum is carrying us. */
	private Vec3 heading(LocalPlayer player) {
		Vec3 input = MoveUtil.inputDirection(player);
		if (input.lengthSqr() > 1.0e-6) {
			return input;
		}
		Vec3 velocity = player.getDeltaMovement();
		Vec3 flat = new Vec3(velocity.x, 0.0, velocity.z);
		return flat.lengthSqr() < 1.0e-6 ? Vec3.ZERO : flat.normalize();
	}

	private void render() {
		if (renderEdge.get() && lastEdge != null) {
			Render3D.blockBox(lastEdge, 0xFF43D96B, 1.5f, 0x2043D96B, true);
		}
		if (renderPlayerBox.get() && lastBox != null) {
			Render3D.box(lastBox, 0xFFFFB347, 1.0f, 0, true);
		}
	}
}
