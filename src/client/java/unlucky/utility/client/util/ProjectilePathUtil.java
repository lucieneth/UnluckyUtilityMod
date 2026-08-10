package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Allocation-conscious projectile physics shared by render, aim and dodge modules. */
public final class ProjectilePathUtil {
	/** Vanilla has both orders: throwables update velocity first; arrows move first. */
	public enum StepOrder {
		BEFORE_MOVE,
		AFTER_MOVE
	}

	/** All constants needed to advance one projectile tick. */
	public record Profile(double speed, double gravity, double airDrag, double fluidDrag,
			float pitchOffset, double radius, StepOrder stepOrder) {
		public Profile {
			if (speed < 0.0 || gravity < 0.0 || airDrag < 0.0 || fluidDrag < 0.0
					|| radius < 0.0 || stepOrder == null) {
				throw new IllegalArgumentException("Invalid projectile profile");
			}
		}
	}

	/**
	 * Shared 26.2 launch/physics profiles. Keep this list as the one source of constants:
	 * Trajectories, aim assist and dodge consume these rather than carrying copies.
	 */
	public enum ProjectileType {
		BOW_ARROW(new Profile(3.0, 0.05, 0.99, 0.60, 0, 0.30, StepOrder.AFTER_MOVE), true),
		CROSSBOW_ARROW(new Profile(3.15, 0.05, 0.99, 0.60, 0, 0.30, StepOrder.AFTER_MOVE), false),
		CROSSBOW_FIREWORK(new Profile(1.60, 0.0, 1.0, 1.0, 0, 0.25, StepOrder.AFTER_MOVE), false),
		TRIDENT(new Profile(2.50, 0.05, 0.99, 0.99, 0, 0.35, StepOrder.AFTER_MOVE), false),
		SNOWBALL(new Profile(1.50, 0.03, 0.99, 0.80, 0, 0.25, StepOrder.BEFORE_MOVE), false),
		EGG(new Profile(1.50, 0.03, 0.99, 0.80, 0, 0.25, StepOrder.BEFORE_MOVE), false),
		ENDER_PEARL(new Profile(1.50, 0.03, 0.99, 0.80, 0, 0.25, StepOrder.BEFORE_MOVE), false),
		EXPERIENCE_BOTTLE(new Profile(0.70, 0.07, 0.99, 0.80, -20, 0.25, StepOrder.BEFORE_MOVE), false),
		POTION(new Profile(0.50, 0.05, 0.99, 0.80, -20, 0.25, StepOrder.BEFORE_MOVE), false),
		FISHING_BOBBER(new Profile(1.50, 0.03, 0.92, 0.92, 0, 0.25, StepOrder.AFTER_MOVE), false),
		WIND_CHARGE(new Profile(1.50, 0.0, 0.95, 0.95, 0, 0.30, StepOrder.BEFORE_MOVE), false);

		private final Profile profile;
		private final boolean chargedBow;

		ProjectileType(Profile profile, boolean chargedBow) {
			this.profile = profile;
			this.chargedBow = chargedBow;
		}

		public Profile profile() {
			return profile;
		}

		/** Exact vanilla bow charge curve; other projectile types return their fixed speed. */
		public double initialSpeed(int useTicks) {
			if (!chargedBow) {
				return profile.speed();
			}
			double charge = Math.max(0, useTicks) / 20.0;
			charge = (charge * charge + charge * 2.0) / 3.0;
			return Math.min(charge, 1.0) * profile.speed();
		}
	}

	/** Compatibility snapshot for existing render consumers. */
	public record Path(List<Vec3> points, HitResult hit) {
		public Vec3 end() {
			return hit != null ? hit.getLocation() : points.getLast();
		}
	}

	/**
	 * Reusable simulation output. The points view is stable while its contents are replaced on
	 * the next call, so a per-module buffer removes the several hundred allocations per frame.
	 */
	public static final class ResultBuffer {
		private final ArrayList<Vec3> mutablePoints = new ArrayList<>(401);
		private final List<Vec3> points = Collections.unmodifiableList(mutablePoints);
		private HitResult hit;
		private Vec3 finalVelocity = Vec3.ZERO;
		private int ticks;

		public List<Vec3> points() {
			return points;
		}

		public HitResult hit() {
			return hit;
		}

		public Vec3 end() {
			return hit != null ? hit.getLocation() : mutablePoints.getLast();
		}

		public Vec3 finalVelocity() {
			return finalVelocity;
		}

		public int ticks() {
			return ticks;
		}

		private void reset(Vec3 start, Vec3 velocity) {
			mutablePoints.clear();
			mutablePoints.add(start);
			hit = null;
			finalVelocity = velocity;
			ticks = 0;
		}
	}

	private ProjectilePathUtil() {
	}

