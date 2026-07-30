package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out <i>which</i> click produces a wanted block state, by asking vanilla.
 *
 * <p>A block's final orientation is decided by {@code Block.getStateForPlacement} from the
 * clicked face, where on that face you clicked, and which way the player was facing. Rather
 * than encode those rules per block — the trap both reference printers fell into, one with
 * a quirk table of stairs/logs/hoppers/buttons, the other with twenty per-block handlers it
 * eventually deleted — this enumerates plausible clicks, runs vanilla's own placement logic
 * on each through a {@link Sim} context, and keeps the one whose predicted state matches.
 * Every orientation-sensitive block is then correct for free, including ones nobody thought
 * about.
 *
 * <p>The same mechanism covers blocks that <em>stack</em> rather than orient. Snow, candles,
 * sea pickles and slabs all answer {@code canBeReplaced} true when you are holding more of
 * themselves, and their {@code getStateForPlacement} returns the incremented state — so
 * three-layer snow is simply three clicks that each measurably close the gap. See
 * {@link #distance}.
 */
public final class PlacementSolver {
	/** Points tried across a clicked face; the vertical spread is what picks a slab's half. */
	private static final double[] FACE_OFFSETS = { 0.5, 0.25, 0.75 };
	private static final float[] PITCHES = { 0.0f, 90.0f, -90.0f };

	private PlacementSolver() {
	}

	/**
	 * A click vanilla says yields {@link #predicted}, and the rotation it must be sent with.
	 *
	 * @param exact      whether {@code predicted} is the wanted state outright
	 * @param convergent whether {@code predicted} is wrong only in how <em>much</em> of the
	 *                   block there is, so further identical clicks finish the job — three
	 *                   snow layers are three clicks and no single one of them is exact
	 */
	public record Solution(BlockHitResult hit, float yaw, float pitch, BlockState predicted,
			boolean exact, boolean convergent) {
	}

	/** Caller's settings, so the solver doesn't reach into a module. */
	public record Options(double range, boolean airPlace, boolean throughWalls, boolean sneak,
			boolean mayRotate) {
	}

	/**
	 * How far {@code from} is from {@code to}, counting each disagreeing property once —
	 * except numeric ones, which count their difference so "one more snow layer" registers
	 * as progress rather than as an equally-wrong state. 0 means identical.
	 *
	 * <p>This is what keeps the printer from looping on a block it cannot fix: a click is
	 * only worth sending if it strictly reduces the distance.
	 */
	public static int distance(BlockState from, BlockState to) {
		if (from == to) {
			return 0;
		}
		if (from.getBlock() != to.getBlock()) {
			return Integer.MAX_VALUE / 4; // a different block entirely — must be replaced
		}
		int total = 0;
		for (Property<?> property : to.getProperties()) {
			if (!from.hasProperty(property)) {
				continue;
			}
			total += propertyDistance(from, to, property);
		}
		return total;
	}

	/**
	 * Whether {@code predicted} is merely unfinished rather than wrong — another click of
	 * the same kind will close the gap.
	 *
	 * <p>Two independent signals, because neither covers everything:
	 * <ul>
	 * <li>vanilla still calls the predicted state replaceable by this very item, so one more
	 * click changes it — this is what recognises a bottom slab on its way to double, where
	 * the property that disagrees is categorical;
	 * <li>the only disagreements left are numeric, so repeating the click counts up to the
	 * target — snow layers, candles, sea pickles.
	 * </ul>
	 * The first is checked against the winning click's own face, and some blocks answer it
	 * differently per face (snow accepts stacking only from above), which is exactly why the
	 * numeric test has to be there as well rather than being folded into it.
	 *
	 * <p>Anything failing both is wrong in a way clicking cannot mend — a stair on the wrong
	 * half, a block facing the wrong way — and has to be broken instead. Refusing those is
	 * the whole job of the Precise setting.
	 */
	private static boolean unfinished(BlockState predicted, BlockState target,
			BlockPlaceContext context) {
		return predicted.canBeReplaced(context) || onlyNumericLeft(predicted, target);
	}

	private static boolean onlyNumericLeft(BlockState from, BlockState to) {
		if (from.getBlock() != to.getBlock()) {
			return false;
		}
		for (Property<?> property : to.getProperties()) {
			if (!from.hasProperty(property) || property instanceof IntegerProperty) {
				continue;
			}
			if (!from.getValue(property).equals(to.getValue(property))) {
				return false;
			}
		}
		return true;
	}

	private static <T extends Comparable<T>> int propertyDistance(BlockState from, BlockState to,
			Property<T> property) {
		T a = from.getValue(property);
		T b = to.getValue(property);
		if (a.equals(b)) {
			return 0;
		}
		if (property instanceof IntegerProperty && a instanceof Integer x && b instanceof Integer y) {
			return Math.abs(x - y);
		}
		return 1;
	}

	/**
	 * The state a schematic entry will actually settle into at {@code pos} in <em>this</em>
	 * world, once vanilla recomputes everything it derives from the surroundings.
	 *
	 * <p>Fence, pane and wall connections, stair shape and redstone links are not chosen by
	 * the placer — vanilla writes them from whatever is adjacent. A schematic records the
	 * values its own neighbourhood produced, so comparing them against ours is comparing two
	 * different questions: a fence beside another fence is `west=true` here no matter what
	 * the file says. Without this the printer treats such blocks as permanently wrong and
	 * keeps re-solving them, which is exactly what it was doing for every fence and pane.
	 */
	public static BlockState settle(BlockState state, BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return state;
		}
		BlockState settled = net.minecraft.world.level.block.Block
				.updateFromNeighbourShapes(state, mc.level, pos);
		// A block that cannot survive here yet comes back as air — snow before its floor
		// exists, a torch before its wall. That is a support question, answered by the
		// caller's own canSurvive check, and letting it through here would be far worse
		// than useless: "target is air" reads as "already correct" and the position would
		// be dropped instead of retried once its support arrives. Only ever let this
		// adjust properties of the same block.
		return settled.getBlock() == state.getBlock() ? settled : state;
	}

	/**
	 * Best click for getting {@code required} at {@code pos}, or null when none of them help.
	 *
	 * <p>An exact match wins immediately. Otherwise the click that gets closest is returned,
	 * and only if it beats what is already there — so a wrongly-oriented block that a click
	 * cannot repair is reported as unsolvable instead of being clicked forever.
	 */
	public static Solution solve(BlockPos pos, BlockState required, ItemStack stack, Options options) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return null;
		}
		// compare against what the schematic entry becomes here, not what the file stored
		BlockState target = settle(required, pos);
		BlockState current = mc.level.getBlockState(pos);
		int currentDistance = distance(current, target);
		Solution best = null;
		int bestDistance = currentDistance;

		for (Click click : clicks(pos, options)) {
			for (float[] facing : facings(mc.player, click.hit().getLocation(), options.mayRotate())) {
				Sim context = new Sim(mc.player, stack, click.hit(), facing[0], facing[1],
						options.sneak());
				// Where vanilla decides the block actually goes, which is not always the
				// neighbour-of-the-clicked-face: clicking a bottom slab with a slab in hand
				// resolves onto *that* slab and doubles it. Crediting such a click to this
				// position placed nothing here and silently doubled the neighbour instead.
				if (!context.getClickedPos().equals(pos)) {
					continue;
				}
				if (!current.canBeReplaced(context)) {
					continue; // vanilla would not let this click place anything here
				}
				BlockState predicted = required.getBlock().getStateForPlacement(context);
				if (predicted == null) {
					continue;
				}
				int predictedDistance = distance(predicted, target);
				if (predictedDistance == 0) {
					return new Solution(click.hit(), facing[0], facing[1], predicted, true, true);
				}
				if (predictedDistance < bestDistance) {
					bestDistance = predictedDistance;
					// closer, but not there — only worth sending if clicking again finishes it
					best = new Solution(click.hit(), facing[0], facing[1], predicted, false,
							unfinished(predicted, target, context));
				}
			}
		}
		return best;
	}

	/** The {yaw, pitch} that looks from {@code eye} straight at {@code target}. */
	private static float[] lookAt(Vec3 eye, Vec3 target) {
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		return new float[] {
				(float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f,
				(float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))
		};
	}

	/**
	 * A point {@code distance} away along the given aim, for handing a yaw/pitch to
	 * {@link RotationManager#face} — which walks toward a position rather than an angle.
	 */
	public static Vec3 lookPoint(Vec3 eye, float yaw, float pitch, double distance) {
		return eye.add(viewVector(yaw, pitch).scale(distance));
	}

	/** Vanilla's view-vector maths, for a rotation that isn't necessarily the player's. */
	private static Vec3 viewVector(float yaw, float pitch) {
		float p = pitch * Mth.DEG_TO_RAD;
		float y = -yaw * Mth.DEG_TO_RAD;
		float cosY = Mth.cos(y);
		float sinY = Mth.sin(y);
		float cosP = Mth.cos(p);
		float sinP = Mth.sin(p);
		return new Vec3(sinY * cosP, -sinP, cosY * cosP);
	}

	/** One geometric click: where the packet says we hit. */
	private record Click(BlockHitResult hit) {
	}

	/** Clickable faces around {@code pos}, nearest-face-first, with a spread of hit points. */
	private static List<Click> clicks(BlockPos pos, Options options) {
		Minecraft mc = Minecraft.getInstance();
		List<Click> result = new ArrayList<>();
		Vec3 eye = mc.player.getEyePosition();
		double reachSqr = options.range() * options.range();
		for (Direction toNeighbour : Direction.values()) {
			BlockPos neighbour = pos.relative(toNeighbour);
			BlockState state = mc.level.getBlockState(neighbour);
			if (state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced()) {
				continue;
			}
			// a shape-less block (torch, tall grass) can't be clicked: the ray goes through
			if (state.getCollisionShape(mc.level, neighbour).isEmpty()) {
				continue;
			}
			// clicking a container opens its screen instead of placing, and that also stalls
			// the printer, so never aim at one
			if (state.getMenuProvider(mc.level, neighbour) != null) {
				continue;
			}
			Direction face = toNeighbour.getOpposite();
			for (Vec3 hitPos : facePoints(neighbour, face)) {
				// the click point is what the server range-checks, not the target block
				if (eye.distanceToSqr(hitPos) > reachSqr) {
					continue;
				}
				if (!options.throughWalls() && !canSee(eye, hitPos, neighbour)) {
					continue;
				}
				result.add(new Click(new BlockHitResult(hitPos, face, neighbour, false)));
			}
		}
		if (options.airPlace()) {
			// Appended, never instead of the above: air place exists for positions with
			// nothing to click, but the clicked *face* is what decides a stair's half or a
			// slab's type, so a real neighbour is always the better answer where one exists.
			// (Clicking the target's own centre with face UP — which is all this used to
			// offer — forces half=bottom, making upside-down stairs impossible.)
			// The target is replaceable, so BlockPlaceContext resolves the placement to pos
			// whichever face we name, and all six are worth offering.
			for (Direction face : Direction.values()) {
				Vec3 hitPos = Vec3.atCenterOf(pos)
						.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
				result.add(new Click(new BlockHitResult(hitPos, face, pos, false)));
			}
		}
		return result;
	}

	/** Points on one face of a block: centre first, then spread along the face. */
	private static List<Vec3> facePoints(BlockPos block, Direction face) {
		Vec3 centre = Vec3.atCenterOf(block)
				.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		List<Vec3> points = new ArrayList<>(FACE_OFFSETS.length);
		if (face.getAxis().isVertical()) {
			points.add(centre); // a top/bottom face has no half to distinguish
			return points;
		}
		for (double offset : FACE_OFFSETS) {
			points.add(new Vec3(centre.x, block.getY() + offset, centre.z));
		}
		return points;
	}

	private static boolean canSee(Vec3 eye, Vec3 hitPos, BlockPos target) {
		Minecraft mc = Minecraft.getInstance();
		BlockHitResult result = mc.level.clip(new net.minecraft.world.level.ClipContext(
				eye, hitPos, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
		return result.getType() == net.minecraft.world.phys.HitResult.Type.MISS
				|| result.getBlockPos().equals(target);
	}

	/**
	 * Rotations to simulate as {yaw, pitch}, best first.
	 *
	 * <p><b>Looking straight at the click leads</b>, so a block that does not care which way
	 * it is placed still gets a real aim. This used to start with the player's own facing,
	 * on the reasoning that a block needing no particular rotation should not cause one —
	 * and that quietly made the whole silent-rotation feature invisible for exactly the
	 * builds it is used on. A mapart is all non-directional blocks, so the first candidate
	 * always won, the solver returned the camera's own angle, and the printer spent the
	 * build "rotating" to where it was already pointing. Three fixes went into the renderer
	 * before {@code .rot} showed the spoofed yaw matching the camera to the decimal.
	 *
	 * <p>Nothing about correctness rests on the order: a facing is only accepted when the
	 * simulation says it produces the state the schematic wants, so a stair or an observer
	 * still falls through to whichever facing actually yields it — the player's own aim
	 * next, then the four compass directions crossed with level/down/up, which covers
	 * every facing vanilla can derive.
	 */
	private static List<float[]> facings(Player player, Vec3 hit, boolean mayRotate) {
		List<float[]> result = new ArrayList<>();
		if (mayRotate) {
			result.add(lookAt(player.getEyePosition(), hit));
		}
		result.add(new float[] { player.getYRot(), player.getXRot() });
		if (!mayRotate) {
			return result;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			for (float pitch : PITCHES) {
				result.add(new float[] { direction.toYRot(), pitch });
			}
		}
		return result;
	}

	/**
	 * A placement context that answers for an arbitrary rotation instead of the player's.
	 *
	 * <p>Every facing question vanilla asks during placement is overridden here, which is
	 * what lets the solver try rotations without moving the camera. The chosen rotation is
	 * then spoofed for real via {@link RotationManager} when the click goes out, so the
	 * server derives the same state we predicted.
	 */
	private static final class Sim extends BlockPlaceContext {
		private final float yaw;
		private final float pitch;
		private final boolean sneaking;

		private Sim(Player player, ItemStack stack, BlockHitResult hit, float yaw, float pitch,
				boolean sneaking) {
			super(player, InteractionHand.MAIN_HAND, stack, hit);
			this.yaw = yaw;
			this.pitch = pitch;
			this.sneaking = sneaking;
		}

		@Override
		public float getRotation() {
			return yaw;
		}

		@Override
		public Direction getHorizontalDirection() {
			return Direction.fromYRot(yaw);
		}

		@Override
		public boolean isSecondaryUseActive() {
			return sneaking;
		}

		@Override
		public Direction getNearestLookingDirection() {
			return orderedByNearest()[0];
		}

		@Override
		public Direction getNearestLookingVerticalDirection() {
			return pitch < 0.0f ? Direction.UP : Direction.DOWN;
		}

		@Override
		public Direction[] getNearestLookingDirections() {
			return orderedByNearest();
		}

		/** The six directions sorted by how closely they align with the simulated look. */
		private Direction[] orderedByNearest() {
			Vec3 look = viewVector(yaw, pitch);
			Direction[] directions = Direction.values().clone();
			// six elements: an insertion sort beats allocating a comparator per call
			for (int i = 1; i < directions.length; i++) {
				Direction moving = directions[i];
				double key = alignment(moving, look);
				int j = i - 1;
				while (j >= 0 && alignment(directions[j], look) < key) {
					directions[j + 1] = directions[j];
					j--;
				}
				directions[j + 1] = moving;
			}
			return directions;
		}

		private static double alignment(Direction direction, Vec3 look) {
			return direction.getStepX() * look.x + direction.getStepY() * look.y
					+ direction.getStepZ() * look.z;
		}
	}
}
