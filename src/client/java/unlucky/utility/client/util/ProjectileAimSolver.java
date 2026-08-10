package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.util.ProjectilePathUtil.ProjectileType;

/**
 * Numerical ballistic aim built entirely on {@link ProjectilePathUtil}.
 *
 * <p>The analytic parabola is only a seed: Minecraft applies drag each tick and uses different
 * update orders for arrows and throwables, so treating the schoolbook answer as the final pitch
 * misses at distance. Each candidate is run through the shared tick simulator against the target
 * AABB moved by its velocity. That keeps the constants and the collision order in one place.
 */
public final class ProjectileAimSolver {
	private static final double COARSE_SPAN = 12.0;
	private static final double COARSE_STEP = 1.5;
	private static final double FINE_SPAN = 2.0;
	private static final double FINE_STEP = 0.20;

	public record Request(Level level, Entity shooter, Vec3 start, ProjectileType projectile,
			int useTicks, AABB target, Vec3 targetVelocity, Vec3 playerVelocity, int maxTicks,
			boolean requireLineOfSight) {
		public Request {
			if (level == null || shooter == null || start == null || projectile == null
					|| target == null) {
				throw new IllegalArgumentException("Aim request is missing world, shooter or target");
			}
			targetVelocity = targetVelocity == null ? Vec3.ZERO : targetVelocity;
			playerVelocity = playerVelocity == null ? Vec3.ZERO : playerVelocity;
			maxTicks = Math.max(1, maxTicks);
		}
	}

	public record Solution(float yaw, float pitch, Vec3 predictedImpact, double timeToImpact,
			boolean valid, boolean visible, HitResult obstruction, double missDistance) {
		public static Solution invalid(Vec3 point) {
			return new Solution(0, 0, point, 0, false, false, null,
					Double.POSITIVE_INFINITY);
		}
	}

	/** One workspace per module removes result-list allocation from every pitch candidate. */
	public static final class Workspace {
		private final ProjectilePathUtil.ResultBuffer path = new ProjectilePathUtil.ResultBuffer();
		private final ArrayList<Double> seeds = new ArrayList<>(4);
	}

	private record Candidate(float yaw, float pitch, Vec3 point, int tick, boolean intersects,
			double missSquared, HitResult obstruction) {
	}

	private ProjectileAimSolver() {
	}

	public static Solution solve(Request request) {
		return solve(request, new Workspace());
	}

	public static Solution solve(Request request, Workspace workspace) {
		Workspace work = workspace == null ? new Workspace() : workspace;
		double speed = request.projectile().initialSpeed(request.useTicks());
		if (speed < 0.05) {
			return Solution.invalid(request.target().getCenter());
		}

		Vec3 centre = request.target().getCenter();
		double firstGuess = Mth.clamp(request.start().distanceTo(centre) / speed, 1.0,
				request.maxTicks());
		Vec3 predicted = centre.add(request.targetVelocity().scale(firstGuess));
		seedPitches(request.start(), predicted, speed,
				request.projectile().profile().gravity(), work.seeds);

		Candidate best = null;
		for (double seed : work.seeds) {
			for (double pitch = seed - COARSE_SPAN; pitch <= seed + COARSE_SPAN;
					pitch += COARSE_STEP) {
				best = better(best, evaluatePitch(request, (float) Mth.clamp(pitch, -89.0, 89.0),
						work.path));
			}
		}
		if (best == null) {
			return Solution.invalid(centre);
		}

		float coarsePitch = best.pitch();
		for (double pitch = coarsePitch - FINE_SPAN; pitch <= coarsePitch + FINE_SPAN;
				pitch += FINE_STEP) {
			best = better(best, evaluatePitch(request, (float) Mth.clamp(pitch, -89.0, 89.0),
					work.path));
		}

		boolean visible = directVisible(request, best.point());
		boolean valid = best.intersects() && (!request.requireLineOfSight() || visible);
		return new Solution(best.yaw(), best.pitch(), best.point(), best.tick() / 20.0,
				valid, visible, best.obstruction(), Math.sqrt(best.missSquared()));
	}