	/** Unit direction with Minecraft yaw/pitch convention and a projectile pitch offset. */
	public static Vec3 direction(float pitch, float yaw, float pitchOffset) {
		// Projectile.shootFromRotation applies the offset only to Y; applying it to
		// horizontal cosine too makes potion/bottle paths several percent too short.
		float x = -Mth.sin(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
		float y = -Mth.sin((pitch + pitchOffset) * Mth.DEG_TO_RAD);
		float z = Mth.cos(yaw * Mth.DEG_TO_RAD) * Mth.cos(pitch * Mth.DEG_TO_RAD);
		return new Vec3(x, y, z).normalize();
	}

	/** Initial velocity including optional shooter movement. */
	public static Vec3 launchVelocity(ProjectileType type, int useTicks, float pitch, float yaw,
			Vec3 shooterVelocity) {
		Profile profile = type.profile();
		Vec3 own = shooterVelocity == null ? Vec3.ZERO : shooterVelocity;
		return direction(pitch, yaw, profile.pitchOffset())
				.scale(type.initialSpeed(useTicks)).add(own);
	}

	/** Reuses {@code output} for crossbow multishot yaw offsets. */
	public static List<Vec3> multishot(Vec3 centre, double[] yawOffsets, List<Vec3> output) {
		output.clear();
		for (double offset : yawOffsets) {
			output.add(centre.yRot((float) Math.toRadians(offset)));
		}
		return output;
	}

	/**
	 * Compatibility terrain-only simulation. This preserves the original throwable order for
	 * PearlChecker while new callers select a named profile below.
	 */
	public static Path simulate(Level level, Entity context, Vec3 start, Vec3 initialVelocity,
			double gravity, double drag, int maxSteps) {
		Profile profile = new Profile(initialVelocity.length(), gravity, drag, drag, 0, 0,
				StepOrder.BEFORE_MOVE);
		ResultBuffer buffer = simulate(level, context, start, initialVelocity, profile, maxSteps,
				false, null, new ResultBuffer());
		return new Path(List.copyOf(buffer.points()), buffer.hit());
	}

	public static ResultBuffer simulate(Level level, Entity context, Vec3 start,
			Vec3 initialVelocity, ProjectileType type, int maxSteps, boolean collideEntities,
			Predicate<Entity> entityFilter, ResultBuffer output) {
		return simulate(level, context, start, initialVelocity, type.profile(), maxSteps,
				collideEntities, entityFilter, output);
	}

	/**
	 * Simulates blocks and, optionally, segment/entity-AABB interception. A block truncates the
	 * entity query to its own hit point, so an entity behind a wall cannot win by being closer to
	 * the untruncated segment end.
	 */
	public static ResultBuffer simulate(Level level, Entity context, Vec3 start,
			Vec3 initialVelocity, Profile profile, int maxSteps, boolean collideEntities,
			Predicate<Entity> entityFilter, ResultBuffer output) {
		if (level == null || start == null || initialVelocity == null || profile == null) {
			throw new IllegalArgumentException("Projectile simulation requires level, vectors and profile");
		}
		ResultBuffer result = output == null ? new ResultBuffer() : output;
		result.reset(start, initialVelocity);
		Vec3 position = start;
		Vec3 velocity = initialVelocity;
		int steps = Math.max(0, maxSteps);
		for (int tick = 0; tick < steps; tick++) {
			if (profile.stepOrder() == StepOrder.BEFORE_MOVE) {
				velocity = beforeMoveVelocity(level, position, velocity, profile);
			}
			Vec3 next = position.add(velocity);
			HitResult block = level.clip(new ClipContext(position, next,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
			Vec3 blockEnd = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
			HitResult collision = block;
			if (collideEntities) {
				EntityHitResult entity = entityHit(level, context, position, blockEnd,
						profile.radius(), entityFilter);
				if (entity != null) {
					collision = entity;
				}
			}

			if (collision.getType() != HitResult.Type.MISS) {
				result.mutablePoints.add(collision.getLocation());
				result.hit = collision;
				result.finalVelocity = velocity;
				result.ticks = tick + 1;
				return result;
			}

			position = next;
			result.mutablePoints.add(position);
			if (profile.stepOrder() == StepOrder.AFTER_MOVE) {
				velocity = afterMoveVelocity(level, position, velocity, profile);
			}
			result.finalVelocity = velocity;
			result.ticks = tick + 1;
			if (position.y < level.getMinY() - 64 || position.y > level.getMaxY() + 64) {
				break;
			}
		}
		return result;
	}

	private static Vec3 beforeMoveVelocity(Level level, Vec3 position, Vec3 velocity,
			Profile profile) {
		double drag = dragAt(level, position, profile);
		return velocity.add(0, -profile.gravity(), 0).scale(drag);
	}

	private static Vec3 afterMoveVelocity(Level level, Vec3 position, Vec3 velocity,
			Profile profile) {
		double drag = dragAt(level, position, profile);
		return velocity.scale(drag).add(0, -profile.gravity(), 0);
	}

	private static double dragAt(Level level, Vec3 position, Profile profile) {
		return level.getFluidState(BlockPos.containing(position)).is(FluidTags.WATER)
				? profile.fluidDrag() : profile.airDrag();
	}

	private static EntityHitResult entityHit(Level level, Entity context, Vec3 start, Vec3 end,
			double radius, Predicate<Entity> supplied) {
		AABB sweep = new AABB(start, end).inflate(radius + 1.0);
		Predicate<Entity> eligible = entity -> entity.isAlive() && entity.isPickable()
				&& !entity.isSpectator() && (supplied == null || supplied.test(entity));
		Entity best = null;
		Vec3 bestPoint = null;
		double bestDistance = start.distanceToSqr(end) + 1.0e-9;
		for (Entity entity : level.getEntities(context, sweep, eligible)) {
			AABB box = entity.getBoundingBox().inflate(radius + entity.getPickRadius());
			Optional<Vec3> clipped = box.contains(start) ? Optional.of(start) : box.clip(start, end);
			if (clipped.isEmpty()) {
				continue;
			}
			double distance = start.distanceToSqr(clipped.get());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entity;
				bestPoint = clipped.get();
			}
		}
		return best == null ? null : new EntityHitResult(best, bestPoint);
	}
}