	/**
	 * Evaluates twice: the first pass supplies a drag-aware flight time, then yaw is recomputed
	 * for where the moving target will be at that actual tick.
	 */
	private static Candidate evaluatePitch(Request request, float pitch,
			ProjectilePathUtil.ResultBuffer buffer) {
		double speed = request.projectile().initialSpeed(request.useTicks());
		double guess = Mth.clamp(request.start().distanceTo(request.target().getCenter()) / speed,
				1.0, request.maxTicks());
		Candidate best = null;
		for (int iteration = 0; iteration < 2; iteration++) {
			Vec3 predicted = request.target().getCenter()
					.add(request.targetVelocity().scale(guess));
			float yaw = yawTo(request.start(), predicted);
			Vec3 velocity = ProjectilePathUtil.launchVelocity(request.projectile(), request.useTicks(),
					pitch, yaw, request.playerVelocity());
			ProjectilePathUtil.simulate(request.level(), request.shooter(), request.start(), velocity,
					request.projectile(), request.maxTicks(), false, null, buffer);
			Candidate candidate = intercept(request, yaw, pitch, buffer);
			best = better(best, candidate);
			guess = Math.max(1, candidate.tick());
		}
		return best;
	}

	private static Candidate intercept(Request request, float yaw, float pitch,
			ProjectilePathUtil.ResultBuffer path) {
		List<Vec3> points = path.points();
		double radius = request.projectile().profile().radius();
		double bestMiss = Double.POSITIVE_INFINITY;
		Vec3 bestPoint = points.getFirst();
		int bestTick = 0;
		for (int tick = 1; tick < points.size(); tick++) {
			Vec3 from = points.get(tick - 1);
			Vec3 to = points.get(tick);
			AABB predicted = request.target()
					.move(request.targetVelocity().scale(tick)).inflate(radius);
			Vec3 contact = predicted.contains(from) ? from : predicted.clip(from, to).orElse(null);
			if (contact != null) {
				return new Candidate(yaw, pitch, contact, tick, true, 0.0, path.hit());
			}
			double fromMiss = predicted.distanceToSqr(from);
			double toMiss = predicted.distanceToSqr(to);
			if (Math.min(fromMiss, toMiss) < bestMiss) {
				bestMiss = Math.min(fromMiss, toMiss);
				bestPoint = fromMiss <= toMiss ? from : to;
				bestTick = tick;
			}
		}
		return new Candidate(yaw, pitch, bestPoint, bestTick, false, bestMiss, path.hit());
	}

	private static Candidate better(Candidate current, Candidate candidate) {
		if (candidate == null) {
			return current;
		}
		if (current == null || candidate.intersects() && !current.intersects()) {
			return candidate;
		}
		if (candidate.intersects() != current.intersects()) {
			return current;
		}
		if (candidate.missSquared() + 1.0e-9 < current.missSquared()) {
			return candidate;
		}
		if (Math.abs(candidate.missSquared() - current.missSquared()) <= 1.0e-9
				&& candidate.tick() < current.tick()) {
			return candidate;
		}
		return current;
	}

	private static void seedPitches(Vec3 start, Vec3 target, double speed, double gravity,
			List<Double> output) {
		output.clear();
		double dx = target.x - start.x;
		double dz = target.z - start.z;
		double horizontal = Math.hypot(dx, dz);
		double vertical = target.y - start.y;
		double direct = -Math.toDegrees(Math.atan2(vertical, Math.max(horizontal, 1.0e-9)));
		if (gravity <= 1.0e-9 || horizontal <= 1.0e-9) {
			output.add(direct);
			return;
		}
		double speedSquared = speed * speed;
		double discriminant = speedSquared * speedSquared
				- gravity * (gravity * horizontal * horizontal + 2.0 * vertical * speedSquared);
		if (discriminant >= 0.0) {
			double root = Math.sqrt(discriminant);
			output.add(-Math.toDegrees(Math.atan((speedSquared - root) / (gravity * horizontal))));
			double high = -Math.toDegrees(Math.atan((speedSquared + root) / (gravity * horizontal)));
			if (Math.abs(high - output.getFirst()) > 0.5) {
				output.add(high);
			}
		} else {
			output.add(direct);
		}
	}

	private static float yawTo(Vec3 from, Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0f;
	}

	private static boolean directVisible(Request request, Vec3 point) {
		HitResult hit = request.level().clip(new ClipContext(request.start(), point,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, request.shooter()));
		return hit.getType() == HitResult.Type.MISS
				|| request.start().distanceToSqr(hit.getLocation()) + 1.0e-6
						>= request.start().distanceToSqr(point);
	}
}
